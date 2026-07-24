package com.overlord.voxel;

import com.overlord.core.thread.MainThreadGuard;
import com.overlord.renderer.ChunkRenderBackend;
import com.overlord.renderer.ChunkRenderObject;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;

public final class ChunkMeshManager implements AutoCloseable {
    private final ChunkRepository repository;
    private final ChunkMesher mesher;
    private final Executor meshExecutor;
    private final ChunkRenderBackend renderBackend;
    private final MainThreadGuard mainThreadGuard;
    private final int maxUploadsPerFrame;
    private final Queue<MeshingCompletion> completed =
            new ConcurrentLinkedQueue<>();
    private final Queue<MeshingFailure> failed =
            new ConcurrentLinkedQueue<>();
    private final Queue<ChunkMeshData> awaitingUpload =
            new ConcurrentLinkedQueue<>();
    private final Queue<Throwable> reportedFailures =
            new ConcurrentLinkedQueue<>();
    private final Queue<ChunkKey> pendingUnloads =
            new ConcurrentLinkedQueue<>();
    private final Map<ChunkKey, ChunkMeshData> failedUploads =
            new HashMap<>();
    private final Map<ChunkKey, ChunkRenderObject> installedRenderObjects =
            new HashMap<>();
    private final Object lifecycleLock = new Object();
    private volatile boolean closed;

    public ChunkMeshManager(
            ChunkRepository repository,
            ChunkMesher mesher,
            Executor meshExecutor,
            ChunkRenderBackend renderBackend,
            MainThreadGuard mainThreadGuard,
            int maxUploadsPerFrame) {
        this.repository =
                Objects.requireNonNull(repository, "repository");
        this.mesher = Objects.requireNonNull(mesher, "mesher");
        this.meshExecutor =
                Objects.requireNonNull(meshExecutor, "meshExecutor");
        this.renderBackend =
                Objects.requireNonNull(renderBackend, "renderBackend");
        this.mainThreadGuard =
                Objects.requireNonNull(mainThreadGuard, "mainThreadGuard");
        if (maxUploadsPerFrame <= 0) {
            throw new IllegalArgumentException(
                    "maxUploadsPerFrame must be greater than zero");
        }
        this.maxUploadsPerFrame = maxUploadsPerFrame;
    }

    public int scheduleEligible() {
        mainThreadGuard.assertMainThread("schedule chunk meshing");
        if (closed) {
            return 0;
        }
        int scheduled = 0;
        for (ChunkKey key : repository.meshingCandidates()) {
            Optional<ChunkMeshInput> claimed =
                    repository.claimMeshing(key);
            if (claimed.isEmpty()) {
                continue;
            }
            ChunkMeshInput input = claimed.orElseThrow();
            try {
                meshExecutor.execute(() -> buildMesh(input));
                scheduled++;
            } catch (RuntimeException | Error failure) {
                if (repository.markMeshingFailureIfCurrent(
                        input.center().key(),
                        input.center().revision(),
                        failure)) {
                    reportedFailures.add(failure);
                }
            }
        }
        return scheduled;
    }

    public int drainCompletedCpuWork() {
        mainThreadGuard.assertMainThread("drain completed chunk meshes");
        if (closed) {
            return 0;
        }
        int drained = 0;
        MeshingCompletion completion;
        while ((completion = completed.poll()) != null) {
            drained++;
            if (repository.markReadyForUpload(
                    completion.key(), completion.revision())) {
                awaitingUpload.add(completion.data());
            }
        }

        MeshingFailure failure;
        while ((failure = failed.poll()) != null) {
            drained++;
            if (repository.markMeshingFailureIfCurrent(
                    failure.key(),
                    failure.revision(),
                    failure.cause())) {
                reportedFailures.add(failure.cause());
            }
        }
        return drained;
    }

    public Optional<Throwable> pollFailure() {
        mainThreadGuard.assertMainThread("poll chunk meshing failure");
        return Optional.ofNullable(reportedFailures.poll());
    }

    public int processMainThreadWork() {
        mainThreadGuard.assertMainThread("chunk mesh upload");
        if (closed) {
            return 0;
        }
        drainUnloads();
        drainCompletedCpuWork();

        int processed = 0;
        ChunkMeshData data;
        while (!closed
                && processed < maxUploadsPerFrame
                && (data = awaitingUpload.poll()) != null) {
            processed++;
            if (data.isEmpty()) {
                installEmptyMesh(data);
            } else {
                uploadReplacement(data);
            }
        }
        return processed;
    }

    public Collection<ChunkRenderObject> renderObjects() {
        mainThreadGuard.assertMainThread("read chunk render objects");
        return List.copyOf(installedRenderObjects.values());
    }

    public void retry(ChunkKey key) {
        mainThreadGuard.assertMainThread("retry chunk mesh");
        Objects.requireNonNull(key, "key");
        if (closed) {
            return;
        }

        ChunkMeshData failedUpload = failedUploads.remove(key);
        if (failedUpload != null
                && repository.state(key) == ChunkState.READY_FOR_UPLOAD
                && repository.revision(key) == failedUpload.revision()) {
            awaitingUpload.add(failedUpload);
            return;
        }
        repository.retry(key);
    }

    public void unload(ChunkKey key) {
        mainThreadGuard.assertMainThread("unload chunk mesh");
        Objects.requireNonNull(key, "key");
        if (!closed && repository.beginUnload(key)) {
            pendingUnloads.add(key);
        }
    }

    public boolean allRenderable(Set<ChunkKey> keys) {
        mainThreadGuard.assertMainThread("check renderable chunks");
        Objects.requireNonNull(keys, "keys");
        return keys.stream().allMatch(repository::isRenderable);
    }

    @Override
    public void close() {
        mainThreadGuard.assertMainThread("close chunk mesh manager");
        synchronized (lifecycleLock) {
            if (closed) {
                return;
            }
            closed = true;
            completed.clear();
            failed.clear();
        }

        awaitingUpload.clear();
        failedUploads.clear();
        reportedFailures.clear();

        Throwable firstFailure = null;
        ChunkKey unloading;
        while ((unloading = pendingUnloads.poll()) != null) {
            ChunkRenderObject object =
                    installedRenderObjects.remove(unloading);
            if (object != null) {
                firstFailure =
                        releaseForClose(object, firstFailure);
            }
            repository.completeUnload(unloading);
        }
        for (ChunkRenderObject object :
                installedRenderObjects.values()) {
            firstFailure = releaseForClose(object, firstFailure);
        }
        installedRenderObjects.clear();

        if (firstFailure != null) {
            rethrow(firstFailure);
        }
    }

    private void buildMesh(ChunkMeshInput input) {
        try {
            ChunkMeshData data =
                    Objects.requireNonNull(
                            mesher.build(input),
                            "mesher result");
            ChunkKey claimedKey = input.center().key();
            long claimedRevision = input.center().revision();
            if (!data.key().equals(claimedKey)
                    || data.revision() != claimedRevision) {
                throw new IllegalStateException(
                        "Mesher result must match claimed chunk "
                                + claimedKey
                                + " revision "
                                + claimedRevision);
            }
            synchronized (lifecycleLock) {
                if (!closed) {
                    completed.add(
                            new MeshingCompletion(
                                    claimedKey,
                                    claimedRevision,
                                    data));
                }
            }
        } catch (RuntimeException | Error failure) {
            synchronized (lifecycleLock) {
                if (!closed) {
                    failed.add(
                            new MeshingFailure(
                                    input.center().key(),
                                    input.center().revision(),
                                    failure));
                }
            }
        }
    }

    private void drainUnloads() {
        ChunkKey key;
        while ((key = pendingUnloads.poll()) != null) {
            ChunkKey unloadingKey = key;
            awaitingUpload.removeIf(
                    data -> data.key().equals(unloadingKey));
            failedUploads.remove(unloadingKey);
            ChunkRenderObject object =
                    installedRenderObjects.remove(unloadingKey);
            try {
                if (object != null) {
                    releaseAndReport(object);
                }
            } finally {
                repository.completeUnload(unloadingKey);
            }
        }
    }

    private void installEmptyMesh(ChunkMeshData data) {
        if (!repository.markRenderable(
                data.key(), data.revision())) {
            return;
        }
        failedUploads.remove(data.key(), data);
        ChunkRenderObject previous =
                installedRenderObjects.remove(data.key());
        if (previous != null) {
            releaseAndReport(previous);
        }
    }

    private void uploadReplacement(ChunkMeshData data) {
        ChunkRenderObject replacement;
        try {
            replacement =
                    Objects.requireNonNull(
                            renderBackend.upload(data),
                            "render backend upload result");
        } catch (RuntimeException | Error failure) {
            if (!closed) {
                failedUploads.put(data.key(), data);
                reportedFailures.add(failure);
            }
            return;
        }

        if (closed) {
            renderBackend.release(replacement);
            return;
        }

        boolean accepted;
        try {
            accepted =
                    repository.markRenderable(
                            data.key(), data.revision());
        } catch (RuntimeException | Error failure) {
            try {
                renderBackend.release(replacement);
            } catch (RuntimeException | Error cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
        if (!accepted) {
            releaseAndReport(replacement);
            return;
        }

        failedUploads.remove(data.key(), data);
        ChunkRenderObject previous =
                installedRenderObjects.put(data.key(), replacement);
        if (previous != null) {
            releaseAndReport(previous);
        }
    }

    private void releaseAndReport(ChunkRenderObject object) {
        try {
            renderBackend.release(object);
        } catch (RuntimeException | Error failure) {
            if (closed) {
                rethrow(failure);
            } else {
                reportedFailures.add(failure);
            }
        }
    }

    private Throwable releaseForClose(
            ChunkRenderObject object, Throwable firstFailure) {
        try {
            renderBackend.release(object);
        } catch (RuntimeException | Error failure) {
            if (firstFailure == null) {
                return failure;
            }
            firstFailure.addSuppressed(failure);
        }
        return firstFailure;
    }

    private static void rethrow(Throwable failure) {
        if (failure instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
        throw (Error) failure;
    }

    private record MeshingCompletion(
            ChunkKey key, long revision, ChunkMeshData data) {}

    private record MeshingFailure(
            ChunkKey key, long revision, Throwable cause) {}
}

package com.overlord.voxel;

import com.overlord.core.thread.MainThreadGuard;
import com.overlord.renderer.ChunkRenderBackend;
import com.overlord.renderer.ChunkRenderObject;
import com.overlord.renderer.RenderOrigin;
import java.util.ArrayDeque;
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
    private static final ChunkKey ZERO_CHUNK_KEY = new ChunkKey(0, 0);
    private final ChunkRepository repository;
    private final ChunkMesher mesher;
    private final Executor meshExecutor;
    private final ChunkRenderBackend renderBackend;
    private final MainThreadGuard mainThreadGuard;
    private final ChunkMeshBudget budget;
    private final Queue<ChunkMeshInput> queuedMeshing = new ArrayDeque<>();
    private final Queue<MeshingCompletion> completed =
            new ConcurrentLinkedQueue<>();
    private final Queue<MeshingFailure> failed =
            new ConcurrentLinkedQueue<>();
    private final Queue<ChunkMeshData> awaitingUpload =
            new ConcurrentLinkedQueue<>();
    private final Queue<Throwable> reportedFailures =
            new ConcurrentLinkedQueue<>();
    private final Queue<PendingDestruction> pendingDestructions =
            new ArrayDeque<>();
    private final Map<ChunkKey, ChunkMeshData> failedUploads =
            new HashMap<>();
    private final Map<ChunkKey, ChunkRenderObject> installedRenderObjects =
            new HashMap<>();
    private final Object lifecycleLock = new Object();
    private int acceptedMeshing;
    private int activeMeshing;
    private int pumpDepth;
    private int remainingUploadsInPump;
    private int remainingDestructionsInPump;
    private long uploadedTotal;
    private long bytesUploadedTotal;
    private long destroyedTotal;
    private volatile long lastMeshLatencyNanos;
    private volatile boolean closed;
    private Throwable closeFailure;
    private RenderOrigin renderOrigin = new RenderOrigin(ZERO_CHUNK_KEY);

    public ChunkMeshManager(
            ChunkRepository repository,
            ChunkMesher mesher,
            Executor meshExecutor,
            ChunkRenderBackend renderBackend,
            MainThreadGuard mainThreadGuard,
            int maxUploadsPerFrame) {
        this(repository, mesher, meshExecutor, renderBackend, mainThreadGuard,
                new ChunkMeshBudget(32, 2, maxUploadsPerFrame, 4));
    }

    public ChunkMeshManager(
            ChunkRepository repository,
            ChunkMesher mesher,
            Executor meshExecutor,
            ChunkRenderBackend renderBackend,
            MainThreadGuard mainThreadGuard,
            ChunkMeshBudget budget) {
        this.repository =
                Objects.requireNonNull(repository, "repository");
        this.mesher = Objects.requireNonNull(mesher, "mesher");
        this.meshExecutor =
                Objects.requireNonNull(meshExecutor, "meshExecutor");
        this.renderBackend =
                Objects.requireNonNull(renderBackend, "renderBackend");
        this.mainThreadGuard =
                Objects.requireNonNull(mainThreadGuard, "mainThreadGuard");
        this.budget = Objects.requireNonNull(budget, "budget");
    }

    public int scheduleEligible() {
        mainThreadGuard.assertMainThread("schedule chunk meshing");
        if (closed) {
            return 0;
        }
        int scheduled = 0;
        for (ChunkKey key : repository.meshingCandidates()) {
            synchronized (lifecycleLock) {
                if (closed || acceptedMeshing >= budget.maxAccepted()) {
                    break;
                }
            }
            Optional<ChunkMeshInput> claimed =
                    repository.claimMeshing(key);
            if (claimed.isEmpty()) {
                continue;
            }
            ChunkMeshInput input = claimed.orElseThrow();
            synchronized (lifecycleLock) {
                if (closed) {
                    break;
                }
                queuedMeshing.add(input);
                acceptedMeshing++;
            }
            DispatchOutcome outcome = dispatchOne(true);
            if (outcome != DispatchOutcome.REJECTED) {
                scheduled++;
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
            boolean ready =
                    repository.markReadyForUpload(
                            completion.key(), completion.revision());
            discardFailedUploadAtOrBefore(
                    completion.key(), completion.revision());
            if (ready) {
                awaitingUpload.add(completion.data());
            } else {
                releaseAccepted();
            }
        }

        MeshingFailure failure;
        while ((failure = failed.poll()) != null) {
            drained++;
            if (repository.markMeshingFailureIfCurrent(
                    failure.key(),
                    failure.revision(),
                    failure.cause())) {
                discardFailedUploadAtOrBefore(
                        failure.key(), failure.revision());
                reportFailure(failure.cause());
            }
            releaseAccepted();
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
        boolean outermostPump = pumpDepth == 0;
        if (outermostPump) {
            remainingUploadsInPump = budget.maxUploadsPerFrame();
            remainingDestructionsInPump = budget.maxDestructionsPerFrame();
        }
        pumpDepth++;
        try {
            drainDestructions();
            drainCompletedCpuWork();

            int processed = 0;
            ChunkMeshData data;
            while (!closed
                    && remainingUploadsInPump > 0
                    && (data = awaitingUpload.poll()) != null) {
                if (!repository.isReadyForUpload(
                        data.key(), data.revision())) {
                    discardFailedUploadAtOrBefore(
                            data.key(), data.revision());
                    releaseAccepted();
                    continue;
                }
                remainingUploadsInPump--;
                processed++;
                if (data.isEmpty()) {
                    installEmptyMesh(data);
                } else {
                    uploadReplacement(data);
                }
            }
            drainDestructions();
            return processed;
        } finally {
            pumpDepth--;
            if (outermostPump) {
                remainingUploadsInPump = 0;
                remainingDestructionsInPump = 0;
            }
        }
    }

    public Collection<ChunkRenderObject> renderObjects() {
        mainThreadGuard.assertMainThread("read chunk render objects");
        return List.copyOf(installedRenderObjects.values());
    }

    /** Prebuilds immutable render replacements and publishes map references only at commit. */
    public PreparedOriginRebase prepareOriginRebase(
            RenderOrigin oldOrigin, RenderOrigin nextOrigin) {
        mainThreadGuard.assertMainThread("prepare chunk render origin rebase");
        Objects.requireNonNull(oldOrigin, "oldOrigin");
        Objects.requireNonNull(nextOrigin, "nextOrigin");
        if (closed) {
            throw new IllegalStateException("chunk mesh manager is closed");
        }
        if (!renderOrigin.equals(oldOrigin)) {
            throw new IllegalStateException("old render origin does not match installed renders");
        }
        ChunkKey[] keys = new ChunkKey[installedRenderObjects.size()];
        ChunkRenderObject[] replacements = new ChunkRenderObject[keys.length];
        int index = 0;
        for (Map.Entry<ChunkKey, ChunkRenderObject> entry
                : installedRenderObjects.entrySet()) {
            keys[index] = entry.getKey();
            replacements[index] = entry.getValue().forOrigin(nextOrigin);
            index++;
        }
        return () -> {
            for (int replacementIndex = 0;
                    replacementIndex < keys.length;
                    replacementIndex++) {
                installedRenderObjects.replace(
                        keys[replacementIndex], replacements[replacementIndex]);
            }
            renderOrigin = nextOrigin;
        };
    }

    public int meshQueueDepth() {
        mainThreadGuard.assertMainThread("read chunk mesh queue depth");
        synchronized (lifecycleLock) {
            return acceptedMeshing;
        }
    }

    public Metrics metrics() {
        mainThreadGuard.assertMainThread("read chunk mesh metrics");
        synchronized (lifecycleLock) {
            return new Metrics(
                    acceptedMeshing,
                    queuedMeshing.size(),
                    activeMeshing,
                    completed.size() + failed.size(),
                    awaitingUpload.size(),
                    failedUploads.size(),
                    pendingDestructions.size());
        }
    }

    /** Monotonic owner observations used to derive immutable per-frame deltas. */
    public LifecycleMetrics lifecycleMetrics() {
        mainThreadGuard.assertMainThread("read chunk mesh lifecycle metrics");
        return new LifecycleMetrics(
                uploadedTotal,
                bytesUploadedTotal,
                destroyedTotal,
                lastMeshLatencyNanos);
    }

    public boolean retry(ChunkKey key) {
        mainThreadGuard.assertMainThread("retry chunk mesh");
        Objects.requireNonNull(key, "key");
        if (closed) {
            return false;
        }

        ChunkMeshData failedUpload = failedUploads.get(key);
        if (failedUpload != null
                && repository.isReadyForUpload(
                        key, failedUpload.revision())
                && failedUploads.remove(key, failedUpload)) {
            awaitingUpload.add(failedUpload);
            return true;
        }
        if (failedUpload != null && failedUploads.remove(key, failedUpload)) {
            releaseAccepted();
            repository.retry(key);
            return true;
        }
        return repository.retry(key);
    }

    public void unload(ChunkKey key) {
        mainThreadGuard.assertMainThread("unload chunk mesh");
        Objects.requireNonNull(key, "key");
        if (!closed && repository.beginUnload(key)) {
            removeQueuedMeshing(key);
            removeAwaitingUploads(key);
            ChunkMeshData failedUpload = failedUploads.remove(key);
            if (failedUpload != null) {
                releaseAccepted();
            }
            ChunkRenderObject object = installedRenderObjects.remove(key);
            pendingDestructions.add(new PendingDestruction(key, object, true));
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
            acceptedMeshing = 0;
            activeMeshing = 0;
            queuedMeshing.clear();
            completed.clear();
            failed.clear();
        }

        awaitingUpload.clear();
        failedUploads.clear();
        reportedFailures.clear();

        Throwable firstFailure = null;
        PendingDestruction pending;
        while ((pending = pendingDestructions.poll()) != null) {
            try {
                if (pending.object() != null) {
                    firstFailure = releaseForClose(pending.object(), firstFailure);
                }
            } finally {
                if (pending.completeUnload()) {
                    repository.completeUnload(pending.key());
                }
            }
        }
        for (ChunkRenderObject object :
                installedRenderObjects.values()) {
            firstFailure = releaseForClose(object, firstFailure);
        }
        installedRenderObjects.clear();

        if (firstFailure != null) {
            closeFailure = firstFailure;
            rethrow(firstFailure);
        }
    }

    private void buildMesh(ChunkMeshInput input) {
        long started = System.nanoTime();
        MeshingCompletion completion = null;
        MeshingFailure failureResult = null;
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
            completion = new MeshingCompletion(claimedKey, claimedRevision, data);
        } catch (RuntimeException | Error failure) {
            failureResult = new MeshingFailure(
                    input.center().key(), input.center().revision(), failure);
        }
        lastMeshLatencyNanos = Math.max(1L, System.nanoTime() - started);
        synchronized (lifecycleLock) {
            if (!closed) {
                activeMeshing--;
                if (completion != null) {
                    completed.add(completion);
                } else {
                    failed.add(failureResult);
                }
            }
        }
        dispatchAvailable();
    }

    private DispatchOutcome dispatchOne(boolean ownerPublicationAllowed) {
        ChunkMeshInput input;
        synchronized (lifecycleLock) {
            if (closed
                    || activeMeshing >= budget.maxActive()
                    || queuedMeshing.isEmpty()) {
                return DispatchOutcome.NONE;
            }
            input = queuedMeshing.remove();
            activeMeshing++;
        }
        try {
            meshExecutor.execute(() -> buildMesh(input));
            return DispatchOutcome.SUBMITTED;
        } catch (RuntimeException | Error failure) {
            boolean report;
            synchronized (lifecycleLock) {
                report = !closed;
                if (report) {
                    activeMeshing--;
                    if (ownerPublicationAllowed) {
                        acceptedMeshing--;
                    } else {
                        failed.add(new MeshingFailure(
                                input.center().key(),
                                input.center().revision(),
                                failure));
                    }
                }
            }
            if (report && ownerPublicationAllowed
                    && repository.markMeshingFailureIfCurrent(
                            input.center().key(),
                            input.center().revision(),
                            failure)) {
                reportFailure(failure);
            }
            return DispatchOutcome.REJECTED;
        }
    }

    private void dispatchAvailable() {
        DispatchOutcome outcome;
        do {
            outcome = dispatchOne(false);
        } while (outcome != DispatchOutcome.NONE);
    }

    private void drainDestructions() {
        while (!closed
                && remainingDestructionsInPump > 0
                && !pendingDestructions.isEmpty()) {
            PendingDestruction pending = pendingDestructions.remove();
            remainingDestructionsInPump--;
            try {
                if (pending.object() != null) {
                    try {
                        renderBackend.release(pending.object());
                        destroyedTotal++;
                    } catch (RuntimeException | Error failure) {
                        if (closed) {
                            rethrow(failure);
                        }
                        reportFailure(failure);
                    }
                }
            } finally {
                if (pending.completeUnload()) {
                    repository.completeUnload(pending.key());
                }
            }
        }
    }

    private void installEmptyMesh(ChunkMeshData data) {
        if (!repository.markRenderable(
                data.key(), data.revision())) {
            discardFailedUploadAtOrBefore(
                    data.key(), data.revision());
            releaseAccepted();
            return;
        }
        discardFailedUploadAtOrBefore(
                data.key(), data.revision());
        ChunkRenderObject previous =
                installedRenderObjects.remove(data.key());
        releaseAccepted();
        if (previous != null) {
            pendingDestructions.add(new PendingDestruction(
                    data.key(), previous, false));
        }
    }

    private void uploadReplacement(ChunkMeshData data) {
        if (!repository.isReadyForUpload(
                data.key(), data.revision())) {
            discardFailedUploadAtOrBefore(
                    data.key(), data.revision());
            releaseAccepted();
            return;
        }

        ChunkRenderObject replacement;
        ChunkRenderObject uploaded = null;
        try {
            uploaded =
                    Objects.requireNonNull(
                            renderBackend.upload(data),
                            "render backend upload result");
            replacement = uploaded;
            uploadedTotal++;
            bytesUploadedTotal = Math.addExact(
                    bytesUploadedTotal,
                    Math.multiplyExact((long) data.vertexCount(),
                            (long) VoxelVertexFormat.FLOATS_PER_VERTEX
                                    * Float.BYTES));
            if (!renderOrigin.chunkKey().equals(ZERO_CHUNK_KEY)) {
                replacement = replacement.forOrigin(renderOrigin);
            }
        } catch (RuntimeException | Error failure) {
            if (uploaded != null) {
                try {
                    renderBackend.release(uploaded);
                    destroyedTotal++;
                } catch (RuntimeException | Error cleanupFailure) {
                    addSuppressedIfDistinct(failure, cleanupFailure);
                }
            }
            if (closed) {
                if (closeFailure != null) {
                    addSuppressedIfDistinct(
                            closeFailure, failure);
                    rethrow(closeFailure);
                }
                return;
            }
            ChunkMeshData current =
                    failedUploads.get(data.key());
            if (current == null
                    || current.revision() <= data.revision()) {
                ChunkMeshData replaced = failedUploads.put(data.key(), data);
                if (replaced != null) {
                    releaseAccepted();
                }
            } else {
                releaseAccepted();
            }
            reportFailure(failure);
            return;
        }

        if (closed) {
            try {
                renderBackend.release(replacement);
            } catch (RuntimeException | Error cleanupFailure) {
                if (closeFailure == null) {
                    rethrow(cleanupFailure);
                } else {
                    addSuppressedIfDistinct(
                            closeFailure, cleanupFailure);
                }
            }
            if (closeFailure != null) {
                rethrow(closeFailure);
            }
            return;
        }

        boolean accepted;
        try {
            accepted =
                    repository.markRenderable(
                            data.key(), data.revision());
        } catch (RuntimeException | Error failure) {
            pendingDestructions.add(new PendingDestruction(
                    data.key(), replacement, false));
            releaseAccepted();
            throw failure;
        }
        if (!accepted) {
            pendingDestructions.add(new PendingDestruction(
                    data.key(), replacement, false));
            releaseAccepted();
            return;
        }

        discardFailedUploadAtOrBefore(
                data.key(), data.revision());
        ChunkRenderObject previous =
                installedRenderObjects.put(data.key(), replacement);
        releaseAccepted();
        if (previous != null) {
            pendingDestructions.add(new PendingDestruction(
                    data.key(), previous, false));
        }
    }

    private void releaseAccepted() {
        synchronized (lifecycleLock) {
            if (closed) {
                return;
            }
            if (acceptedMeshing <= 0) {
                throw new IllegalStateException("chunk mesh capacity token underflow");
            }
            acceptedMeshing--;
        }
    }

    private void discardFailedUploadAtOrBefore(
            ChunkKey key, long revision) {
        ChunkMeshData failedUpload = failedUploads.get(key);
        if (failedUpload != null
                && failedUpload.revision() <= revision
                && failedUploads.remove(key, failedUpload)) {
            releaseAccepted();
        }
    }

    private void removeQueuedMeshing(ChunkKey key) {
        synchronized (lifecycleLock) {
            int before = queuedMeshing.size();
            queuedMeshing.removeIf(input -> input.center().key().equals(key));
            acceptedMeshing -= before - queuedMeshing.size();
        }
    }

    private void removeAwaitingUploads(ChunkKey key) {
        for (ChunkMeshData data : List.copyOf(awaitingUpload)) {
            if (data.key().equals(key) && awaitingUpload.remove(data)) {
                releaseAccepted();
            }
        }
    }

    private void reportFailure(Throwable failure) {
        while (reportedFailures.size() >= budget.maxAccepted()) {
            reportedFailures.poll();
        }
        reportedFailures.add(failure);
    }

    private Throwable releaseForClose(
            ChunkRenderObject object, Throwable firstFailure) {
        try {
            renderBackend.release(object);
        } catch (RuntimeException | Error failure) {
            if (firstFailure == null) {
                return failure;
            }
            addSuppressedIfDistinct(firstFailure, failure);
        }
        return firstFailure;
    }

    private static void addSuppressedIfDistinct(
            Throwable primary, Throwable secondary) {
        if (secondary != primary) {
            primary.addSuppressed(secondary);
        }
    }

    private static void rethrow(Throwable failure) {
        if (failure instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
        throw (Error) failure;
    }

    public record Metrics(
            int accepted,
            int queued,
            int active,
            int completed,
            int awaitingUpload,
            int failedUploads,
            int pendingDestructions) {
        public Metrics {
            if (accepted < 0
                    || queued < 0
                    || active < 0
                    || completed < 0
                    || awaitingUpload < 0
                    || failedUploads < 0
                    || pendingDestructions < 0) {
                throw new IllegalArgumentException("chunk mesh metrics are negative");
            }
        }
    }

    public record LifecycleMetrics(
            long uploadedTotal,
            long bytesUploadedTotal,
            long destroyedTotal,
            long lastMeshLatencyNanos) {
        public LifecycleMetrics {
            if (uploadedTotal < 0L || bytesUploadedTotal < 0L
                    || destroyedTotal < 0L || lastMeshLatencyNanos < 0L) {
                throw new IllegalArgumentException(
                        "chunk mesh lifecycle metrics are negative");
            }
        }
    }

    @FunctionalInterface
    public interface PreparedOriginRebase {
        void commit();
    }

    private enum DispatchOutcome {
        NONE,
        SUBMITTED,
        REJECTED
    }

    private record PendingDestruction(
            ChunkKey key,
            ChunkRenderObject object,
            boolean completeUnload) {
        private PendingDestruction {
            Objects.requireNonNull(key, "key");
        }
    }

    private record MeshingCompletion(
            ChunkKey key, long revision, ChunkMeshData data) {}

    private record MeshingFailure(
            ChunkKey key, long revision, Throwable cause) {}
}

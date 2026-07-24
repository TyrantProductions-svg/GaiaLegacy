package com.overlord.voxel;

import com.overlord.core.thread.MainThreadGuard;
import com.overlord.renderer.ChunkRenderBackend;
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

    public boolean allRenderable(Set<ChunkKey> keys) {
        mainThreadGuard.assertMainThread("check renderable chunks");
        Objects.requireNonNull(keys, "keys");
        return keys.stream().allMatch(repository::isRenderable);
    }

    @Override
    public void close() {
        mainThreadGuard.assertMainThread("close chunk mesh manager");
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
            completed.add(
                    new MeshingCompletion(
                            claimedKey, claimedRevision, data));
        } catch (RuntimeException | Error failure) {
            failed.add(
                    new MeshingFailure(
                            input.center().key(),
                            input.center().revision(),
                            failure));
        }
    }

    private record MeshingCompletion(
            ChunkKey key, long revision, ChunkMeshData data) {}

    private record MeshingFailure(
            ChunkKey key, long revision, Throwable cause) {}
}

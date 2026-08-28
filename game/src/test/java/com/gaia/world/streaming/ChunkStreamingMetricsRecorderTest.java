package com.gaia.world.streaming;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.gaia.save.streaming.StreamedChunkUnloadResult;
import com.overlord.core.thread.MainThreadGuard;
import com.overlord.physics.SimulationOrigin;
import com.overlord.renderer.ChunkGpuMesh;
import com.overlord.renderer.ChunkRenderBackend;
import com.overlord.renderer.ChunkRenderObject;
import com.overlord.voxel.ChunkDirtyTracker;
import com.overlord.voxel.ChunkGenerationData;
import com.overlord.voxel.ChunkKey;
import com.overlord.voxel.ChunkMeshData;
import com.overlord.voxel.ChunkMeshManager;
import com.overlord.voxel.ChunkRepository;
import com.overlord.voxel.ChunkStreamingTicket;
import com.overlord.voxel.ChunkUnloadPreparation;
import com.overlord.voxel.GlobalPosition;
import com.overlord.worlditem.LogicalWorldItemService;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ChunkStreamingMetricsRecorderTest {
    @Test
    void continuouslyReadyOwnerLanesShareBudgetAndBothMakeBoundedProgress()
            throws Exception {
        MainThreadGuard guard = MainThreadGuard.captureCurrentThread();
        ChunkRepository repository = new ChunkRepository(1, new ChunkDirtyTracker());
        ChunkStreamingPolicy policy = ChunkStreamingPolicy.productionDefaults();
        ChunkStreamingPipeline pipeline = new ChunkStreamingPipeline(
                repository,
                policy,
                guard,
                work -> ChunkWorkResult.loadSuccess(
                        work.workId(),
                        work.key(),
                        work.desiredEpoch(),
                        work.expectedRevision(),
                        ChunkStreamingTicket.SourcePreference.GENERATE,
                        new ChunkGenerationData(work.key(), 1, new byte[16 * 16])),
                work -> { throw new AssertionError("save work was not expected"); },
                new NoUnloadLifecycle());
        ChunkMeshManager meshes = new ChunkMeshManager(
                repository,
                input -> new ChunkMeshData(
                        input.center().key(), input.center().revision(), new float[10]),
                Runnable::run,
                new FakeRenderBackend(),
                guard,
                2);
        LogicalWorldItemService worldItems = new LogicalWorldItemService(guard, 4, 0L);
        ChunkKey meshFirst = new ChunkKey(-2, 0);
        ChunkKey meshSecond = new ChunkKey(-1, 0);
        repository.generate(meshFirst,
                chunk -> chunk.setBlock(0, 0, 0, (byte) 1));
        repository.generate(meshSecond,
                chunk -> chunk.setBlock(0, 0, 0, (byte) 1));
        ChunkKey loadFirst = new ChunkKey(10, 0);
        ChunkKey loadSecond = new ChunkKey(11, 0);
        ChunkKey loadThird = new ChunkKey(12, 0);
        ChunkKey loadFourth = new ChunkKey(13, 0);
        Set<ChunkKey> desiredKeys = Set.of(
                loadFirst, loadSecond, loadThird, loadFourth);
        ChunkDesiredSets desired = new ChunkDesiredSets(
                desiredKeys, desiredKeys, desiredKeys);
        ChunkStreamingDecision decision = new ChunkStreamingDecision(
                desired,
                1L,
                List.of(loadFirst, loadSecond, loadThird, loadFourth),
                List.of(loadFirst, loadSecond, loadThird, loadFourth),
                List.of(),
                List.of(),
                List.of());
        ChunkStreamingMetricsRecorder recorder = new ChunkStreamingMetricsRecorder();

        try {
            recorder.capture(
                    new GlobalPosition(loadFirst, 0.5, 4.0, 0.5),
                    new SimulationOrigin(loadFirst),
                    decision,
                    0,
                    pipeline,
                    meshes,
                    worldItems);

            assertEquals(2, meshes.scheduleEligible(
                    List.of(meshFirst, meshSecond)));
            pipeline.apply(decision);
            pipeline.awaitWorkers(Duration.ofSeconds(5));

            for (int frame = 1; frame <= 2; frame++) {
                ChunkMeshManager.Metrics meshMetrics = meshes.metrics();
                assertEquals(2 - (frame - 1), meshMetrics.completed());
                int pipelineAllowance = policy.publicationBudget() - 1;
                int chunkPublications =
                        pipeline.drainOwnerResults(pipelineAllowance);
                int uploads = meshes.processMainThreadWork(
                        policy.publicationBudget() - chunkPublications);
                ChunkStreamingMetrics metrics = recorder.capture(
                        new GlobalPosition(loadFirst, 0.5, 4.0, 0.5),
                        new SimulationOrigin(loadFirst),
                        decision,
                        repository.keys().size(),
                        pipeline,
                        meshes,
                        worldItems);

                assertEquals(1, chunkPublications,
                        "pipeline must make progress in contention frame " + frame);
                assertEquals(1, uploads,
                        "mesh must make progress in contention frame " + frame);
                assertEquals(1L, metrics.uploadsThisFrame());
                assertEquals(2L, metrics.publicationsThisFrame());
            }
            assertEquals(2L, pipeline.metrics().published());
            assertEquals(2L, meshes.lifecycleMetrics().uploadedTotal());
            assertEquals(2, pipeline.drainOwnerResults(100),
                    "caller allowance cannot raise the fixed pipeline budget");
            assertThrows(IllegalArgumentException.class,
                    () -> pipeline.drainOwnerResults(-1));
            assertEquals(4L, pipeline.metrics().published());
        } finally {
            meshes.close();
            pipeline.close();
            worldItems.close();
        }
    }

    private static final class FakeRenderBackend implements ChunkRenderBackend {
        @Override
        public ChunkRenderObject upload(ChunkMeshData data) {
            return new ChunkRenderObject(
                    data.key(),
                    data.revision(),
                    new FakeGpuMesh(data.vertexCount()),
                    data.localBounds().orElseThrow());
        }

        @Override
        public void release(ChunkRenderObject object) {
            object.mesh().cleanup();
        }
    }

    private record FakeGpuMesh(int vertexCount) implements ChunkGpuMesh {
        @Override
        public void draw() {}

        @Override
        public void cleanup() {}
    }

    private static final class NoUnloadLifecycle
            implements ChunkStreamingPipeline.UnloadLifecycle {
        @Override
        public ChunkStreamingPipeline.PreparedUnload prepare(
                ChunkUnloadPreparation preparation) {
            throw new AssertionError("unload preparation was not expected");
        }

        @Override
        public boolean commit(
                ChunkStreamingPipeline.PreparedUnload prepared,
                StreamedChunkUnloadResult durability) {
            throw new AssertionError("unload commit was not expected");
        }

        @Override
        public void cancel(ChunkStreamingPipeline.PreparedUnload prepared) {
            throw new AssertionError("unload cancel was not expected");
        }
    }
}

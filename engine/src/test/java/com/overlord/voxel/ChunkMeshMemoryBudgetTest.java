package com.overlord.voxel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.overlord.core.thread.MainThreadGuard;
import com.overlord.renderer.AxisAlignedBounds;
import com.overlord.renderer.ChunkGpuMesh;
import com.overlord.renderer.ChunkRenderBackend;
import com.overlord.renderer.ChunkRenderObject;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ChunkMeshMemoryBudgetTest {
    private static final long ONE_MESH_OUTPUT_BYTES = 120L;
    private static final long ONE_MESH_ACTIVE_BYTES = 360L;

    @Test
    void productionPolicyIsOneHundredTwentyEightMibibytes() {
        assertEquals(134_217_728L,
                ChunkMeshManager.MAX_CPU_MESH_MEMORY_BYTES);
    }

    @Test
    void activeBuildRequiresItsWholeReservationAndPressureOnlyQueues() {
        Fixture fixture = fixture(2, ONE_MESH_ACTIVE_BYTES,
                plannedTriangleMesher());

        assertEquals(2, fixture.manager.scheduleEligible(fixture.keys));
        assertEquals(1, fixture.executor.size());
        assertEquals(1, fixture.manager.metrics().active());
        assertEquals(1, fixture.manager.metrics().queued());
        assertEquals(ONE_MESH_ACTIVE_BYTES,
                fixture.manager.memoryMetrics().activeReservedBytes());
        assertEquals(1,
                fixture.manager.memoryMetrics().memoryBlockedQueuedCount());
        assertTrue(fixture.manager.pollFailure().isEmpty());

        fixture.executor.runNext();

        assertEquals(0, fixture.manager.metrics().active());
        assertEquals(1, fixture.manager.metrics().completed());
        assertEquals(ONE_MESH_OUTPUT_BYTES,
                fixture.manager.memoryMetrics().completedRetainedBytes());
        assertEquals(0L,
                fixture.manager.memoryMetrics().activeReservedBytes());
        assertEquals(0, fixture.executor.size(),
                "the next job remains queued until completed output is drained");

        assertEquals(1, fixture.manager.processMainThreadWork());

        assertEquals(1, fixture.executor.size());
        assertEquals(ONE_MESH_ACTIVE_BYTES,
                fixture.manager.memoryMetrics().activeReservedBytes());
        assertTrue(fixture.manager.pollFailure().isEmpty(),
                "ordinary global pressure must not become a failure");
    }

    @Test
    void completedOutputAndDeclaredUploadScratchTransitionExactlyOnce() {
        Fixture fixture = fixture(1, 600L, plannedTriangleMesher());
        fixture.backend.requirement =
                new ChunkRenderBackend.UploadMemoryRequirement(120L, 120L);
        fixture.backend.duringUpload = () -> {
            ChunkMeshManager.MemoryMetrics metrics =
                    fixture.manager.memoryMetrics();
            assertEquals(120L, metrics.completedRetainedBytes());
            assertEquals(120L, metrics.uploadScratchBytes());
            assertEquals(240L, metrics.usedBytes());
            assertEquals(120L, metrics.directUploadBytes());
        };
        fixture.manager.scheduleEligible(fixture.keys);
        fixture.executor.runNext();

        assertEquals(120L,
                fixture.manager.memoryMetrics().completedRetainedBytes());
        assertEquals(1, fixture.manager.processMainThreadWork());

        ChunkMeshManager.MemoryMetrics after = fixture.manager.memoryMetrics();
        assertEquals(0L, after.activeReservedBytes());
        assertEquals(0L, after.completedRetainedBytes());
        assertEquals(0L, after.uploadScratchBytes());
        assertEquals(0L, after.usedBytes());
        assertEquals(0L, after.directUploadBytes());
        assertEquals(120L, after.peakDirectUploadBytes());
    }

    @Test
    void singleImpossibleReservationProducesDistinctTypedFailure() {
        long impossible = ChunkMeshManager.MAX_CPU_MESH_MEMORY_BYTES + 1L;
        ChunkMesher mesher = plannedMesher(
                new ChunkMeshMemoryPlan(ONE_MESH_OUTPUT_BYTES, impossible));
        Fixture fixture = fixture(
                1, ChunkMeshManager.MAX_CPU_MESH_MEMORY_BYTES, mesher);

        assertEquals(1, fixture.manager.scheduleEligible(fixture.keys));
        assertEquals(0, fixture.executor.size());
        assertEquals(1, fixture.manager.drainCompletedCpuWork());

        assertTrue(fixture.manager.pollFailure().isEmpty(),
                "single-job budget diagnostics are not fatal worker failures");
        ChunkMeshMemoryBudgetExceededException typed = fixture.manager
                .memoryBudgetDiagnostic(fixture.keys.get(0)).orElseThrow();
        assertEquals(fixture.keys.get(0), typed.chunkKey());
        assertEquals(ChunkMeshManager.MAX_CPU_MESH_MEMORY_BYTES,
                typed.configuredByteLimit());
        assertEquals(impossible, typed.requiredReservationBytes());
        assertTrue(fixture.manager.memoryBudgetDiagnostic(
                fixture.keys.get(0)).isPresent());
        assertEquals(ChunkMeshManager.MeshPhase.FAILED,
                fixture.manager.meshPhase(fixture.keys.get(0)));
        assertEquals(0L, fixture.manager.memoryMetrics().usedBytes());
    }

    @Test
    void staleCompletedOutputAndCloseReleaseAllAccounting() {
        Fixture fixture = fixture(2, 600L, plannedTriangleMesher());
        fixture.manager.scheduleEligible(fixture.keys);
        fixture.executor.runNext();
        ChunkKey first = fixture.keys.get(0);
        assertTrue(fixture.repository.setBlock(
                first.worldOriginX(), 0, first.worldOriginZ(), (byte) 2));

        fixture.manager.drainCompletedCpuWork();
        assertEquals(0L,
                fixture.manager.memoryMetrics().completedRetainedBytes());
        assertTrue(fixture.manager.memoryMetrics().usedBytes() >= 0L);

        fixture.manager.close();
        ChunkMeshManager.MemoryMetrics closed = fixture.manager.memoryMetrics();
        assertEquals(0L, closed.activeReservedBytes());
        assertEquals(0L, closed.completedRetainedBytes());
        assertEquals(0L, closed.uploadScratchBytes());
        assertEquals(0L, closed.usedBytes());
        assertEquals(0L, closed.directUploadBytes());
    }

    @Test
    void workerFailureAndErrorReleaseTheFullActiveReservation() {
        for (Throwable expected : List.of(
                new IllegalStateException("failure"),
                new AssertionError("error"))) {
            ChunkMesher mesher = new ChunkMesher() {
                @Override
                public ChunkMeshMemoryPlan preflight(ChunkMeshInput input) {
                    return new ChunkMeshMemoryPlan(120L, 360L);
                }

                @Override
                public ChunkMeshData build(ChunkMeshInput input) {
                    if (expected instanceof RuntimeException runtime) {
                        throw runtime;
                    }
                    throw (Error) expected;
                }
            };
            Fixture fixture = fixture(1, 360L, mesher);
            fixture.manager.scheduleEligible(fixture.keys);
            fixture.executor.runNext();

            assertEquals(0L,
                    fixture.manager.memoryMetrics().activeReservedBytes());
            assertEquals(0L, fixture.manager.memoryMetrics().usedBytes());
            assertEquals(1, fixture.manager.drainCompletedCpuWork());
            assertEquals(expected,
                    fixture.manager.pollFailure().orElseThrow());
        }
    }

    @Test
    void strictHeadAdmissionPreventsSmallBypassAndLargeEventuallyRuns() {
        List<ChunkKey> buildOrder = new ArrayList<>();
        ChunkMesher mesher = new ChunkMesher() {
            @Override
            public ChunkMeshMemoryPlan preflight(ChunkMeshInput input) {
                int ordinal = input.center().key().x() / 2;
                return new ChunkMeshMemoryPlan(
                        120L,
                        ordinal == 0 ? 600L : ordinal == 1 ? 500L : 120L);
            }

            @Override
            public ChunkMeshData build(ChunkMeshInput input) {
                buildOrder.add(input.center().key());
                return mesh(input);
            }
        };
        Fixture fixture = fixture(3, 600L, mesher);
        fixture.manager.scheduleEligible(fixture.keys);
        fixture.executor.runNext();

        assertEquals(0, fixture.executor.size(),
                "the small third job must not bypass the blocked large head");
        assertEquals(2,
                fixture.manager.memoryMetrics().memoryBlockedQueuedCount());

        fixture.manager.processMainThreadWork();
        assertEquals(1, fixture.executor.size());
        fixture.executor.runNext();
        fixture.manager.processMainThreadWork();
        assertEquals(1, fixture.executor.size());
        fixture.executor.runNext();

        assertEquals(fixture.keys, buildOrder);
    }

    @Test
    void activeCancellationAndCompletedCancellationReleaseExactlyOnce() {
        Fixture active = fixture(1, 360L, plannedTriangleMesher());
        active.manager.scheduleEligible(active.keys);
        active.manager.unload(active.keys.get(0));
        assertEquals(360L, active.manager.memoryMetrics().usedBytes(),
                "active work retains its full reservation until worker exit");
        active.executor.runNext();
        assertEquals(120L, active.manager.memoryMetrics().usedBytes());
        active.manager.drainCompletedCpuWork();
        assertEquals(0L, active.manager.memoryMetrics().usedBytes());

        Fixture completed = fixture(1, 360L, plannedTriangleMesher());
        completed.manager.scheduleEligible(completed.keys);
        completed.executor.runNext();
        assertEquals(120L, completed.manager.memoryMetrics().usedBytes());
        completed.manager.unload(completed.keys.get(0));
        assertEquals(0L, completed.manager.memoryMetrics().usedBytes());
        assertEquals(0, completed.manager.metrics().accepted());
    }

    @Test
    void impossibleRevisionDoesNotRetryUntilRevisionChanges() {
        AtomicInteger builds = new AtomicInteger();
        ChunkMesher mesher = new ChunkMesher() {
            @Override
            public ChunkMeshMemoryPlan preflight(ChunkMeshInput input) {
                return input.center().revision() == 1L
                        ? new ChunkMeshMemoryPlan(
                                120L,
                                ChunkMeshManager.MAX_CPU_MESH_MEMORY_BYTES + 1L)
                        : new ChunkMeshMemoryPlan(120L, 360L);
            }

            @Override
            public ChunkMeshData build(ChunkMeshInput input) {
                builds.incrementAndGet();
                return mesh(input);
            }
        };
        Fixture fixture = fixture(
                1, ChunkMeshManager.MAX_CPU_MESH_MEMORY_BYTES, mesher);
        ChunkKey key = fixture.keys.get(0);
        fixture.manager.scheduleEligible(fixture.keys);
        fixture.manager.drainCompletedCpuWork();

        assertEquals(0, fixture.manager.scheduleEligible(fixture.keys));
        assertEquals(0, fixture.executor.size());
        assertEquals(0, builds.get());

        assertTrue(fixture.repository.setBlock(
                key.worldOriginX(), 0, key.worldOriginZ(), (byte) 2));
        assertEquals(1, fixture.manager.scheduleEligible(fixture.keys));
        fixture.executor.runNext();
        assertEquals(1, builds.get());
    }

    private static Fixture fixture(
            int count, long memoryBudget, ChunkMesher mesher) {
        ChunkRepository repository = new ChunkRepository(
                1, new ChunkDirtyTracker());
        List<ChunkKey> keys = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            ChunkKey key = new ChunkKey(index * 2, 0);
            keys.add(key);
            repository.generate(key,
                    chunk -> chunk.setBlock(0, 0, 0, (byte) 1));
        }
        ManualExecutor executor = new ManualExecutor();
        RecordingBackend backend = new RecordingBackend();
        ChunkMeshManager manager = new ChunkMeshManager(
                repository,
                mesher,
                executor,
                backend,
                MainThreadGuard.captureCurrentThread(),
                ChunkMeshBudget.productionDefaults(),
                memoryBudget);
        return new Fixture(repository, List.copyOf(keys), executor,
                backend, manager);
    }

    private static ChunkMesher plannedTriangleMesher() {
        return plannedMesher(new ChunkMeshMemoryPlan(
                ONE_MESH_OUTPUT_BYTES, ONE_MESH_ACTIVE_BYTES));
    }

    private static ChunkMesher plannedMesher(ChunkMeshMemoryPlan plan) {
        return new ChunkMesher() {
            @Override
            public ChunkMeshMemoryPlan preflight(ChunkMeshInput input) {
                return plan;
            }

            @Override
            public ChunkMeshData build(ChunkMeshInput input) {
                return mesh(input);
            }

            @Override
            public ChunkMeshData build(
                    ChunkMeshInput input, ChunkMeshMemoryPlan approvedPlan) {
                assertEquals(plan, approvedPlan);
                return mesh(input);
            }
        };
    }

    private static ChunkMeshData mesh(ChunkMeshInput input) {
        return new ChunkMeshData(
                input.center().key(), input.center().revision(), triangle());
    }

    private static float[] triangle() {
        return new float[] {
            0, 0, 0, 0, 0, 0, 0, 1, 15, 1,
            1, 0, 0, 1, 0, 0, 0, 1, 15, 1,
            0, 1, 0, 0, 1, 0, 0, 1, 15, 1
        };
    }

    private record Fixture(
            ChunkRepository repository,
            List<ChunkKey> keys,
            ManualExecutor executor,
            RecordingBackend backend,
            ChunkMeshManager manager) {}

    private static final class ManualExecutor
            implements java.util.concurrent.Executor {
        private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();

        @Override
        public void execute(Runnable command) {
            tasks.addLast(command);
        }

        private int size() {
            return tasks.size();
        }

        private void runNext() {
            tasks.removeFirst().run();
        }
    }

    private static final class RecordingBackend
            implements ChunkRenderBackend {
        private UploadMemoryRequirement requirement =
                UploadMemoryRequirement.NONE;
        private Runnable duringUpload;

        @Override
        public UploadMemoryRequirement uploadMemoryRequirement(
                ChunkMeshData data) {
            return requirement;
        }

        @Override
        public ChunkRenderObject upload(ChunkMeshData data) {
            if (duringUpload != null) {
                duringUpload.run();
            }
            return new ChunkRenderObject(
                    data.key(), data.revision(),
                    new FakeGpuMesh(data.vertexCount()),
                    data.localBounds().orElseThrow());
        }

        @Override
        public void release(ChunkRenderObject object) {
            object.mesh().cleanup();
        }
    }

    private static final class FakeGpuMesh implements ChunkGpuMesh {
        private final int vertexCount;

        private FakeGpuMesh(int vertexCount) {
            this.vertexCount = vertexCount;
        }

        @Override
        public int vertexCount() {
            return vertexCount;
        }

        @Override
        public void draw() {}

        @Override
        public void cleanup() {}
    }
}

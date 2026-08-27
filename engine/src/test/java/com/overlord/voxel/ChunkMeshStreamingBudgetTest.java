package com.overlord.voxel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.overlord.core.thread.MainThreadGuard;
import com.overlord.renderer.AxisAlignedBounds;
import com.overlord.renderer.ChunkGpuMesh;
import com.overlord.renderer.ChunkRenderBackend;
import com.overlord.renderer.ChunkRenderObject;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ChunkMeshStreamingBudgetTest {
    @Test
    void acceptedBacklogNeverExceedsThirtyTwoAndOnlyTwoReachExecutor() {
        Fixture fixture = fixture(40);

        assertEquals(32, fixture.manager.scheduleEligible());
        assertEquals(2, fixture.executor.size());
        assertEquals(
                new ChunkMeshManager.Metrics(32, 30, 2, 0, 0, 0, 0),
                fixture.manager.metrics());
        assertEquals(0, fixture.manager.scheduleEligible());

        fixture.executor.runLast();

        assertEquals(2, fixture.executor.size(),
                "one completion dispatches one bounded queued input");
        assertEquals(32, fixture.manager.metrics().accepted());
        assertEquals(1, fixture.manager.metrics().completed());
    }

    @Test
    void completedButUndrainedMeshesKeepCapacityUntilTerminalPublication() {
        Fixture fixture = fixture(40);
        assertEquals(32, fixture.manager.scheduleEligible());

        fixture.executor.runAll();

        assertEquals(32, fixture.manager.metrics().accepted());
        assertEquals(32, fixture.manager.metrics().completed());
        assertEquals(0, fixture.manager.scheduleEligible());
        assertEquals(32, fixture.manager.drainCompletedCpuWork());
        assertEquals(32, fixture.manager.metrics().accepted());
        assertEquals(32, fixture.manager.metrics().awaitingUpload());

        assertEquals(2, fixture.manager.processMainThreadWork());
        assertEquals(30, fixture.manager.metrics().accepted());
        assertEquals(2, fixture.manager.scheduleEligible());
        assertEquals(32, fixture.manager.metrics().accepted());
    }

    @Test
    void ownerFrameDequeuesOnlyTheBoundedUploadBudgetFromCompletedBacklog() {
        Fixture fixture = fixture(40);
        assertEquals(32, fixture.manager.scheduleEligible());
        fixture.executor.runAll();
        assertEquals(32, fixture.manager.metrics().completed());

        assertEquals(2, fixture.manager.processMainThreadWork());

        assertEquals(30, fixture.manager.metrics().completed());
        assertEquals(30, fixture.manager.metrics().accepted());
        assertEquals(0, fixture.manager.metrics().awaitingUpload());
        assertEquals(2, fixture.backend.uploaded.size());
        assertEquals(2, fixture.manager.scheduleEligible());
        assertEquals(32, fixture.manager.metrics().accepted());
    }

    @Test
    void oneFrameUploadsAtMostTwoAndDestroysAtMostFourGpuObjects() {
        Fixture fixture = fixture(6);
        assertEquals(6, fixture.manager.scheduleEligible());
        fixture.executor.runAll();
        assertEquals(6, fixture.manager.drainCompletedCpuWork());

        assertEquals(2, fixture.manager.processMainThreadWork());
        assertEquals(2, fixture.backend.uploaded.size());
        assertEquals(2, fixture.manager.processMainThreadWork());
        assertEquals(2, fixture.manager.processMainThreadWork());
        assertEquals(6, fixture.backend.uploaded.size());
        for (ChunkKey key : fixture.keys) {
            fixture.manager.unload(key);
        }

        fixture.manager.processMainThreadWork();

        assertEquals(4, fixture.backend.released.size());
        assertEquals(2, fixture.manager.metrics().pendingDestructions());
        assertEquals(2, fixture.keys.stream().filter(fixture.repository::contains).count());

        fixture.manager.processMainThreadWork();

        assertEquals(6, fixture.backend.released.size());
        assertEquals(0, fixture.manager.metrics().pendingDestructions());
        assertTrue(fixture.keys.stream().noneMatch(fixture.repository::contains));
    }

    @Test
    void reentrantUploadPumpSharesTopLevelFrameBudget() {
        Fixture fixture = fixture(4);
        fixture.manager.scheduleEligible();
        fixture.executor.runAll();
        fixture.manager.drainCompletedCpuWork();
        AtomicReference<Integer> nestedProcessed = new AtomicReference<>();
        fixture.backend.beforeUpload = () -> nestedProcessed.set(
                fixture.manager.processMainThreadWork());

        int outerProcessed = fixture.manager.processMainThreadWork();

        assertEquals(2, fixture.backend.uploaded.size());
        assertEquals(2, outerProcessed + nestedProcessed.get());
        assertEquals(2, fixture.manager.metrics().awaitingUpload());
    }

    @Test
    void reentrantDestructionPumpSharesTopLevelFrameBudget() {
        Fixture fixture = fixture(6);
        fixture.manager.scheduleEligible();
        fixture.executor.runAll();
        fixture.manager.drainCompletedCpuWork();
        fixture.manager.processMainThreadWork();
        fixture.manager.processMainThreadWork();
        fixture.manager.processMainThreadWork();
        fixture.keys.forEach(fixture.manager::unload);
        AtomicReference<Integer> nestedProcessed = new AtomicReference<>();
        fixture.backend.beforeRelease = () -> nestedProcessed.set(
                fixture.manager.processMainThreadWork());

        fixture.manager.processMainThreadWork();

        assertEquals(0, nestedProcessed.get());
        assertEquals(4, fixture.backend.released.size());
        assertEquals(2, fixture.manager.metrics().pendingDestructions());
        assertEquals(2, fixture.keys.stream().filter(fixture.repository::contains).count());
    }

    @Test
    void staleReadyMeshReleasesCapacityWithoutCallingGpuBackend() {
        Fixture fixture = fixture(1);
        ChunkKey key = fixture.keys.get(0);
        fixture.manager.scheduleEligible();
        fixture.executor.runAll();
        fixture.manager.drainCompletedCpuWork();
        assertTrue(fixture.repository.setBlock(
                key.worldOriginX(), 0, key.worldOriginZ(), (byte) 2));

        assertEquals(0, fixture.manager.processMainThreadWork());

        assertTrue(fixture.backend.uploaded.isEmpty());
        assertEquals(0, fixture.manager.metrics().accepted());
        assertEquals(ChunkState.DIRTY, fixture.repository.state(key));
    }

    @Test
    void workerSideRefillRejectionPublishesOnlyDuringOwnerDrain()
            throws Exception {
        ChunkRepository repository = new ChunkRepository(1, new ChunkDirtyTracker());
        List<ChunkKey> keys = List.of(
                new ChunkKey(0, 0), new ChunkKey(2, 0), new ChunkKey(4, 0));
        for (ChunkKey key : keys) {
            repository.generate(key, chunk -> chunk.setBlock(0, 0, 0, (byte) 1));
        }
        IllegalStateException rejection = new IllegalStateException("refill rejected");
        RejectingRefillExecutor executor = new RejectingRefillExecutor(rejection);
        ChunkMeshManager manager = new ChunkMeshManager(
                repository,
                input -> new ChunkMeshData(
                        input.center().key(), input.center().revision(), triangle()),
                executor,
                new FakeBackend(),
                MainThreadGuard.captureCurrentThread(),
                ChunkMeshBudget.productionDefaults());
        assertEquals(3, manager.scheduleEligible());

        executor.runFirstOnWorker();

        assertTrue(keys.stream().allMatch(
                key -> repository.state(key) == ChunkState.MESHING));
        assertTrue(manager.pollFailure().isEmpty());
        assertEquals(3, manager.metrics().accepted());
        assertEquals(2, manager.metrics().completed());

        assertEquals(2, manager.drainCompletedCpuWork());
        assertEquals(1, keys.stream().filter(
                key -> repository.state(key) == ChunkState.DIRTY).count());
        assertEquals(1, keys.stream().filter(
                key -> repository.state(key) == ChunkState.READY_FOR_UPLOAD).count());
        assertEquals(rejection, manager.pollFailure().orElseThrow());
    }

    @Test
    void orderedSchedulingClaimsOnlySuppliedSimulationAndRenderKeysNearFirst() {
        ChunkRepository repository = new ChunkRepository(1, new ChunkDirtyTracker());
        ChunkKey center = new ChunkKey(0, 0);
        ChunkKey west = new ChunkKey(-1, 0);
        ChunkKey east = new ChunkKey(1, 0);
        ChunkKey render = new ChunkKey(4, 0);
        ChunkKey preloadOnly = new ChunkKey(5, 0);
        for (ChunkKey key : List.of(center, west, east, render, preloadOnly)) {
            repository.generate(key, chunk -> chunk.setBlock(0, 0, 0, (byte) 1));
        }
        List<ChunkKey> meshed = new ArrayList<>();
        ManualExecutor executor = new ManualExecutor();
        ChunkMeshManager manager = new ChunkMeshManager(
                repository,
                input -> {
                    meshed.add(input.center().key());
                    return new ChunkMeshData(
                            input.center().key(), input.center().revision(), triangle());
                },
                executor,
                new FakeBackend(),
                MainThreadGuard.captureCurrentThread(),
                ChunkMeshBudget.productionDefaults());

        assertEquals(4, scheduleOrdered(
                manager, List.of(center, west, east, render)));
        executor.runAll();

        assertEquals(List.of(center, west, east, render), meshed);
        assertEquals(ChunkState.GENERATED, repository.state(preloadOnly),
                "preload-only data must not be scheduled by the ordered visible path");
        assertEquals(32, ChunkMeshBudget.productionDefaults().maxAccepted());
        assertEquals(2, ChunkMeshBudget.productionDefaults().maxActive());
    }

    @Test
    void centerShiftReordersOnlyNotYetStartedQueuedMeshInputs() {
        ChunkRepository repository = new ChunkRepository(1, new ChunkDirtyTracker());
        List<ChunkKey> initial = List.of(
                new ChunkKey(0, 0),
                new ChunkKey(1, 0),
                new ChunkKey(2, 0),
                new ChunkKey(3, 0),
                new ChunkKey(4, 0),
                new ChunkKey(5, 0));
        initial.forEach(key -> repository.generate(
                key, chunk -> chunk.setBlock(0, 0, 0, (byte) 1)));
        List<ChunkKey> meshed = new ArrayList<>();
        ManualExecutor executor = new ManualExecutor();
        ChunkMeshManager manager = new ChunkMeshManager(
                repository,
                input -> {
                    meshed.add(input.center().key());
                    return new ChunkMeshData(
                            input.center().key(), input.center().revision(), triangle());
                },
                executor,
                new FakeBackend(),
                MainThreadGuard.captureCurrentThread(),
                ChunkMeshBudget.productionDefaults());

        assertEquals(6, scheduleOrdered(manager, initial));
        assertEquals(2, executor.size());
        List<ChunkKey> shifted = List.of(
                initial.get(0), initial.get(1),
                initial.get(5), initial.get(4), initial.get(3), initial.get(2));
        assertEquals(0, scheduleOrdered(manager, shifted));

        executor.runAll();

        assertEquals(shifted, meshed,
                "active work stays fixed while the queued deque follows current priority");
        assertTrue(manager.metrics().accepted() <= 32);
        assertTrue(manager.metrics().active() <= 2);
    }

    @Test
    void fullCapacityReleasesOneExactQueuedClaimForNewNearResidentKey() {
        ChunkRepository repository = new ChunkRepository(1, new ChunkDirtyTracker());
        List<ChunkKey> old = new ArrayList<>();
        for (int index = 0; index < 32; index++) {
            ChunkKey key = new ChunkKey(index + 10, 0);
            old.add(key);
            repository.generate(key, chunk -> chunk.setBlock(0, 0, 0, (byte) 1));
        }
        ChunkKey near = new ChunkKey(0, 0);
        repository.generate(near, chunk -> chunk.setBlock(0, 0, 0, (byte) 1));
        List<ChunkKey> meshed = new ArrayList<>();
        ManualExecutor executor = new ManualExecutor();
        ChunkMeshManager manager = new ChunkMeshManager(
                repository,
                input -> {
                    meshed.add(input.center().key());
                    return new ChunkMeshData(
                            input.center().key(), input.center().revision(), triangle());
                },
                executor,
                new FakeBackend(),
                MainThreadGuard.captureCurrentThread(),
                ChunkMeshBudget.productionDefaults());

        assertEquals(32, manager.scheduleEligible(old));
        assertEquals(32, manager.metrics().accepted());
        assertEquals(2, manager.metrics().active());
        List<ChunkKey> current = new ArrayList<>();
        current.add(near);
        current.addAll(old);

        assertEquals(1, manager.scheduleEligible(current));
        assertEquals(ChunkState.MESHING, repository.state(near));
        assertEquals(32, manager.metrics().accepted());
        assertEquals(2, manager.metrics().active());

        executor.runAll();
        assertEquals(near, meshed.get(2),
                "the newly critical resident key must run after the two pinned active inputs");
        assertEquals(32, meshed.size(),
                "one lower-priority queued claim is released rather than exceeding capacity");
    }

    private static int scheduleOrdered(
            ChunkMeshManager manager, List<ChunkKey> orderedKeys) {
        Object result = assertDoesNotThrow(() -> manager.getClass()
                .getMethod("scheduleEligible", List.class)
                .invoke(manager, orderedKeys));
        return (Integer) result;
    }

    private static Fixture fixture(int count) {
        ChunkRepository repository = new ChunkRepository(1, new ChunkDirtyTracker());
        List<ChunkKey> keys = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            ChunkKey key = new ChunkKey(index * 2, 0);
            keys.add(key);
            repository.generate(key, chunk -> chunk.setBlock(0, 0, 0, (byte) 1));
        }
        ManualExecutor executor = new ManualExecutor();
        FakeBackend backend = new FakeBackend();
        ChunkMeshManager manager = new ChunkMeshManager(
                repository,
                input -> new ChunkMeshData(
                        input.center().key(), input.center().revision(), triangle()),
                executor,
                backend,
                MainThreadGuard.captureCurrentThread(),
                ChunkMeshBudget.productionDefaults());
        return new Fixture(repository, executor, backend, manager, List.copyOf(keys));
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
            ManualExecutor executor,
            FakeBackend backend,
            ChunkMeshManager manager,
            List<ChunkKey> keys) {}

    private static final class ManualExecutor implements java.util.concurrent.Executor {
        private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();

        @Override
        public void execute(Runnable command) {
            tasks.addLast(command);
        }

        void runLast() {
            tasks.removeLast().run();
        }

        void runAll() {
            while (!tasks.isEmpty()) {
                tasks.removeFirst().run();
            }
        }

        int size() {
            return tasks.size();
        }
    }

    private static final class RejectingRefillExecutor
            implements java.util.concurrent.Executor {
        private final Queue<Runnable> accepted = new ArrayDeque<>();
        private final RuntimeException rejection;
        private int submissions;

        private RejectingRefillExecutor(RuntimeException rejection) {
            this.rejection = rejection;
        }

        @Override
        public synchronized void execute(Runnable command) {
            submissions++;
            if (submissions > 2) {
                throw rejection;
            }
            accepted.add(command);
        }

        void runFirstOnWorker() throws InterruptedException {
            Runnable task;
            synchronized (this) {
                task = accepted.remove();
            }
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread worker = new Thread(() -> {
                try {
                    task.run();
                } catch (Throwable thrown) {
                    failure.set(thrown);
                }
            }, "task9-refill-worker");
            worker.start();
            worker.join(5_000L);
            assertFalse(worker.isAlive());
            assertNull(failure.get());
        }
    }

    private static final class FakeBackend implements ChunkRenderBackend {
        private final List<ChunkRenderObject> uploaded = new ArrayList<>();
        private final List<ChunkRenderObject> released = new ArrayList<>();
        private Runnable beforeUpload;
        private Runnable beforeRelease;

        @Override
        public ChunkRenderObject upload(ChunkMeshData data) {
            runOnceBeforeUpload();
            ChunkRenderObject object = new ChunkRenderObject(
                    data.key(), data.revision(),
                    new FakeGpuMesh(data.vertexCount()),
                    data.localBounds().orElseThrow());
            uploaded.add(object);
            return object;
        }

        @Override
        public void release(ChunkRenderObject object) {
            released.add(object);
            object.mesh().cleanup();
            runOnceBeforeRelease();
        }

        private void runOnceBeforeUpload() {
            Runnable callback = beforeUpload;
            beforeUpload = null;
            if (callback != null) {
                callback.run();
            }
        }

        private void runOnceBeforeRelease() {
            Runnable callback = beforeRelease;
            beforeRelease = null;
            if (callback != null) {
                callback.run();
            }
        }
    }

    private static final class FakeGpuMesh implements ChunkGpuMesh {
        private final int vertexCount;
        private boolean released;

        private FakeGpuMesh(int vertexCount) {
            this.vertexCount = vertexCount;
        }

        @Override
        public int vertexCount() {
            return vertexCount;
        }

        @Override
        public void draw() {
            assertFalse(released);
        }

        @Override
        public void cleanup() {
            assertFalse(released);
            released = true;
        }
    }
}

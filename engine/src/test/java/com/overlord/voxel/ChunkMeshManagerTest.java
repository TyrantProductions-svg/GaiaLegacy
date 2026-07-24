package com.overlord.voxel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.overlord.config.GameConfig;
import com.overlord.core.thread.MainThreadGuard;
import com.overlord.renderer.ChunkRenderBackend;
import com.overlord.renderer.ChunkRenderObject;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ChunkMeshManagerTest {
    private static final ChunkKey KEY = new ChunkKey(0, 0);

    @Test
    void coalescesRepeatedDirtyStateIntoOneClaimedTask() {
        Fixture fixture = generatedFixture();

        assertEquals(1, fixture.manager.scheduleEligible());
        assertEquals(0, fixture.manager.scheduleEligible());

        assertEquals(1, fixture.executor.size());
        assertEquals(ChunkState.MESHING, fixture.repository.state(KEY));
    }

    @Test
    void discardsResultWhenRevisionChangesDuringMeshing() {
        Fixture fixture = generatedFixture();
        fixture.manager.scheduleEligible();

        fixture.repository.setBlock(1, 1, 1, (byte) 2);
        fixture.executor.runNext();

        assertEquals(1, fixture.manager.drainCompletedCpuWork());
        assertEquals(ChunkState.DIRTY, fixture.repository.state(KEY));
    }

    @Test
    void staleResultDoesNotClobberNewerMeshingClaim() {
        Fixture fixture = generatedFixture();
        fixture.manager.scheduleEligible();
        fixture.repository.setBlock(1, 1, 1, (byte) 2);
        fixture.manager.scheduleEligible();

        fixture.executor.runNext();
        fixture.manager.drainCompletedCpuWork();

        assertEquals(ChunkState.MESHING, fixture.repository.state(KEY));

        fixture.executor.runNext();
        fixture.manager.drainCompletedCpuWork();

        assertEquals(
                ChunkState.READY_FOR_UPLOAD,
                fixture.repository.state(KEY));
    }

    @Test
    void workerBuildsFromSnapshotCapturedWhenTaskWasClaimed() {
        ChunkRepository repository = generatedRepository();
        ManualExecutor executor = new ManualExecutor();
        AtomicReference<ChunkMeshInput> observedInput = new AtomicReference<>();
        ChunkMeshManager manager =
                new ChunkMeshManager(
                        repository,
                        input -> {
                            observedInput.set(input);
                            return meshFor(input);
                        },
                        executor,
                        failOnBackendCall(new AtomicInteger()),
                        MainThreadGuard.captureCurrentThread(),
                        2);

        manager.scheduleEligible();
        repository.setBlock(1, 1, 1, (byte) 2);
        executor.runNext();

        assertEquals(
                1,
                Byte.toUnsignedInt(
                        observedInput.get().center().getBlock(1, 1, 1)));
        assertEquals(2, Byte.toUnsignedInt(repository.getBlock(1, 1, 1)));
    }

    @Test
    void neighborEdgeMutationInvalidatesPendingWork() {
        ChunkRepository repository = new ChunkRepository();
        ChunkKey east = KEY.east();
        repository.generate(
                KEY, chunk -> chunk.setBlock(1, 1, 1, (byte) 1));
        repository.generate(
                east, chunk -> chunk.setBlock(1, 1, 1, (byte) 1));
        ManualExecutor executor = new ManualExecutor();
        ChunkMeshManager manager =
                new ChunkMeshManager(
                        repository,
                        ChunkMeshManagerTest::meshFor,
                        executor,
                        failOnBackendCall(new AtomicInteger()),
                        MainThreadGuard.captureCurrentThread(),
                        2);
        manager.scheduleEligible();

        repository.setBlock(
                east.worldOriginX(), 1, east.worldOriginZ() + 1, (byte) 2);
        executor.runAll();
        manager.drainCompletedCpuWork();

        assertEquals(ChunkState.DIRTY, repository.state(KEY));
    }

    @Test
    void transfersWorkerFailureOnlyWhenCpuWorkIsDrained() {
        ChunkRepository repository = generatedRepository();
        ManualExecutor executor = new ManualExecutor();
        IllegalStateException expected =
                new IllegalStateException("mesher failed");
        ChunkMeshManager manager =
                new ChunkMeshManager(
                        repository,
                        input -> {
                            throw expected;
                        },
                        executor,
                        failOnBackendCall(new AtomicInteger()),
                        MainThreadGuard.captureCurrentThread(),
                        2);
        manager.scheduleEligible();

        executor.runNext();

        assertEquals(ChunkState.MESHING, repository.state(KEY));
        assertTrue(manager.pollFailure().isEmpty());

        assertEquals(1, manager.drainCompletedCpuWork());
        assertEquals(ChunkState.DIRTY, repository.state(KEY));
        assertSame(expected, manager.pollFailure().orElseThrow());
        assertTrue(manager.pollFailure().isEmpty());
    }

    @Test
    void discardsStaleWorkerFailureWithoutDiagnosingNewerClaim() {
        ChunkRepository repository = generatedRepository();
        ManualExecutor executor = new ManualExecutor();
        AtomicInteger attempts = new AtomicInteger();
        ChunkMeshManager manager =
                new ChunkMeshManager(
                        repository,
                        input -> {
                            if (attempts.incrementAndGet() == 1) {
                                throw new IllegalStateException(
                                        "stale failure");
                            }
                            return meshFor(input);
                        },
                        executor,
                        failOnBackendCall(new AtomicInteger()),
                        MainThreadGuard.captureCurrentThread(),
                        2);
        manager.scheduleEligible();
        executor.runNext();
        repository.setBlock(1, 1, 1, (byte) 2);
        manager.scheduleEligible();

        assertEquals(1, manager.drainCompletedCpuWork());
        assertEquals(ChunkState.MESHING, repository.state(KEY));
        assertTrue(manager.pollFailure().isEmpty());

        executor.runNext();
        assertEquals(1, manager.drainCompletedCpuWork());
        assertEquals(
                ChunkState.READY_FOR_UPLOAD,
                repository.state(KEY));
    }

    @Test
    void rejectsMesherResultWithWrongChunkIdentity() {
        ChunkRepository repository = generatedRepository();
        ManualExecutor executor = new ManualExecutor();
        ChunkMeshManager manager =
                new ChunkMeshManager(
                        repository,
                        input ->
                                new ChunkMeshData(
                                        KEY.east(),
                                        input.center().revision(),
                                        oneBlockVertices()),
                        executor,
                        failOnBackendCall(new AtomicInteger()),
                        MainThreadGuard.captureCurrentThread(),
                        2);
        manager.scheduleEligible();

        executor.runNext();
        manager.drainCompletedCpuWork();

        assertEquals(ChunkState.DIRTY, repository.state(KEY));
        assertTrue(manager.pollFailure().isPresent());
    }

    @Test
    void rejectsMesherResultWithWrongRevision() {
        ChunkRepository repository = generatedRepository();
        ManualExecutor executor = new ManualExecutor();
        ChunkMeshManager manager =
                new ChunkMeshManager(
                        repository,
                        input ->
                                new ChunkMeshData(
                                        input.center().key(),
                                        input.center().revision() + 1,
                                        oneBlockVertices()),
                        executor,
                        failOnBackendCall(new AtomicInteger()),
                        MainThreadGuard.captureCurrentThread(),
                        2);
        manager.scheduleEligible();

        executor.runNext();
        manager.drainCompletedCpuWork();

        assertEquals(ChunkState.DIRTY, repository.state(KEY));
        assertTrue(manager.pollFailure().isPresent());
    }

    @Test
    void failedEntryIsNotAutomaticallyRescheduledUntilExplicitRetry() {
        ChunkRepository repository = generatedRepository();
        ManualExecutor executor = new ManualExecutor();
        AtomicInteger attempts = new AtomicInteger();
        ChunkMeshManager manager =
                new ChunkMeshManager(
                        repository,
                        input -> {
                            if (attempts.incrementAndGet() == 1) {
                                throw new IllegalStateException("mesher failed");
                            }
                            return meshFor(input);
                        },
                        executor,
                        failOnBackendCall(new AtomicInteger()),
                        MainThreadGuard.captureCurrentThread(),
                        2);
        manager.scheduleEligible();
        executor.runNext();
        manager.drainCompletedCpuWork();

        assertEquals(0, manager.scheduleEligible());

        repository.retry(KEY);

        assertEquals(1, manager.scheduleEligible());
        executor.runNext();
        assertEquals(1, manager.drainCompletedCpuWork());
        assertEquals(ChunkState.READY_FOR_UPLOAD, repository.state(KEY));
    }

    @Test
    void cpuSchedulingAndDrainingNeverCallRenderBackend() {
        Fixture fixture = generatedFixture();

        fixture.manager.scheduleEligible();
        fixture.executor.runAll();
        fixture.manager.drainCompletedCpuWork();
        fixture.manager.close();

        assertEquals(0, fixture.backendCalls.get());
        assertEquals(
                ChunkState.READY_FOR_UPLOAD,
                fixture.repository.state(KEY));
    }

    @Test
    void allRenderableReflectsRepositoryStateAndEmptySetsAreComplete() {
        Fixture fixture = generatedFixture();

        assertTrue(fixture.manager.allRenderable(Set.of()));
        assertFalse(fixture.manager.allRenderable(Set.of(KEY)));
    }

    @Test
    void constructorRejectsInvalidDependenciesAndUploadBudget() {
        ChunkRepository repository = new ChunkRepository();
        ChunkMesher mesher = ChunkMeshManagerTest::meshFor;
        ManualExecutor executor = new ManualExecutor();
        ChunkRenderBackend backend = failOnBackendCall(new AtomicInteger());
        MainThreadGuard guard = MainThreadGuard.captureCurrentThread();

        assertThrows(
                NullPointerException.class,
                () ->
                        new ChunkMeshManager(
                                null, mesher, executor, backend, guard, 1));
        assertThrows(
                NullPointerException.class,
                () ->
                        new ChunkMeshManager(
                                repository, null, executor, backend, guard, 1));
        assertThrows(
                NullPointerException.class,
                () ->
                        new ChunkMeshManager(
                                repository, mesher, null, backend, guard, 1));
        assertThrows(
                NullPointerException.class,
                () ->
                        new ChunkMeshManager(
                                repository, mesher, executor, null, guard, 1));
        assertThrows(
                NullPointerException.class,
                () ->
                        new ChunkMeshManager(
                                repository,
                                mesher,
                                executor,
                                backend,
                                null,
                                1));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new ChunkMeshManager(
                                repository,
                                mesher,
                                executor,
                                backend,
                                guard,
                                0));
    }

    private static Fixture generatedFixture() {
        ChunkRepository repository = generatedRepository();
        ManualExecutor executor = new ManualExecutor();
        AtomicInteger backendCalls = new AtomicInteger();
        ChunkMeshManager manager =
                new ChunkMeshManager(
                        repository,
                        ChunkMeshManagerTest::meshFor,
                        executor,
                        failOnBackendCall(backendCalls),
                        MainThreadGuard.captureCurrentThread(),
                        2);
        return new Fixture(repository, executor, backendCalls, manager);
    }

    private static ChunkRepository generatedRepository() {
        ChunkRepository repository = new ChunkRepository();
        repository.generate(
                KEY, chunk -> chunk.setBlock(1, 1, 1, (byte) 1));
        return repository;
    }

    private static ChunkMeshData meshFor(ChunkMeshInput input) {
        return new ChunkMeshData(
                input.center().key(),
                input.center().revision(),
                oneBlockVertices());
    }

    private static ChunkRenderBackend failOnBackendCall(
            AtomicInteger backendCalls) {
        return new ChunkRenderBackend() {
            @Override
            public ChunkRenderObject upload(ChunkMeshData data) {
                backendCalls.incrementAndGet();
                throw new AssertionError("CPU scheduling called upload");
            }

            @Override
            public void release(ChunkRenderObject object) {
                backendCalls.incrementAndGet();
                throw new AssertionError("CPU scheduling called release");
            }
        };
    }

    private static float[] oneBlockVertices() {
        return new float[] {
            0, 0, 0, 0, 0,
            1, 0, 0, 1, 0,
            0, 1, 0, 0, 1
        };
    }

    private record Fixture(
            ChunkRepository repository,
            ManualExecutor executor,
            AtomicInteger backendCalls,
            ChunkMeshManager manager) {}

    private static final class ManualExecutor implements Executor {
        private final Queue<Runnable> tasks = new ArrayDeque<>();

        @Override
        public void execute(Runnable command) {
            tasks.add(command);
        }

        void runNext() {
            tasks.remove().run();
        }

        void runAll() {
            while (!tasks.isEmpty()) {
                runNext();
            }
        }

        int size() {
            return tasks.size();
        }
    }
}

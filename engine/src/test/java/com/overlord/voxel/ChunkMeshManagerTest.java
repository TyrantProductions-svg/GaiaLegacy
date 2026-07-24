package com.overlord.voxel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.overlord.config.GameConfig;
import com.overlord.core.thread.MainThreadGuard;
import com.overlord.renderer.ChunkGpuMesh;
import com.overlord.renderer.ChunkRenderBackend;
import com.overlord.renderer.ChunkRenderObject;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
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

        manager.retry(KEY);

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
    void executorReentrantCloseThenRejectionStopsSchedulingAndReporting() {
        ChunkRepository repository = new ChunkRepository();
        List<ChunkKey> keys =
                List.of(KEY, new ChunkKey(2, 0), new ChunkKey(4, 0));
        for (ChunkKey key : keys) {
            repository.generate(
                    key,
                    chunk -> chunk.setBlock(1, 1, 1, (byte) 1));
        }
        AtomicReference<ChunkMeshManager> managerReference =
                new AtomicReference<>();
        AtomicInteger executeCalls = new AtomicInteger();
        IllegalStateException rejection =
                new IllegalStateException("rejected after close");
        Executor executor =
                command -> {
                    executeCalls.incrementAndGet();
                    managerReference.get().close();
                    throw rejection;
                };
        ChunkMeshManager manager =
                new ChunkMeshManager(
                        repository,
                        ChunkMeshManagerTest::meshFor,
                        executor,
                        failOnBackendCall(new AtomicInteger()),
                        MainThreadGuard.captureCurrentThread(),
                        2);
        managerReference.set(manager);

        assertEquals(0, manager.scheduleEligible());

        assertEquals(1, executeCalls.get());
        assertEquals(
                1,
                keys.stream()
                        .filter(
                                key ->
                                        repository.state(key)
                                                == ChunkState.MESHING)
                        .count());
        assertEquals(
                2,
                keys.stream()
                        .filter(
                                key ->
                                        repository.state(key)
                                                == ChunkState.GENERATED)
                        .count());
        assertTrue(manager.pollFailure().isEmpty());
        assertEquals(0, manager.drainCompletedCpuWork());
        assertEquals(0, manager.scheduleEligible());
    }

    @Test
    void executorReentrantCloseThenDirectRunStopsSchedulingAndCompletion() {
        ChunkRepository repository = new ChunkRepository();
        List<ChunkKey> keys =
                List.of(KEY, new ChunkKey(2, 0), new ChunkKey(4, 0));
        for (ChunkKey key : keys) {
            repository.generate(
                    key,
                    chunk -> chunk.setBlock(1, 1, 1, (byte) 1));
        }
        AtomicReference<ChunkMeshManager> managerReference =
                new AtomicReference<>();
        AtomicInteger executeCalls = new AtomicInteger();
        AtomicInteger meshCalls = new AtomicInteger();
        Executor executor =
                command -> {
                    executeCalls.incrementAndGet();
                    managerReference.get().close();
                    command.run();
                };
        ChunkMeshManager manager =
                new ChunkMeshManager(
                        repository,
                        input -> {
                            meshCalls.incrementAndGet();
                            return meshFor(input);
                        },
                        executor,
                        failOnBackendCall(new AtomicInteger()),
                        MainThreadGuard.captureCurrentThread(),
                        2);
        managerReference.set(manager);

        assertEquals(1, manager.scheduleEligible());

        assertEquals(1, executeCalls.get());
        assertEquals(1, meshCalls.get());
        assertEquals(
                1,
                keys.stream()
                        .filter(
                                key ->
                                        repository.state(key)
                                                == ChunkState.MESHING)
                        .count());
        assertEquals(
                2,
                keys.stream()
                        .filter(
                                key ->
                                        repository.state(key)
                                                == ChunkState.GENERATED)
                        .count());
        assertEquals(0, manager.drainCompletedCpuWork());
        assertTrue(manager.pollFailure().isEmpty());
        assertEquals(0, manager.scheduleEligible());
    }

    @Test
    void executorRejectionWhileOpenStillReportsAndFailsCurrentClaim() {
        ChunkRepository repository = generatedRepository();
        IllegalStateException expected =
                new IllegalStateException("executor rejected");
        ChunkMeshManager manager =
                new ChunkMeshManager(
                        repository,
                        ChunkMeshManagerTest::meshFor,
                        command -> {
                            throw expected;
                        },
                        failOnBackendCall(new AtomicInteger()),
                        MainThreadGuard.captureCurrentThread(),
                        2);

        assertEquals(0, manager.scheduleEligible());

        assertEquals(ChunkState.DIRTY, repository.state(KEY));
        assertSame(expected, manager.pollFailure().orElseThrow());
        assertEquals(0, manager.scheduleEligible());
    }

    @Test
    void allRenderableReflectsRepositoryStateAndEmptySetsAreComplete() {
        Fixture fixture = generatedFixture();

        assertTrue(fixture.manager.allRenderable(Set.of()));
        assertFalse(fixture.manager.allRenderable(Set.of(KEY)));
    }

    @Test
    void uploadsAtMostConfiguredBudgetPerFrame() {
        UploadFixture fixture = readyFixture(3, 2);

        assertEquals(2, fixture.manager.processMainThreadWork());
        assertEquals(2, fixture.backend.uploaded.size());
        assertEquals(1, fixture.manager.processMainThreadWork());
        assertEquals(3, fixture.backend.uploaded.size());
        assertTrue(fixture.manager.allRenderable(Set.copyOf(fixture.keys)));
    }

    @Test
    void emptyMeshBecomesRenderableWithoutGpuAllocation() {
        UploadFixture fixture = readyEmptyFixture();

        assertEquals(1, fixture.manager.processMainThreadWork());

        assertEquals(0, fixture.backend.uploadCalls);
        assertEquals(ChunkState.RENDERABLE, fixture.repository.state(KEY));
        assertTrue(fixture.manager.renderObjects().isEmpty());
    }

    @Test
    void rebuildReleasesExactlyOnePreviousObject() {
        UploadFixture fixture = uploadedFixture();
        ChunkRenderObject previous =
                fixture.manager.renderObjects().iterator().next();

        dirtyBuildAndUpload(fixture, KEY);

        assertEquals(List.of(previous), fixture.backend.released);
        assertEquals(1, fixture.manager.renderObjects().size());
        assertFalse(fixture.manager.renderObjects().contains(previous));
    }

    @Test
    void staleUploadedReplacementIsReleasedWithoutRemovingCurrentObject() {
        UploadFixture fixture = uploadedFixture();
        ChunkRenderObject current =
                fixture.manager.renderObjects().iterator().next();
        dirtyBuild(fixture, KEY);
        fixture.backend.beforeUpload =
                () ->
                        fixture.repository.setBlock(
                                2, 1, 1, (byte) 3);

        assertEquals(1, fixture.manager.processMainThreadWork());

        ChunkRenderObject staleReplacement =
                fixture.backend.uploaded.get(1);
        assertEquals(List.of(staleReplacement), fixture.backend.released);
        assertEquals(List.of(current), List.copyOf(fixture.manager.renderObjects()));
        assertEquals(ChunkState.DIRTY, fixture.repository.state(KEY));
    }

    @Test
    void currentEmptyRebuildReleasesOldAndLeavesNoRenderObject() {
        UploadFixture fixture = uploadedFixture();
        ChunkRenderObject previous =
                fixture.manager.renderObjects().iterator().next();
        fixture.meshVertices.set(new float[0]);
        dirtyBuild(fixture, KEY);

        assertEquals(1, fixture.manager.processMainThreadWork());

        assertEquals(1, fixture.backend.uploadCalls);
        assertEquals(List.of(previous), fixture.backend.released);
        assertTrue(fixture.manager.renderObjects().isEmpty());
        assertEquals(ChunkState.RENDERABLE, fixture.repository.state(KEY));
    }

    @Test
    void staleEmptyResultDoesNotRemoveCurrentObject() {
        UploadFixture fixture = uploadedFixture();
        ChunkRenderObject current =
                fixture.manager.renderObjects().iterator().next();
        fixture.meshVertices.set(new float[0]);
        dirtyBuild(fixture, KEY);
        fixture.repository.setBlock(2, 1, 1, (byte) 3);

        assertEquals(1, fixture.manager.processMainThreadWork());

        assertEquals(1, fixture.backend.uploadCalls);
        assertTrue(fixture.backend.released.isEmpty());
        assertEquals(List.of(current), List.copyOf(fixture.manager.renderObjects()));
        assertEquals(ChunkState.DIRTY, fixture.repository.state(KEY));
    }

    @Test
    void uploadFailurePreservesPreviousObjectUntilExplicitRetry() {
        UploadFixture fixture = uploadedFixture();
        ChunkRenderObject previous =
                fixture.manager.renderObjects().iterator().next();
        dirtyBuild(fixture, KEY);
        IllegalStateException expected =
                new IllegalStateException("upload failed");
        fixture.backend.nextUploadFailure = expected;

        assertEquals(1, fixture.manager.processMainThreadWork());

        assertEquals(List.of(previous), List.copyOf(fixture.manager.renderObjects()));
        assertTrue(fixture.backend.released.isEmpty());
        assertEquals(ChunkState.READY_FOR_UPLOAD, fixture.repository.state(KEY));
        assertSame(expected, fixture.manager.pollFailure().orElseThrow());
        assertEquals(0, fixture.manager.processMainThreadWork());
        assertEquals(2, fixture.backend.uploadCalls);

        fixture.manager.retry(KEY);

        assertEquals(1, fixture.manager.processMainThreadWork());
        assertEquals(3, fixture.backend.uploadCalls);
        assertEquals(List.of(previous), fixture.backend.released);
        assertEquals(ChunkState.RENDERABLE, fixture.repository.state(KEY));
    }

    @Test
    void retryDoesNotRequeueFailedUploadAfterRevisionChanges() {
        UploadFixture fixture = uploadedFixture();
        dirtyBuild(fixture, KEY);
        fixture.backend.nextUploadFailure =
                new IllegalStateException("upload failed");
        fixture.manager.processMainThreadWork();
        fixture.manager.pollFailure();
        fixture.repository.setBlock(2, 1, 1, (byte) 3);

        fixture.manager.retry(KEY);

        assertEquals(0, fixture.manager.processMainThreadWork());
        assertEquals(2, fixture.backend.uploadCalls);
        assertEquals(ChunkState.DIRTY, fixture.repository.state(KEY));
    }

    @Test
    void unloadInvalidatesLateResultAndReleasesOnce() {
        UploadFixture fixture = uploadedFixture();
        ChunkRenderObject installed =
                fixture.manager.renderObjects().iterator().next();
        fixture.repository.setBlock(1, 1, 1, (byte) 2);
        assertEquals(1, fixture.manager.scheduleEligible());

        fixture.manager.unload(KEY);
        fixture.manager.processMainThreadWork();
        fixture.executor.runAll();
        fixture.manager.drainCompletedCpuWork();

        assertFalse(fixture.repository.contains(KEY));
        assertEquals(List.of(installed), fixture.backend.released);
        assertTrue(fixture.manager.renderObjects().isEmpty());
        assertEquals(1, fixture.backend.uploadCalls);
    }

    @Test
    void lateResultCannotMatchRegeneratedChunkRevision() {
        ChunkRepository repository = generatedRepository();
        ManualExecutor executor = new ManualExecutor();
        FakeRenderBackend backend = new FakeRenderBackend();
        ChunkMeshManager manager =
                new ChunkMeshManager(
                        repository,
                        ChunkMeshManagerTest::meshFor,
                        executor,
                        backend,
                        MainThreadGuard.captureCurrentThread(),
                        2);
        assertEquals(1, manager.scheduleEligible());

        manager.unload(KEY);
        assertEquals(0, manager.processMainThreadWork());
        repository.generate(
                KEY, chunk -> chunk.setBlock(1, 1, 1, (byte) 2));
        assertEquals(1, manager.scheduleEligible());

        executor.runNext();
        assertEquals(1, manager.drainCompletedCpuWork());
        assertEquals(ChunkState.MESHING, repository.state(KEY));
        assertTrue(manager.renderObjects().isEmpty());

        executor.runNext();
        assertEquals(1, manager.drainCompletedCpuWork());
        assertEquals(
                ChunkState.READY_FOR_UPLOAD,
                repository.state(KEY));
        assertEquals(1, manager.processMainThreadWork());
        assertEquals(
                repository.revision(KEY),
                manager.renderObjects().iterator().next().revision());
    }

    @Test
    void lateFailureCannotPoisonRegeneratedChunkClaim() {
        ChunkRepository repository = generatedRepository();
        ManualExecutor executor = new ManualExecutor();
        AtomicInteger attempts = new AtomicInteger();
        ChunkMeshManager manager =
                new ChunkMeshManager(
                        repository,
                        input -> {
                            if (attempts.incrementAndGet() == 1) {
                                throw new IllegalStateException(
                                        "old incarnation failed");
                            }
                            return meshFor(input);
                        },
                        executor,
                        new FakeRenderBackend(),
                        MainThreadGuard.captureCurrentThread(),
                        2);
        assertEquals(1, manager.scheduleEligible());
        manager.unload(KEY);
        manager.processMainThreadWork();
        repository.generate(
                KEY, chunk -> chunk.setBlock(1, 1, 1, (byte) 2));
        assertEquals(1, manager.scheduleEligible());

        executor.runNext();
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
    void repeatedUnloadRequestsAreIdempotent() {
        UploadFixture fixture = uploadedFixture();
        ChunkRenderObject installed =
                fixture.manager.renderObjects().iterator().next();

        fixture.manager.unload(KEY);
        fixture.manager.unload(KEY);
        fixture.manager.processMainThreadWork();
        fixture.manager.unload(KEY);
        fixture.manager.processMainThreadWork();

        assertFalse(fixture.repository.contains(KEY));
        assertEquals(List.of(installed), fixture.backend.released);
    }

    @Test
    void unloadReleaseFailureIsReportedAfterRepositoryRemoval() {
        UploadFixture fixture = uploadedFixture();
        IllegalStateException expected =
                new IllegalStateException("release failed");
        fixture.backend.releaseFailures.add(expected);

        fixture.manager.unload(KEY);
        fixture.manager.processMainThreadWork();

        assertFalse(fixture.repository.contains(KEY));
        assertSame(expected, fixture.manager.pollFailure().orElseThrow());
    }

    @Test
    void reentrantCloseDuringFailingUnloadReleaseStillCompletesUnload() {
        UploadFixture fixture = uploadedFixture();
        IllegalStateException expected =
                new IllegalStateException("release failed after close");
        fixture.backend.beforeRelease = fixture.manager::close;
        fixture.backend.releaseFailures.add(expected);
        fixture.manager.unload(KEY);

        IllegalStateException actual =
                assertThrows(
                        IllegalStateException.class,
                        fixture.manager::processMainThreadWork);

        assertSame(expected, actual);
        assertFalse(fixture.repository.contains(KEY));
        assertTrue(fixture.manager.renderObjects().isEmpty());
    }

    @Test
    void pendingUnloadIsDrainedBeforeReadyUploadForSameChunk() {
        UploadFixture fixture = uploadedFixture();
        ChunkRenderObject installed =
                fixture.manager.renderObjects().iterator().next();
        dirtyBuild(fixture, KEY);

        fixture.manager.unload(KEY);

        assertEquals(0, fixture.manager.processMainThreadWork());
        assertFalse(fixture.repository.contains(KEY));
        assertEquals(1, fixture.backend.uploadCalls);
        assertEquals(List.of(installed), fixture.backend.released);
    }

    @Test
    void renderObjectsIsAnImmutableDetachedSnapshot() {
        UploadFixture fixture = uploadedFixture();
        Collection<ChunkRenderObject> snapshot =
                fixture.manager.renderObjects();
        ChunkRenderObject first = snapshot.iterator().next();

        assertThrows(
                UnsupportedOperationException.class,
                () -> snapshot.clear());

        dirtyBuildAndUpload(fixture, KEY);

        assertEquals(List.of(first), List.copyOf(snapshot));
        assertFalse(fixture.manager.renderObjects().contains(first));
    }

    @Test
    void closeReleasesAllObjectsOnceAndCompletesPendingUnloads() {
        UploadFixture fixture = readyFixture(2, 2);
        fixture.manager.processMainThreadWork();
        Set<ChunkRenderObject> installed =
                Set.copyOf(fixture.manager.renderObjects());
        ChunkKey unloading = fixture.keys.get(0);
        fixture.manager.unload(unloading);

        fixture.manager.close();
        fixture.manager.close();

        assertEquals(2, fixture.backend.released.size());
        assertEquals(installed, Set.copyOf(fixture.backend.released));
        assertTrue(fixture.manager.renderObjects().isEmpty());
        assertFalse(fixture.repository.contains(unloading));
        assertEquals(0, fixture.manager.processMainThreadWork());
    }

    @Test
    void closeAggregatesReleaseFailuresAndStillBecomesIdempotent() {
        UploadFixture fixture = readyFixture(2, 2);
        fixture.manager.processMainThreadWork();
        IllegalStateException first =
                new IllegalStateException("first release failed");
        IllegalArgumentException second =
                new IllegalArgumentException("second release failed");
        fixture.backend.releaseFailures.add(first);
        fixture.backend.releaseFailures.add(second);

        RuntimeException actual =
                assertThrows(RuntimeException.class, fixture.manager::close);

        assertSame(first, actual);
        assertEquals(List.of(second), List.of(actual.getSuppressed()));
        assertEquals(2, fixture.backend.released.size());
        fixture.manager.close();
        assertEquals(2, fixture.backend.released.size());
    }

    @Test
    void closeSkipsSelfSuppressionAndStillReleasesEveryObject() {
        assertRepeatedCloseFailureIsAggregated(
                new IllegalStateException("repeated runtime failure"));
        assertRepeatedCloseFailureIsAggregated(
                new AssertionError("repeated error"));
    }

    @Test
    void closeClearsReadyUploadsAndRejectsLaterRetry() {
        UploadFixture fixture = readyFixture(1, 1);

        fixture.manager.close();
        fixture.manager.retry(KEY);

        assertEquals(0, fixture.manager.processMainThreadWork());
        assertEquals(0, fixture.backend.uploadCalls);
        assertTrue(fixture.manager.renderObjects().isEmpty());
    }

    @Test
    void closeDuringUploadReleasesReturnedObjectWithoutInstallingIt() {
        UploadFixture fixture = readyFixture(1, 1);
        fixture.backend.beforeUpload = fixture.manager::close;

        assertEquals(1, fixture.manager.processMainThreadWork());

        ChunkRenderObject returned = fixture.backend.uploaded.get(0);
        assertEquals(List.of(returned), fixture.backend.released);
        assertTrue(fixture.manager.renderObjects().isEmpty());
        assertEquals(
                ChunkState.READY_FOR_UPLOAD,
                fixture.repository.state(returned.key()));
        fixture.manager.close();
        assertEquals(List.of(returned), fixture.backend.released);
    }

    @Test
    void closeReleaseFailureDuringUploadIsSurfacedAndLeavesNoReplacement() {
        UploadFixture fixture = uploadedFixture();
        ChunkRenderObject installed =
                fixture.manager.renderObjects().iterator().next();
        dirtyBuild(fixture, KEY);
        IllegalStateException expected =
                new IllegalStateException("close release failed during upload");
        fixture.backend.beforeUpload = fixture.manager::close;
        fixture.backend.releaseFailures.add(expected);

        IllegalStateException actual =
                assertThrows(
                        IllegalStateException.class,
                        fixture.manager::processMainThreadWork);

        assertSame(expected, actual);
        assertEquals(List.of(installed), fixture.backend.released);
        assertEquals(1, fixture.backend.uploaded.size());
        assertTrue(fixture.manager.renderObjects().isEmpty());
        assertEquals(
                ChunkState.READY_FOR_UPLOAD,
                fixture.repository.state(KEY));
        fixture.manager.close();
        assertEquals(List.of(installed), fixture.backend.released);
        assertEquals(0, fixture.manager.processMainThreadWork());
    }

    @Test
    void uploadFailureAfterReentrantCloseDoesNotRepopulateFailures() {
        UploadFixture fixture = readyFixture(1, 1);
        fixture.backend.beforeUpload = fixture.manager::close;
        fixture.backend.nextUploadFailure =
                new IllegalStateException("upload failed after close");

        assertEquals(1, fixture.manager.processMainThreadWork());

        assertTrue(fixture.manager.pollFailure().isEmpty());
        fixture.manager.retry(KEY);
        assertEquals(0, fixture.manager.processMainThreadWork());
        assertEquals(1, fixture.backend.uploadCalls);
    }

    @Test
    void mainThreadLifecycleMethodsRejectWorkerCallsBeforeBackendAccess()
            throws Exception {
        UploadFixture fixture = readyFixture(1, 1);

        assertWorkerRejected(fixture.manager::processMainThreadWork);
        assertWorkerRejected(() -> fixture.manager.retry(KEY));
        assertWorkerRejected(() -> fixture.manager.unload(KEY));
        assertWorkerRejected(fixture.manager::renderObjects);
        assertWorkerRejected(fixture.manager::close);

        assertEquals(0, fixture.backend.uploadCalls);
        assertEquals(ChunkState.READY_FOR_UPLOAD, fixture.repository.state(KEY));
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

    private static UploadFixture readyFixture(int count, int budget) {
        AtomicReference<float[]> vertices =
                new AtomicReference<>(oneBlockVertices());
        return readyFixture(count, budget, vertices);
    }

    private static UploadFixture readyEmptyFixture() {
        return readyFixture(
                1, 2, new AtomicReference<>(new float[0]));
    }

    private static UploadFixture uploadedFixture() {
        UploadFixture fixture = readyFixture(1, 2);
        assertEquals(1, fixture.manager.processMainThreadWork());
        return fixture;
    }

    private static UploadFixture readyFixture(
            int count,
            int budget,
            AtomicReference<float[]> vertices) {
        ChunkRepository repository = new ChunkRepository();
        List<ChunkKey> keys = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            ChunkKey key = new ChunkKey(index * 2, 0);
            keys.add(key);
            repository.generate(
                    key,
                    chunk -> chunk.setBlock(1, 1, 1, (byte) 1));
        }
        ManualExecutor executor = new ManualExecutor();
        FakeRenderBackend backend = new FakeRenderBackend();
        ChunkMeshManager manager =
                new ChunkMeshManager(
                        repository,
                        input ->
                                new ChunkMeshData(
                                        input.center().key(),
                                        input.center().revision(),
                                        vertices.get()),
                        executor,
                        backend,
                        MainThreadGuard.captureCurrentThread(),
                        budget);
        assertEquals(count, manager.scheduleEligible());
        executor.runAll();
        assertEquals(count, manager.drainCompletedCpuWork());
        return new UploadFixture(
                repository,
                executor,
                backend,
                manager,
                List.copyOf(keys),
                vertices);
    }

    private static void dirtyBuild(
            UploadFixture fixture, ChunkKey key) {
        assertTrue(
                fixture.repository.setBlock(
                        key.worldOriginX() + 1,
                        1,
                        key.worldOriginZ() + 1,
                        (byte) 2));
        assertEquals(1, fixture.manager.scheduleEligible());
        fixture.executor.runAll();
        assertEquals(1, fixture.manager.drainCompletedCpuWork());
        assertEquals(
                ChunkState.READY_FOR_UPLOAD,
                fixture.repository.state(key));
    }

    private static void dirtyBuildAndUpload(
            UploadFixture fixture, ChunkKey key) {
        dirtyBuild(fixture, key);
        assertEquals(1, fixture.manager.processMainThreadWork());
    }

    private static void assertRepeatedCloseFailureIsAggregated(
            Throwable repeatedFailure) {
        UploadFixture fixture = readyFixture(3, 3);
        fixture.manager.processMainThreadWork();
        Set<ChunkRenderObject> installed =
                Set.copyOf(fixture.manager.renderObjects());
        fixture.backend.releaseFailures.add(repeatedFailure);
        fixture.backend.releaseFailures.add(repeatedFailure);
        fixture.backend.releaseFailures.add(repeatedFailure);

        Throwable actual =
                assertThrows(
                        repeatedFailure.getClass(),
                        fixture.manager::close);

        assertSame(repeatedFailure, actual);
        assertEquals(0, actual.getSuppressed().length);
        assertEquals(3, fixture.backend.released.size());
        assertEquals(installed, Set.copyOf(fixture.backend.released));
        assertTrue(fixture.manager.renderObjects().isEmpty());
        fixture.manager.close();
        assertEquals(3, fixture.backend.released.size());
    }

    private static void assertWorkerRejected(Runnable action)
            throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread worker =
                new Thread(
                        () -> {
                            try {
                                action.run();
                            } catch (Throwable thrown) {
                                failure.set(thrown);
                            }
                        },
                        "chunk-worker");
        worker.start();
        worker.join(TimeUnit.SECONDS.toMillis(5));
        assertFalse(worker.isAlive(), "worker did not terminate");
        assertTrue(failure.get() instanceof IllegalStateException);
    }

    private record Fixture(
            ChunkRepository repository,
            ManualExecutor executor,
            AtomicInteger backendCalls,
            ChunkMeshManager manager) {}

    private record UploadFixture(
            ChunkRepository repository,
            ManualExecutor executor,
            FakeRenderBackend backend,
            ChunkMeshManager manager,
            List<ChunkKey> keys,
            AtomicReference<float[]> meshVertices) {}

    private static final class FakeRenderBackend
            implements ChunkRenderBackend {
        private final List<ChunkRenderObject> uploaded =
                new ArrayList<>();
        private final List<ChunkRenderObject> released =
                new ArrayList<>();
        private final Queue<Throwable> releaseFailures =
                new ArrayDeque<>();
        private RuntimeException nextUploadFailure;
        private Runnable beforeUpload;
        private Runnable beforeRelease;
        private int uploadCalls;

        @Override
        public ChunkRenderObject upload(ChunkMeshData data) {
            uploadCalls++;
            if (beforeUpload != null) {
                Runnable action = beforeUpload;
                beforeUpload = null;
                action.run();
            }
            if (nextUploadFailure != null) {
                RuntimeException failure = nextUploadFailure;
                nextUploadFailure = null;
                throw failure;
            }
            ChunkRenderObject object =
                    new ChunkRenderObject(
                            data.key(),
                            data.revision(),
                            new FakeGpuMesh(data.vertexCount()),
                            data.localBounds().orElseThrow());
            uploaded.add(object);
            return object;
        }

        @Override
        public void release(ChunkRenderObject object) {
            released.add(object);
            if (beforeRelease != null) {
                Runnable action = beforeRelease;
                beforeRelease = null;
                action.run();
            }
            Throwable failure = releaseFailures.poll();
            if (failure instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (failure instanceof Error error) {
                throw error;
            }
        }
    }

    private record FakeGpuMesh(int vertexCount)
            implements ChunkGpuMesh {
        @Override
        public void draw() {}

        @Override
        public void cleanup() {}
    }

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

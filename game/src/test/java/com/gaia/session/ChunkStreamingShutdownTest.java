package com.gaia.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.gaia.interaction.GameMode;
import com.gaia.save.session.SaveCoordinator;
import com.gaia.save.streaming.StreamedChunkPayload;
import com.gaia.save.streaming.StreamedChunkUnloadPlan;
import com.gaia.save.streaming.StreamedChunkUnloadResult;
import com.gaia.save.streaming.StreamedChunkStore;
import com.gaia.save.store.SaveWriteResult;
import com.gaia.world.streaming.ChunkDesiredSets;
import com.gaia.world.streaming.ChunkStreamingDecision;
import com.gaia.world.streaming.ChunkStreamingPipeline;
import com.gaia.world.streaming.ChunkStreamingPolicy;
import com.gaia.world.streaming.ChunkWorkResult;
import com.overlord.core.input.MouseDelta;
import com.overlord.core.lifecycle.ShutdownCoordinator;
import com.overlord.core.thread.MainThreadGuard;
import com.overlord.voxel.ChunkDirtyTracker;
import com.overlord.voxel.ChunkGenerationData;
import com.overlord.voxel.ChunkKey;
import com.overlord.voxel.ChunkRepository;
import com.overlord.voxel.ChunkStreamingTicket;
import com.overlord.voxel.ChunkUnloadPreparation;
import com.overlord.worlditem.api.SaveIdentity;
import com.overlord.worlditem.api.WorldItemDurableProof;
import com.overlord.worlditem.api.WorldItemPagingCheckpoint;
import com.overlord.worlditem.api.WorldItemPersistencePlan;
import java.lang.reflect.Method;
import java.time.Instant;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

class ChunkStreamingShutdownTest {
    @Test
    void pauseSavePublishesCheckpointOnlyAfterCombinedStreamedCommitValidates() {
        List<String> events = new ArrayList<>();
        var snapshot = GameSessionSaveLifecycleTest.snapshot();
        WorldItemPersistencePlan plan = emptyPlan(1L);
        Proof proof = new Proof();
        RecordingStreamedSession session =
                new RecordingStreamedSession(snapshot, plan, proof, events);
        SaveCoordinator coordinator = new SaveCoordinator(ignored ->
                new SaveCoordinator.SaveTarget() {
                    @Override
                    public SaveCoordinator.AtomicSaveWrite saveAtomically(
                            com.gaia.save.snapshot.SaveGameSnapshot captured,
                            Instant modified,
                            Optional<WorldItemPersistencePlan> worldItems,
                            java.util.function.Function<com.overlord.voxel.ChunkKey,
                                    Optional<com.overlord.voxel.ChunkSnapshot>> chunks) {
                        events.add("validate-combined-streamed-commit");
                        assertEquals(Optional.of(plan), worldItems);
                        return new SaveCoordinator.AtomicSaveWrite(
                                SaveWriteResult.success(GameSessionSaveLifecycleTest.manifest()),
                                Optional.of(proof));
                    }

                    @Override
                    public SaveWriteResult save(
                            com.gaia.save.snapshot.SaveGameSnapshot captured,
                            Instant modified) {
                        throw new AssertionError("combined streamed save must own publication");
                    }
                });

        assertEquals(GameSessionSaveResult.Status.SUCCESS,
                coordinator.save(session, Instant.parse("2026-08-12T11:00:00Z")).status());

        assertEquals(List.of(
                        "prepare-streaming-save-capture",
                        "prepare-pending-world-items",
                        "capture-session",
                        "validate-combined-streamed-commit",
                        "commit-logical-and-physical-hibernation",
                        "publish-checkpoint",
                        "resume-streaming-after-save-capture"),
                events);
        assertFalse(session.closed);
    }

    @Test
    void modifiedSaveFailureCancelsPendingPersistenceAndKeepsOldCheckpointOpen() {
        List<String> events = new ArrayList<>();
        var snapshot = GameSessionSaveLifecycleTest.snapshot();
        WorldItemPersistencePlan plan = emptyPlan(1L);
        RecordingStreamedSession session =
                new RecordingStreamedSession(snapshot, plan, new Proof(), events);
        SaveCoordinator coordinator = new SaveCoordinator(ignored ->
                new SaveCoordinator.SaveTarget() {
                    @Override
                    public SaveCoordinator.AtomicSaveWrite saveAtomically(
                            com.gaia.save.snapshot.SaveGameSnapshot captured,
                            Instant modified,
                            Optional<WorldItemPersistencePlan> worldItems,
                            java.util.function.Function<com.overlord.voxel.ChunkKey,
                                    Optional<com.overlord.voxel.ChunkSnapshot>> chunks) {
                        events.add("combined-streamed-commit-failed");
                        return new SaveCoordinator.AtomicSaveWrite(
                                SaveWriteResult.failed(com.gaia.save.archive.SaveDiagnostic.of(
                                        "task11.injected", "injected modified save failure")),
                                Optional.empty());
                    }

                    @Override
                    public SaveWriteResult save(
                            com.gaia.save.snapshot.SaveGameSnapshot captured,
                            Instant modified) {
                        throw new AssertionError("combined streamed save must own publication");
                    }
                });

        assertEquals(GameSessionSaveResult.Status.WRITE_FAILED,
                coordinator.save(session, Instant.parse("2026-08-12T11:00:01Z")).status());

        assertEquals(List.of(
                        "prepare-streaming-save-capture",
                        "prepare-pending-world-items",
                        "capture-session",
                        "combined-streamed-commit-failed",
                        "cancel-pending-world-items",
                        "resume-streaming-after-save-capture"),
                events);
        assertEquals(0, session.publishedCheckpoints);
        assertFalse(session.closed);
    }

    @Test
    void actualProductionShutdownUsesTheLockedDependencyOrder() throws Exception {
        var access = GameSessionFactory.productionSessionTestAccess();
        Method trace = requireMethod(access.getClass(), "lastShutdownTrace");
        GameSession session = access.factory().restore(
                ChunkStreamingSessionIntegrationTest.productionSnapshot());
        driveToReady(session);

        session.close();

        assertEquals(List.of(
                        "stop-streaming-admissions",
                        "freeze-final-observation",
                        "cancel-discardable-work",
                        "complete-or-fail-modified-durability",
                        "drain-owner-gpu-state",
                        "close-load-generation-executor",
                        "close-mesh-executor",
                        "close-save-executor"),
                invokeList(trace, access));
        assertEquals(0, invokeInt(requireMethod(
                access.getClass(), "retainedStreamingWorkCount"), access));
        assertEquals(0, access.liveWorkerCount());
    }

    @Test
    void repeatedProductionSessionsRetainNoStreamingWorkOrWorkers() throws Exception {
        var access = GameSessionFactory.productionSessionTestAccess();
        Method retained = requireMethod(access.getClass(), "retainedStreamingWorkCount");

        for (int attempt = 0; attempt < 2; attempt++) {
            GameSession session = access.factory().restore(
                    ChunkStreamingSessionIntegrationTest.productionSnapshot());
            driveToReady(session);
            session.close();
            assertEquals(0, invokeInt(retained, access));
            assertEquals(0, access.liveWorkerCount());
        }
    }

    @Test
    void productionWorkerObservationIgnoresUnrelatedStreamingPipelines() {
        ChunkStreamingPipeline unrelated = idlePipeline();
        var access = GameSessionFactory.productionSessionTestAccess();
        GameSession session = access.factory().restore(
                ChunkStreamingSessionIntegrationTest.productionSnapshot());
        try {
            driveToReady(session);
            session.close();

            assertTrue(unrelated.liveWorkerCount() > 0,
                    "the unrelated pipeline must remain live during observation");
            assertEquals(0, access.liveWorkerCount());
        } finally {
            session.close();
            unrelated.close();
        }
    }

    @Test
    void productionSaveQuiescenceFailureStillClosesActualOwnedLanesInOrder()
            throws Exception {
        var access = GameSessionFactory.productionSessionTestAccess();
        GameSession session = access.factory().restore(
                ChunkStreamingSessionIntegrationTest.productionSnapshot());
        RuntimeException primary = new RuntimeException("save quiescence failed");
        RuntimeException meshCleanup = new RuntimeException("mesh close failed");
        driveToReady(session);
        Method inject = requireMethod(
                access.getClass(),
                "injectShutdownFailures",
                GameSession.class,
                RuntimeException.class,
                RuntimeException.class);
        inject.invoke(access, session, primary, meshCleanup);

        RuntimeException thrown = assertThrows(RuntimeException.class, session::close);

        assertSame(primary, thrown);
        assertEquals(List.of(meshCleanup), List.of(thrown.getSuppressed()));
        assertEquals(List.of("load-generation", "mesh", "save"),
                invokeList(requireMethod(
                        access.getClass(), "ownedWorkerTerminationTrace"), access),
                "actual lane termination, not trace labels, owns shutdown order");
        assertEquals(0, invokeInt(requireMethod(
                access.getClass(), "retainedStreamingWorkCount"), access));
        assertEquals(0, access.liveWorkerCount());
    }

    @Test
    void productionShutdownWaitsForActualBlockedMeshExecutorBeforeSaveLane()
            throws Exception {
        var access = GameSessionFactory.productionSessionTestAccess();
        GameSession session = access.factory().restore(
                ChunkStreamingSessionIntegrationTest.productionSnapshot());
        CountDownLatch meshStarted = new CountDownLatch(1);
        CountDownLatch meshTerminated = new CountDownLatch(1);
        try {
            driveToReady(session);
            Method inject = requireMethod(
                    access.getClass(), "injectActualBlockedMeshWorker",
                    GameSession.class, CountDownLatch.class, CountDownLatch.class);
            inject.invoke(access, session, meshStarted, meshTerminated);
            assertTrue(meshStarted.await(5, TimeUnit.SECONDS));

            session.close();

            assertTrue(meshTerminated.await(5, TimeUnit.SECONDS),
                    "actual mesh executor worker must terminate before close returns");
            assertEquals(List.of("load-generation", "mesh", "save"),
                    invokeList(requireMethod(
                            access.getClass(), "ownedWorkerTerminationTrace"), access));
            assertEquals(0, access.liveWorkerCount());
        } finally {
            if (session.state() != GameSessionState.CLOSED) {
                session.close();
            }
        }
    }

    @Test
    void newWorldShutdownStopsWorldLoadThenActualMeshThenSaveLane()
            throws Exception {
        var access = GameSessionFactory.productionSessionTestAccess();
        GameSession session = access.factory().create(
                new GameSessionConfig(12345L, 2, GameMode.SURVIVAL, false));
        try {
            driveToReady(session);

            session.close();

            assertEquals(List.of("load-generation", "mesh", "save"),
                    invokeList(requireMethod(
                            access.getClass(), "ownedWorkerTerminationTrace"), access));
            assertEquals(0, access.liveWorkerCount());
        } finally {
            if (session.state() != GameSessionState.CLOSED) {
                session.close();
            }
        }
    }

    @Test
    void shutdownPreservesPrimaryAndIdentityDistinctSuppressedCleanupFailures() {
        ShutdownCoordinator shutdown = new ShutdownCoordinator();
        RuntimeException olderCleanup = new RuntimeException("older cleanup");
        RuntimeException primary = new RuntimeException("primary durability failure");
        shutdown.register("older-cleanup", () -> { throw olderCleanup; });
        shutdown.register("same-primary", () -> { throw primary; });
        shutdown.register("modified-durability", () -> { throw primary; });

        RuntimeException thrown = assertThrows(RuntimeException.class, shutdown::close);

        assertSame(primary, thrown);
        assertEquals(List.of(olderCleanup), List.of(thrown.getSuppressed()));
        shutdown.close();
    }

    @Test
    void shutdownPreparationCancelsLoadsButPreservesSaveDurability() throws Exception {
        ChunkKey loadKey = new ChunkKey(20, 0);
        ChunkKey saveKey = new ChunkKey(0, 0);
        ChunkRepository repository = new ChunkRepository(
                1, new ChunkDirtyTracker());
        repository.generate(saveKey, ignored -> {});
        CountDownLatch loadStarted = new CountDownLatch(1);
        CountDownLatch loadCanceled = new CountDownLatch(1);
        CountDownLatch saveStarted = new CountDownLatch(1);
        CountDownLatch releaseSave = new CountDownLatch(1);
        AtomicReference<BooleanSupplier> saveCancellation = new AtomicReference<>();
        AtomicInteger committedSaves = new AtomicInteger();
        ChunkStreamingPipeline pipeline = new ChunkStreamingPipeline(
                repository,
                ChunkStreamingPolicy.productionDefaults(),
                MainThreadGuard.captureCurrentThread(),
                work -> {
                    loadStarted.countDown();
                    while (!work.canceled().getAsBoolean()) {
                        Thread.yield();
                    }
                    loadCanceled.countDown();
                    return ChunkWorkResult.loadSuccess(
                            work.workId(), work.key(), work.desiredEpoch(),
                            work.expectedRevision(),
                            ChunkStreamingTicket.SourcePreference.GENERATE,
                            new ChunkGenerationData(
                                    work.key(), 1, new byte[16 * 16]));
                },
                work -> {
                    saveCancellation.set(work.canceled());
                    saveStarted.countDown();
                    assertTrue(releaseSave.await(5, TimeUnit.SECONDS));
                    return ChunkWorkResult.saveSuccess(
                            work.workId(), work.key(), work.desiredEpoch(),
                            work.expectedRevision(),
                            StreamedChunkUnloadResult.success(Optional.empty()));
                },
                new ChunkStreamingPipeline.UnloadLifecycle() {
                    @Override
                    public ChunkStreamingPipeline.PreparedUnload prepare(
                            ChunkUnloadPreparation preparation) {
                        var capture = preparation.capture().orElseThrow();
                        StreamedChunkPayload payload = new StreamedChunkPayload(
                                GameSessionSaveLifecycleTest.ID,
                                capture.key(),
                                "task11-test",
                                "44".repeat(32),
                                capture.revision(),
                                0L,
                                true,
                                true,
                                capture.worldHeight(),
                                capture.copyBlocks(),
                                List.of());
                        return new ChunkStreamingPipeline.PreparedUnload(
                                new StreamedChunkUnloadPlan(
                                        new StreamedChunkStore.ExactChunkCapture(
                                                payload, preparation.stillCurrent()),
                                        Optional.empty(),
                                        List.of()),
                                capture.revision());
                    }

                    @Override
                    public boolean commit(
                            ChunkStreamingPipeline.PreparedUnload prepared,
                            StreamedChunkUnloadResult durability) {
                        committedSaves.incrementAndGet();
                        return true;
                    }

                    @Override
                    public void cancel(ChunkStreamingPipeline.PreparedUnload prepared) {}
                });
        ChunkDesiredSets desired = new ChunkDesiredSets(
                Set.of(loadKey), Set.of(loadKey), Set.of(loadKey));
        pipeline.apply(new ChunkStreamingDecision(
                desired,
                1L,
                List.of(loadKey),
                List.of(),
                List.of(),
                List.of(saveKey)));
        assertTrue(loadStarted.await(5, TimeUnit.SECONDS));
        assertTrue(saveStarted.await(5, TimeUnit.SECONDS));

        pipeline.prepareShutdown();

        assertTrue(loadCanceled.await(5, TimeUnit.SECONDS));
        assertFalse(saveCancellation.get().getAsBoolean());
        assertThrows(IllegalStateException.class, () -> pipeline.apply(
                new ChunkStreamingDecision(
                        desired, 2L, List.of(), List.of(), List.of(), List.of())));
        releaseSave.countDown();
        pipeline.awaitSaveWorkers(Duration.ofSeconds(5));
        for (int drain = 0; drain < 8 && pipeline.retainedWorkCount() > 0; drain++) {
            pipeline.drainOwnerResults();
        }
        assertEquals(1, committedSaves.get());
        pipeline.shutdownOwnerOrdered(() -> {});
        assertEquals(0, pipeline.liveWorkerCount(),
                "closed owned workers must be observed exactly");
    }

    private static ChunkStreamingPipeline idlePipeline() {
        return new ChunkStreamingPipeline(
                new ChunkRepository(1, new ChunkDirtyTracker()),
                ChunkStreamingPolicy.productionDefaults(),
                MainThreadGuard.captureCurrentThread(),
                work -> { throw new AssertionError("idle load worker ran"); },
                work -> { throw new AssertionError("idle save worker ran"); },
                new ChunkStreamingPipeline.UnloadLifecycle() {
                    @Override
                    public ChunkStreamingPipeline.PreparedUnload prepare(
                            ChunkUnloadPreparation preparation) {
                        throw new AssertionError("idle unload prepared");
                    }

                    @Override
                    public boolean commit(
                            ChunkStreamingPipeline.PreparedUnload prepared,
                            StreamedChunkUnloadResult durability) {
                        throw new AssertionError("idle unload committed");
                    }

                    @Override
                    public void cancel(
                            ChunkStreamingPipeline.PreparedUnload prepared) {
                        throw new AssertionError("idle unload canceled");
                    }
                });
    }

    private static WorldItemPersistencePlan emptyPlan(long checkpointRevision) {
        SaveIdentity identity = new SaveIdentity(UUID.fromString(
                GameSessionSaveLifecycleTest.ID.value()));
        return new WorldItemPersistencePlan(
                checkpointRevision - 1L,
                new WorldItemPagingCheckpoint(
                        identity,
                        checkpointRevision,
                        GameSessionSaveLifecycleTest.snapshot().fixedTick(),
                        GameSessionSaveLifecycleTest.snapshot().worldItems().nextItemId(),
                        GameSessionSaveLifecycleTest.snapshot().worldItems().itemIdsExhausted(),
                        0,
                        List.of()),
                List.of(),
                "11".repeat(32),
                () -> true);
    }

    private static void driveToReady(GameSession session) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15L);
        while (session.state() == GameSessionState.LOADING
                && System.nanoTime() < deadline) {
            session.pollLoad();
            if (session.state() == GameSessionState.LOADING) {
                java.util.concurrent.locks.LockSupport.parkNanos(
                        TimeUnit.MILLISECONDS.toNanos(1L));
            }
        }
        assertEquals(GameSessionState.READY, session.state());
    }

    @SuppressWarnings("unchecked")
    private static List<String> invokeList(Method method, Object target) throws Exception {
        return List.copyOf((List<String>) method.invoke(target));
    }

    private static int invokeInt(Method method, Object target) throws Exception {
        return ((Number) method.invoke(target)).intValue();
    }

    private static Method requireMethod(Class<?> type, String name) {
        return requireMethod(type, name, new Class<?>[0]);
    }

    private static Method requireMethod(
            Class<?> type, String name, Class<?>... parameterTypes) {
        try {
            Method method = type.getDeclaredMethod(name, parameterTypes);
            method.setAccessible(true);
            return method;
        } catch (ReflectiveOperationException missing) {
            return fail("Missing Task 11 production shutdown observation seam: "
                    + type.getName() + "." + name + "()");
        }
    }

    private static final class Proof implements WorldItemDurableProof {}

    private static final class RecordingStreamedSession implements GameSession {
        private final com.gaia.save.snapshot.SaveGameSnapshot snapshot;
        private final WorldItemPersistencePlan plan;
        private final WorldItemDurableProof proof;
        private final List<String> events;
        private int publishedCheckpoints;
        private boolean closed;

        private RecordingStreamedSession(
                com.gaia.save.snapshot.SaveGameSnapshot snapshot,
                WorldItemPersistencePlan plan,
                WorldItemDurableProof proof,
                List<String> events) {
            this.snapshot = snapshot;
            this.plan = plan;
            this.proof = proof;
            this.events = events;
        }

        @Override
        public void prepareSaveCapture() {
            events.add("prepare-streaming-save-capture");
        }

        @Override
        public void finishSaveCapture() {
            events.add("resume-streaming-after-save-capture");
        }

        @Override
        public Optional<WorldItemPersistencePlan> prepareWorldItemPersistence() {
            events.add("prepare-pending-world-items");
            return Optional.of(plan);
        }

        @Override
        public SessionSaveCaptureResult captureSave() {
            events.add("capture-session");
            return GameSessionPersistenceTestFixture.runtimeCaptured(snapshot, 5L);
        }

        @Override
        public void commitWorldItemPersistence(WorldItemDurableProof actual) {
            assertSame(proof, actual);
            events.add("commit-logical-and-physical-hibernation");
        }

        @Override
        public void cancelWorldItemPersistence() {
            events.add("cancel-pending-world-items");
        }

        @Override
        public void markSaved(SessionPersistenceRevision revision) {
            events.add("publish-checkpoint");
            publishedCheckpoints++;
        }

        @Override public GameSessionState state() { return GameSessionState.READY; }
        @Override public void pollLoad() {}
        @Override public GameSessionFrame advancePlaying(
                double frameDeltaSeconds, MouseDelta look, boolean focused) {
            throw new UnsupportedOperationException();
        }
        @Override public GameSessionFrame capturePaused() {
            throw new UnsupportedOperationException();
        }
        @Override public boolean hasUnsavedChanges() { return true; }
        @Override public void discardFixedTime() {}
        @Override public void close() { closed = true; }
    }
}

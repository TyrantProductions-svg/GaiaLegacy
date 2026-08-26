package com.gaia.save.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gaia.save.archive.SaveDiagnostic;
import com.gaia.save.format.SaveGameManifest;
import com.gaia.save.store.SaveWriteResult;
import com.gaia.session.GameSessionPersistenceTestFixture;
import com.gaia.session.GameSessionSaveLifecycleTest;
import com.gaia.session.GameSessionSaveResult;
import com.gaia.session.GameSessionConfig;
import com.gaia.session.GameSession;
import com.gaia.session.GameSessionFrame;
import com.gaia.session.GameSessionState;
import com.gaia.session.SessionPersistenceRevision;
import com.gaia.session.SessionSaveCaptureResult;
import com.gaia.interaction.GameMode;
import com.overlord.core.input.MouseDelta;
import com.overlord.worlditem.api.SaveIdentity;
import com.overlord.worlditem.api.WorldItemDurableProof;
import com.overlord.worlditem.api.WorldItemPagingCheckpoint;
import com.overlord.worlditem.api.WorldItemPersistencePlan;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class SaveCoordinatorTest {
    private static final Instant MODIFIED = Instant.parse("2026-08-12T01:00:00Z");

    @Test
    void successCapturesAndWritesOnceThenMarksOnlyTheExactCapturedRevision() {
        var snapshot = GameSessionSaveLifecycleTest.snapshot();
        var harness = GameSessionPersistenceTestFixture.sessionHarness(
                new GameSessionConfig(12345L, 2, GameMode.SURVIVAL, false),
                List.of(GameSessionPersistenceTestFixture.runtimeCaptured(snapshot, 5L)));
        harness.makeReady();
        AtomicInteger writes = new AtomicInteger();
        AtomicReference<Instant> writtenAt = new AtomicReference<>();
        SaveGameManifest manifest = GameSessionSaveLifecycleTest.manifest();
        SaveCoordinator coordinator = new SaveCoordinator(id -> (captured, modified) -> {
            writes.incrementAndGet();
            writtenAt.set(modified);
            assertEquals(snapshot, captured);
            return SaveWriteResult.success(manifest);
        });

        GameSessionSaveResult result = coordinator.save(harness.session(), MODIFIED);

        assertEquals(GameSessionSaveResult.Status.SUCCESS, result.status());
        assertEquals(manifest, result.committedManifest().orElseThrow());
        assertEquals(1, writes.get());
        assertEquals(MODIFIED, writtenAt.get());
        assertEquals(List.of(5L), harness.runtime().marked().stream()
                .map(revision -> revision.value()).toList());
        harness.session().close();
    }

    @Test
    void writeFailurePreservesTheCheckpointAndLiveReadySessionForRetry() {
        var snapshot = GameSessionSaveLifecycleTest.snapshot();
        var harness = GameSessionPersistenceTestFixture.sessionHarness(
                new GameSessionConfig(12345L, 2, GameMode.SURVIVAL, false),
                List.of(
                        GameSessionPersistenceTestFixture.runtimeCaptured(snapshot, 5L),
                        GameSessionPersistenceTestFixture.runtimeCaptured(snapshot, 6L)));
        harness.makeReady();
        AtomicInteger attempt = new AtomicInteger();
        SaveDiagnostic diagnostic = SaveDiagnostic.of(
                "save-write.injected", "Injected save failure");
        SaveCoordinator coordinator = new SaveCoordinator(id -> (captured, modified) ->
                attempt.getAndIncrement() == 0
                        ? SaveWriteResult.success(GameSessionSaveLifecycleTest.manifest())
                        : SaveWriteResult.failed(diagnostic));

        assertEquals(GameSessionSaveResult.Status.SUCCESS,
                coordinator.save(harness.session(), MODIFIED).status());
        GameSessionSaveResult failed = coordinator.save(harness.session(), MODIFIED.plusSeconds(1));

        assertEquals(GameSessionSaveResult.Status.WRITE_FAILED, failed.status());
        assertEquals(List.of(diagnostic), failed.diagnostics());
        assertEquals(List.of(5L), harness.runtime().marked().stream()
                .map(revision -> revision.value()).toList());
        assertFalse(harness.session().state().name().equals("CLOSED"));
        harness.session().close();
    }

    @Test
    void rejectedCaptureNeverCallsTheWriterOrPublishesAManifest() {
        var harness = GameSessionPersistenceTestFixture.sessionHarness(
                new GameSessionConfig(12345L, 2, GameMode.SURVIVAL, false),
                List.of(com.gaia.session.SessionSaveCaptureResult.pendingTransaction()));
        harness.makeReady();
        AtomicInteger writes = new AtomicInteger();
        SaveCoordinator coordinator = new SaveCoordinator(id -> (captured, modified) -> {
            writes.incrementAndGet();
            return SaveWriteResult.success(GameSessionSaveLifecycleTest.manifest());
        });

        GameSessionSaveResult result = coordinator.save(harness.session(), MODIFIED);

        assertEquals(GameSessionSaveResult.Status.CAPTURE_REJECTED, result.status());
        assertTrue(result.committedManifest().isEmpty());
        assertEquals(0, writes.get());
        harness.session().close();
    }

    @Test
    void streamedSaveCapturesBeforeOneAtomicPageAndSessionPublication() {
        var snapshot = GameSessionSaveLifecycleTest.snapshot();
        WorldItemPersistencePlan plan = emptyPlan(snapshot, 1L);
        BackendProof proof = new BackendProof();
        List<String> events = new java.util.ArrayList<>();
        RecordingStreamedSession session = new RecordingStreamedSession(
                snapshot, plan, events, proof);
        SaveCoordinator.SaveTarget target = new SaveCoordinator.SaveTarget() {
            @Override
            public SaveCoordinator.AtomicSaveWrite saveAtomically(
                    com.gaia.save.snapshot.SaveGameSnapshot captured,
                    Instant modified,
                    Optional<WorldItemPersistencePlan> actual,
                    java.util.function.Function<com.overlord.voxel.ChunkKey,
                            Optional<com.overlord.voxel.ChunkSnapshot>> chunks) {
                WorldItemDurableProof durable = persistWorldItems(actual.orElseThrow());
                return new SaveCoordinator.AtomicSaveWrite(
                        save(captured, modified), Optional.of(durable));
            }

            @Override
            public WorldItemDurableProof persistWorldItems(
                    WorldItemPersistencePlan actual) {
                events.add("persist-world-items");
                assertEquals(plan, actual);
                return proof;
            }

            @Override
            public SaveWriteResult save(
                    com.gaia.save.snapshot.SaveGameSnapshot captured,
                    Instant modified) {
                events.add("write-v2");
                assertFalse(session.persistenceCommitted());
                assertEquals(snapshot, captured);
                return SaveWriteResult.success(GameSessionSaveLifecycleTest.manifest());
            }
        };
        SaveCoordinator coordinator = new SaveCoordinator(ignored -> target);

        GameSessionSaveResult result = coordinator.save(session, MODIFIED);

        assertEquals(GameSessionSaveResult.Status.SUCCESS, result.status());
        assertEquals(List.of(
                        "prepare-world-items",
                        "capture",
                        "persist-world-items",
                        "write-v2",
                        "commit-world-items",
                        "mark-saved"),
                events);
        assertTrue(session.persistenceCommitted());
        assertFalse(session.closed());
    }

    @Test
    void streamedPersistenceFailureCancelsTicketAndKeepsSessionOpenAndOldCheckpoint()
            throws Exception {
        var snapshot = GameSessionSaveLifecycleTest.snapshot();
        WorldItemPersistencePlan plan = emptyPlan(snapshot, 1L);
        List<String> events = new java.util.ArrayList<>();
        RecordingStreamedSession session = new RecordingStreamedSession(
                snapshot, plan, events, null);
        IllegalStateException injected = new IllegalStateException("injected page write");
        SaveCoordinator.SaveTarget target = new SaveCoordinator.SaveTarget() {
            @Override
            public SaveCoordinator.AtomicSaveWrite saveAtomically(
                    com.gaia.save.snapshot.SaveGameSnapshot captured,
                    Instant modified,
                    Optional<WorldItemPersistencePlan> actual,
                    java.util.function.Function<com.overlord.voxel.ChunkKey,
                            Optional<com.overlord.voxel.ChunkSnapshot>> chunks) {
                persistWorldItems(actual.orElseThrow());
                throw new AssertionError("injected persistence must throw");
            }

            @Override
            public WorldItemDurableProof persistWorldItems(WorldItemPersistencePlan actual) {
                events.add("persist-world-items");
                throw injected;
            }

            @Override
            public SaveWriteResult save(
                    com.gaia.save.snapshot.SaveGameSnapshot captured,
                    Instant modified) {
                throw new AssertionError("snapshot write must not run");
            }
        };
        SaveCoordinator coordinator = new SaveCoordinator(ignored -> target);

        GameSessionSaveResult result = coordinator.save(session, MODIFIED);

        assertEquals(GameSessionSaveResult.Status.WRITE_FAILED, result.status());
        assertEquals("save-write.world-items-persistence-failed",
                result.diagnostics().get(0).code());
        assertEquals(List.of(
                "prepare-world-items", "capture", "persist-world-items",
                "cancel-world-items"),
                events);
        assertFalse(session.persistenceCommitted());
        assertFalse(session.closed());
        assertEquals(0L, session.visibleCheckpointRevision());
    }

    @Test
    void foreignDurabilityProofCancelsThePreparedTicketBeforeCaptureOrV2Write() {
        var snapshot = GameSessionSaveLifecycleTest.snapshot();
        WorldItemPersistencePlan plan = emptyPlan(snapshot, 1L);
        BackendProof expected = new BackendProof();
        List<String> events = new java.util.ArrayList<>();
        RecordingStreamedSession session = new RecordingStreamedSession(
                snapshot, plan, events, expected);
        SaveCoordinator.SaveTarget target = new SaveCoordinator.SaveTarget() {
            @Override
            public SaveCoordinator.AtomicSaveWrite saveAtomically(
                    com.gaia.save.snapshot.SaveGameSnapshot captured,
                    Instant modified,
                    Optional<WorldItemPersistencePlan> actual,
                    java.util.function.Function<com.overlord.voxel.ChunkKey,
                            Optional<com.overlord.voxel.ChunkSnapshot>> chunks) {
                WorldItemDurableProof durable = persistWorldItems(actual.orElseThrow());
                return new SaveCoordinator.AtomicSaveWrite(
                        save(captured, modified), Optional.of(durable));
            }

            @Override
            public WorldItemDurableProof persistWorldItems(WorldItemPersistencePlan actual) {
                events.add("persist-world-items");
                return new ForeignProof();
            }

            @Override
            public SaveWriteResult save(
                    com.gaia.save.snapshot.SaveGameSnapshot captured,
                    Instant modified) {
                events.add("write-v2");
                return SaveWriteResult.success(GameSessionSaveLifecycleTest.manifest());
            }
        };

        GameSessionSaveResult result = new SaveCoordinator(ignored -> target)
                .save(session, MODIFIED);

        assertEquals(GameSessionSaveResult.Status.WRITE_FAILED, result.status());
        assertEquals("save-write.world-items-proof-rejected",
                result.diagnostics().get(0).code());
        assertEquals(List.of(
                "prepare-world-items",
                "capture",
                "persist-world-items",
                "write-v2",
                "commit-world-items",
                "cancel-world-items"), events);
        assertFalse(session.persistenceCommitted());
        assertEquals(0L, session.visibleCheckpointRevision());
        assertFalse(session.closed());
    }

    @Test
    void atomicV2WriterFailureCancelsUnpublishedPlanAndRetryPublishesOnce() {
        var snapshot = GameSessionSaveLifecycleTest.snapshot();
        WorldItemPersistencePlan plan = emptyPlan(snapshot, 1L);
        BackendProof proof = new BackendProof();
        List<String> events = new java.util.ArrayList<>();
        RecordingStreamedSession session = new RecordingStreamedSession(
                snapshot, plan, events, proof);
        SaveDiagnostic diagnostic = SaveDiagnostic.of(
                "save-write.v2-session-failed", "Injected v2 session publication failure");
        AtomicInteger writerAttempts = new AtomicInteger();
        SaveCoordinator.SaveTarget target = new SaveCoordinator.SaveTarget() {
            @Override
            public SaveCoordinator.AtomicSaveWrite saveAtomically(
                    com.gaia.save.snapshot.SaveGameSnapshot captured,
                    Instant modified,
                    Optional<WorldItemPersistencePlan> actual,
                    java.util.function.Function<com.overlord.voxel.ChunkKey,
                            Optional<com.overlord.voxel.ChunkSnapshot>> chunks) {
                events.add("persist-world-items");
                assertEquals(Optional.of(plan), actual);
                events.add("write-v2");
                return writerAttempts.getAndIncrement() == 0
                        ? new SaveCoordinator.AtomicSaveWrite(
                                SaveWriteResult.failed(diagnostic), Optional.empty())
                        : new SaveCoordinator.AtomicSaveWrite(
                                SaveWriteResult.success(
                                        GameSessionSaveLifecycleTest.manifest()),
                                Optional.of(proof));
            }

            @Override
            public SaveWriteResult save(
                    com.gaia.save.snapshot.SaveGameSnapshot captured,
                    Instant modified) {
                throw new AssertionError("atomic override must own the write");
            }
        };

        GameSessionSaveResult result = new SaveCoordinator(ignored -> target)
                .save(session, MODIFIED);

        assertEquals(GameSessionSaveResult.Status.WRITE_FAILED, result.status());
        assertEquals(List.of(diagnostic), result.diagnostics());
        assertEquals(List.of(
                "prepare-world-items",
                "capture",
                "persist-world-items",
                "write-v2",
                "cancel-world-items"), events);
        assertFalse(session.persistenceCommitted());
        assertEquals(0L, session.visibleCheckpointRevision());
        assertFalse(session.closed());

        GameSessionSaveResult retried = new SaveCoordinator(ignored -> target)
                .save(session, MODIFIED.plusSeconds(1L));

        assertEquals(GameSessionSaveResult.Status.SUCCESS, retried.status());
        assertEquals(List.of(
                "prepare-world-items",
                "capture",
                "persist-world-items",
                "write-v2",
                "cancel-world-items",
                "prepare-world-items",
                "capture",
                "persist-world-items",
                "write-v2",
                "commit-world-items",
                "mark-saved"), events);
        assertEquals(2, events.stream()
                .filter("persist-world-items"::equals).count());
        assertEquals(1, events.stream()
                .filter("commit-world-items"::equals).count());
        assertFalse(session.closed());
    }

    private static WorldItemPersistencePlan emptyPlan(
            com.gaia.save.snapshot.SaveGameSnapshot snapshot,
            long checkpointRevision) {
        SaveIdentity identity = new SaveIdentity(UUID.fromString(
                snapshot.metadata().saveGameId().value()));
        return new WorldItemPersistencePlan(
                checkpointRevision - 1L,
                new WorldItemPagingCheckpoint(
                        identity,
                        checkpointRevision,
                        snapshot.fixedTick(),
                        snapshot.worldItems().nextItemId(),
                        snapshot.worldItems().itemIdsExhausted(),
                        0,
                        List.of()),
                List.of(),
                "11".repeat(32),
                () -> true);
    }

    private static final class BackendProof implements WorldItemDurableProof {}

    private static final class ForeignProof implements WorldItemDurableProof {}

    private static final class RecordingStreamedSession implements GameSession {
        private final com.gaia.save.snapshot.SaveGameSnapshot snapshot;
        private final WorldItemPersistencePlan plan;
        private final List<String> events;
        private final WorldItemDurableProof expectedProof;
        private boolean committed;
        private boolean closed;
        private long visibleCheckpointRevision;

        private RecordingStreamedSession(
                com.gaia.save.snapshot.SaveGameSnapshot snapshot,
                WorldItemPersistencePlan plan,
                List<String> events,
                WorldItemDurableProof expectedProof) {
            this.snapshot = snapshot;
            this.plan = plan;
            this.events = events;
            this.expectedProof = expectedProof;
        }

        @Override
        public Optional<WorldItemPersistencePlan> prepareWorldItemPersistence() {
            events.add("prepare-world-items");
            return committed ? Optional.empty() : Optional.of(plan);
        }

        @Override
        public void commitWorldItemPersistence(WorldItemDurableProof proof) {
            events.add("commit-world-items");
            if (proof != expectedProof) {
                throw new IllegalStateException("foreign durability proof");
            }
            committed = true;
            visibleCheckpointRevision = plan.intendedCheckpoint().checkpointRevision();
        }

        @Override
        public void cancelWorldItemPersistence() {
            events.add("cancel-world-items");
        }

        @Override
        public SessionSaveCaptureResult captureSave() {
            events.add("capture");
            return GameSessionPersistenceTestFixture.runtimeCaptured(snapshot, 5L);
        }

        @Override
        public void markSaved(SessionPersistenceRevision revision) {
            events.add("mark-saved");
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
        @Override public void discardFixedTime() {}
        @Override public void close() { closed = true; }

        private boolean persistenceCommitted() { return committed; }
        private boolean closed() { return closed; }
        private long visibleCheckpointRevision() { return visibleCheckpointRevision; }
    }

}

package com.gaia.save.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.gaia.save.snapshot.SaveGameSnapshot;
import com.gaia.save.store.SaveWriteResult;
import com.gaia.session.GameSession;
import com.gaia.session.GameSessionFrame;
import com.gaia.session.GameSessionPersistenceTestFixture;
import com.gaia.session.GameSessionSaveResult;
import com.gaia.session.GameSessionSaveLifecycleTest;
import com.gaia.session.GameSessionState;
import com.gaia.session.SessionPersistenceRevision;
import com.gaia.session.SessionSaveCaptureResult;
import com.overlord.core.input.MouseDelta;
import com.overlord.worlditem.api.SaveIdentity;
import com.overlord.worlditem.api.WorldItemDurableProof;
import com.overlord.worlditem.api.WorldItemPagingCheckpoint;
import com.overlord.worlditem.api.WorldItemPersistencePlan;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SaveCoordinatorFatalCleanupTest {
    private static final Instant MODIFIED =
            Instant.parse("2026-08-12T12:00:00Z");

    @Test
    void captureFailureCancelsExactPreparedPersistenceAndPreservesPrimary() {
        RecordingSession session = new RecordingSession();
        RuntimeException primary = new RuntimeException("capture failed");
        session.captureFailure = primary;

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> coordinator(successTarget()).save(session, MODIFIED));

        assertSame(primary, thrown);
        assertCanceled(session);
    }

    @Test
    void targetFactoryFailureCancelsExactPreparedPersistenceAndPreservesPrimary() {
        RecordingSession session = new RecordingSession();
        AssertionError primary = new AssertionError("target factory failed");
        SaveCoordinator coordinator = new SaveCoordinator(ignored -> {
            throw primary;
        });

        AssertionError thrown = assertThrows(AssertionError.class,
                () -> coordinator.save(session, MODIFIED));

        assertSame(primary, thrown);
        assertCanceled(session);
    }

    @Test
    void atomicWriteRuntimeFailureCancelsAndReturnsTheExistingTypedFailure() {
        RecordingSession session = new RecordingSession();
        RuntimeException primary = new RuntimeException("atomic write failed");

        var result = coordinator(throwingTarget(primary)).save(session, MODIFIED);

        assertEquals(GameSessionSaveResult.Status.WRITE_FAILED, result.status());
        assertEquals("save-write.world-items-persistence-failed",
                result.diagnostics().get(0).code());
        assertCanceled(session);
    }

    @Test
    void atomicWriteErrorCancelsAndRethrowsTheOriginalFailure() {
        RecordingSession session = new RecordingSession();
        AssertionError primary = new AssertionError("atomic write fatal");

        AssertionError thrown = assertThrows(AssertionError.class,
                () -> coordinator(throwingTarget(primary)).save(session, MODIFIED));

        assertSame(primary, thrown);
        assertCanceled(session);
    }

    @Test
    void proofCommitRuntimeFailureCancelsAndReturnsTheExistingTypedFailure() {
        RecordingSession session = new RecordingSession();
        RuntimeException primary = new RuntimeException("proof rejected");
        session.commitFailure = primary;

        var result = coordinator(successTarget()).save(session, MODIFIED);

        assertEquals(GameSessionSaveResult.Status.WRITE_FAILED, result.status());
        assertEquals("save-write.world-items-proof-rejected",
                result.diagnostics().get(0).code());
        assertCanceled(session);
    }

    @Test
    void proofCommitErrorSuppressesDistinctPreConsumeFailureAndRetriesCleanup() {
        RecordingSession session = new RecordingSession();
        AssertionError primary = new AssertionError("proof commit fatal");
        RuntimeException cleanup = new RuntimeException("cancel failed");
        session.commitFailure = primary;
        session.preConsumeCancelFailure = cleanup;

        AssertionError thrown = assertThrows(AssertionError.class,
                () -> coordinator(successTarget()).save(session, MODIFIED));

        assertSame(primary, thrown);
        assertEquals(List.of(cleanup), List.of(thrown.getSuppressed()));
        assertEquals(2, session.cancelCalls);
        assertFalse(session.pending,
                "the bounded retry must consume the exact persistence ticket");
    }

    @Test
    void catchCleanupRetriesAnUnconsumedPendingGuardAfterFirstCancelThrows() {
        RecordingSession session = new RecordingSession();
        RuntimeException primary = new RuntimeException("capture failed");
        RuntimeException firstCleanup = new RuntimeException("cancel failed before consume");
        session.captureFailure = primary;
        session.preConsumeCancelFailure = firstCleanup;

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> coordinator(successTarget()).save(session, MODIFIED));

        assertSame(primary, thrown);
        assertEquals(List.of(firstCleanup), List.of(thrown.getSuppressed()));
        assertEquals(2, session.cancelCalls,
                "bounded catch cleanup must retry the still-owned pending guard once");
        assertFalse(session.pending,
                "successful cleanup retry must consume the exact pending guard");
    }

    private static SaveCoordinator coordinator(SaveCoordinator.SaveTarget target) {
        return new SaveCoordinator(ignored -> target);
    }

    private static SaveCoordinator.SaveTarget successTarget() {
        return new SaveCoordinator.SaveTarget() {
            @Override
            public SaveCoordinator.AtomicSaveWrite saveAtomically(
                    SaveGameSnapshot snapshot,
                    Instant modified,
                    Optional<WorldItemPersistencePlan> worldItems,
                    java.util.function.Function<com.overlord.voxel.ChunkKey,
                            Optional<com.overlord.voxel.ChunkSnapshot>> chunks) {
                return new SaveCoordinator.AtomicSaveWrite(
                        SaveWriteResult.success(
                                GameSessionSaveLifecycleTest.manifest()),
                        Optional.of(new Proof()));
            }

            @Override
            public SaveWriteResult save(SaveGameSnapshot snapshot, Instant modified) {
                throw new AssertionError("atomic save path required");
            }
        };
    }

    private static SaveCoordinator.SaveTarget throwingTarget(Throwable failure) {
        return new SaveCoordinator.SaveTarget() {
            @Override
            public SaveCoordinator.AtomicSaveWrite saveAtomically(
                    SaveGameSnapshot snapshot,
                    Instant modified,
                    Optional<WorldItemPersistencePlan> worldItems,
                    java.util.function.Function<com.overlord.voxel.ChunkKey,
                            Optional<com.overlord.voxel.ChunkSnapshot>> chunks) {
                rethrow(failure);
                throw new AssertionError("unreachable");
            }

            @Override
            public SaveWriteResult save(SaveGameSnapshot snapshot, Instant modified) {
                throw new AssertionError("atomic save path required");
            }
        };
    }

    private static void assertCanceled(RecordingSession session) {
        assertEquals(1, session.cancelCalls);
        assertFalse(session.pending, "the exact persistence ticket must not remain pinned");
    }

    private static WorldItemPersistencePlan plan() {
        SaveGameSnapshot snapshot = GameSessionSaveLifecycleTest.snapshot();
        return new WorldItemPersistencePlan(
                0L,
                new WorldItemPagingCheckpoint(
                        new SaveIdentity(UUID.fromString(
                                snapshot.metadata().saveGameId().value())),
                        1L, snapshot.fixedTick(),
                        snapshot.worldItems().nextItemId(),
                        snapshot.worldItems().itemIdsExhausted(),
                        0, List.of()),
                List.of(), "55".repeat(32), () -> true);
    }

    private static void rethrow(Throwable failure) {
        if (failure instanceof Error error) {
            throw error;
        }
        throw (RuntimeException) failure;
    }

    private static final class Proof implements WorldItemDurableProof {}

    private static final class RecordingSession implements GameSession {
        private final WorldItemPersistencePlan plan = plan();
        private boolean pending;
        private int cancelCalls;
        private Throwable captureFailure;
        private Throwable commitFailure;
        private Throwable preConsumeCancelFailure;

        @Override
        public Optional<WorldItemPersistencePlan> prepareWorldItemPersistence() {
            pending = true;
            return Optional.of(plan);
        }

        @Override
        public SessionSaveCaptureResult captureSave() {
            if (captureFailure != null) {
                rethrow(captureFailure);
            }
            return GameSessionPersistenceTestFixture.runtimeCaptured(
                    GameSessionSaveLifecycleTest.snapshot(), 5L);
        }

        @Override
        public void commitWorldItemPersistence(WorldItemDurableProof proof) {
            if (commitFailure != null) {
                rethrow(commitFailure);
            }
            pending = false;
        }

        @Override
        public void cancelWorldItemPersistence() {
            cancelCalls++;
            if (preConsumeCancelFailure != null) {
                Throwable failure = preConsumeCancelFailure;
                preConsumeCancelFailure = null;
                rethrow(failure);
            }
            pending = false;
        }

        @Override public void markSaved(SessionPersistenceRevision revision) {}
        @Override public GameSessionState state() { return GameSessionState.READY; }
        @Override public void pollLoad() {}
        @Override public GameSessionFrame advancePlaying(
                double delta, MouseDelta look, boolean focused) {
            throw new UnsupportedOperationException();
        }
        @Override public GameSessionFrame capturePaused() {
            throw new UnsupportedOperationException();
        }
        @Override public void discardFixedTime() {}
        @Override public void close() {}
    }
}

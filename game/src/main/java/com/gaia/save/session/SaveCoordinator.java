package com.gaia.save.session;

import com.gaia.save.archive.SaveDiagnostic;
import com.gaia.save.format.SaveGameId;
import com.gaia.save.snapshot.SaveGameSnapshot;
import com.gaia.save.store.SaveWriteResult;
import com.gaia.session.GameSession;
import com.gaia.session.GameSessionSaveResult;
import com.gaia.session.NewWorldRequest;
import com.gaia.session.SessionPersistenceRevision;
import com.gaia.session.SessionSaveCaptureResult;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Owner-thread capture, write, and exact-checkpoint publication boundary. */
public final class SaveCoordinator {
    private final SaveTargetFactory targets;

    public SaveCoordinator(SaveTargetFactory targets) {
        this.targets = Objects.requireNonNull(targets, "targets");
    }

    public GameSessionSaveResult save(GameSession session, Instant modifiedTime) {
        return saveCaptured(session, modifiedTime, null);
    }

    public GameSessionSaveResult saveInitial(
            GameSession session,
            Instant modifiedTime,
            NewWorldRequest request) {
        return saveCaptured(
                session,
                modifiedTime,
                Objects.requireNonNull(request, "request"));
    }

    private GameSessionSaveResult saveCaptured(
            GameSession session,
            Instant modifiedTime,
            NewWorldRequest initialRequest) {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(modifiedTime, "modifiedTime");
        SessionSaveCaptureResult capture = session.captureSave();
        if (capture.status() != SessionSaveCaptureResult.Status.CAPTURED) {
            return GameSessionSaveResult.failed(
                    GameSessionSaveResult.Status.CAPTURE_REJECTED,
                    List.of(captureDiagnostic(capture.status())));
        }
        SaveGameSnapshot snapshot = capture.snapshot().orElseThrow();
        if (initialRequest != null && !matches(initialRequest, snapshot)) {
            return GameSessionSaveResult.failed(
                    GameSessionSaveResult.Status.CAPTURE_REJECTED,
                    List.of(SaveDiagnostic.of(
                            "save-capture.initial-metadata-mismatch",
                            "The generated session does not match the new-world request")));
        }
        SessionPersistenceRevision revision = capture.persistenceRevision().orElseThrow();
        SaveWriteResult write = Objects.requireNonNull(
                targets.target(snapshot.metadata().saveGameId())
                        .save(snapshot, modifiedTime),
                "save write result");
        if (write.status() == SaveWriteResult.Status.SUCCESS) {
            session.markSaved(revision);
            return GameSessionSaveResult.success(write.committedManifest().orElseThrow());
        }
        return GameSessionSaveResult.failed(
                write.status() == SaveWriteResult.Status.BLOCKING_FAILURE
                        ? GameSessionSaveResult.Status.BLOCKING_FAILURE
                        : GameSessionSaveResult.Status.WRITE_FAILED,
                write.diagnostics());
    }

    private static boolean matches(
            NewWorldRequest request, SaveGameSnapshot snapshot) {
        return snapshot.metadata().saveGameId().equals(request.saveGameId())
                && snapshot.metadata().displayName().equals(request.displayName())
                && snapshot.metadata().worldSeed() == request.seed();
    }

    public void markLoaded(GameSession session) {
        Objects.requireNonNull(session, "session");
        SessionSaveCaptureResult capture = session.captureSave();
        if (capture.status() != SessionSaveCaptureResult.Status.CAPTURED) {
            throw new IllegalStateException("loaded session could not publish its checkpoint");
        }
        session.markSaved(capture.persistenceRevision().orElseThrow());
    }

    private static SaveDiagnostic captureDiagnostic(
            SessionSaveCaptureResult.Status status) {
        return switch (status) {
            case PENDING_TRANSACTION -> SaveDiagnostic.of(
                    "save-capture.pending-transaction",
                    "The session has an in-flight canonical transaction");
            case INCONSISTENT_REVISION -> SaveDiagnostic.of(
                    "save-capture.inconsistent-revision",
                    "The session changed while the save snapshot was captured");
            case CAPTURED -> throw new IllegalArgumentException("CAPTURED is not a failure");
        };
    }

    @FunctionalInterface
    public interface SaveTargetFactory {
        SaveTarget target(SaveGameId saveGameId);
    }

    @FunctionalInterface
    public interface SaveTarget {
        SaveWriteResult save(SaveGameSnapshot snapshot, Instant modifiedTime);
    }
}

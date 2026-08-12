package com.gaia.session;

import com.gaia.save.snapshot.SaveGameSnapshot;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/** Closed, read-only outcome of an attempted immutable session save capture. */
public final class SessionSaveCaptureResult {
    private final Status status;
    private final Optional<SaveGameSnapshot> snapshot;
    private final OptionalLong capturedRevision;
    private final Optional<SessionPersistenceRevision> persistenceRevision;

    private SessionSaveCaptureResult(
            Status status,
            Optional<SaveGameSnapshot> snapshot,
            OptionalLong capturedRevision,
            Optional<SessionPersistenceRevision> persistenceRevision) {
        this.status = Objects.requireNonNull(status, "status");
        this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
        this.capturedRevision = Objects.requireNonNull(
                capturedRevision, "capturedRevision");
        this.persistenceRevision = Objects.requireNonNull(
                persistenceRevision, "persistenceRevision");
    }

    static SessionSaveCaptureResult captured(
            SaveGameSnapshot snapshot,
            SessionPersistenceRevision persistenceRevision) {
        SaveGameSnapshot capturedSnapshot =
                Objects.requireNonNull(snapshot, "snapshot");
        SessionPersistenceRevision capturedToken =
                Objects.requireNonNull(
                        persistenceRevision, "persistenceRevision");
        return new SessionSaveCaptureResult(
                Status.CAPTURED,
                Optional.of(capturedSnapshot),
                OptionalLong.of(capturedToken.value()),
                Optional.of(capturedToken));
    }

    public static SessionSaveCaptureResult pendingTransaction() {
        return empty(Status.PENDING_TRANSACTION);
    }

    public static SessionSaveCaptureResult inconsistentRevision() {
        return empty(Status.INCONSISTENT_REVISION);
    }

    private static SessionSaveCaptureResult empty(Status status) {
        return new SessionSaveCaptureResult(
                status,
                Optional.empty(),
                OptionalLong.empty(),
                Optional.empty());
    }

    public Status status() {
        return status;
    }

    public Optional<SaveGameSnapshot> snapshot() {
        return snapshot;
    }

    public OptionalLong capturedRevision() {
        return capturedRevision;
    }

    public Optional<SessionPersistenceRevision> persistenceRevision() {
        return persistenceRevision;
    }

    @Override
    public boolean equals(Object object) {
        return this == object
                || object instanceof SessionSaveCaptureResult other
                        && status == other.status
                        && snapshot.equals(other.snapshot)
                        && capturedRevision.equals(other.capturedRevision)
                        && persistenceRevision.equals(
                                other.persistenceRevision);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                status, snapshot, capturedRevision, persistenceRevision);
    }

    @Override
    public String toString() {
        return "SessionSaveCaptureResult[status=" + status
                + ", snapshot=" + snapshot
                + ", capturedRevision=" + capturedRevision
                + ", persistenceRevision=" + persistenceRevision + "]";
    }

    public enum Status {
        CAPTURED,
        PENDING_TRANSACTION,
        INCONSISTENT_REVISION
    }
}

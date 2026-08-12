package com.gaia.session;

import com.gaia.save.snapshot.SaveGameSnapshot;
import java.util.Objects;

/** Package-owned checked fixed-tick/revision and capture authority. */
final class SessionPersistenceClock {
    private final Object provenance = new Object();
    private long fixedTick;
    private long revision;
    private long issuedNonce;

    private SessionPersistenceClock(long fixedTick, long revision) {
        if (fixedTick < 0) {
            throw new IllegalArgumentException("fixed tick must be non-negative");
        }
        if (revision < 0) {
            throw new IllegalArgumentException(
                    "session persistence revision must be non-negative");
        }
        this.fixedTick = fixedTick;
        this.revision = revision;
    }

    static SessionPersistenceClock restored(
            long fixedTick, long revision) {
        return new SessionPersistenceClock(fixedTick, revision);
    }

    long fixedTick() {
        return fixedTick;
    }

    long revision() {
        return revision;
    }

    MutationReservation reserveFixedStep() {
        return new MutationReservation(
                this,
                Math.incrementExact(fixedTick),
                Math.incrementExact(revision));
    }

    MutationReservation reserveRevisionMutation() {
        return new MutationReservation(
                this, fixedTick, Math.incrementExact(revision));
    }

    SessionSaveCaptureResult captured(
            SaveGameSnapshot snapshot, long value) {
        SaveGameSnapshot capturedSnapshot =
                Objects.requireNonNull(snapshot, "snapshot");
        long nonce = Math.incrementExact(issuedNonce);
        issuedNonce = nonce;
        SessionPersistenceRevision capturedRevision =
                new SessionPersistenceRevision(
                        provenance, value, nonce);
        return SessionSaveCaptureResult.captured(
                capturedSnapshot, capturedRevision);
    }

    static final class MutationReservation {
        private final SessionPersistenceClock owner;
        private final long nextFixedTick;
        private final long nextRevision;
        private boolean committed;

        private MutationReservation(
                SessionPersistenceClock owner,
                long nextFixedTick,
                long nextRevision) {
            this.owner = owner;
            this.nextFixedTick = nextFixedTick;
            this.nextRevision = nextRevision;
        }

        void commit() {
            if (committed) {
                return;
            }
            owner.fixedTick = nextFixedTick;
            owner.revision = nextRevision;
            committed = true;
        }
    }
}

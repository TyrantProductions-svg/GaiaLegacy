package com.gaia.session;

import java.util.Objects;

/** Opaque, lightweight checkpoint capability returned by a successful capture. */
public final class SessionPersistenceRevision {
    private final Object provenance;
    private final long value;
    private final long nonce;

    SessionPersistenceRevision(
            Object provenance,
            long value,
            long nonce) {
        if (value < 0) {
            throw new IllegalArgumentException(
                    "session persistence revision must be non-negative");
        }
        if (nonce <= 0) {
            throw new IllegalArgumentException(
                    "session persistence nonce must be positive");
        }
        this.provenance = Objects.requireNonNull(provenance, "provenance");
        this.value = value;
        this.nonce = nonce;
    }

    public long value() {
        return value;
    }

    @Override
    public boolean equals(Object object) {
        return this == object
                || object instanceof SessionPersistenceRevision other
                        && provenance == other.provenance
                        && value == other.value
                        && nonce == other.nonce;
    }

    @Override
    public int hashCode() {
        int result = System.identityHashCode(provenance);
        result = 31 * result + Long.hashCode(value);
        return 31 * result + Long.hashCode(nonce);
    }

    @Override
    public String toString() {
        return "SessionPersistenceRevision[value=" + value + "]";
    }
}

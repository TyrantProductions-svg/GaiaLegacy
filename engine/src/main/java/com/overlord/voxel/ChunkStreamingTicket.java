package com.overlord.voxel;

import java.util.Objects;

/** Exact authority for one coalesced streaming request incarnation. */
public record ChunkStreamingTicket(
        ChunkKey key,
        long epoch,
        SourcePreference sourcePreference,
        long expectedRevision,
        long requestId) {
    public ChunkStreamingTicket {
        key = ChunkCoordinatePolicy.requireSafe(key);
        if (epoch < 0) {
            throw new IllegalArgumentException("epoch must not be negative");
        }
        Objects.requireNonNull(sourcePreference, "sourcePreference");
        if (expectedRevision < 0) {
            throw new IllegalArgumentException(
                    "expectedRevision must not be negative");
        }
        if (requestId <= 0) {
            throw new IllegalArgumentException(
                    "requestId must be greater than zero");
        }
    }

    public enum SourcePreference {
        LOAD,
        GENERATE
    }

    /** Worker-observed source and canonical base revision. */
    public record BaseIdentity(
            SourcePreference sourcePreference,
            long expectedRevision,
            long persistedRevision) {
        public BaseIdentity(
                SourcePreference sourcePreference, long expectedRevision) {
            this(sourcePreference, expectedRevision, 0L);
        }

        public BaseIdentity {
            Objects.requireNonNull(sourcePreference, "sourcePreference");
            if (expectedRevision < 0 || persistedRevision < 0) {
                throw new IllegalArgumentException(
                        "revisions must not be negative");
            }
            if (sourcePreference == SourcePreference.GENERATE
                    && persistedRevision != 0L) {
                throw new IllegalArgumentException(
                        "generated Chunk data has no persisted revision");
            }
        }
    }
}

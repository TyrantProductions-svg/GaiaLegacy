package com.overlord.voxel;

import java.util.Objects;

/** Outcome of attempting to publish an immutable streaming worker result. */
public record ChunkStreamingPublication(
        Status status, ChunkKey key, long epoch, long revision) {
    public ChunkStreamingPublication {
        Objects.requireNonNull(status, "status");
        key = ChunkCoordinatePolicy.requireSafe(key);
        if (epoch < 0) {
            throw new IllegalArgumentException("epoch must not be negative");
        }
        if (revision < 0) {
            throw new IllegalArgumentException(
                    "revision must not be negative");
        }
        if (status == Status.PUBLISHED && revision == 0) {
            throw new IllegalArgumentException(
                    "PUBLISHED requires a positive revision");
        }
    }

    public enum Status {
        PUBLISHED,
        STALE
    }
}

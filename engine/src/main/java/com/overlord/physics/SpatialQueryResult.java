package com.overlord.physics;

import com.overlord.voxel.ChunkKey;
import java.util.Objects;
import java.util.Optional;

/** Explicit availability result for an origin-aware spatial query. */
public record SpatialQueryResult<T>(
        Status status, Optional<T> result, Optional<ChunkKey> unavailableKey) {
    public SpatialQueryResult {
        status = Objects.requireNonNull(status, "status");
        result = Objects.requireNonNull(result, "result");
        unavailableKey = Objects.requireNonNull(unavailableKey, "unavailableKey");
        if (status == Status.AVAILABLE && unavailableKey.isPresent()) {
            throw new IllegalArgumentException("available query cannot have an unavailable key");
        }
        if (status != Status.AVAILABLE && (result.isPresent() || unavailableKey.isEmpty())) {
            throw new IllegalArgumentException("unavailable query requires only a canonical key");
        }
    }

    public static <T> SpatialQueryResult<T> available(Optional<T> result) {
        return new SpatialQueryResult<>(Status.AVAILABLE, result, Optional.empty());
    }

    public static <T> SpatialQueryResult<T> unavailable(Status status, ChunkKey key) {
        if (status == Status.AVAILABLE) {
            throw new IllegalArgumentException("status must be unavailable");
        }
        return new SpatialQueryResult<>(status, Optional.empty(), Optional.of(key));
    }

    public enum Status {
        AVAILABLE,
        UNKNOWN,
        FAILED
    }
}

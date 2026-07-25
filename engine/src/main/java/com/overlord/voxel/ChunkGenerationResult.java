package com.overlord.voxel;

import java.util.Objects;
import java.util.Optional;

public record ChunkGenerationResult(
        Status status,
        ChunkKey key,
        long revision,
        Optional<Throwable> failure) {
    public ChunkGenerationResult {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(failure, "failure");
    }

    public enum Status {
        COMMITTED,
        CONFLICT,
        FAILED
    }
}

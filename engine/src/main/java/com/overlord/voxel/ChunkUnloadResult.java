package com.overlord.voxel;

import java.util.Objects;

public record ChunkUnloadResult(Status status) {
    public enum Status {
        VALID,
        CANCELED,
        COMMITTED,
        STALE,
        FOREIGN
    }

    public ChunkUnloadResult {
        Objects.requireNonNull(status, "status");
    }
}

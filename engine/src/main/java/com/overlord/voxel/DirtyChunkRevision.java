package com.overlord.voxel;

import java.util.Objects;

public record DirtyChunkRevision(ChunkKey key, long revision) {
    public DirtyChunkRevision {
        key = Objects.requireNonNull(key, "key");
        if (revision <= 0) {
            throw new IllegalArgumentException(
                    "revision must be positive");
        }
    }
}

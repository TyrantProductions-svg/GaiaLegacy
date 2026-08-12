package com.overlord.voxel;

import java.util.List;
import java.util.Objects;

public record ChunkRepositorySnapshot(
        int worldHeight,
        long revisionHighWater,
        List<ChunkSnapshot> chunks) {
    public ChunkRepositorySnapshot {
        chunks = List.copyOf(Objects.requireNonNull(chunks, "chunks"));
    }
}

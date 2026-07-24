package com.overlord.interaction.api;

import com.overlord.voxel.ChunkKey;
import java.util.Objects;
import java.util.Set;

public record ChunkDirtyEvent(
        BlockChangeRequest request, Set<ChunkKey> chunks) {
    public ChunkDirtyEvent {
        request = Objects.requireNonNull(request, "request");
        chunks = Set.copyOf(chunks);
        if (chunks.isEmpty()) {
            throw new IllegalArgumentException(
                    "dirty event requires at least one chunk");
        }
    }
}

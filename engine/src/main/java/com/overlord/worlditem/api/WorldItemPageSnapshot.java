package com.overlord.worlditem.api;

import com.overlord.config.GameConfig;
import com.overlord.voxel.ChunkCoordinatePolicy;
import com.overlord.voxel.ChunkKey;
import java.util.List;
import java.util.Objects;

/** Detached canonical candidate for one Chunk-owned WorldItem page. */
public record WorldItemPageSnapshot(
        ChunkKey chunkKey,
        long pageRevision,
        List<WorldItemRestoreEntry> entries) {
    public WorldItemPageSnapshot {
        chunkKey = ChunkCoordinatePolicy.requireSafe(chunkKey);
        if (pageRevision <= 0L) {
            throw new IllegalArgumentException("pageRevision must be positive");
        }
        Objects.requireNonNull(entries, "entries");
        if (entries.size() > GameConfig.Interaction.MAX_LOGICAL_WORLD_ITEMS) {
            throw new IllegalArgumentException("WorldItem page exceeds its entry bound");
        }
        entries = List.copyOf(entries);
    }
}

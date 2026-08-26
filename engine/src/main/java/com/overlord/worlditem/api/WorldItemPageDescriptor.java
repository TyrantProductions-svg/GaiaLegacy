package com.overlord.worlditem.api;

import com.overlord.config.GameConfig;
import com.overlord.voxel.ChunkCoordinatePolicy;
import com.overlord.voxel.ChunkKey;
import java.util.Objects;

/** Exact immutable identity and checkpoint-time survivor count of one page. */
public record WorldItemPageDescriptor(
        ChunkKey chunkKey,
        long pageRevision,
        String pageHash,
        int encodedEntryCount,
        int expectedLiveCountAtCheckpointTick) {
    public WorldItemPageDescriptor {
        chunkKey = ChunkCoordinatePolicy.requireSafe(chunkKey);
        if (pageRevision <= 0L) {
            throw new IllegalArgumentException("pageRevision must be positive");
        }
        pageHash = Objects.requireNonNull(pageHash, "pageHash");
        if (!pageHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("pageHash must be canonical SHA-256");
        }
        if (encodedEntryCount <= 0
                || encodedEntryCount > GameConfig.Interaction.MAX_LOGICAL_WORLD_ITEMS) {
            throw new IllegalArgumentException("encodedEntryCount exceeds its bound");
        }
        if (expectedLiveCountAtCheckpointTick < 0
                || expectedLiveCountAtCheckpointTick > encodedEntryCount) {
            throw new IllegalArgumentException(
                    "expected live count must be within the encoded entry count");
        }
    }
}

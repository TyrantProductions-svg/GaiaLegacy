package com.overlord.voxel;

import com.overlord.config.GameConfig;
import java.util.Objects;

public record ParentCellObservation(
        ChunkKey chunkKey,
        int localX,
        int y,
        int localZ,
        long chunkRevision,
        ParentCellState state) {
    public ParentCellObservation {
        chunkKey = ChunkCoordinatePolicy.requireSafe(chunkKey);
        requireLocal(localX, "localX");
        if (y < 0) {
            throw new IllegalArgumentException(
                    "y must be nonnegative");
        }
        requireLocal(localZ, "localZ");
        if (chunkRevision <= 0L) {
            throw new IllegalArgumentException(
                    "chunkRevision must be positive");
        }
        state = Objects.requireNonNull(state, "state");
    }

    public int worldX() {
        return Math.addExact(chunkKey.worldOriginX(), localX);
    }

    public int worldZ() {
        return Math.addExact(chunkKey.worldOriginZ(), localZ);
    }

    private static void requireLocal(int coordinate, String name) {
        if (coordinate < 0 || coordinate >= GameConfig.Chunk.SIZE) {
            throw new IllegalArgumentException(
                    name
                            + " must be between 0 and "
                            + (GameConfig.Chunk.SIZE - 1));
        }
    }
}

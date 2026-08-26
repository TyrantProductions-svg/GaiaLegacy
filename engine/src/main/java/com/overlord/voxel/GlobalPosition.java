package com.overlord.voxel;

import com.overlord.config.GameConfig;

/** Canonical immutable global position expressed as a Chunk and local doubles. */
public record GlobalPosition(
        ChunkKey chunkKey,
        double localX,
        double y,
        double localZ) {
    public GlobalPosition {
        chunkKey = ChunkCoordinatePolicy.requireSafe(chunkKey);
        localX = canonicalLocal(localX, "localX");
        localZ = canonicalLocal(localZ, "localZ");
        if (!Double.isFinite(y)) {
            throw new IllegalArgumentException("y must be finite");
        }
        y = canonicalZero(y);
    }

    private static double canonicalLocal(double value, String name) {
        if (!Double.isFinite(value)
                || value < 0.0
                || value >= GameConfig.Chunk.SIZE) {
            throw new IllegalArgumentException(
                    name + " must be finite and canonical within one Chunk");
        }
        return canonicalZero(value);
    }

    private static double canonicalZero(double value) {
        return value == 0.0 ? 0.0 : value;
    }
}

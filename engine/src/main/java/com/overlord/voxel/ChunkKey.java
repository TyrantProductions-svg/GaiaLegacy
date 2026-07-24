package com.overlord.voxel;

import com.overlord.config.GameConfig;

public record ChunkKey(int x, int z) {
    public static ChunkKey fromWorld(int worldX, int worldZ) {
        return new ChunkKey(
                Math.floorDiv(worldX, GameConfig.Chunk.SIZE),
                Math.floorDiv(worldZ, GameConfig.Chunk.SIZE));
    }

    public static int localCoordinate(int worldCoordinate) {
        return Math.floorMod(worldCoordinate, GameConfig.Chunk.SIZE);
    }

    public int worldOriginX() {
        return x * GameConfig.Chunk.SIZE;
    }

    public int worldOriginZ() {
        return z * GameConfig.Chunk.SIZE;
    }

    public ChunkKey north() {
        return new ChunkKey(x, z - 1);
    }

    public ChunkKey south() {
        return new ChunkKey(x, z + 1);
    }

    public ChunkKey west() {
        return new ChunkKey(x - 1, z);
    }

    public ChunkKey east() {
        return new ChunkKey(x + 1, z);
    }
}

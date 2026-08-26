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
        return Math.toIntExact(ChunkCoordinatePolicy.worldOriginX(this));
    }

    public int worldOriginZ() {
        return Math.toIntExact(ChunkCoordinatePolicy.worldOriginZ(this));
    }

    public ChunkKey north() {
        return ChunkCoordinatePolicy.neighbor(this, 0, -1);
    }

    public ChunkKey south() {
        return ChunkCoordinatePolicy.neighbor(this, 0, 1);
    }

    public ChunkKey west() {
        return ChunkCoordinatePolicy.neighbor(this, -1, 0);
    }

    public ChunkKey east() {
        return ChunkCoordinatePolicy.neighbor(this, 1, 0);
    }

    public ChunkKey northWest() {
        return ChunkCoordinatePolicy.neighbor(this, -1, -1);
    }

    public ChunkKey northEast() {
        return ChunkCoordinatePolicy.neighbor(this, 1, -1);
    }

    public ChunkKey southWest() {
        return ChunkCoordinatePolicy.neighbor(this, -1, 1);
    }

    public ChunkKey southEast() {
        return ChunkCoordinatePolicy.neighbor(this, 1, 1);
    }
}

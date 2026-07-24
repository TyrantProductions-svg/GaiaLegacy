package com.overlord.voxel;

import com.overlord.config.GameConfig;
import java.util.Objects;

public record ChunkMeshInput(
        ChunkSnapshot center,
        ChunkSnapshot north,
        ChunkSnapshot south,
        ChunkSnapshot west,
        ChunkSnapshot east) {
    public ChunkMeshInput {
        center = Objects.requireNonNull(center, "center");
        int worldHeight = center.worldHeight();
        north =
                normalizeNeighbor(
                        north,
                        center.key().north(),
                        worldHeight,
                        "north");
        south =
                normalizeNeighbor(
                        south,
                        center.key().south(),
                        worldHeight,
                        "south");
        west =
                normalizeNeighbor(
                        west,
                        center.key().west(),
                        worldHeight,
                        "west");
        east =
                normalizeNeighbor(
                        east,
                        center.key().east(),
                        worldHeight,
                        "east");
    }

    public byte getBlock(int localX, int y, int localZ) {
        if (y < 0 || y >= center.worldHeight()) {
            return 0;
        }
        if (localX < 0) {
            return west.getBlock(
                    GameConfig.Chunk.SIZE - 1, y, localZ);
        }
        if (localX >= GameConfig.Chunk.SIZE) {
            return east.getBlock(0, y, localZ);
        }
        if (localZ < 0) {
            return north.getBlock(
                    localX, y, GameConfig.Chunk.SIZE - 1);
        }
        if (localZ >= GameConfig.Chunk.SIZE) {
            return south.getBlock(localX, y, 0);
        }
        return center.getBlock(localX, y, localZ);
    }

    public BlockSize getBlockSize(int localX, int y, int localZ) {
        if (y < 0 || y >= center.worldHeight()) {
            return BlockSize.SIZE_16;
        }
        if (localX < 0) {
            return west.getBlockSize(
                    GameConfig.Chunk.SIZE - 1, y, localZ);
        }
        if (localX >= GameConfig.Chunk.SIZE) {
            return east.getBlockSize(0, y, localZ);
        }
        if (localZ < 0) {
            return north.getBlockSize(
                    localX, y, GameConfig.Chunk.SIZE - 1);
        }
        if (localZ >= GameConfig.Chunk.SIZE) {
            return south.getBlockSize(localX, y, 0);
        }
        return center.getBlockSize(localX, y, localZ);
    }

    private static ChunkSnapshot normalizeNeighbor(
            ChunkSnapshot neighbor,
            ChunkKey expectedKey,
            int worldHeight,
            String direction) {
        if (neighbor == null) {
            return ChunkSnapshot.empty(
                    expectedKey, 0, worldHeight);
        }
        if (!neighbor.key().equals(expectedKey)) {
            throw new IllegalArgumentException(
                    direction
                            + " neighbor key must be "
                            + expectedKey);
        }
        if (neighbor.worldHeight() != worldHeight) {
            throw new IllegalArgumentException(
                    direction
                            + " neighbor worldHeight must be "
                            + worldHeight);
        }
        return neighbor;
    }
}
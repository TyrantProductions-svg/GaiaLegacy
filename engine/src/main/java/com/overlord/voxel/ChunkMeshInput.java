package com.overlord.voxel;

import com.overlord.config.GameConfig;
import java.util.Objects;

public record ChunkMeshInput(
        ChunkSnapshot center,
        ChunkSnapshot north,
        ChunkSnapshot northEast,
        ChunkSnapshot east,
        ChunkSnapshot southEast,
        ChunkSnapshot south,
        ChunkSnapshot southWest,
        ChunkSnapshot west,
        ChunkSnapshot northWest) {
    public ChunkMeshInput {
        center = Objects.requireNonNull(center, "center");
        int worldHeight = center.worldHeight();
        north =
                normalizeNeighbor(
                        north,
                        center.key().north(),
                        worldHeight,
                        "north");
        northEast =
                normalizeNeighbor(
                        northEast,
                        center.key().north().east(),
                        worldHeight,
                        "northEast");
        east =
                normalizeNeighbor(
                        east,
                        center.key().east(),
                        worldHeight,
                        "east");
        southEast =
                normalizeNeighbor(
                        southEast,
                        center.key().south().east(),
                        worldHeight,
                        "southEast");
        south =
                normalizeNeighbor(
                        south,
                        center.key().south(),
                        worldHeight,
                        "south");
        southWest =
                normalizeNeighbor(
                        southWest,
                        center.key().south().west(),
                        worldHeight,
                        "southWest");
        west =
                normalizeNeighbor(
                        west,
                        center.key().west(),
                        worldHeight,
                        "west");
        northWest =
                normalizeNeighbor(
                        northWest,
                        center.key().north().west(),
                        worldHeight,
                        "northWest");
    }

    public byte getBlock(int localX, int y, int localZ) {
        if (localX < -1
                || localX > GameConfig.Chunk.SIZE
                || localZ < -1
                || localZ > GameConfig.Chunk.SIZE) {
            throw new IllegalArgumentException(
                    "horizontal coordinates must stay within one-block halo");
        }
        if (y < 0 || y >= center.worldHeight()) {
            return 0;
        }
        int horizontalOffsetX =
                Math.floorDiv(localX, GameConfig.Chunk.SIZE);
        int horizontalOffsetZ =
                Math.floorDiv(localZ, GameConfig.Chunk.SIZE);
        return snapshotFor(horizontalOffsetX, horizontalOffsetZ)
                .getBlock(
                        Math.floorMod(localX, GameConfig.Chunk.SIZE),
                        y,
                        Math.floorMod(localZ, GameConfig.Chunk.SIZE));
    }

    public BlockSize getBlockSize(int localX, int y, int localZ) {
        if (localX < -1
                || localX > GameConfig.Chunk.SIZE
                || localZ < -1
                || localZ > GameConfig.Chunk.SIZE) {
            throw new IllegalArgumentException(
                    "horizontal coordinates must stay within one-block halo");
        }
        if (y < 0 || y >= center.worldHeight()) {
            return BlockSize.SIZE_16;
        }
        int horizontalOffsetX =
                Math.floorDiv(localX, GameConfig.Chunk.SIZE);
        int horizontalOffsetZ =
                Math.floorDiv(localZ, GameConfig.Chunk.SIZE);
        return snapshotFor(horizontalOffsetX, horizontalOffsetZ)
                .getBlockSize(
                        Math.floorMod(localX, GameConfig.Chunk.SIZE),
                        y,
                        Math.floorMod(localZ, GameConfig.Chunk.SIZE));
    }

    public BlockPlacement getBlockPlacement(
            int localX, int y, int localZ) {
        if (localX < -1
                || localX > GameConfig.Chunk.SIZE
                || localZ < -1
                || localZ > GameConfig.Chunk.SIZE) {
            throw new IllegalArgumentException(
                    "horizontal coordinates must stay within one-block halo");
        }
        if (y < 0 || y >= center.worldHeight()) {
            return BlockPlacement.full();
        }
        int horizontalOffsetX =
                Math.floorDiv(localX, GameConfig.Chunk.SIZE);
        int horizontalOffsetZ =
                Math.floorDiv(localZ, GameConfig.Chunk.SIZE);
        return snapshotFor(horizontalOffsetX, horizontalOffsetZ)
                .getBlockPlacement(
                        Math.floorMod(localX, GameConfig.Chunk.SIZE),
                        y,
                        Math.floorMod(localZ, GameConfig.Chunk.SIZE));
    }

    private ChunkSnapshot snapshotFor(
            int horizontalOffsetX, int horizontalOffsetZ) {
        if (horizontalOffsetX == -1) {
            if (horizontalOffsetZ == -1) {
                return northWest;
            }
            if (horizontalOffsetZ == 0) {
                return west;
            }
            return southWest;
        }
        if (horizontalOffsetX == 0) {
            if (horizontalOffsetZ == -1) {
                return north;
            }
            if (horizontalOffsetZ == 0) {
                return center;
            }
            return south;
        }
        if (horizontalOffsetZ == -1) {
            return northEast;
        }
        if (horizontalOffsetZ == 0) {
            return east;
        }
        return southEast;
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
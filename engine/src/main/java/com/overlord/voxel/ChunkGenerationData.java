package com.overlord.voxel;

import com.overlord.config.GameConfig;
import java.util.Arrays;
import java.util.Objects;

public final class ChunkGenerationData {
    private final ChunkKey key;
    private final int worldHeight;
    private final byte[] blocks;

    public ChunkGenerationData(
            ChunkKey key, int worldHeight, byte[] blocks) {
        this.key = Objects.requireNonNull(key, "key");
        if (worldHeight <= 0) {
            throw new IllegalArgumentException(
                    "worldHeight must be greater than zero");
        }
        Objects.requireNonNull(blocks, "blocks");
        int expectedLength = canonicalBlockCount(worldHeight);
        if (blocks.length != expectedLength) {
            throw new IllegalArgumentException(
                    "blocks must contain exactly "
                            + expectedLength
                            + " bytes");
        }
        this.worldHeight = worldHeight;
        this.blocks = Arrays.copyOf(blocks, blocks.length);
    }

    public ChunkKey key() {
        return key;
    }

    public int worldHeight() {
        return worldHeight;
    }

    public byte getBlock(int localX, int y, int localZ) {
        validateCoordinate(
                localX, GameConfig.Chunk.SIZE, "localX");
        validateCoordinate(y, worldHeight, "y");
        validateCoordinate(
                localZ, GameConfig.Chunk.SIZE, "localZ");
        return blocks[index(localX, y, localZ)];
    }

    public byte[] copyBlocks() {
        return Arrays.copyOf(blocks, blocks.length);
    }

    private int index(int localX, int y, int localZ) {
        return localX
                + y * GameConfig.Chunk.SIZE
                + localZ * GameConfig.Chunk.SIZE * worldHeight;
    }

    private static int canonicalBlockCount(int worldHeight) {
        try {
            return Math.multiplyExact(
                    Math.multiplyExact(
                            GameConfig.Chunk.SIZE, worldHeight),
                    GameConfig.Chunk.SIZE);
        } catch (ArithmeticException failure) {
            throw new IllegalArgumentException(
                    "worldHeight is too large", failure);
        }
    }

    private static void validateCoordinate(
            int coordinate, int upperBound, String name) {
        if (coordinate < 0 || coordinate >= upperBound) {
            throw new IllegalArgumentException(
                    name
                            + " must be between 0 and "
                            + (upperBound - 1));
        }
    }
}

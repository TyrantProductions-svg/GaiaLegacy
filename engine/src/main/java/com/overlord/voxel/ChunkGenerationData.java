package com.overlord.voxel;

import com.overlord.config.GameConfig;
import java.util.Arrays;
import java.util.Objects;

public final class ChunkGenerationData {
    private final ChunkKey key;
    private final int worldHeight;
    private final byte[] blocks;
    private final BlockPlacement[] blockPlacements;

    public ChunkGenerationData(
            ChunkKey key, int worldHeight, byte[] blocks, BlockSize[] blockSizes) {
        this(key, worldHeight, blocks, blockPlacementsFromBlockSizes(blockSizes));
    }

    public ChunkGenerationData(
            ChunkKey key, int worldHeight, byte[] blocks, BlockPlacement[] blockPlacements) {
        this.key = Objects.requireNonNull(key, "key");
        if (worldHeight <= 0) {
            throw new IllegalArgumentException(
                    "worldHeight must be greater than zero");
        }
        Objects.requireNonNull(blocks, "blocks");
        Objects.requireNonNull(blockPlacements, "blockPlacements");
        int expectedLength = canonicalBlockCount(worldHeight);
        if (blocks.length != expectedLength) {
            throw new IllegalArgumentException(
                    "blocks must contain exactly "
                            + expectedLength
                            + " bytes");
        }
        if (blockPlacements.length != expectedLength) {
            throw new IllegalArgumentException(
                    "blockPlacements must contain exactly "
                            + expectedLength
                            + " entries");
        }
        this.worldHeight = worldHeight;
        this.blocks = Arrays.copyOf(blocks, blocks.length);
        this.blockPlacements = Arrays.copyOf(blockPlacements, blockPlacements.length);
    }

    private static BlockPlacement[] blockPlacementsFromBlockSizes(
            BlockSize[] blockSizes) {
        Objects.requireNonNull(blockSizes, "blockSizes");
        BlockPlacement[] placements = new BlockPlacement[blockSizes.length];
        for (int i = 0; i < blockSizes.length; i++) {
            placements[i] = BlockPlacement.of(blockSizes[i]);
        }
        return placements;
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

    public BlockSize getBlockSize(int localX, int y, int localZ) {
        validateCoordinate(
                localX, GameConfig.Chunk.SIZE, "localX");
        validateCoordinate(y, worldHeight, "y");
        validateCoordinate(
                localZ, GameConfig.Chunk.SIZE, "localZ");
        return blockPlacements[index(localX, y, localZ)].size();
    }

    public BlockSize[] copyBlockSizes() {
        BlockSize[] sizes = new BlockSize[blockPlacements.length];
        for (int i = 0; i < blockPlacements.length; i++) {
            sizes[i] = blockPlacements[i].size();
        }
        return sizes;
    }

    public BlockPlacement[] copyBlockPlacements() {
        return Arrays.copyOf(blockPlacements, blockPlacements.length);
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
package com.overlord.voxel;

import com.overlord.config.GameConfig;
import java.util.Arrays;
import java.util.Objects;

public final class ChunkSnapshot {
    private final ChunkKey key;
    private final long revision;
    private final int worldHeight;
    private final byte[] blocks;
    private final BlockPlacement[] blockPlacements;

    private ChunkSnapshot(
            ChunkKey key,
            long revision,
            int worldHeight,
            byte[] blocks,
            BlockPlacement[] blockPlacements) {
        this.key = Objects.requireNonNull(key, "key");
        this.revision = revision;
        this.worldHeight = requireValidWorldHeight(worldHeight);
        Objects.requireNonNull(blocks, "blocks");
        Objects.requireNonNull(blockPlacements, "blockPlacements");
        int expectedLength =
                Math.multiplyExact(
                        Math.multiplyExact(GameConfig.Chunk.SIZE, worldHeight),
                        GameConfig.Chunk.SIZE);
        if (blocks.length != expectedLength) {
            throw new IllegalArgumentException(
                    "blocks length must be " + expectedLength);
        }
        if (blockPlacements.length != expectedLength) {
            throw new IllegalArgumentException(
                    "blockPlacements length must be " + expectedLength);
        }
        this.blocks = Arrays.copyOf(blocks, blocks.length);
        this.blockPlacements = Arrays.copyOf(blockPlacements, blockPlacements.length);
    }

    public ChunkKey key() {
        return key;
    }

    public long revision() {
        return revision;
    }

    public int worldHeight() {
        return worldHeight;
    }

    public byte getBlock(int localX, int y, int localZ) {
        if (localX < 0
                || localX >= GameConfig.Chunk.SIZE
                || y < 0
                || y >= worldHeight
                || localZ < 0
                || localZ >= GameConfig.Chunk.SIZE) {
            return 0;
        }
        int index =
                localX
                        + y * GameConfig.Chunk.SIZE
                        + localZ * GameConfig.Chunk.SIZE * worldHeight;
        return blocks[index];
    }

    public BlockPlacement getBlockPlacement(
            int localX, int y, int localZ) {
        if (localX < 0
                || localX >= GameConfig.Chunk.SIZE
                || y < 0
                || y >= worldHeight
                || localZ < 0
                || localZ >= GameConfig.Chunk.SIZE) {
            return BlockPlacement.full();
        }
        int index =
                localX
                        + y * GameConfig.Chunk.SIZE
                        + localZ * GameConfig.Chunk.SIZE * worldHeight;
        return blockPlacements[index];
    }

    public BlockSize getBlockSize(int localX, int y, int localZ) {
        if (localX < 0
                || localX >= GameConfig.Chunk.SIZE
                || y < 0
                || y >= worldHeight
                || localZ < 0
                || localZ >= GameConfig.Chunk.SIZE) {
            return BlockSize.SIZE_16;
        }
        int index =
                localX
                        + y * GameConfig.Chunk.SIZE
                        + localZ * GameConfig.Chunk.SIZE * worldHeight;
        return blockPlacements[index].size();
    }

    public static ChunkSnapshot of(
            ChunkKey key,
            long revision,
            int worldHeight,
            byte[] blocks,
            BlockSize[] blockSizes) {
        BlockPlacement[] placements = new BlockPlacement[blockSizes.length];
        for (int i = 0; i < blockSizes.length; i++) {
            placements[i] = BlockPlacement.of(blockSizes[i]);
        }
        return new ChunkSnapshot(
                key, revision, worldHeight, blocks, placements);
    }

    public static ChunkSnapshot of(
            ChunkKey key,
            long revision,
            int worldHeight,
            byte[] blocks,
            BlockPlacement[] blockPlacements) {
        return new ChunkSnapshot(
                key, revision, worldHeight, blocks, blockPlacements);
    }

    public static ChunkSnapshot empty(
            ChunkKey key, long revision, int worldHeight) {
        int validatedHeight = requireValidWorldHeight(worldHeight);
        int length =
                Math.multiplyExact(
                        Math.multiplyExact(
                                GameConfig.Chunk.SIZE,
                                validatedHeight),
                        GameConfig.Chunk.SIZE);
        byte[] blocks = new byte[length];
        BlockPlacement[] blockPlacements = new BlockPlacement[length];
        Arrays.fill(blockPlacements, BlockPlacement.full());
        return new ChunkSnapshot(
                key,
                revision,
                validatedHeight,
                blocks,
                blockPlacements);
    }

    private static int requireValidWorldHeight(int worldHeight) {
        if (worldHeight <= 0) {
            throw new IllegalArgumentException(
                    "worldHeight must be greater than zero");
        }
        return worldHeight;
    }
}
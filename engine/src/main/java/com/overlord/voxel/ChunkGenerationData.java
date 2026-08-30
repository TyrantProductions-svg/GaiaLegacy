package com.overlord.voxel;

import com.overlord.config.GameConfig;
import java.util.Arrays;
import java.util.Objects;

public final class ChunkGenerationData {
    private final ChunkKey key;
    private final int worldHeight;
    private final byte[] blocks;
    private final DetailChunkSnapshot details;

    public ChunkGenerationData(
            ChunkKey key, int worldHeight, byte[] blocks) {
        this(
                key,
                worldHeight,
                blocks,
                DetailChunkSnapshot.emptyView());
    }

    public ChunkGenerationData(
            ChunkKey key,
            int worldHeight,
            byte[] blocks,
            DetailChunkSnapshot details) {
        this.key = Objects.requireNonNull(key, "key");
        if (worldHeight <= 0
                || worldHeight > GameConfig.Chunk.MAX_HEIGHT) {
            throw new IllegalArgumentException(
                    "worldHeight must be between 1 and "
                            + GameConfig.Chunk.MAX_HEIGHT);
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
        DetailChunkSnapshot checkedDetails =
                Objects.requireNonNull(details, "details");
        validateDetailBacking(checkedDetails, this.blocks);
        this.details = checkedDetails.isEmpty() ? null : checkedDetails;
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
        int parentIndex = index(localX, y, localZ);
        if (details != null
                && details.stateAtParentIndex(parentIndex).isPresent()) {
            throw new IllegalStateException(
                    "byte block access cannot represent a DETAIL parent");
        }
        return blocks[parentIndex];
    }

    public ParentCellState cellState(int localX, int y, int localZ) {
        validateCoordinate(
                localX, GameConfig.Chunk.SIZE, "localX");
        validateCoordinate(y, worldHeight, "y");
        validateCoordinate(
                localZ, GameConfig.Chunk.SIZE, "localZ");
        int parentIndex = index(localX, y, localZ);
        if (details != null) {
            java.util.Optional<DetailCellState> detail =
                    details.stateAtParentIndex(parentIndex);
            if (detail.isPresent()) {
                return detail.orElseThrow();
            }
        }
        return new FullCellState(blocks[parentIndex]);
    }

    public DetailChunkSnapshot details() {
        return details == null
                ? DetailChunkSnapshot.emptyView()
                : details;
    }

    /** Rebinds this detached canonical generation payload to a repository revision. */
    public ChunkSnapshot toSnapshot(long revision) {
        if (revision <= 0L) {
            throw new IllegalArgumentException("revision must be positive");
        }
        return ChunkSnapshot.of(
                key, revision, worldHeight, blocks, details());
    }

    public byte[] copyBlocks() {
        if (details != null) {
            throw new IllegalStateException(
                    "byte generation copy cannot represent DETAIL parents");
        }
        return copyFullBlocks();
    }

    byte[] copyFullBlocks() {
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

    private static void validateDetailBacking(
            DetailChunkSnapshot details, byte[] blocks) {
        for (int parentIndex : details.copyParentIndices()) {
            if (parentIndex >= blocks.length) {
                throw new IllegalArgumentException(
                        "DETAIL parent index is outside generation height");
            }
            if (blocks[parentIndex] != 0) {
                throw new IllegalArgumentException(
                        "DETAIL parent backing FULL byte must be AIR");
            }
        }
    }
}

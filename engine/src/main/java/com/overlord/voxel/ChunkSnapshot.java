package com.overlord.voxel;

import com.overlord.config.GameConfig;
import java.util.Arrays;
import java.util.Objects;

public final class ChunkSnapshot {
    private final ChunkKey key;
    private final long revision;
    private final int worldHeight;
    private final byte[] blocks;

    private ChunkSnapshot(
            ChunkKey key,
            long revision,
            int worldHeight,
            byte[] blocks) {
        this.key = Objects.requireNonNull(key, "key");
        this.revision = revision;
        this.worldHeight = requireValidWorldHeight(worldHeight);
        Objects.requireNonNull(blocks, "blocks");
        int expectedLength =
                Math.multiplyExact(
                        Math.multiplyExact(GameConfig.Chunk.SIZE, worldHeight),
                        GameConfig.Chunk.SIZE);
        if (blocks.length != expectedLength) {
            throw new IllegalArgumentException(
                    "blocks length must be " + expectedLength);
        }
        this.blocks = Arrays.copyOf(blocks, blocks.length);
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

    public static ChunkSnapshot of(
            ChunkKey key,
            long revision,
            int worldHeight,
            byte[] blocks) {
        return new ChunkSnapshot(key, revision, worldHeight, blocks);
    }

    public static ChunkSnapshot empty(
            ChunkKey key, long revision, int worldHeight) {
        int validatedHeight = requireValidWorldHeight(worldHeight);
        return new ChunkSnapshot(
                key,
                revision,
                validatedHeight,
                new byte[
                        Math.multiplyExact(
                                Math.multiplyExact(
                                        GameConfig.Chunk.SIZE,
                                        validatedHeight),
                                GameConfig.Chunk.SIZE)]);
    }

    private static int requireValidWorldHeight(int worldHeight) {
        if (worldHeight <= 0) {
            throw new IllegalArgumentException(
                    "worldHeight must be greater than zero");
        }
        return worldHeight;
    }
}

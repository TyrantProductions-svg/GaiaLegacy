package com.gaia.world.generation;

import com.overlord.config.GameConfig;
import com.overlord.voxel.ChunkGenerationData;
import com.overlord.voxel.ChunkKey;
import java.util.Arrays;
import java.util.Objects;

public final class GenerationRegion {
    private static final int CHUNK_SIZE =
            GameConfig.Chunk.SIZE;

    private final ChunkKey key;
    private final int worldHeight;
    private final byte air;
    private final byte[] blocks;
    private final BiomeSample[] biomes;
    private final int[] heights;
    private int writeCount;

    public GenerationRegion(
            ChunkKey key, int worldHeight, byte air) {
        this.key = Objects.requireNonNull(key, "key");
        if (worldHeight <= 0) {
            throw new IllegalArgumentException(
                    "worldHeight must be positive");
        }
        int blockCount;
        try {
            blockCount =
                    Math.multiplyExact(
                            Math.multiplyExact(
                                    CHUNK_SIZE, worldHeight),
                            CHUNK_SIZE);
        } catch (ArithmeticException failure) {
            throw new IllegalArgumentException(
                    "worldHeight is too large", failure);
        }
        this.worldHeight = worldHeight;
        this.air = air;
        this.blocks = new byte[blockCount];
        if (air != 0) {
            Arrays.fill(blocks, air);
        }
        this.biomes =
                new BiomeSample[CHUNK_SIZE * CHUNK_SIZE];
        this.heights = new int[CHUNK_SIZE * CHUNK_SIZE];
        Arrays.fill(heights, -1);
    }

    public ChunkKey key() {
        return key;
    }

    public int worldHeight() {
        return worldHeight;
    }

    public byte getBlock(
            int localX, int y, int localZ) {
        validateBlockCoordinates(localX, y, localZ);
        return blocks[blockIndex(localX, y, localZ)];
    }

    public byte sampleLocalOrAir(
            int localX, int y, int localZ) {
        if (!containsLocal(localX, y, localZ)) {
            return air;
        }
        return blocks[blockIndex(localX, y, localZ)];
    }

    public void writeBlock(
            int localX, int y, int localZ, byte block) {
        validateBlockCoordinates(localX, y, localZ);
        blocks[blockIndex(localX, y, localZ)] = block;
        writeCount++;
    }

    public int writeCount() {
        return writeCount;
    }

    public byte[] copyBlocks() {
        return Arrays.copyOf(blocks, blocks.length);
    }

    public void setBiome(
            int localX, int localZ, BiomeSample biome) {
        validateColumn(localX, localZ);
        biomes[columnIndex(localX, localZ)] =
                Objects.requireNonNull(biome, "biome");
    }

    public BiomeSample getBiome(
            int localX, int localZ) {
        validateColumn(localX, localZ);
        BiomeSample biome =
                biomes[columnIndex(localX, localZ)];
        if (biome == null) {
            throw new IllegalStateException(
                    "Biome column has not been generated");
        }
        return biome;
    }

    public void setHeight(
            int localX, int localZ, int height) {
        validateColumn(localX, localZ);
        if (height < 0 || height >= worldHeight) {
            throw new IllegalArgumentException(
                    "height must be within 0.."
                            + (worldHeight - 1));
        }
        heights[columnIndex(localX, localZ)] = height;
    }

    public int getHeight(int localX, int localZ) {
        validateColumn(localX, localZ);
        int height = heights[columnIndex(localX, localZ)];
        if (height < 0) {
            throw new IllegalStateException(
                    "Height column has not been generated");
        }
        return height;
    }

    public int worldX(int localX) {
        validateLocalHorizontal("localX", localX);
        return Math.addExact(key.worldOriginX(), localX);
    }

    public int worldZ(int localZ) {
        validateLocalHorizontal("localZ", localZ);
        return Math.addExact(key.worldOriginZ(), localZ);
    }

    public int localX(int worldX) {
        return localHorizontal(
                "worldX", worldX, key.worldOriginX());
    }

    public int localZ(int worldZ) {
        return localHorizontal(
                "worldZ", worldZ, key.worldOriginZ());
    }

    public ChunkGenerationData freeze() {
        return new ChunkGenerationData(
                key, worldHeight, blocks);
    }

    private boolean containsLocal(
            int localX, int y, int localZ) {
        return localX >= 0
                && localX < CHUNK_SIZE
                && y >= 0
                && y < worldHeight
                && localZ >= 0
                && localZ < CHUNK_SIZE;
    }

    private int blockIndex(
            int localX, int y, int localZ) {
        return localX
                + y * CHUNK_SIZE
                + localZ * CHUNK_SIZE * worldHeight;
    }

    private static int columnIndex(
            int localX, int localZ) {
        return localX + localZ * CHUNK_SIZE;
    }

    private void validateBlockCoordinates(
            int localX, int y, int localZ) {
        if (!containsLocal(localX, y, localZ)) {
            throw new IndexOutOfBoundsException(
                    "Block coordinate outside generation region: "
                            + localX
                            + ","
                            + y
                            + ","
                            + localZ);
        }
    }

    private static void validateColumn(
            int localX, int localZ) {
        validateLocalHorizontal("localX", localX);
        validateLocalHorizontal("localZ", localZ);
    }

    private static void validateLocalHorizontal(
            String name, int coordinate) {
        if (coordinate < 0 || coordinate >= CHUNK_SIZE) {
            throw new IndexOutOfBoundsException(
                    name + " must be within 0.."
                            + (CHUNK_SIZE - 1));
        }
    }

    private static int localHorizontal(
            String name, int worldCoordinate, int origin) {
        long local = (long) worldCoordinate - origin;
        if (local < 0 || local >= CHUNK_SIZE) {
            throw new IndexOutOfBoundsException(
                    name
                            + " is outside this generation region");
        }
        return (int) local;
    }
}

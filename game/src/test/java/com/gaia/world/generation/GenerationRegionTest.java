package com.gaia.world.generation;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.overlord.config.GameConfig;
import com.overlord.voxel.ChunkGenerationData;
import com.overlord.voxel.ChunkKey;
import org.junit.jupiter.api.Test;

class GenerationRegionTest {
    @Test
    void exposesCpuStagingWriteWithoutLiveWorldMutationName() {
        assertDoesNotThrow(
                () ->
                        GenerationRegion.class.getMethod(
                                "writeBlock",
                                int.class,
                                int.class,
                                int.class,
                                byte.class));
        assertThrows(
                NoSuchMethodException.class,
                () ->
                        GenerationRegion.class.getMethod(
                                "setBlock",
                                int.class,
                                int.class,
                                int.class,
                                byte.class));
    }

    @Test
    void regionRejectsEveryOutOfBoundsWrite() {
        GenerationRegion region = region(new ChunkKey(-1, 2), 64);

        assertThrows(
                IndexOutOfBoundsException.class,
                () -> region.writeBlock(-1, 2, 1, (byte) 3));
        assertThrows(
                IndexOutOfBoundsException.class,
                () -> region.writeBlock(16, 2, 1, (byte) 3));
        assertThrows(
                IndexOutOfBoundsException.class,
                () -> region.writeBlock(1, -1, 1, (byte) 3));
        assertThrows(
                IndexOutOfBoundsException.class,
                () -> region.writeBlock(1, 64, 1, (byte) 3));
        assertThrows(
                IndexOutOfBoundsException.class,
                () -> region.writeBlock(1, 2, -1, (byte) 3));
        assertThrows(
                IndexOutOfBoundsException.class,
                () -> region.writeBlock(1, 2, 16, (byte) 3));
        assertEquals(0, region.writeCount());
    }

    @Test
    void normalReadsAreBoundedButExplicitSamplingReturnsAir() {
        GenerationRegion region = region(new ChunkKey(0, 0), 32);

        assertThrows(
                IndexOutOfBoundsException.class,
                () -> region.getBlock(-1, 0, 0));
        assertEquals((byte) 0, region.sampleLocalOrAir(-1, 0, 0));
        assertEquals((byte) 0, region.sampleLocalOrAir(0, 32, 0));
        assertEquals((byte) 0, region.sampleLocalOrAir(0, 0, 16));
    }

    @Test
    void storesBlocksInCanonicalLayoutAndCountsWrites() {
        GenerationRegion region = region(new ChunkKey(0, 0), 32);

        region.writeBlock(2, 7, 3, (byte) 5);
        region.writeBlock(2, 7, 3, (byte) 5);

        assertEquals((byte) 5, region.getBlock(2, 7, 3));
        assertEquals(2, region.writeCount());
        assertEquals(
                (byte) 5,
                region.copyBlocks()[
                        2 + 7 * 16 + 3 * 16 * 32]);
    }

    @Test
    void convertsNegativeChunkCoordinatesInWorldSpace() {
        GenerationRegion region = region(new ChunkKey(-1, 2), 64);

        assertEquals(-16, region.worldX(0));
        assertEquals(-1, region.worldX(15));
        assertEquals(32, region.worldZ(0));
        assertEquals(47, region.worldZ(15));
        assertEquals(0, region.localX(-16));
        assertEquals(15, region.localX(-1));
        assertEquals(0, region.localZ(32));
        assertEquals(15, region.localZ(47));
        assertThrows(
                IndexOutOfBoundsException.class,
                () -> region.localX(0));
    }

    @Test
    void storesBoundedBiomeAndHeightColumns() {
        GenerationRegion region = region(new ChunkKey(0, 0), 64);
        BiomeSample biome = new BiomeSample(0.2, 0.3, 0.5);

        region.setBiome(4, 7, biome);
        region.setHeight(4, 7, 31);

        assertEquals(biome, region.getBiome(4, 7));
        assertEquals(31, region.getHeight(4, 7));
        assertThrows(
                IndexOutOfBoundsException.class,
                () -> region.setBiome(16, 7, biome));
        assertThrows(
                IndexOutOfBoundsException.class,
                () -> region.setHeight(4, -1, 31));
        assertThrows(
                IllegalArgumentException.class,
                () -> region.setHeight(4, 7, 64));
        assertThrows(
                IllegalStateException.class,
                () -> region.getBiome(0, 0));
        assertThrows(
                IllegalStateException.class,
                () -> region.getHeight(0, 0));
    }

    @Test
    void freezeCreatesOneChunkSnapshotIndependentOfRegion() {
        ChunkKey key = new ChunkKey(-2, 3);
        GenerationRegion region = region(key, 32);
        region.writeBlock(1, 2, 3, (byte) 4);

        ChunkGenerationData frozen = region.freeze();
        region.writeBlock(1, 2, 3, (byte) 9);
        byte[] returned = frozen.copyBlocks();
        returned[1 + 2 * 16 + 3 * 16 * 32] = 8;

        assertEquals(key, frozen.key());
        assertEquals(32, frozen.worldHeight());
        assertEquals((byte) 4, frozen.getBlock(1, 2, 3));
    }

    @Test
    void rejectsInvalidRegionDimensions() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new GenerationRegion(
                                new ChunkKey(0, 0), 0, (byte) 0));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new GenerationRegion(
                                new ChunkKey(0, 0),
                                Integer.MAX_VALUE,
                                (byte) 0));
    }

    private static GenerationRegion region(
            ChunkKey key, int worldHeight) {
        return new GenerationRegion(key, worldHeight, (byte) 0);
    }
}

package com.gaia.world.generation;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.overlord.assets.ResourceLocation;
import com.overlord.config.GameConfig;
import com.overlord.voxel.ChunkKey;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class StoneOutcropDecorationProviderTest {
    private static final byte AIR = 7;
    private static final byte GRASS = 11;
    private static final byte DIRT = 13;
    private static final byte STONE = 17;

    private final DecorationProvider provider =
            new StoneOutcropDecorationProvider();

    @Test
    void hasStableStageId() {
        assertEquals(
                ResourceLocation.parse("gaia:decoration"),
                provider.id());
    }

    @Test
    void heavyDecorationPlacesOnlyBoundedPaletteStone() {
        GenerationRegion region =
                surfacedRegion(new ChunkKey(0, 0), 16, 10);

        provider.generate(context(12345L, 1, 3), region);

        assertEquals(STONE, region.getBlock(0, 11, 0));
        assertTrue(
                count(region, STONE)
                        > GameConfig.Chunk.SIZE
                                * GameConfig.Chunk.SIZE);
        assertOnlyPaletteBlocks(region);
    }

    @Test
    void maximumHeightSurfaceCannotWriteOutsideRegion() {
        GenerationRegion region =
                surfacedRegion(new ChunkKey(0, 0), 8, 7);

        GenerationStageResult result =
                provider.generate(
                        context(12345L, 1, 3),
                        region);

        assertEquals(256, result.samples());
        assertEquals(0, result.writes());
        assertEquals(GRASS, region.getBlock(15, 7, 15));
    }

    @Test
    void chunkGenerationOrderDoesNotChangeDecoration() {
        GenerationContext context =
                context(987654321L, 3, 3);
        GenerationRegion eastFirst =
                surfacedRegion(
                        new ChunkKey(1, -2), 16, 10);
        GenerationRegion westSecond =
                surfacedRegion(
                        new ChunkKey(0, -2), 16, 10);
        GenerationRegion westFirst =
                surfacedRegion(
                        new ChunkKey(0, -2), 16, 10);
        GenerationRegion eastSecond =
                surfacedRegion(
                        new ChunkKey(1, -2), 16, 10);

        provider.generate(context, eastFirst);
        provider.generate(context, westSecond);
        provider.generate(context, westFirst);
        provider.generate(context, eastSecond);

        assertArrayEquals(
                eastFirst.copyBlocks(),
                eastSecond.copyBlocks());
        assertArrayEquals(
                westFirst.copyBlocks(),
                westSecond.copyBlocks());
    }

    @Test
    void emptyColumnsRemainEmpty() {
        GenerationRegion region =
                new GenerationRegion(
                        new ChunkKey(-1, -1), 8, AIR);
        for (int localZ = 0;
                localZ < GameConfig.Chunk.SIZE;
                localZ++) {
            for (int localX = 0;
                    localX < GameConfig.Chunk.SIZE;
                    localX++) {
                region.setHeight(localX, localZ, 0);
            }
        }

        provider.generate(context(12345L, 1, 3), region);

        assertEquals(0, count(region, STONE));
        assertOnlyPaletteBlocks(region);
    }

    private static GenerationRegion surfacedRegion(
            ChunkKey key,
            int worldHeight,
            int surfaceHeight) {
        GenerationRegion region =
                new GenerationRegion(key, worldHeight, AIR);
        for (int localZ = 0;
                localZ < GameConfig.Chunk.SIZE;
                localZ++) {
            for (int localX = 0;
                    localX < GameConfig.Chunk.SIZE;
                    localX++) {
                region.setHeight(
                        localX, localZ, surfaceHeight);
                for (int y = 0; y < surfaceHeight; y++) {
                    region.writeBlock(
                            localX, y, localZ, DIRT);
                }
                region.writeBlock(
                        localX,
                        surfaceHeight,
                        localZ,
                        GRASS);
            }
        }
        return region;
    }

    private static GenerationContext context(
            long seed,
            int chanceDenominator,
            int maximumOutcropHeight) {
        WorldGenerationConfig defaults =
                WorldGenerationConfig.defaults();
        WorldGenerationConfig config =
                new WorldGenerationConfig(
                        seed,
                        defaults.algorithmVersion(),
                        defaults.chunkRadius(),
                        defaults.biome(),
                        defaults.height(),
                        defaults.cave(),
                        defaults.surface(),
                        new WorldGenerationConfig.DecorationSettings(
                                chanceDenominator,
                                maximumOutcropHeight),
                        defaults.spawn());
        return new GenerationContext(
                config,
                new GenerationBlockPalette(
                        AIR, GRASS, DIRT, STONE),
                new DeterministicCoordinateSampler(
                        config.seed(),
                        config.algorithmVersion()));
    }

    private static int count(
            GenerationRegion region, byte expected) {
        int count = 0;
        for (byte block : region.copyBlocks()) {
            if (block == expected) {
                count++;
            }
        }
        return count;
    }

    private static void assertOnlyPaletteBlocks(
            GenerationRegion region) {
        assertTrue(
                Arrays.stream(toUnsigned(region.copyBlocks()))
                        .allMatch(
                                block ->
                                        block == AIR
                                                || block == GRASS
                                                || block == DIRT
                                                || block == STONE));
    }

    private static int[] toUnsigned(byte[] blocks) {
        int[] values = new int[blocks.length];
        for (int index = 0; index < blocks.length; index++) {
            values[index] =
                    Byte.toUnsignedInt(blocks[index]);
        }
        return values;
    }
}

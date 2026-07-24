package com.gaia.world.generation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.overlord.assets.ResourceLocation;
import com.overlord.config.GameConfig;
import com.overlord.voxel.ChunkKey;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class DefaultStrataDensityProviderTest {
    private static final byte AIR = 7;
    private static final byte GRASS = 11;
    private static final byte DIRT = 13;
    private static final byte STONE = 17;

    private final StrataDensityProvider provider =
            new DefaultStrataDensityProvider();

    @Test
    void hasStableStageId() {
        assertEquals(
                ResourceLocation.parse("gaia:strata_density"),
                provider.id());
    }

    @Test
    void fillsStoneAndConfiguredShallowSoilBand() {
        GenerationRegion region = region(32, 23);

        GenerationStageResult result =
                provider.generate(context(2), region);

        assertEquals(256, result.samples());
        assertEquals(24 * 256, result.writes());
        assertEquals(DIRT, region.getBlock(3, 23, 5));
        assertEquals(DIRT, region.getBlock(3, 22, 5));
        assertEquals(DIRT, region.getBlock(3, 21, 5));
        assertEquals(STONE, region.getBlock(3, 20, 5));
        assertEquals(AIR, region.getBlock(3, 24, 5));
        assertOnlyPaletteBlocks(region);
    }

    @Test
    void handlesLowestAndMaximumColumnsWithoutLeavingRegion() {
        GenerationRegion region =
                new GenerationRegion(
                        new ChunkKey(-1, 2), 8, AIR);
        for (int localZ = 0;
                localZ < GameConfig.Chunk.SIZE;
                localZ++) {
            for (int localX = 0;
                    localX < GameConfig.Chunk.SIZE;
                    localX++) {
                region.setHeight(
                        localX,
                        localZ,
                        (localX + localZ) % 2 == 0 ? 0 : 7);
            }
        }

        provider.generate(context(3), region);

        assertEquals(DIRT, region.getBlock(0, 0, 0));
        assertEquals(AIR, region.getBlock(0, 1, 0));
        assertEquals(DIRT, region.getBlock(1, 7, 0));
        assertEquals(STONE, region.getBlock(1, 3, 0));
        assertOnlyPaletteBlocks(region);
    }

    private static GenerationRegion region(
            int worldHeight, int columnHeight) {
        GenerationRegion region =
                new GenerationRegion(
                        new ChunkKey(0, 0),
                        worldHeight,
                        AIR);
        for (int localZ = 0;
                localZ < GameConfig.Chunk.SIZE;
                localZ++) {
            for (int localX = 0;
                    localX < GameConfig.Chunk.SIZE;
                    localX++) {
                region.setHeight(
                        localX, localZ, columnHeight);
            }
        }
        return region;
    }

    private static GenerationContext context(int dirtDepth) {
        WorldGenerationConfig defaults =
                WorldGenerationConfig.defaults();
        WorldGenerationConfig config =
                new WorldGenerationConfig(
                        defaults.seed(),
                        defaults.algorithmVersion(),
                        defaults.chunkRadius(),
                        defaults.biome(),
                        defaults.height(),
                        defaults.cave(),
                        new WorldGenerationConfig.SurfaceSettings(
                                dirtDepth,
                                defaults.surface()
                                        .rockyWeightThreshold(),
                                defaults.surface()
                                        .rockySlopeThreshold()),
                        defaults.decoration(),
                        defaults.spawn());
        return new GenerationContext(
                config,
                new GenerationBlockPalette(
                        AIR, GRASS, DIRT, STONE),
                new DeterministicCoordinateSampler(
                        config.seed(),
                        config.algorithmVersion()));
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

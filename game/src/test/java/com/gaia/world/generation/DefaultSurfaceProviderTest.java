package com.gaia.world.generation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.overlord.assets.ResourceLocation;
import com.overlord.config.GameConfig;
import com.overlord.voxel.ChunkKey;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class DefaultSurfaceProviderTest {
    private static final byte AIR = 7;
    private static final byte GRASS = 11;
    private static final byte DIRT = 13;
    private static final byte STONE = 17;

    private final StrataDensityProvider strata =
            new DefaultStrataDensityProvider();
    private final SurfaceProvider provider =
            new DefaultSurfaceProvider();

    @Test
    void hasStableStageId() {
        assertEquals(
                ResourceLocation.parse("gaia:surface"),
                provider.id());
    }

    @Test
    void plainsSurfaceProducesGrassDirtStoneLayers() {
        GenerationContext context = context(2, 0.55, 2.0);
        GenerationRegion region =
                generatedRegion(32, 23, plains());
        strata.generate(context, region);

        provider.generate(context, region);

        assertEquals(GRASS, region.getBlock(3, 23, 5));
        assertEquals(DIRT, region.getBlock(3, 22, 5));
        assertEquals(DIRT, region.getBlock(3, 21, 5));
        assertEquals(STONE, region.getBlock(3, 20, 5));
        assertOnlyPaletteBlocks(region);
    }

    @Test
    void steepLocalSlopeProducesRockySurface() {
        GenerationContext context = context(3, 0.95, 2.0);
        GenerationRegion region =
                generatedRegion(32, 20, plains());
        region.setHeight(8, 7, 25);
        strata.generate(context, region);

        provider.generate(context, region);

        assertEquals(STONE, region.getBlock(8, 20, 8));
        assertEquals(STONE, region.getBlock(8, 19, 8));
    }

    @Test
    void rockyBiomeWeightProducesStoneOnFlatTerrain() {
        GenerationContext context = context(3, 0.55, 8.0);
        GenerationRegion region =
                generatedRegion(
                        32,
                        20,
                        new BiomeSample(0.1, 0.2, 0.7));
        strata.generate(context, region);

        provider.generate(context, region);

        assertEquals(STONE, region.getBlock(6, 20, 6));
    }

    @Test
    void reEvaluatesHighestSolidBlockAfterCaves() {
        GenerationContext context = context(2, 0.55, 2.0);
        GenerationRegion region =
                generatedRegion(32, 23, plains());
        strata.generate(context, region);
        region.writeBlock(4, 23, 4, AIR);
        region.writeBlock(4, 22, 4, AIR);

        provider.generate(context, region);

        assertEquals(GRASS, region.getBlock(4, 21, 4));
        assertEquals(DIRT, region.getBlock(4, 20, 4));
    }

    @Test
    void handlesEmptyLowestAndMaximumColumns() {
        GenerationContext context = context(2, 0.55, 2.0);
        GenerationRegion region =
                generatedRegion(8, 7, plains());
        for (int y = 0; y < 8; y++) {
            region.writeBlock(0, y, 0, STONE);
        }
        region.setHeight(15, 15, 0);
        region.setHeight(14, 15, 0);
        region.setHeight(15, 14, 0);
        region.writeBlock(15, 0, 15, STONE);

        provider.generate(context, region);

        assertEquals(GRASS, region.getBlock(0, 7, 0));
        assertEquals(GRASS, region.getBlock(15, 0, 15));
        assertEquals(AIR, region.getBlock(2, 7, 0));
        assertOnlyPaletteBlocks(region);
    }

    private static GenerationRegion generatedRegion(
            int worldHeight,
            int columnHeight,
            BiomeSample biome) {
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
                region.setBiome(localX, localZ, biome);
                region.setHeight(
                        localX, localZ, columnHeight);
            }
        }
        return region;
    }

    private static GenerationContext context(
            int dirtDepth,
            double rockyWeightThreshold,
            double rockySlopeThreshold) {
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
                                rockyWeightThreshold,
                                rockySlopeThreshold),
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

    private static BiomeSample plains() {
        return new BiomeSample(1.0, 0.0, 0.0);
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

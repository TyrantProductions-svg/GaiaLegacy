package com.gaia.world.generation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.overlord.voxel.ChunkKey;
import org.junit.jupiter.api.Test;

class BlendedHeightProviderTest {
    private final ContinuousBiomeProvider biome =
            new ContinuousBiomeProvider();
    private final BlendedHeightProvider height =
            new BlendedHeightProvider();

    @Test
    void adjacentChunksUseOneWorldSpaceHeightFunction() {
        GenerationContext context = context(12345L);

        int left = height.sampleHeight(
                context, 15, 7, biome.sample(context, 15, 7));
        int right = height.sampleHeight(
                context, 16, 7, biome.sample(context, 16, 7));

        assertTrue(Math.abs(left - right) <= 3);
    }

    @Test
    void clampsSamplesToConfiguredHeightBoundsAtNegativeCoordinates() {
        GenerationContext context = context(12345L);
        WorldGenerationConfig.HeightSettings settings = context.config().height();

        for (int worldZ = -80; worldZ <= 80; worldZ += 5) {
            for (int worldX = -80; worldX <= 80; worldX += 5) {
                int sampled = height.sampleHeight(
                        context,
                        worldX,
                        worldZ,
                        biome.sample(context, worldX, worldZ));
                assertTrue(sampled >= settings.minimumSurfaceHeight());
                assertTrue(sampled <= settings.maximumSurfaceHeight());
            }
        }
    }

    @Test
    void changesHeightFieldWhenSeedChanges() {
        int first = height.sampleHeight(
                context(12345L),
                29,
                -47,
                biome.sample(context(12345L), 29, -47));
        int second = height.sampleHeight(
                context(54321L),
                29,
                -47,
                biome.sample(context(54321L), 29, -47));

        assertNotEquals(first, second);
    }

    @Test
    void generatesHeightsFromBiomeColumnsWithoutBlockWrites() {
        GenerationContext context = context(12345L);
        GenerationRegion region =
                new GenerationRegion(new ChunkKey(1, -2), 97, (byte) 0);
        biome.generate(context, region);

        GenerationStageResult result = height.generate(context, region);

        assertEquals(height.id(), result.stageId());
        assertEquals(GenerationStageResult.Status.SUCCEEDED, result.status());
        assertEquals(256, result.samples());
        assertEquals(0, result.writes());
        assertEquals(0, region.writeCount());
        assertEquals(
                height.sampleHeight(context, 16, -32, region.getBiome(0, 0)),
                region.getHeight(0, 0));
        assertEquals(
                height.sampleHeight(context, 31, -17, region.getBiome(15, 15)),
                region.getHeight(15, 15));
    }

    private static GenerationContext context(long seed) {
        WorldGenerationConfig defaults = WorldGenerationConfig.defaults();
        WorldGenerationConfig config = new WorldGenerationConfig(
                seed,
                defaults.algorithmVersion(),
                defaults.chunkRadius(),
                defaults.biome(),
                defaults.height(),
                defaults.cave(),
                defaults.surface(),
                defaults.decoration(),
                defaults.spawn());
        return new GenerationContext(
                config,
                new GenerationBlockPalette((byte) 0, (byte) 1, (byte) 2, (byte) 3),
                new DeterministicCoordinateSampler(seed, config.algorithmVersion()));
    }
}

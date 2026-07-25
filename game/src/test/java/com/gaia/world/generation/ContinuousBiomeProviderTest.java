package com.gaia.world.generation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.overlord.voxel.ChunkKey;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import org.junit.jupiter.api.Test;

class ContinuousBiomeProviderTest {
    private final ContinuousBiomeProvider provider =
            new ContinuousBiomeProvider();

    @Test
    void normalizationUsesPlatformStableStrictExponential()
            throws IOException {
        String source =
                Files.readString(
                        Path.of(
                                "src/main/java/com/gaia/world/generation/"
                                        + "ContinuousBiomeProvider.java"));

        assertTrue(source.contains("StrictMath.exp("));
        assertFalse(
                source.matches(
                        "(?s).*(?<!Strict)Math\\.exp\\(.*"));
    }

    @Test
    void biomeWeightsAreNormalizedAtChunkBoundary() {
        GenerationContext context = context(12345L);

        BiomeSample west = provider.sample(context, 15, -4);
        BiomeSample east = provider.sample(context, 16, -4);

        assertNormalized(west);
        assertNormalized(east);
        assertTrue(distance(west, east) < 0.15);
    }

    @Test
    void samplesNegativeWorldCoordinatesDeterministically() {
        GenerationContext context = context(12345L);

        assertEquals(
                provider.sample(context, -17, -42),
                provider.sample(context, -17, -42));
        assertNormalized(provider.sample(context, -17, -42));
    }

    @Test
    void changesFieldsWhenSeedChanges() {
        assertNotEquals(
                provider.sample(context(12345L), -21, 37),
                provider.sample(context(54321L), -21, 37));
    }

    @Test
    void defaultFiniteWorldContainsEveryDominantBiome() {
        GenerationContext context = context(12345L);
        EnumSet<BiomeType> dominants = EnumSet.noneOf(BiomeType.class);

        for (int worldZ = -64; worldZ < 80; worldZ++) {
            for (int worldX = -64; worldX < 80; worldX++) {
                dominants.add(provider.sample(context, worldX, worldZ).dominant());
            }
        }

        assertEquals(EnumSet.allOf(BiomeType.class), dominants);
    }

    @Test
    void generatesEveryRegionColumnWithoutBlockWrites() {
        GenerationContext context = context(12345L);
        GenerationRegion region =
                new GenerationRegion(new ChunkKey(-1, 2), 97, (byte) 0);

        GenerationStageResult result = provider.generate(context, region);

        assertEquals(provider.id(), result.stageId());
        assertEquals(GenerationStageResult.Status.SUCCEEDED, result.status());
        assertEquals(256, result.samples());
        assertEquals(0, result.writes());
        assertEquals(0, region.writeCount());
        assertEquals(
                provider.sample(context, -16, 32), region.getBiome(0, 0));
        assertEquals(
                provider.sample(context, -1, 47), region.getBiome(15, 15));
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

    private static void assertNormalized(BiomeSample sample) {
        assertTrue(sample.plains() >= 0.0 && sample.plains() <= 1.0);
        assertTrue(sample.rollingHills() >= 0.0 && sample.rollingHills() <= 1.0);
        assertTrue(sample.rockyHighlands() >= 0.0 && sample.rockyHighlands() <= 1.0);
        assertEquals(
                1.0,
                sample.plains() + sample.rollingHills() + sample.rockyHighlands(),
                1.0e-12);
    }

    private static double distance(BiomeSample first, BiomeSample second) {
        return Math.abs(first.plains() - second.plains())
                + Math.abs(first.rollingHills() - second.rollingHills())
                + Math.abs(first.rockyHighlands() - second.rockyHighlands());
    }
}

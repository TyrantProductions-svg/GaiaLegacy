package com.gaia.world.generation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class WorldGenerationConfigTest {
    @Test
    void defaultsDefineApprovedFiniteWorld() {
        WorldGenerationConfig config = WorldGenerationConfig.defaults();

        assertEquals(12345L, config.seed());
        assertEquals(1, config.algorithmVersion());
        assertEquals(4, config.chunkRadius());
        assertEquals(81, squareChunkCount(config));
    }

    @Test
    void approvedVisualRevisionUsesAlgorithmVersionTwo() {
        WorldGenerationConfig config =
                WorldGenerationConfig.visualRevisionCandidate();

        assertEquals(2, config.algorithmVersion());
        assertEquals(1, WorldGenerationConfig.defaults().algorithmVersion());
    }

    @Test
    void rejectsInvalidTopLevelConfiguration() {
        WorldGenerationConfig defaults = WorldGenerationConfig.defaults();

        assertThrows(
                IllegalArgumentException.class,
                () -> copy(defaults, 0, defaults.chunkRadius()));
        assertThrows(
                IllegalArgumentException.class,
                () -> copy(defaults, 1, -1));
        assertThrows(
                NullPointerException.class,
                () ->
                        new WorldGenerationConfig(
                                1L,
                                1,
                                0,
                                null,
                                defaults.height(),
                                defaults.cave(),
                                defaults.surface(),
                                defaults.decoration(),
                                defaults.spawn()));
    }

    @Test
    void rejectsInvalidNestedTuning() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new WorldGenerationConfig.BiomeSettings(
                                0.0, 1.0));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new WorldGenerationConfig.BiomeSettings(
                                0.1, Double.NaN));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new WorldGenerationConfig.HeightSettings(
                                0.1, 20, 10, 15, 1, 15, 1, 15, 1));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new WorldGenerationConfig.HeightSettings(
                                0.1, 1, 20, 10, -1, 10, 1, 10, 1));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new WorldGenerationConfig.CaveSettings(
                                0.1, 1.1, 1, 1));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new WorldGenerationConfig.SurfaceSettings(
                                -1, 0.5, 1.0));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new WorldGenerationConfig.DecorationSettings(
                                0, 1));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new WorldGenerationConfig.SpawnSettings(
                                1, 0));
    }

    @Test
    void canonicalFingerprintIsStableAndIncludesEveryInput() {
        WorldGenerationConfig config = WorldGenerationConfig.defaults();

        assertEquals(
                "seed=12345"
                        + "|algorithmVersion=1"
                        + "|chunkRadius=4"
                        + "|biome.scale=0x1.cac083126e979p-9"
                        + "|biome.transitionSharpness=0x1.0p1"
                        + "|height.detailScale=0x1.eb851eb851eb8p-7"
                        + "|height.minimumSurfaceHeight=8"
                        + "|height.maximumSurfaceHeight=96"
                        + "|height.plainsBase=24"
                        + "|height.plainsVariation=6"
                        + "|height.hillsBase=34"
                        + "|height.hillsVariation=14"
                        + "|height.highlandsBase=50"
                        + "|height.highlandsVariation=28"
                        + "|cave.scale=0x1.70a3d70a3d70ap-5"
                        + "|cave.threshold=0x1.8f5c28f5c28f6p-1"
                        + "|cave.bedrockDepth=2"
                        + "|cave.surfaceBuffer=3"
                        + "|surface.dirtDepth=3"
                        + "|surface.rockyWeightThreshold=0x1.199999999999ap-1"
                        + "|surface.rockySlopeThreshold=0x1.0p1"
                        + "|decoration.chanceDenominator=96"
                        + "|decoration.maximumOutcropHeight=3"
                        + "|spawn.maximumSearchRadiusBlocks=96"
                        + "|spawn.requiredEmptyBlocks=2",
                config.canonicalFingerprintInput());
        assertEquals(
                config.canonicalFingerprintInput(),
                WorldGenerationConfig.defaults()
                        .canonicalFingerprintInput());
        assertNotEquals(
                config.canonicalFingerprintInput(),
                copy(config, 2, config.chunkRadius())
                        .canonicalFingerprintInput());
    }

    private static int squareChunkCount(
            WorldGenerationConfig config) {
        int diameter = config.chunkRadius() * 2 + 1;
        return diameter * diameter;
    }

    private static WorldGenerationConfig copy(
            WorldGenerationConfig config,
            int algorithmVersion,
            int chunkRadius) {
        return new WorldGenerationConfig(
                config.seed(),
                algorithmVersion,
                chunkRadius,
                config.biome(),
                config.height(),
                config.cave(),
                config.surface(),
                config.decoration(),
                config.spawn());
    }
}

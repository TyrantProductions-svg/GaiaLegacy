package com.gaia.world.generation;

import java.util.Objects;

public record WorldGenerationConfig(
        long seed,
        int algorithmVersion,
        int chunkRadius,
        BiomeSettings biome,
        HeightSettings height,
        CaveSettings cave,
        SurfaceSettings surface,
        DecorationSettings decoration,
        SpawnSettings spawn) {
    public WorldGenerationConfig {
        if (algorithmVersion <= 0) {
            throw new IllegalArgumentException(
                    "algorithmVersion must be positive");
        }
        if (chunkRadius < 0) {
            throw new IllegalArgumentException(
                    "chunkRadius must be non-negative");
        }
        Objects.requireNonNull(biome, "biome");
        Objects.requireNonNull(height, "height");
        Objects.requireNonNull(cave, "cave");
        Objects.requireNonNull(surface, "surface");
        Objects.requireNonNull(decoration, "decoration");
        Objects.requireNonNull(spawn, "spawn");
    }

    public static WorldGenerationConfig defaults() {
        return new WorldGenerationConfig(
                12345L,
                1,
                4,
                new BiomeSettings(0.0035, 2.0),
                new HeightSettings(
                        0.015,
                        8,
                        96,
                        24,
                        6,
                        34,
                        14,
                        50,
                        28),
                new CaveSettings(0.045, 0.78, 2, 3),
                new SurfaceSettings(3, 0.55, 2.0),
                new DecorationSettings(96, 3),
                new SpawnSettings(96, 2));
    }

    public static WorldGenerationConfig visualRevisionCandidate() {
        return new WorldGenerationConfig(
                12345L,
                2,
                4,
                new BiomeSettings(0.0035, 2.5),
                new HeightSettings(
                        0.015,
                        8,
                        96,
                        24,
                        4,
                        36,
                        18,
                        52,
                        36),
                new CaveSettings(0.045, 0.72, 2, 5),
                new SurfaceSettings(3, 0.48, 2.0),
                new DecorationSettings(768, 6),
                new SpawnSettings(96, 2));
    }

    public String canonicalFingerprintInput() {
        return "seed="
                + seed
                + "|algorithmVersion="
                + algorithmVersion
                + "|chunkRadius="
                + chunkRadius
                + "|biome.scale="
                + canonical(biome.scale)
                + "|biome.transitionSharpness="
                + canonical(biome.transitionSharpness)
                + "|height.detailScale="
                + canonical(height.detailScale)
                + "|height.minimumSurfaceHeight="
                + height.minimumSurfaceHeight
                + "|height.maximumSurfaceHeight="
                + height.maximumSurfaceHeight
                + "|height.plainsBase="
                + height.plainsBase
                + "|height.plainsVariation="
                + height.plainsVariation
                + "|height.hillsBase="
                + height.hillsBase
                + "|height.hillsVariation="
                + height.hillsVariation
                + "|height.highlandsBase="
                + height.highlandsBase
                + "|height.highlandsVariation="
                + height.highlandsVariation
                + "|cave.scale="
                + canonical(cave.scale)
                + "|cave.threshold="
                + canonical(cave.threshold)
                + "|cave.bedrockDepth="
                + cave.bedrockDepth
                + "|cave.surfaceBuffer="
                + cave.surfaceBuffer
                + "|surface.dirtDepth="
                + surface.dirtDepth
                + "|surface.rockyWeightThreshold="
                + canonical(surface.rockyWeightThreshold)
                + "|surface.rockySlopeThreshold="
                + canonical(surface.rockySlopeThreshold)
                + "|decoration.chanceDenominator="
                + decoration.chanceDenominator
                + "|decoration.maximumOutcropHeight="
                + decoration.maximumOutcropHeight
                + "|spawn.maximumSearchRadiusBlocks="
                + spawn.maximumSearchRadiusBlocks
                + "|spawn.requiredEmptyBlocks="
                + spawn.requiredEmptyBlocks;
    }

    private static String canonical(double value) {
        return Double.toHexString(value);
    }

    public record BiomeSettings(
            double scale,
            double transitionSharpness) {
        public BiomeSettings {
            requireFinitePositive("scale", scale);
            requireFinitePositive(
                    "transitionSharpness", transitionSharpness);
        }
    }

    public record HeightSettings(
            double detailScale,
            int minimumSurfaceHeight,
            int maximumSurfaceHeight,
            int plainsBase,
            int plainsVariation,
            int hillsBase,
            int hillsVariation,
            int highlandsBase,
            int highlandsVariation) {
        public HeightSettings {
            requireFinitePositive("detailScale", detailScale);
            if (minimumSurfaceHeight < 0) {
                throw new IllegalArgumentException(
                        "minimumSurfaceHeight must be non-negative");
            }
            if (maximumSurfaceHeight < minimumSurfaceHeight) {
                throw new IllegalArgumentException(
                        "maximumSurfaceHeight must be at least "
                                + "minimumSurfaceHeight");
            }
            requireInRange(
                    "plainsBase",
                    plainsBase,
                    minimumSurfaceHeight,
                    maximumSurfaceHeight);
            requireInRange(
                    "hillsBase",
                    hillsBase,
                    minimumSurfaceHeight,
                    maximumSurfaceHeight);
            requireInRange(
                    "highlandsBase",
                    highlandsBase,
                    minimumSurfaceHeight,
                    maximumSurfaceHeight);
            requireNonNegative(
                    "plainsVariation", plainsVariation);
            requireNonNegative(
                    "hillsVariation", hillsVariation);
            requireNonNegative(
                    "highlandsVariation", highlandsVariation);
        }
    }

    public record CaveSettings(
            double scale,
            double threshold,
            int bedrockDepth,
            int surfaceBuffer) {
        public CaveSettings {
            requireFinitePositive("scale", scale);
            requireUnitInterval("threshold", threshold);
            requireNonNegative("bedrockDepth", bedrockDepth);
            requireNonNegative("surfaceBuffer", surfaceBuffer);
        }
    }

    public record SurfaceSettings(
            int dirtDepth,
            double rockyWeightThreshold,
            double rockySlopeThreshold) {
        public SurfaceSettings {
            requireNonNegative("dirtDepth", dirtDepth);
            requireUnitInterval(
                    "rockyWeightThreshold",
                    rockyWeightThreshold);
            requireFiniteNonNegative(
                    "rockySlopeThreshold",
                    rockySlopeThreshold);
        }
    }

    public record DecorationSettings(
            int chanceDenominator,
            int maximumOutcropHeight) {
        public DecorationSettings {
            if (chanceDenominator <= 0) {
                throw new IllegalArgumentException(
                        "chanceDenominator must be positive");
            }
            requireNonNegative(
                    "maximumOutcropHeight",
                    maximumOutcropHeight);
        }
    }

    public record SpawnSettings(
            int maximumSearchRadiusBlocks,
            int requiredEmptyBlocks) {
        public SpawnSettings {
            requireNonNegative(
                    "maximumSearchRadiusBlocks",
                    maximumSearchRadiusBlocks);
            if (requiredEmptyBlocks <= 0) {
                throw new IllegalArgumentException(
                        "requiredEmptyBlocks must be positive");
            }
        }
    }

    private static void requireFinitePositive(
            String name, double value) {
        if (!Double.isFinite(value) || value <= 0.0) {
            throw new IllegalArgumentException(
                    name + " must be finite and positive");
        }
    }

    private static void requireFiniteNonNegative(
            String name, double value) {
        if (!Double.isFinite(value) || value < 0.0) {
            throw new IllegalArgumentException(
                    name + " must be finite and non-negative");
        }
    }

    private static void requireUnitInterval(
            String name, double value) {
        if (!Double.isFinite(value)
                || value < 0.0
                || value > 1.0) {
            throw new IllegalArgumentException(
                    name + " must be within 0..1");
        }
    }

    private static void requireNonNegative(
            String name, int value) {
        if (value < 0) {
            throw new IllegalArgumentException(
                    name + " must be non-negative");
        }
    }

    private static void requireInRange(
            String name, int value, int minimum, int maximum) {
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(
                    name
                            + " must be within "
                            + minimum
                            + ".."
                            + maximum);
        }
    }
}

package com.gaia.world.generation;

import com.overlord.assets.ResourceLocation;
import com.overlord.config.GameConfig;
import java.util.Optional;

public final class ContinuousBiomeProvider implements BiomeProvider {
    private static final ResourceLocation ID =
            ResourceLocation.parse("gaia:continuous_biomes");
    private static final GenerationStageContract CONTRACT =
            new GenerationStageContract(ID, 1, 0);
    private static final double FIELD_FREQUENCY_MULTIPLIER = 4.0;
    private static final long CLIMATE_SALT = 0L;
    private static final long RELIEF_SALT = 1L;

    @Override
    public ResourceLocation id() {
        return ID;
    }

    @Override
    public GenerationStageContract contract() {
        return CONTRACT;
    }

    @Override
    public BiomeSample sample(
            GenerationContext context, long worldX, long worldZ) {
        WorldGenerationConfig.BiomeSettings settings =
                context.config().biome();
        double scale = settings.scale() * FIELD_FREQUENCY_MULTIPLIER;
        double climate = centeredNoise(context, worldX, worldZ, scale, CLIMATE_SALT);
        double relief = centeredNoise(context, worldX, worldZ, scale, RELIEF_SALT);
        double sharpness = settings.transitionSharpness();

        return normalize(
                sharpness * (-climate - 0.5 * relief),
                sharpness * (climate - 0.5 * relief),
                sharpness * relief);
    }

    @Override
    public GenerationStageResult generate(
            GenerationContext context, GenerationRegion region) {
        int samples = 0;
        for (int localZ = 0; localZ < GameConfig.Chunk.SIZE; localZ++) {
            for (int localX = 0; localX < GameConfig.Chunk.SIZE; localX++) {
                region.setBiome(
                        localX,
                        localZ,
                        sample(
                                context,
                                region.worldXLong(localX),
                                region.worldZLong(localZ)));
                samples++;
            }
        }
        return new GenerationStageResult(
                id(),
                GenerationStageResult.Status.SUCCEEDED,
                samples,
                0,
                Optional.empty());
    }

    private static double centeredNoise(
            GenerationContext context,
            long worldX,
            long worldZ,
            double scale,
            long salt) {
        return context.sampler().valueNoise2D(
                        CONTRACT, worldX, worldZ, scale, salt)
                * 2.0
                - 1.0;
    }

    private static BiomeSample normalize(
            double plainsLogit,
            double hillsLogit,
            double highlandsLogit) {
        double maximum = Math.max(
                plainsLogit, Math.max(hillsLogit, highlandsLogit));
        double plains = StrictMath.exp(plainsLogit - maximum);
        double hills = StrictMath.exp(hillsLogit - maximum);
        double highlands = StrictMath.exp(highlandsLogit - maximum);
        double total = plains + hills + highlands;
        return new BiomeSample(
                plains / total, hills / total, highlands / total);
    }
}

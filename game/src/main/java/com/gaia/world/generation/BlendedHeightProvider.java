package com.gaia.world.generation;

import com.overlord.assets.ResourceLocation;
import com.overlord.config.GameConfig;
import java.util.Optional;

public final class BlendedHeightProvider implements HeightProvider {
    private static final ResourceLocation ID =
            ResourceLocation.parse("gaia:blended_heights");
    private static final long DETAIL_SALT = 0L;
    private static final long RUGGEDNESS_SALT = 1L;

    @Override
    public ResourceLocation id() {
        return ID;
    }

    @Override
    public int sampleHeight(
            GenerationContext context,
            int worldX,
            int worldZ,
            BiomeSample biome) {
        WorldGenerationConfig.HeightSettings settings =
                context.config().height();
        double detail = centeredNoise(
                context, worldX, worldZ, settings.detailScale(), DETAIL_SALT);
        double ruggedness = centeredNoise(
                context, worldX, worldZ, settings.detailScale(), RUGGEDNESS_SALT);
        double plains = settings.plainsBase()
                + settings.plainsVariation() * detail;
        double hills = settings.hillsBase()
                + settings.hillsVariation() * (0.7 * detail + 0.3 * ruggedness);
        double highlands = settings.highlandsBase()
                + settings.highlandsVariation()
                        * (0.35 * detail + 0.65 * ruggedness);
        double blended = biome.plains() * plains
                + biome.rollingHills() * hills
                + biome.rockyHighlands() * highlands;
        return clamp(
                (int) Math.round(blended),
                settings.minimumSurfaceHeight(),
                settings.maximumSurfaceHeight());
    }

    @Override
    public GenerationStageResult generate(
            GenerationContext context, GenerationRegion region) {
        int samples = 0;
        for (int localZ = 0; localZ < GameConfig.Chunk.SIZE; localZ++) {
            for (int localX = 0; localX < GameConfig.Chunk.SIZE; localX++) {
                int worldX = region.worldX(localX);
                int worldZ = region.worldZ(localZ);
                region.setHeight(
                        localX,
                        localZ,
                        sampleHeight(
                                context,
                                worldX,
                                worldZ,
                                region.getBiome(localX, localZ)));
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
            int worldX,
            int worldZ,
            double scale,
            long salt) {
        return context.sampler().valueNoise2D(
                        ID, worldX, worldZ, scale, salt)
                * 2.0
                - 1.0;
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}

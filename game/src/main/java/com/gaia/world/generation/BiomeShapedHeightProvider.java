package com.gaia.world.generation;

import com.overlord.assets.ResourceLocation;
import com.overlord.config.GameConfig;
import java.util.Optional;

public final class BiomeShapedHeightProvider
        implements HeightProvider {
    private static final ResourceLocation ID =
            ResourceLocation.parse("gaia:blended_heights");
    private static final GenerationStageContract CONTRACT =
            new GenerationStageContract(ID, 1, 0);

    @Override
    public ResourceLocation id() {
        return ID;
    }

    @Override
    public GenerationStageContract contract() {
        return CONTRACT;
    }

    @Override
    public int sampleHeight(
            GenerationContext context,
            long worldX,
            long worldZ,
            BiomeSample biome) {
        WorldGenerationConfig.HeightSettings settings =
                context.config().height();
        double detailScale = settings.detailScale();
        double plainsNoise =
                centered(
                        context,
                        worldX,
                        worldZ,
                        detailScale * 0.4,
                        20L);
        double hillsLow =
                centered(
                        context,
                        worldX,
                        worldZ,
                        detailScale * (14.0 / 15.0),
                        30L);
        double hillsDetail =
                centered(
                        context,
                        worldX,
                        worldZ,
                        detailScale * (7.0 / 3.0),
                        31L);
        double ridgeSource =
                centered(
                        context,
                        worldX,
                        worldZ,
                        detailScale * (11.0 / 15.0),
                        40L);
        double highDetail =
                centered(
                        context,
                        worldX,
                        worldZ,
                        detailScale * (28.0 / 15.0),
                        41L);

        double plains =
                settings.plainsBase()
                        + settings.plainsVariation()
                                * plainsNoise
                                * StrictMath.abs(plainsNoise);
        double hills =
                settings.hillsBase()
                        + settings.hillsVariation()
                                * (0.78 * hillsLow
                                        + 0.22 * hillsDetail);
        double ridge =
                1.0 - StrictMath.abs(ridgeSource);
        double highlands =
                settings.highlandsBase()
                        - settings.highlandsVariation() * 0.35
                        + settings.highlandsVariation()
                                * (0.95 * ridge
                                        + 0.22 * highDetail);
        double blended =
                biome.plains() * plains
                        + biome.rollingHills() * hills
                        + biome.rockyHighlands() * highlands;
        return Math.max(
                settings.minimumSurfaceHeight(),
                Math.min(
                        settings.maximumSurfaceHeight(),
                        (int) StrictMath.round(blended)));
    }

    @Override
    public GenerationStageResult generate(
            GenerationContext context, GenerationRegion region) {
        int samples = 0;
        for (int z = 0; z < GameConfig.Chunk.SIZE; z++) {
            for (int x = 0; x < GameConfig.Chunk.SIZE; x++) {
                long worldX = region.worldXLong(x);
                long worldZ = region.worldZLong(z);
                region.setHeight(
                        x,
                        z,
                        sampleHeight(
                                context,
                                worldX,
                                worldZ,
                                region.getBiome(x, z)));
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

    private static double centered(
            GenerationContext context,
            long worldX,
            long worldZ,
            double scale,
            long salt) {
        return context.sampler()
                        .valueNoise2D(
                                CONTRACT, worldX, worldZ, scale, salt)
                        * 2.0
                - 1.0;
    }
}

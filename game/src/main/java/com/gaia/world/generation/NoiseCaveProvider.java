package com.gaia.world.generation;

import com.overlord.assets.ResourceLocation;
import com.overlord.config.GameConfig;
import java.util.Optional;

public final class NoiseCaveProvider
        implements CaveProvider {
    private static final ResourceLocation ID =
            ResourceLocation.parse("gaia:cave");
    private static final GenerationStageContract CONTRACT =
            new GenerationStageContract(ID, 1, 0);
    private static final long CAVE_SALT = 0L;

    @Override
    public ResourceLocation id() {
        return ID;
    }

    @Override
    public GenerationStageContract contract() {
        return CONTRACT;
    }

    @Override
    public GenerationStageResult generate(
            GenerationContext context,
            GenerationRegion region) {
        WorldGenerationConfig.CaveSettings settings =
                context.config().cave();
        int samples = 0;
        int writes = 0;
        for (int localZ = 0;
                localZ < GameConfig.Chunk.SIZE;
                localZ++) {
            long worldZ = region.worldZLong(localZ);
            for (int localX = 0;
                    localX < GameConfig.Chunk.SIZE;
                    localX++) {
                long worldX = region.worldXLong(localX);
                int surfaceHeight =
                        region.getHeight(localX, localZ);
                int firstCarvable =
                        settings.bedrockDepth();
                int lastCarvable =
                        surfaceHeight
                                - settings.surfaceBuffer();
                for (int y = firstCarvable;
                        y <= lastCarvable;
                        y++) {
                    double density =
                            context.sampler()
                                    .valueNoise3D(
                                            CONTRACT,
                                            worldX,
                                            y,
                                            worldZ,
                                            settings.scale(),
                                            CAVE_SALT);
                    samples++;
                    if (density
                                    >= settings.threshold()
                            && region.getBlock(
                                            localX,
                                            y,
                                            localZ)
                                    != context.palette().air()) {
                        region.writeBlock(
                                localX,
                                y,
                                localZ,
                                context.palette().air());
                        writes++;
                    }
                }
            }
        }
        return succeeded(samples, writes);
    }

    private GenerationStageResult succeeded(
            int samples, int writes) {
        return new GenerationStageResult(
                id(),
                GenerationStageResult.Status.SUCCEEDED,
                samples,
                writes,
                Optional.empty());
    }
}

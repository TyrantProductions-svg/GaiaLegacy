package com.gaia.world.generation;

import com.overlord.assets.ResourceLocation;
import com.overlord.config.GameConfig;
import java.util.Optional;

public final class StoneOutcropDecorationProvider
        implements DecorationProvider {
    private static final ResourceLocation ID =
            ResourceLocation.parse("gaia:decoration");
    private static final long CHANCE_SALT = 0L;
    private static final long HEIGHT_SALT = 1L;

    @Override
    public ResourceLocation id() {
        return ID;
    }

    @Override
    public GenerationStageResult generate(
            GenerationContext context,
            GenerationRegion region) {
        WorldGenerationConfig.DecorationSettings settings =
                context.config().decoration();
        int samples = 0;
        int writes = 0;
        for (int localZ = 0;
                localZ < GameConfig.Chunk.SIZE;
                localZ++) {
            int worldZ = region.worldZ(localZ);
            for (int localX = 0;
                    localX < GameConfig.Chunk.SIZE;
                    localX++) {
                int worldX = region.worldX(localX);
                int surfaceY =
                        highestSolid(
                                context,
                                region,
                                localX,
                                localZ);
                samples++;
                if (surfaceY < 0
                        || settings.maximumOutcropHeight()
                                == 0
                        || !selected(
                                context,
                                settings,
                                worldX,
                                surfaceY,
                                worldZ)) {
                    continue;
                }
                int desiredHeight =
                        outcropHeight(
                                context,
                                settings,
                                worldX,
                                surfaceY,
                                worldZ);
                int availableHeight =
                        region.worldHeight()
                                - surfaceY
                                - 1;
                int boundedHeight =
                        Math.min(
                                desiredHeight, availableHeight);
                for (int offset = 1;
                        offset <= boundedHeight;
                        offset++) {
                    int y = surfaceY + offset;
                    if (region.getBlock(
                                    localX, y, localZ)
                            != context.palette().air()) {
                        break;
                    }
                    region.writeBlock(
                            localX,
                            y,
                            localZ,
                            context.palette().stone());
                    writes++;
                }
            }
        }
        return succeeded(samples, writes);
    }

    private static boolean selected(
            GenerationContext context,
            WorldGenerationConfig.DecorationSettings settings,
            int worldX,
            int surfaceY,
            int worldZ) {
        return context.sampler()
                        .unit(
                                ID,
                                worldX,
                                surfaceY,
                                worldZ,
                                CHANCE_SALT)
                < 1.0 / settings.chanceDenominator();
    }

    private static int outcropHeight(
            GenerationContext context,
            WorldGenerationConfig.DecorationSettings settings,
            int worldX,
            int surfaceY,
            int worldZ) {
        return 1
                + (int)
                        (context.sampler()
                                        .unit(
                                                ID,
                                                worldX,
                                                surfaceY,
                                                worldZ,
                                                HEIGHT_SALT)
                                * settings
                                        .maximumOutcropHeight());
    }

    private static int highestSolid(
            GenerationContext context,
            GenerationRegion region,
            int localX,
            int localZ) {
        int expectedSurface =
                Math.min(
                        region.getHeight(localX, localZ),
                        region.worldHeight() - 1);
        for (int y = expectedSurface; y >= 0; y--) {
            if (region.getBlock(localX, y, localZ)
                    != context.palette().air()) {
                return y;
            }
        }
        return -1;
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

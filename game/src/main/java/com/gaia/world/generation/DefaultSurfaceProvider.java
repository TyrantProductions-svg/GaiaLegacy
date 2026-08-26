package com.gaia.world.generation;

import com.overlord.assets.ResourceLocation;
import com.overlord.config.GameConfig;
import java.util.Optional;

public final class DefaultSurfaceProvider
        implements SurfaceProvider {
    private static final ResourceLocation ID =
            ResourceLocation.parse("gaia:surface");
    private static final GenerationStageContract CONTRACT =
            new GenerationStageContract(ID, 1, 1);

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
        WorldGenerationConfig.SurfaceSettings settings =
                context.config().surface();
        int samples = 0;
        int writes = 0;
        for (int localZ = 0;
                localZ < GameConfig.Chunk.SIZE;
                localZ++) {
            for (int localX = 0;
                    localX < GameConfig.Chunk.SIZE;
                    localX++) {
                int surfaceY =
                        highestSolid(
                                context,
                                region,
                                localX,
                                localZ);
                samples++;
                if (surfaceY < 0) {
                    continue;
                }
                boolean rocky =
                        region.getBiome(localX, localZ)
                                                .rockyHighlands()
                                        >= settings
                                                .rockyWeightThreshold()
                                || worldSlope(
                                                context,
                                                region,
                                                localX,
                                                localZ)
                                        >= settings
                                                .rockySlopeThreshold();
                writes +=
                        writeSurface(
                                context,
                                region,
                                localX,
                                localZ,
                                surfaceY,
                                rocky);
            }
        }
        return succeeded(samples, writes);
    }

    private static int highestSolid(
            GenerationContext context,
            GenerationRegion region,
            int localX,
            int localZ) {
        int expectedSurface =
                region.getHeight(localX, localZ);
        for (int y =
                        Math.min(
                                expectedSurface,
                                region.worldHeight() - 1);
                y >= 0;
                y--) {
            if (region.getBlock(localX, y, localZ)
                    != context.palette().air()) {
                return y;
            }
        }
        return -1;
    }

    private static double worldSlope(
            GenerationContext context,
            GenerationRegion region,
            int localX,
            int localZ) {
        int center =
                region.getHeight(localX, localZ);
        int maximumDifference = 0;
        long worldX = region.worldXLong(localX);
        long worldZ = region.worldZLong(localZ);
        for (int[] direction :
                new int[][] {
                    {-1, 0}, {1, 0}, {0, -1}, {0, 1}
                }) {
            int neighbor =
                    region.heightAtWorld(
                                    context,
                                    worldX + direction[0],
                                    worldZ + direction[1])
                            .orElseThrow(
                                    () ->
                                            new IllegalStateException(
                                                    "Required surface halo column was omitted"));
            maximumDifference =
                    Math.max(
                            maximumDifference,
                            Math.abs(center - neighbor));
        }
        return maximumDifference;
    }

    private static int writeSurface(
            GenerationContext context,
            GenerationRegion region,
            int localX,
            int localZ,
            int surfaceY,
            boolean rocky) {
        int writes = 0;
        int dirtDepth =
                context.config().surface().dirtDepth();
        for (int depth = 0;
                depth <= dirtDepth && surfaceY - depth >= 0;
                depth++) {
            int y = surfaceY - depth;
            if (region.getBlock(localX, y, localZ)
                    == context.palette().air()) {
                break;
            }
            byte block;
            if (rocky) {
                block = context.palette().stone();
            } else if (depth == 0) {
                block = context.palette().grass();
            } else {
                block = context.palette().dirt();
            }
            region.writeBlock(localX, y, localZ, block);
            writes++;
        }
        return writes;
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

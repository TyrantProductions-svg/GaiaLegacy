package com.gaia.world.generation;

import com.overlord.assets.ResourceLocation;
import com.overlord.config.GameConfig;
import java.util.Optional;

public final class DefaultStrataDensityProvider
        implements StrataDensityProvider {
    private static final ResourceLocation ID =
            ResourceLocation.parse("gaia:strata_density");
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
    public GenerationStageResult generate(
            GenerationContext context,
            GenerationRegion region) {
        GenerationBlockPalette palette =
                context.palette();
        int dirtDepth =
                context.config().surface().dirtDepth();
        int writes = 0;
        int samples = 0;
        for (int localZ = 0;
                localZ < GameConfig.Chunk.SIZE;
                localZ++) {
            for (int localX = 0;
                    localX < GameConfig.Chunk.SIZE;
                    localX++) {
                int height =
                        region.getHeight(localX, localZ);
                int dirtStart =
                        Math.max(0, height - dirtDepth);
                for (int y = 0; y <= height; y++) {
                    byte block =
                            y >= dirtStart
                                    ? palette.dirt()
                                    : palette.stone();
                    region.writeBlock(
                            localX, y, localZ, block);
                    writes++;
                }
                samples++;
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

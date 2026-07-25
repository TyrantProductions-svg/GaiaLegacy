package com.gaia.world.generation;

import com.overlord.assets.ResourceLocation;

public interface WorldGenerationStage {
    ResourceLocation id();

    GenerationStageResult generate(
            GenerationContext context, GenerationRegion region);
}

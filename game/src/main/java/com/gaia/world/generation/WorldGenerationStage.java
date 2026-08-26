package com.gaia.world.generation;

import com.overlord.assets.ResourceLocation;

public interface WorldGenerationStage {
    ResourceLocation id();

    default GenerationStageContract contract() {
        return new GenerationStageContract(id(), 1, 0);
    }

    GenerationStageResult generate(
            GenerationContext context, GenerationRegion region);
}

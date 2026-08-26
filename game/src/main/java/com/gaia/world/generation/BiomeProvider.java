package com.gaia.world.generation;

public interface BiomeProvider extends WorldGenerationStage {
    BiomeSample sample(
            GenerationContext context, long worldX, long worldZ);
}

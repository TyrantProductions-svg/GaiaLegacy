package com.gaia.world.generation;

public interface BiomeProvider extends WorldGenerationStage {
    BiomeSample sample(GenerationContext context, int worldX, int worldZ);
}

package com.gaia.world.generation;

public interface HeightProvider extends WorldGenerationStage {
    int sampleHeight(
            GenerationContext context,
            long worldX,
            long worldZ,
            BiomeSample biome);
}

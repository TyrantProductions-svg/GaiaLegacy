package com.gaia.world.generation;

public interface HeightProvider extends WorldGenerationStage {
    int sampleHeight(
            GenerationContext context,
            int worldX,
            int worldZ,
            BiomeSample biome);
}

package com.gaia.world.generation;

import com.overlord.voxel.ChunkKey;

public interface WorldGenerator {
    WorldGenerationResult generate(
            GenerationContext context, ChunkKey key);
}

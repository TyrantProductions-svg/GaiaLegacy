package com.gaia.world;

import com.gaia.blocks.BlockRegistry;
import com.overlord.config.GameConfig;
import com.overlord.voxel.Chunk;
import com.overlord.voxel.PerlinNoise;
import com.overlord.voxel.World;

public class GaiaWorldGenerator {
    
    private static final PerlinNoise perlinNoise = new PerlinNoise(GameConfig.WorldGeneration.SEED);
    
    public static void generateChunk(World world, int chunkX, int chunkZ) {
        Chunk chunk = world.getChunk(chunkX, chunkZ);
        
        for (int x = 0; x < Chunk.SIZE; x++) {
            for (int z = 0; z < Chunk.SIZE; z++) {
                int worldX = chunkX * Chunk.SIZE + x;
                int worldZ = chunkZ * Chunk.SIZE + z;
                
                double noiseValue = perlinNoise.octaveNoise2D(
                    worldX * GameConfig.WorldGeneration.SCALE, 
                    worldZ * GameConfig.WorldGeneration.SCALE, 
                    GameConfig.WorldGeneration.OCTAVES, 
                    GameConfig.WorldGeneration.PERSISTENCE
                );
                int height = GameConfig.WorldGeneration.BASE_HEIGHT + 
                    (int) (noiseValue * GameConfig.WorldGeneration.HEIGHT_VARIATION);
                
                height = Math.max(1, height);
                
                for (int y = 0; y < height; y++) {
                    byte blockType;
                    if (y == height - 1) {
                        blockType = BlockRegistry.GRASS.getId();
                    } else if (y > height - 4) {
                        blockType = BlockRegistry.DIRT.getId();
                    } else {
                        blockType = BlockRegistry.STONE.getId();
                    }
                    
                    chunk.setBlock(x, y, z, blockType);
                }
            }
        }
    }
}
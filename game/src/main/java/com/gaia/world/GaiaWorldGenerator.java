package com.gaia.world;

import com.gaia.blocks.BlockRegistry;
import com.overlord.config.GameConfig;
import com.overlord.voxel.Chunk;
import com.overlord.voxel.PerlinNoise;
import com.overlord.voxel.World;

public class GaiaWorldGenerator {
    
    private static final int SEED = 12345;
    private static final int OCTAVES = 4;
    private static final double PERSISTENCE = 0.5;
    private static final double SCALE = 0.02;
    
    private static final PerlinNoise perlinNoise = new PerlinNoise(SEED);
    
    public static void generateChunk(World world, int chunkX, int chunkZ) {
        Chunk chunk = world.getChunk(chunkX, chunkZ);
        
        for (int x = 0; x < GameConfig.Chunk.SIZE; x++) {
            for (int z = 0; z < GameConfig.Chunk.SIZE; z++) {
                int worldX = chunkX * GameConfig.Chunk.SIZE + x;
                int worldZ = chunkZ * GameConfig.Chunk.SIZE + z;
                
                double noiseValue = perlinNoise.octaveNoise2D(worldX * SCALE, worldZ * SCALE, OCTAVES, PERSISTENCE);
                int height = GameConfig.WorldGeneration.BASE_HEIGHT + (int) (noiseValue * GameConfig.WorldGeneration.HEIGHT_VARIATION);
                
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
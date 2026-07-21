package com.gaia.world;

import com.gaia.blocks.BlockRegistry;
import com.overlord.voxel.Chunk;
import com.overlord.voxel.World;

public class GaiaWorldGenerator {
    
    public static void generateChunk(World world, int chunkX, int chunkZ) {
        Chunk chunk = world.getChunk(chunkX, chunkZ);
        
        for (int x = 0; x < Chunk.SIZE; x++) {
            for (int z = 0; z < Chunk.SIZE; z++) {
                int worldX = chunkX * Chunk.SIZE + x;
                int worldZ = chunkZ * Chunk.SIZE + z;
                
                int height = 40 + (int) (Math.sin(worldX * 0.1) * 12 + Math.cos(worldZ * 0.1) * 12);
                
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
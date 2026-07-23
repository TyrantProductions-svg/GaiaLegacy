package com.overlord.voxel;

import com.overlord.config.GameConfig;

import java.util.HashMap;
import java.util.Map;

public class World {
    
    private Map<String, Chunk> chunks = new HashMap<>();
    
    public Chunk getChunk(int chunkX, int chunkZ) {
        String key = chunkX + "," + chunkZ;
        return chunks.computeIfAbsent(key, k -> new Chunk());
    }
    
    public byte getBlock(int x, int y, int z) {
        int chunkX = Math.floorDiv(x, GameConfig.Chunk.SIZE);
        int chunkZ = Math.floorDiv(z, GameConfig.Chunk.SIZE);
        
        Chunk chunk = getChunk(chunkX, chunkZ);
        
        int localX = Math.floorMod(x, GameConfig.Chunk.SIZE);
        int localZ = Math.floorMod(z, GameConfig.Chunk.SIZE);
        
        return chunk.getBlock(localX, y, localZ);
    }
    
    public void setBlock(int x, int y, int z, byte blockType) {
        int chunkX = Math.floorDiv(x, GameConfig.Chunk.SIZE);
        int chunkZ = Math.floorDiv(z, GameConfig.Chunk.SIZE);
        
        Chunk chunk = getChunk(chunkX, chunkZ);
        
        int localX = Math.floorMod(x, GameConfig.Chunk.SIZE);
        int localZ = Math.floorMod(z, GameConfig.Chunk.SIZE);
        
        chunk.setBlock(localX, y, localZ, blockType);
    }
}
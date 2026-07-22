package com.overlord.voxel;

import com.overlord.config.GameConfig;

public class SubChunk {
    
    public static final int SIZE = GameConfig.Chunk.SIZE;
    
    private byte[] blocks;
    private boolean dirty;
    
    public SubChunk() {
        blocks = new byte[SIZE * SIZE * SIZE];
        dirty = true;
    }
    
    public byte getBlock(int x, int y, int z) {
        if (x < 0 || x >= SIZE || y < 0 || y >= SIZE || z < 0 || z >= SIZE) {
            return 0;
        }
        return blocks[x + (y * SIZE) + (z * SIZE * SIZE)];
    }
    
    public void setBlock(int x, int y, int z, byte blockType) {
        if (x < 0 || x >= SIZE || y < 0 || y >= SIZE || z < 0 || z >= SIZE) {
            return;
        }
        blocks[x + (y * SIZE) + (z * SIZE * SIZE)] = blockType;
        dirty = true;
    }
    
    public boolean isEmpty() {
        for (byte b : blocks) {
            if (b != 0) return false;
        }
        return true;
    }
    
    public boolean isDirty() {
        return dirty;
    }
    
    public void setClean() {
        dirty = false;
    }
}
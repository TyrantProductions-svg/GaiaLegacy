package com.overlord.voxel;

import com.overlord.config.GameConfig;

public class SubChunk {
    
    private byte[] blocks;
    private boolean dirty;
    
    public SubChunk() {
        blocks = new byte[GameConfig.Chunk.SIZE * GameConfig.Chunk.SUBCHUNK_HEIGHT * GameConfig.Chunk.SIZE];
        dirty = true;
    }
    
    public byte getBlock(int x, int y, int z) {
        if (x < 0 || x >= GameConfig.Chunk.SIZE || y < 0 || y >= GameConfig.Chunk.SUBCHUNK_HEIGHT || z < 0 || z >= GameConfig.Chunk.SIZE) {
            return 0;
        }
        return blocks[x + (y * GameConfig.Chunk.SIZE) + (z * GameConfig.Chunk.SIZE * GameConfig.Chunk.SIZE)];
    }
    
    public void setBlock(int x, int y, int z, byte blockType) {
        if (x < 0 || x >= GameConfig.Chunk.SIZE || y < 0 || y >= GameConfig.Chunk.SUBCHUNK_HEIGHT || z < 0 || z >= GameConfig.Chunk.SIZE) {
            return;
        }
        blocks[x + (y * GameConfig.Chunk.SIZE) + (z * GameConfig.Chunk.SIZE * GameConfig.Chunk.SIZE)] = blockType;
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

    void copyBlocksTo(byte[] target, int baseY, int worldHeight) {
        for (int z = 0; z < GameConfig.Chunk.SIZE; z++) {
            for (int y = 0;
                    y < GameConfig.Chunk.SUBCHUNK_HEIGHT
                            && baseY + y < worldHeight;
                    y++) {
                for (int x = 0; x < GameConfig.Chunk.SIZE; x++) {
                    int sourceIndex =
                            x
                                    + y * GameConfig.Chunk.SIZE
                                    + z
                                            * GameConfig.Chunk.SIZE
                                            * GameConfig.Chunk.SUBCHUNK_HEIGHT;
                    int yWorld = baseY + y;
                    int targetIndex =
                            x
                                    + yWorld * GameConfig.Chunk.SIZE
                                    + z * GameConfig.Chunk.SIZE * worldHeight;
                    target[targetIndex] = blocks[sourceIndex];
                }
            }
        }
    }
}

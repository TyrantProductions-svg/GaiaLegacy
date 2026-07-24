package com.overlord.voxel;

import com.overlord.config.GameConfig;

public class SubChunkSize {
    
    private byte[] blockSizes;
    
    public SubChunkSize() {
        blockSizes = new byte[GameConfig.Chunk.SIZE * GameConfig.Chunk.SUBCHUNK_HEIGHT * GameConfig.Chunk.SIZE];
        for (int i = 0; i < blockSizes.length; i++) {
            blockSizes[i] = (byte) BlockSize.SIZE_16.ordinal();
        }
    }
    
    public BlockSize getBlockSize(int x, int y, int z) {
        if (x < 0 || x >= GameConfig.Chunk.SIZE || y < 0 || y >= GameConfig.Chunk.SUBCHUNK_HEIGHT || z < 0 || z >= GameConfig.Chunk.SIZE) {
            return BlockSize.SIZE_16;
        }
        int ordinal = blockSizes[x + (y * GameConfig.Chunk.SIZE) + (z * GameConfig.Chunk.SIZE * GameConfig.Chunk.SIZE)];
        return BlockSize.values()[ordinal];
    }
    
    public void setBlockSize(int x, int y, int z, BlockSize blockSize) {
        if (x < 0 || x >= GameConfig.Chunk.SIZE || y < 0 || y >= GameConfig.Chunk.SUBCHUNK_HEIGHT || z < 0 || z >= GameConfig.Chunk.SIZE) {
            return;
        }
        blockSizes[x + (y * GameConfig.Chunk.SIZE) + (z * GameConfig.Chunk.SIZE * GameConfig.Chunk.SIZE)] = (byte) blockSize.ordinal();
    }

    void copyBlockSizesTo(BlockSize[] target, int sectionYOffset, int worldHeight) {
        int baseIndex = sectionYOffset * GameConfig.Chunk.SIZE * worldHeight;
        for (int y = 0; y < GameConfig.Chunk.SUBCHUNK_HEIGHT; y++) {
            int worldY = sectionYOffset + y;
            if (worldY >= worldHeight) break;
            for (int z = 0; z < GameConfig.Chunk.SIZE; z++) {
                for (int x = 0; x < GameConfig.Chunk.SIZE; x++) {
                    int localIndex = x + (y * GameConfig.Chunk.SIZE) + (z * GameConfig.Chunk.SIZE * GameConfig.Chunk.SUBCHUNK_HEIGHT);
                    int worldIndex = x + (worldY * GameConfig.Chunk.SIZE) + (z * GameConfig.Chunk.SIZE * worldHeight);
                    target[worldIndex] = BlockSize.values()[blockSizes[localIndex]];
                }
            }
        }
    }
}
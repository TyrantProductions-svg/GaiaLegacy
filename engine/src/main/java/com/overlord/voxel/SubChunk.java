package com.overlord.voxel;

import com.overlord.config.GameConfig;
import java.util.Objects;

public class SubChunk {
    
    private byte[] blocks;
    private BlockPlacement[] blockPlacements;
    private boolean dirty;
    
    public SubChunk() {
        blocks = new byte[GameConfig.Chunk.SIZE * GameConfig.Chunk.SUBCHUNK_HEIGHT * GameConfig.Chunk.SIZE];
        blockPlacements = new BlockPlacement[blocks.length];
        for (int i = 0; i < blockPlacements.length; i++) {
            blockPlacements[i] = BlockPlacement.full();
        }
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
    
    public BlockPlacement getBlockPlacement(int x, int y, int z) {
        if (x < 0 || x >= GameConfig.Chunk.SIZE || y < 0 || y >= GameConfig.Chunk.SUBCHUNK_HEIGHT || z < 0 || z >= GameConfig.Chunk.SIZE) {
            return BlockPlacement.full();
        }
        return blockPlacements[x + (y * GameConfig.Chunk.SIZE) + (z * GameConfig.Chunk.SIZE * GameConfig.Chunk.SIZE)];
    }
    
    public void setBlockPlacement(int x, int y, int z, BlockPlacement placement) {
        if (x < 0 || x >= GameConfig.Chunk.SIZE || y < 0 || y >= GameConfig.Chunk.SUBCHUNK_HEIGHT || z < 0 || z >= GameConfig.Chunk.SIZE) {
            return;
        }
        blockPlacements[x + (y * GameConfig.Chunk.SIZE) + (z * GameConfig.Chunk.SIZE * GameConfig.Chunk.SIZE)] =
                Objects.requireNonNull(placement, "placement");
        dirty = true;
    }
    
    public BlockSize getBlockSize(int x, int y, int z) {
        return getBlockPlacement(x, y, z).size();
    }
    
    public void setBlockSize(int x, int y, int z, BlockSize size) {
        setBlockPlacement(x, y, z, BlockPlacement.of(size));
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

    void copyBlockPlacementsTo(BlockPlacement[] target, int baseY, int worldHeight) {
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
                    target[targetIndex] = blockPlacements[sourceIndex];
                }
            }
        }
    }

    void copyBlockSizesTo(BlockSize[] target, int baseY, int worldHeight) {
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
                    target[targetIndex] = blockPlacements[sourceIndex].size();
                }
            }
        }
    }
}
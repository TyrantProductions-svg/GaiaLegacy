package com.overlord.voxel;

import com.overlord.config.GameConfig;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

public class Chunk {
    
    private final int worldHeight;
    private final int numSubChunks;
    private Map<Integer, SubChunk> subChunks;
    
    public Chunk() {
        this(GameConfig.Chunk.MAX_HEIGHT);
    }
    
    public Chunk(int worldHeight) {
        this.worldHeight = worldHeight;
        this.numSubChunks = worldHeight / GameConfig.Chunk.SUBCHUNK_HEIGHT;
        this.subChunks = new HashMap<>();
    }
    
    public byte getBlock(int x, int y, int z) {
        if (x < 0 || x >= GameConfig.Chunk.SIZE || y < 0 || y >= worldHeight || z < 0 || z >= GameConfig.Chunk.SIZE) {
            return 0;
        }
        
        int sectionIndex = y / GameConfig.Chunk.SUBCHUNK_HEIGHT;
        int localY = y % GameConfig.Chunk.SUBCHUNK_HEIGHT;
        
        SubChunk subChunk = subChunks.get(sectionIndex);
        if (subChunk == null) {
            return 0;
        }
        
        return subChunk.getBlock(x, localY, z);
    }
    
    public void setBlock(int x, int y, int z, byte blockType) {
        if (x < 0 || x >= GameConfig.Chunk.SIZE || y < 0 || y >= worldHeight || z < 0 || z >= GameConfig.Chunk.SIZE) {
            return;
        }
        
        int sectionIndex = y / GameConfig.Chunk.SUBCHUNK_HEIGHT;
        int localY = y % GameConfig.Chunk.SUBCHUNK_HEIGHT;
        
        if (blockType == 0) {
            SubChunk subChunk = subChunks.get(sectionIndex);
            if (subChunk != null) {
                subChunk.setBlock(x, localY, z, (byte) 0);
                if (subChunk.isEmpty()) {
                    subChunks.remove(sectionIndex);
                }
            }
        } else {
            SubChunk subChunk = subChunks.computeIfAbsent(sectionIndex, k -> new SubChunk());
            subChunk.setBlock(x, localY, z, blockType);
        }
    }
    
    public SubChunk getSubChunk(int sectionIndex) {
        if (sectionIndex < 0 || sectionIndex >= numSubChunks) {
            return null;
        }
        return subChunks.get(sectionIndex);
    }
    
    public int getWorldHeight() {
        return worldHeight;
    }
    
    public int getNumSubChunks() {
        return numSubChunks;
    }

    static Chunk fromCanonicalBytes(
            int worldHeight, byte[] blocks) {
        if (worldHeight <= 0) {
            throw new IllegalArgumentException(
                    "worldHeight must be greater than zero");
        }
        Objects.requireNonNull(blocks, "blocks");
        int expectedLength =
                Math.multiplyExact(
                        Math.multiplyExact(
                                GameConfig.Chunk.SIZE, worldHeight),
                        GameConfig.Chunk.SIZE);
        if (blocks.length != expectedLength) {
            throw new IllegalArgumentException(
                    "blocks must contain exactly "
                            + expectedLength
                            + " bytes");
        }

        Chunk chunk = new Chunk(worldHeight);
        for (int localZ = 0;
                localZ < GameConfig.Chunk.SIZE;
                localZ++) {
            for (int y = 0; y < worldHeight; y++) {
                for (int localX = 0;
                        localX < GameConfig.Chunk.SIZE;
                        localX++) {
                    int index =
                            localX
                                    + y * GameConfig.Chunk.SIZE
                                    + localZ
                                            * GameConfig.Chunk.SIZE
                                            * worldHeight;
                    chunk.setBlock(
                            localX, y, localZ, blocks[index]);
                }
            }
        }
        return chunk;
    }

    void copyBlocksTo(byte[] target) {
        for (Map.Entry<Integer, SubChunk> entry : subChunks.entrySet()) {
            entry.getValue()
                    .copyBlocksTo(
                            target,
                            entry.getKey()
                                    * GameConfig.Chunk.SUBCHUNK_HEIGHT,
                            worldHeight);
        }
    }
}

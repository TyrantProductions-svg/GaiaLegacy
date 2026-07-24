package com.gaia.world;

import com.gaia.blocks.BlockRegistry;
import com.overlord.assets.ResourceLocation;
import com.overlord.config.GameConfig;
import com.overlord.voxel.BlockSize;
import com.overlord.voxel.Chunk;
import com.overlord.voxel.ChunkKey;
import com.overlord.voxel.PerlinNoise;
import com.overlord.voxel.World;
import java.util.Objects;

public class GaiaWorldGenerator {
    
    private static final int SEED = 12345;
    private static final int OCTAVES = 4;
    private static final double PERSISTENCE = 0.5;
    private static final double SCALE = 0.02;
    
    private static final PerlinNoise perlinNoise = new PerlinNoise(SEED);

    private final byte grassId;
    private final byte dirtId;
    private final byte stoneId;
    private final byte airId;

    public GaiaWorldGenerator(BlockRegistry blocks) {
        Objects.requireNonNull(blocks, "blocks");
        grassId =
                blocks.requireStoredId(
                        ResourceLocation.parse("gaia:grass"));
        dirtId =
                blocks.requireStoredId(
                        ResourceLocation.parse("gaia:dirt"));
        stoneId =
                blocks.requireStoredId(
                        ResourceLocation.parse("gaia:stone"));
        airId = 0;
    }

    public void generateChunk(World world, ChunkKey key) {
        Objects.requireNonNull(world, "world")
                .generate(
                        Objects.requireNonNull(key, "key"),
                        chunk -> {
                            generateBaseTerrain(chunk, key.x(), key.z());
                            addDetailLayers(chunk, key.x(), key.z());
                        });
    }

    private void generateBaseTerrain(Chunk chunk, int chunkX, int chunkZ) {
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
                        blockType = grassId;
                    } else if (y > height - 4) {
                        blockType = dirtId;
                    } else {
                        blockType = stoneId;
                    }
                    
                    chunk.setBlock(x, y, z, blockType);
                    chunk.setBlockSize(x, y, z, BlockSize.SIZE_16);
                }
            }
        }
    }

    private void addDetailLayers(Chunk chunk, int chunkX, int chunkZ) {
        for (int x = 0; x < GameConfig.Chunk.SIZE; x++) {
            for (int z = 0; z < GameConfig.Chunk.SIZE; z++) {
                int worldX = chunkX * GameConfig.Chunk.SIZE + x;
                int worldZ = chunkZ * GameConfig.Chunk.SIZE + z;
                
                double noiseValue = perlinNoise.octaveNoise2D(worldX * SCALE, worldZ * SCALE, OCTAVES, PERSISTENCE);
                int baseHeight = GameConfig.WorldGeneration.BASE_HEIGHT + (int) (noiseValue * GameConfig.WorldGeneration.HEIGHT_VARIATION);
                baseHeight = Math.max(1, baseHeight);
                
                addSurfaceDetails(chunk, x, z, baseHeight, worldX, worldZ);
            }
        }
    }

    private void addSurfaceDetails(Chunk chunk, int localX, int localZ, int baseHeight, int worldX, int worldZ) {
        byte surfaceBlock = chunk.getBlock(localX, baseHeight - 1, localZ);
        
        for (int dy = 0; dy < 4; dy++) {
            int detailY = baseHeight + dy;
            if (detailY >= GameConfig.Chunk.MAX_HEIGHT) break;
            
            double heightFactor = 1.0 - (dy * 0.25);
            double density = 0.15 * heightFactor;
            
            double detailNoise = perlinNoise.octaveNoise2D(
                    worldX * SCALE * 2.0, 
                    worldZ * SCALE * 2.0, 
                    2, 
                    0.5
            ) * 0.5 + 0.5;
            
            if (detailNoise < density) {
                continue;
            }
            
            boolean supported = isSupported(chunk, localX, detailY, localZ);
            if (!supported) {
                continue;
            }
            
            BlockSize size = selectDetailSize(dy, worldX, detailY, worldZ);
            if (size == null) {
                continue;
            }
            
            if (chunk.getBlock(localX, detailY, localZ) == airId) {
                chunk.setBlock(localX, detailY, localZ, surfaceBlock);
                chunk.setBlockSize(localX, detailY, localZ, size);
            }
        }
    }

    private boolean isSupported(Chunk chunk, int x, int y, int z) {
        if (y <= 0) return false;
        
        int checkY = y - 1;
        byte blockBelow = chunk.getBlock(x, checkY, z);
        if (blockBelow != airId) {
            return true;
        }
        
        if (z > 0 && chunk.getBlock(x, checkY, z - 1) != airId) return true;
        if (z < GameConfig.Chunk.SIZE - 1 && chunk.getBlock(x, checkY, z + 1) != airId) return true;
        if (x > 0 && chunk.getBlock(x - 1, checkY, z) != airId) return true;
        if (x < GameConfig.Chunk.SIZE - 1 && chunk.getBlock(x + 1, checkY, z) != airId) return true;
        
        return false;
    }

    private BlockSize selectDetailSize(int dy, int worldX, int y, int worldZ) {
        int hash = (worldX * 73856093) ^ (y * 19349663) ^ (worldZ * 83492791);
        int value = Math.abs(hash) % 100;
        
        if (dy == 0) {
            if (value < 60) return BlockSize.SIZE_8;
            if (value < 85) return BlockSize.SIZE_4;
            return BlockSize.SIZE_2;
        } else if (dy == 1) {
            if (value < 40) return BlockSize.SIZE_8;
            if (value < 70) return BlockSize.SIZE_4;
            return BlockSize.SIZE_2;
        } else if (dy == 2) {
            if (value < 30) return BlockSize.SIZE_4;
            return BlockSize.SIZE_2;
        } else {
            return BlockSize.SIZE_2;
        }
    }
}
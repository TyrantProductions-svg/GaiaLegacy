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
    private static final int GRID_SCALE = 8;
    
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
                        chunk -> generateTerrain(chunk, key.x(), key.z()));
    }

    private void generateTerrain(Chunk chunk, int chunkX, int chunkZ) {
        double[][] heightMap = computeHeightMap(chunkX, chunkZ);
        
        generateBaseLayer(chunk, heightMap, chunkX, chunkZ);
        
        generateDetailLayer(chunk, heightMap, chunkX, chunkZ);
    }

    private double[][] computeHeightMap(int chunkX, int chunkZ) {
        double[][] heights = new double[GameConfig.Chunk.SIZE][GameConfig.Chunk.SIZE];
        
        for (int x = 0; x < GameConfig.Chunk.SIZE; x++) {
            for (int z = 0; z < GameConfig.Chunk.SIZE; z++) {
                int worldX = chunkX * GameConfig.Chunk.SIZE + x;
                int worldZ = chunkZ * GameConfig.Chunk.SIZE + z;
                
                double noiseValue = perlinNoise.octaveNoise2D(
                        worldX * SCALE, 
                        worldZ * SCALE, 
                        OCTAVES, 
                        PERSISTENCE
                );
                
                heights[x][z] = GameConfig.WorldGeneration.BASE_HEIGHT + 
                        (noiseValue * GameConfig.WorldGeneration.HEIGHT_VARIATION);
            }
        }
        
        return heights;
    }

    private void generateBaseLayer(Chunk chunk, double[][] heightMap, int chunkX, int chunkZ) {
        for (int x = 0; x < GameConfig.Chunk.SIZE; x++) {
            for (int z = 0; z < GameConfig.Chunk.SIZE; z++) {
                double exactHeight = heightMap[x][z];
                int solidHeight = (int) Math.floor(exactHeight);
                
                for (int y = 0; y < solidHeight; y++) {
                    byte blockType = selectBlockType(y, solidHeight);
                    chunk.setBlock(x, y, z, blockType);
                    chunk.setBlockSize(x, y, z, BlockSize.SIZE_16);
                }
            }
        }
    }

    private void generateDetailLayer(Chunk chunk, double[][] heightMap, int chunkX, int chunkZ) {
        for (int x = 0; x < GameConfig.Chunk.SIZE; x++) {
            for (int z = 0; z < GameConfig.Chunk.SIZE; z++) {
                double exactHeight = heightMap[x][z];
                int baseHeight = (int) Math.floor(exactHeight);
                double fractionalPart = exactHeight - baseHeight;
                
                if (fractionalPart < 0.01) {
                    continue;
                }
                
                int worldX = chunkX * GameConfig.Chunk.SIZE + x;
                int worldZ = chunkZ * GameConfig.Chunk.SIZE + z;
                
                fillTransition(chunk, x, z, baseHeight, fractionalPart, worldX, worldZ);
            }
        }
    }

    private void fillTransition(Chunk chunk, int x, int z, int baseY, double fraction, int worldX, int worldZ) {
        byte surfaceBlock = chunk.getBlock(x, baseY - 1, z);
        if (surfaceBlock == airId) {
            surfaceBlock = grassId;
        }
        
        BlockSize[] sizes = {BlockSize.SIZE_8, BlockSize.SIZE_4, BlockSize.SIZE_2};
        double[] thresholds = {0.5, 0.25, 0.125};
        
        for (int i = 0; i < sizes.length; i++) {
            double sizeInUnits = sizes[i].units();
            double threshold = thresholds[i];
            
            if (fraction >= threshold) {
                int detailY = baseY;
                
                if (canPlaceBlock(chunk, x, detailY, z, sizes[i])) {
                    chunk.setBlock(x, detailY, z, surfaceBlock);
                    chunk.setBlockSize(x, detailY, z, sizes[i]);
                    break;
                }
            }
        }
    }

    private boolean canPlaceBlock(Chunk chunk, int x, int y, int z, BlockSize size) {
        if (y < 0 || y >= GameConfig.Chunk.MAX_HEIGHT) {
            return false;
        }
        
        if (chunk.getBlock(x, y, z) != airId) {
            return false;
        }
        
        return isSupported(chunk, x, y, z, size);
    }

    private boolean isSupported(Chunk chunk, int x, int y, int z, BlockSize size) {
        if (y <= 0) {
            return false;
        }
        
        int supportY = y - 1;
        
        if (chunk.getBlock(x, supportY, z) != airId) {
            return true;
        }
        
        if (x > 0 && chunk.getBlock(x - 1, supportY, z) != airId) return true;
        if (x < GameConfig.Chunk.SIZE - 1 && chunk.getBlock(x + 1, supportY, z) != airId) return true;
        if (z > 0 && chunk.getBlock(x, supportY, z - 1) != airId) return true;
        if (z < GameConfig.Chunk.SIZE - 1 && chunk.getBlock(x, supportY, z + 1) != airId) return true;
        
        return false;
    }

    private byte selectBlockType(int y, int surfaceY) {
        if (y == surfaceY - 1) {
            return grassId;
        } else if (y > surfaceY - 4) {
            return dirtId;
        } else {
            return stoneId;
        }
    }
}
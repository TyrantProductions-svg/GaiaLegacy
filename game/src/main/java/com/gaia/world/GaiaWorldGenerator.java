package com.gaia.world;

import com.gaia.blocks.BlockRegistry;
import com.overlord.assets.ResourceLocation;
import com.overlord.config.GameConfig;
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
    }

    public void generateChunk(World world, ChunkKey key) {
        Objects.requireNonNull(world, "world")
                .generate(
                        Objects.requireNonNull(key, "key"),
                        chunk -> fillChunk(chunk, key.x(), key.z()));
    }

    private void fillChunk(Chunk chunk, int chunkX, int chunkZ) {
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
                }
            }
        }
    }
}

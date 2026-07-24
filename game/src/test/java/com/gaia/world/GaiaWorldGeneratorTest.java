package com.gaia.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gaia.assets.GaiaAssetCatalog;
import com.gaia.assets.GaiaResourceLoader;
import com.overlord.assets.AssetManager;
import com.overlord.config.GameConfig;
import com.overlord.voxel.BlockSize;
import com.overlord.voxel.ChunkKey;
import com.overlord.voxel.ChunkState;
import com.overlord.voxel.World;
import org.junit.jupiter.api.Test;

class GaiaWorldGeneratorTest {
    private static final GaiaAssetCatalog CATALOG = productionCatalog();

    @Test
    void generatesTerrainWithProductionRegistryIds() {
        World world = new World();
        GaiaWorldGenerator generator =
                new GaiaWorldGenerator(CATALOG.blockRegistry());
        ChunkKey key = new ChunkKey(0, 0);

        generator.generateChunk(world, key);

        assertTrue(world.chunks().contains(key));
        assertEquals(ChunkState.GENERATED, world.chunks().state(key));
        
        int baseSurfaceY = findBaseSurfaceY(world, 0, 0);
        assertEquals(1, Byte.toUnsignedInt(world.getBlock(0, baseSurfaceY, 0)));
        assertEquals(2, Byte.toUnsignedInt(world.getBlock(0, baseSurfaceY - 1, 0)));
        assertEquals(2, Byte.toUnsignedInt(world.getBlock(0, baseSurfaceY - 2, 0)));
        assertEquals(3, Byte.toUnsignedInt(world.getBlock(0, baseSurfaceY - 3, 0)));
    }

    private static int findBaseSurfaceY(World world, int x, int z) {
        for (int y = GameConfig.Chunk.MAX_HEIGHT - 1; y >= 0; y--) {
            byte block = world.getBlock(x, y, z);
            if (block != 0 && world.getBlockSize(x, y, z) == BlockSize.SIZE_16) {
                return y;
            }
        }
        throw new AssertionError("No SIZE_16 block found in column");
    }

    @Test
    void generatesBaseTerrainWithSize16Blocks() {
        World world = new World();
        GaiaWorldGenerator generator =
                new GaiaWorldGenerator(CATALOG.blockRegistry());
        ChunkKey key = new ChunkKey(0, 0);

        generator.generateChunk(world, key);

        int topY = highestBlock(world, 0, 0);
        int baseY = topY - 5;
        
        assertEquals(BlockSize.SIZE_16, world.getBlockSize(0, baseY, 0));
        assertEquals(BlockSize.SIZE_16, world.getBlockSize(0, baseY - 1, 0));
    }

    private static int highestBlock(World world, int x, int z) {
        for (int y = GameConfig.Chunk.MAX_HEIGHT - 1; y >= 0; y--) {
            if (world.getBlock(x, y, z) != 0) {
                return y;
            }
        }
        throw new AssertionError("Generated column is empty");
    }

    private static GaiaAssetCatalog productionCatalog() {
        return new GaiaResourceLoader(
                        new AssetManager(
                                GaiaWorldGeneratorTest.class.getClassLoader()))
                .load();
    }
}
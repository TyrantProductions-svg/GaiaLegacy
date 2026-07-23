package com.gaia.world;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.gaia.assets.GaiaAssetCatalog;
import com.gaia.assets.GaiaResourceLoader;
import com.overlord.assets.AssetManager;
import com.overlord.config.GameConfig;
import com.overlord.voxel.World;
import org.junit.jupiter.api.Test;

class GaiaWorldGeneratorTest {
    private static final GaiaAssetCatalog CATALOG = productionCatalog();

    @Test
    void generatesTerrainWithProductionRegistryIds() {
        World world = new World();
        GaiaWorldGenerator generator =
                new GaiaWorldGenerator(CATALOG.blockRegistry());

        generator.generateChunk(world, 0, 0);

        int topY = highestBlock(world, 0, 0);
        assertEquals(1, Byte.toUnsignedInt(world.getBlock(0, topY, 0)));
        assertEquals(2, Byte.toUnsignedInt(world.getBlock(0, topY - 1, 0)));
        assertEquals(2, Byte.toUnsignedInt(world.getBlock(0, topY - 2, 0)));
        assertEquals(3, Byte.toUnsignedInt(world.getBlock(0, topY - 3, 0)));
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

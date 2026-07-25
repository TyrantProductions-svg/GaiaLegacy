package com.gaia.world.generation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.overlord.config.GameConfig;
import com.overlord.voxel.ChunkGenerationData;
import com.overlord.voxel.ChunkKey;
import org.junit.jupiter.api.Test;

class WorldGenerationBoundaryTest {
    @Test
    void adjacentBoundaryColumnsMatchWorldSpaceProviders() {
        WorldGenerationConfig config = WorldGenerationConfig.defaults();
        GenerationContext context =
                WorldGenerationDeterminismTest.context(config);
        ChunkGenerationData left =
                WorldGenerationDeterminismTest.generate(
                        new ChunkKey(0, 0), config);
        ChunkGenerationData right =
                WorldGenerationDeterminismTest.generate(
                        new ChunkKey(1, 0), config);
        BiomeProvider biomes = new ContinuousBiomeProvider();
        HeightProvider heights = new BlendedHeightProvider();

        for (int z = 0; z < GameConfig.Chunk.SIZE; z++) {
            int leftHeight = heights.sampleHeight(
                    context, 15, z, biomes.sample(context, 15, z));
            int rightHeight = heights.sampleHeight(
                    context, 16, z, biomes.sample(context, 16, z));
            assertTrue(Math.abs(leftHeight - rightHeight) <= 3);
            assertTrue(
                    Math.abs(
                            highestSolid(left, 15, z)
                                    - highestSolid(right, 0, z))
                            <= 3 + config.decoration().maximumOutcropHeight());
            assertSurfaceIsValid(left, 15, z, leftHeight, config);
            assertSurfaceIsValid(right, 0, z, rightHeight, config);
        }
    }

    @Test
    void caveStrataSurfaceAndDecorationStayWithinConfiguredBounds() {
        WorldGenerationConfig config = WorldGenerationConfig.defaults();
        GenerationContext context =
                WorldGenerationDeterminismTest.context(config);
        BiomeProvider biomes = new ContinuousBiomeProvider();
        HeightProvider heights = new BlendedHeightProvider();
        boolean caveExposure = false;

        for (ChunkGenerationData data :
                WorldGenerationDeterminismTest.generate(
                        WorldGenerationDeterminismTest.defaultKeys(),
                        config)) {
            for (int localZ = 0; localZ < GameConfig.Chunk.SIZE; localZ++) {
                for (int localX = 0; localX < GameConfig.Chunk.SIZE; localX++) {
                    int worldX = data.key().worldOriginX() + localX;
                    int worldZ = data.key().worldOriginZ() + localZ;
                    int surface = heights.sampleHeight(
                            context,
                            worldX,
                            worldZ,
                            biomes.sample(context, worldX, worldZ));
                    int maximumTop =
                            surface
                                    + config.decoration()
                                            .maximumOutcropHeight();
                    assertTrue(highestSolid(data, localX, localZ) <= maximumTop);
                    for (int y = 0;
                            y < config.cave().bedrockDepth();
                            y++) {
                        assertEquals(
                                WorldGenerationDeterminismTest.PALETTE.stone(),
                                data.getBlock(localX, y, localZ));
                    }
                    int protectedStart =
                            Math.max(
                                    0,
                                    surface
                                            - config.cave().surfaceBuffer()
                                            + 1);
                    for (int y = protectedStart; y <= surface; y++) {
                        assertTrue(
                                data.getBlock(localX, y, localZ)
                                        != WorldGenerationDeterminismTest
                                                .PALETTE.air());
                    }
                    for (int y = maximumTop + 1;
                            y < data.worldHeight();
                            y++) {
                        assertEquals(
                                WorldGenerationDeterminismTest.PALETTE.air(),
                                data.getBlock(localX, y, localZ));
                    }
                    for (int y = config.cave().bedrockDepth();
                            y
                                    <= surface
                                            - config.cave().surfaceBuffer();
                            y++) {
                        caveExposure |=
                                data.getBlock(localX, y, localZ)
                                        == WorldGenerationDeterminismTest
                                                .PALETTE.air();
                    }
                }
            }
        }
        assertTrue(caveExposure, "Representative chunks contain no cave");
    }

    static int highestSolid(
            ChunkGenerationData data, int localX, int localZ) {
        for (int y = data.worldHeight() - 1; y >= 0; y--) {
            if (data.getBlock(localX, y, localZ)
                    != WorldGenerationDeterminismTest.PALETTE.air()) {
                return y;
            }
        }
        return -1;
    }

    private static void assertSurfaceIsValid(
            ChunkGenerationData data,
            int localX,
            int localZ,
            int providerHeight,
            WorldGenerationConfig config) {
        int top = highestSolid(data, localX, localZ);
        assertTrue(top >= providerHeight);
        assertTrue(
                top
                        <= providerHeight
                                + config.decoration()
                                        .maximumOutcropHeight());
        byte surface =
                data.getBlock(localX, providerHeight, localZ);
        assertTrue(
                surface == WorldGenerationDeterminismTest.PALETTE.grass()
                        || surface
                                == WorldGenerationDeterminismTest
                                        .PALETTE.stone());
        if (top + 1 < data.worldHeight()) {
            assertEquals(
                    WorldGenerationDeterminismTest.PALETTE.air(),
                    data.getBlock(localX, top + 1, localZ));
        }
    }
}

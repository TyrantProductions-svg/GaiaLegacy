package com.gaia.world.generation;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.overlord.assets.ResourceLocation;
import com.overlord.config.GameConfig;
import com.overlord.voxel.ChunkKey;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class NoiseCaveProviderTest {
    private static final byte AIR = 7;
    private static final byte GRASS = 11;
    private static final byte DIRT = 13;
    private static final byte STONE = 17;

    private final CaveProvider provider =
            new NoiseCaveProvider();

    @Test
    void hasStableStageId() {
        assertEquals(
                ResourceLocation.parse("gaia:cave"),
                provider.id());
    }

    @Test
    void preservesConfiguredBedrockAndSurfaceBuffer() {
        GenerationRegion region = solidRegion(40, 39);

        provider.generate(
                context(12345L, 0.0, 2, 2),
                region);

        for (int localZ = 0;
                localZ < GameConfig.Chunk.SIZE;
                localZ++) {
            for (int localX = 0;
                    localX < GameConfig.Chunk.SIZE;
                    localX++) {
                assertNotEquals(
                        AIR,
                        region.getBlock(localX, 0, localZ));
                assertNotEquals(
                        AIR,
                        region.getBlock(localX, 1, localZ));
                assertEquals(
                        AIR,
                        region.getBlock(localX, 2, localZ));
                assertEquals(
                        AIR,
                        region.getBlock(localX, 37, localZ));
                assertNotEquals(
                        AIR,
                        region.getBlock(localX, 38, localZ));
                assertNotEquals(
                        AIR,
                        region.getBlock(localX, 39, localZ));
            }
        }
        assertOnlyPaletteBlocks(region);
    }

    @Test
    void fixedSeedCarvesKnownInteriorSample() {
        GenerationRegion region = solidRegion(48, 40);

        provider.generate(
                context(12345L, 0.78, 2, 3),
                region);

        assertEquals(AIR, region.getBlock(0, 31, 0));
        assertEquals(STONE, region.getBlock(0, 0, 0));
    }

    @Test
    void caveFieldIsDeterministicAcrossRepeatedRegions() {
        GenerationRegion first =
                solidRegion(new ChunkKey(-2, 3), 48, 40);
        GenerationRegion second =
                solidRegion(new ChunkKey(-2, 3), 48, 40);
        GenerationContext context =
                context(987654321L, 0.62, 2, 3);

        provider.generate(context, first);
        provider.generate(context, second);

        assertArrayEquals(
                first.copyBlocks(), second.copyBlocks());
    }

    @Test
    void skipsEmptyInteriorRangesWithoutCrossingBounds() {
        GenerationRegion region = solidRegion(4, 1);

        GenerationStageResult result =
                provider.generate(
                        context(12345L, 0.0, 2, 2),
                        region);

        assertEquals(0, result.samples());
        assertEquals(0, result.writes());
        assertEquals(STONE, region.getBlock(0, 0, 0));
        assertEquals(STONE, region.getBlock(0, 1, 0));
    }

    private static GenerationRegion solidRegion(
            int worldHeight, int columnHeight) {
        return solidRegion(
                new ChunkKey(0, 0),
                worldHeight,
                columnHeight);
    }

    private static GenerationRegion solidRegion(
            ChunkKey key,
            int worldHeight,
            int columnHeight) {
        GenerationRegion region =
                new GenerationRegion(
                        key, worldHeight, AIR);
        for (int localZ = 0;
                localZ < GameConfig.Chunk.SIZE;
                localZ++) {
            for (int localX = 0;
                    localX < GameConfig.Chunk.SIZE;
                    localX++) {
                region.setHeight(
                        localX, localZ, columnHeight);
                for (int y = 0; y <= columnHeight; y++) {
                    region.writeBlock(
                            localX, y, localZ, STONE);
                }
            }
        }
        return region;
    }

    private static GenerationContext context(
            long seed,
            double threshold,
            int bedrockDepth,
            int surfaceBuffer) {
        WorldGenerationConfig defaults =
                WorldGenerationConfig.defaults();
        WorldGenerationConfig config =
                new WorldGenerationConfig(
                        seed,
                        defaults.algorithmVersion(),
                        defaults.chunkRadius(),
                        defaults.biome(),
                        defaults.height(),
                        new WorldGenerationConfig.CaveSettings(
                                defaults.cave().scale(),
                                threshold,
                                bedrockDepth,
                                surfaceBuffer),
                        defaults.surface(),
                        defaults.decoration(),
                        defaults.spawn());
        return new GenerationContext(
                config,
                new GenerationBlockPalette(
                        AIR, GRASS, DIRT, STONE),
                new DeterministicCoordinateSampler(
                        config.seed(),
                        config.algorithmVersion()));
    }

    private static void assertOnlyPaletteBlocks(
            GenerationRegion region) {
        assertTrue(
                Arrays.stream(toUnsigned(region.copyBlocks()))
                        .allMatch(
                                block ->
                                        block == AIR
                                                || block == GRASS
                                                || block == DIRT
                                                || block == STONE));
    }

    private static int[] toUnsigned(byte[] blocks) {
        int[] values = new int[blocks.length];
        for (int index = 0; index < blocks.length; index++) {
            values[index] =
                    Byte.toUnsignedInt(blocks[index]);
        }
        return values;
    }
}

package com.gaia.world;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gaia.assets.GaiaAssetCatalog;
import com.gaia.assets.GaiaResourceLoader;
import com.gaia.world.generation.DeterministicCoordinateSampler;
import com.gaia.world.generation.GenerationBlockPalette;
import com.gaia.world.generation.GenerationContext;
import com.gaia.world.generation.GenerationStageResult;
import com.gaia.world.generation.WorldGenerationConfig;
import com.gaia.world.generation.WorldGenerationResult;
import com.gaia.world.generation.WorldGenerator;
import com.overlord.assets.AssetManager;
import com.overlord.assets.ResourceLocation;
import com.overlord.config.GameConfig;
import com.overlord.voxel.ChunkGenerationData;
import com.overlord.voxel.ChunkKey;
import com.overlord.voxel.PerlinNoise;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class GaiaWorldGeneratorTest {
    private static final GaiaAssetCatalog CATALOG = productionCatalog();
    private static final GenerationBlockPalette PALETTE =
            GenerationBlockPalette.from(CATALOG.blockRegistry());
    private static final Set<Byte> PRODUCTION_BLOCKS =
            Set.of(
                    PALETTE.air(),
                    PALETTE.grass(),
                    PALETTE.dirt(),
                    PALETTE.stone());

    @Test
    void defaultPipelineUsesProductionResourceIds() {
        WorldGenerator generator =
                GaiaWorldGenerator.createDefault();
        ChunkKey key = new ChunkKey(0, 0);

        WorldGenerationResult result =
                generator.generate(productionContext(), key);

        assertTrue(result.succeeded());
        assertEquals(
                List.of(
                        parse("gaia:continuous_biomes"),
                        parse("gaia:blended_heights"),
                        parse("gaia:strata_density"),
                        parse("gaia:cave"),
                        parse("gaia:surface"),
                        parse("gaia:decoration"),
                        parse("gaia:adaptive_subdivision")),
                result.stageResults().stream()
                        .map(GenerationStageResult::stageId)
                        .toList());
        ChunkGenerationData data =
                result.chunkData().orElseThrow();
        assertContainsOnlyProductionBlocks(data);
        assertHasLayeredSurface(data);
    }

    @Test
    void defaultPipelineIsDeterministicForSameContextAndKey() {
        WorldGenerator generator =
                GaiaWorldGenerator.createDefault();
        GenerationContext context = productionContext();
        ChunkKey key = new ChunkKey(-4, 7);

        WorldGenerationResult first =
                generator.generate(context, key);
        WorldGenerationResult second =
                generator.generate(context, key);

        assertEquals(first.stageResults(), second.stageResults());
        assertArrayEquals(
                first.chunkData().orElseThrow().copyBlocks(),
                second.chunkData().orElseThrow().copyBlocks());
    }

    @Test
    void compositionFactoryHasNoLegacyStaticPerlinState() {
        for (Field field :
                GaiaWorldGenerator.class.getDeclaredFields()) {
            assertFalse(
                    Modifier.isStatic(field.getModifiers())
                            && field.getType()
                                    == PerlinNoise.class,
                    () ->
                            "Legacy static Perlin field remains: "
                                    + field.getName());
        }
    }

    private static void assertContainsOnlyProductionBlocks(
            ChunkGenerationData data) {
        for (byte block : data.copyBlocks()) {
            assertTrue(
                    PRODUCTION_BLOCKS.contains(block),
                    () ->
                            "Unexpected stored block ID "
                                    + Byte.toUnsignedInt(block));
        }
    }

    private static void assertHasLayeredSurface(
            ChunkGenerationData data) {
        boolean foundLayeredSurface = false;
        for (int localZ = 0;
                localZ < GameConfig.Chunk.SIZE;
                localZ++) {
            for (int localX = 0;
                    localX < GameConfig.Chunk.SIZE;
                    localX++) {
                int top = highestSolid(data, localX, localZ);
                if (top >= 4
                        && data.getBlock(localX, top, localZ)
                                == PALETTE.grass()
                        && data.getBlock(
                                        localX,
                                        top - 1,
                                        localZ)
                                == PALETTE.dirt()
                        && data.getBlock(
                                        localX,
                                        top - 2,
                                        localZ)
                                == PALETTE.dirt()
                        && data.getBlock(
                                        localX,
                                        top - 3,
                                        localZ)
                                == PALETTE.dirt()
                        && data.getBlock(
                                        localX,
                                        top - 4,
                                        localZ)
                                == PALETTE.stone()) {
                    foundLayeredSurface = true;
                }
            }
        }
        assertTrue(
                foundLayeredSurface,
                "No grass, three dirt, then stone column was generated");
    }

    private static int highestSolid(
            ChunkGenerationData data,
            int localX,
            int localZ) {
        for (int y = data.worldHeight() - 1; y >= 0; y--) {
            if (data.getBlock(localX, y, localZ)
                    != PALETTE.air()) {
                return y;
            }
        }
        return -1;
    }

    private static GenerationContext productionContext() {
        WorldGenerationConfig config =
                WorldGenerationConfig.defaults();
        return new GenerationContext(
                config,
                PALETTE,
                new DeterministicCoordinateSampler(
                        config.seed(),
                        config.algorithmVersion()));
    }

    private static ResourceLocation parse(String id) {
        return ResourceLocation.parse(id);
    }

    private static GaiaAssetCatalog productionCatalog() {
        return new GaiaResourceLoader(
                        new AssetManager(
                                GaiaWorldGeneratorTest.class.getClassLoader()))
                .load();
    }
}
package com.gaia.world.generation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gaia.blocks.BlockDefinition;
import com.gaia.blocks.BlockRegistry;
import com.gaia.blocks.ItemFormDefinition;
import com.overlord.assets.ResourceLocation;
import com.overlord.renderer.material.MaterialDefinition;
import com.overlord.renderer.material.RenderType;
import com.overlord.renderer.texture.TextureRegion;
import com.overlord.voxel.BlockFace;
import com.overlord.voxel.BlockRenderInfo;
import com.overlord.voxel.ChunkGenerationData;
import com.overlord.voxel.ChunkKey;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class GenerationContractTest {
    private static final ResourceLocation STAGE =
            ResourceLocation.parse("gaia:test_stage");
    private static final ResourceLocation MISSING =
            ResourceLocation.parse("gaia:missing");
    private static final MaterialDefinition MATERIAL =
            new MaterialDefinition(
                    ResourceLocation.parse("gaia:opaque"),
                    ResourceLocation.parse("gaia:blocks"),
                    RenderType.OPAQUE,
                    0.5f,
                    MISSING);
    private static final TextureRegion REGION =
            new TextureRegion(MISSING, 0, 0, 1, 1, 1, 1);

    @Test
    void dominantBiomeUsesStableDeclarationOrderForTies() {
        assertEquals(
                BiomeType.PLAINS,
                new BiomeSample(0.5, 0.5, 0.5).dominant());
        assertEquals(
                BiomeType.ROLLING_HILLS,
                new BiomeSample(0.1, 0.7, 0.7).dominant());
        assertEquals(
                BiomeType.ROCKY_HIGHLANDS,
                new BiomeSample(0.1, 0.2, 0.7).dominant());
    }

    @Test
    void biomeSampleRejectsInvalidWeights() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new BiomeSample(-0.1, 0.4, 0.7));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new BiomeSample(
                                Double.NaN, 0.4, 0.6));
    }

    @Test
    void paletteResolvesProductionResourceLocations() {
        GenerationBlockPalette palette =
                GenerationBlockPalette.from(blockRegistry());

        assertEquals((byte) 0, palette.air());
        assertEquals((byte) 1, palette.grass());
        assertEquals((byte) 2, palette.dirt());
        assertEquals((byte) 200, palette.stone());
    }

    @Test
    void contextRequiresEveryImmutableDependency() {
        WorldGenerationConfig config =
                WorldGenerationConfig.defaults();
        GenerationBlockPalette palette =
                new GenerationBlockPalette(
                        (byte) 0, (byte) 1, (byte) 2, (byte) 3);
        DeterministicCoordinateSampler sampler =
                new DeterministicCoordinateSampler(1L, 1);

        assertThrows(
                NullPointerException.class,
                () -> new GenerationContext(null, palette, sampler));
        assertThrows(
                NullPointerException.class,
                () -> new GenerationContext(config, null, sampler));
        assertThrows(
                NullPointerException.class,
                () -> new GenerationContext(config, palette, null));
    }

    @Test
    void stageResultEnforcesStatusFailureAndCountInvariants() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new GenerationStageResult(
                                STAGE,
                                GenerationStageResult.Status.SUCCEEDED,
                                -1,
                                0,
                                Optional.empty()));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new GenerationStageResult(
                                STAGE,
                                GenerationStageResult.Status.SUCCEEDED,
                                1,
                                1,
                                Optional.of(
                                        new RuntimeException("boom"))));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new GenerationStageResult(
                                STAGE,
                                GenerationStageResult.Status.FAILED,
                                1,
                                1,
                                Optional.empty()));
    }

    @Test
    void successfulWorldResultDefensivelyCopiesStageList() {
        ArrayList<GenerationStageResult> stages =
                new ArrayList<>(List.of(succeededStage()));
        WorldGenerationResult result =
                new WorldGenerationResult(
                        Optional.of(chunkData()), stages);

        stages.clear();

        assertTrue(result.succeeded());
        assertTrue(result.failedStage().isEmpty());
        assertEquals(1, result.stageResults().size());
        assertThrows(
                UnsupportedOperationException.class,
                () -> result.stageResults().clear());
    }

    @Test
    void failedWorldResultHasNoChunkAndExposesFailure() {
        GenerationStageResult failed = failedStage();
        WorldGenerationResult result =
                new WorldGenerationResult(
                        Optional.empty(), List.of(failed));

        assertFalse(result.succeeded());
        assertEquals(failed, result.failedStage().orElseThrow());
    }

    @Test
    void worldResultRejectsContradictorySuccessAndFailure() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new WorldGenerationResult(
                                Optional.of(chunkData()),
                                List.of(failedStage())));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new WorldGenerationResult(
                                Optional.empty(),
                                List.of(succeededStage())));
        assertThrows(
                NullPointerException.class,
                () ->
                        new WorldGenerationResult(
                                Optional.of(chunkData()),
                                List.of(
                                        succeededStage(),
                                        null)));
    }

    @Test
    void stageAndGeneratorContractsComposeThroughValues() {
        GenerationContext context =
                new GenerationContext(
                        WorldGenerationConfig.defaults(),
                        new GenerationBlockPalette(
                                (byte) 0,
                                (byte) 1,
                                (byte) 2,
                                (byte) 3),
                        new DeterministicCoordinateSampler(12345L, 1));
        GenerationRegion region =
                new GenerationRegion(
                        new ChunkKey(0, 0), 8, (byte) 0);
        WorldGenerationStage stage =
                new WorldGenerationStage() {
                    @Override
                    public ResourceLocation id() {
                        return STAGE;
                    }

                    @Override
                    public GenerationStageResult generate(
                            GenerationContext ignoredContext,
                            GenerationRegion ignoredRegion) {
                        return succeededStage();
                    }
                };
        WorldGenerator generator =
                (ignoredContext, ignoredKey) ->
                        new WorldGenerationResult(
                                Optional.of(region.freeze()),
                                List.of(
                                        stage.generate(
                                                context, region)));

        WorldGenerationResult result =
                generator.generate(
                        context, new ChunkKey(0, 0));

        assertEquals(STAGE, stage.id());
        assertTrue(result.succeeded());
    }

    private static GenerationStageResult succeededStage() {
        return new GenerationStageResult(
                STAGE,
                GenerationStageResult.Status.SUCCEEDED,
                256,
                12,
                Optional.empty());
    }

    private static GenerationStageResult failedStage() {
        return new GenerationStageResult(
                STAGE,
                GenerationStageResult.Status.FAILED,
                4,
                2,
                Optional.of(new RuntimeException("boom")));
    }

    private static ChunkGenerationData chunkData() {
        return new ChunkGenerationData(
                new ChunkKey(0, 0), 8, new byte[16 * 8 * 16]);
    }

    private static BlockRegistry blockRegistry() {
        return BlockRegistry.create(
                List.of(
                        definition(0, "gaia:air"),
                        definition(1, "gaia:grass"),
                        definition(2, "gaia:dirt"),
                        definition(200, "gaia:stone")),
                Map.of(
                        0, renderInfo(false),
                        1, renderInfo(true),
                        2, renderInfo(true),
                        200, renderInfo(true)));
    }

    private static BlockDefinition definition(int id, String name) {
        ResourceLocation resource = ResourceLocation.parse(name);
        return new BlockDefinition(
                id,
                resource,
                MATERIAL.id(),
                textures(),
                1.0f,
                1.0f,
                1.0f,
                false,
                false,
                1.0f,
                id == 0
                        ? null
                        : new ItemFormDefinition(
                                resource, 64, false, false));
    }

    private static EnumMap<BlockFace, ResourceLocation> textures() {
        EnumMap<BlockFace, ResourceLocation> textures =
                new EnumMap<>(BlockFace.class);
        for (BlockFace face : BlockFace.values()) {
            textures.put(face, MISSING);
        }
        return textures;
    }

    private static BlockRenderInfo renderInfo(boolean renderable) {
        EnumMap<BlockFace, TextureRegion> faces =
                new EnumMap<>(BlockFace.class);
        for (BlockFace face : BlockFace.values()) {
            faces.put(face, REGION);
        }
        return renderable
                ? new BlockRenderInfo(MATERIAL, faces, true)
                : BlockRenderInfo.nonRenderable(MATERIAL, REGION);
    }
}

package com.gaia.interaction.feedback;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gaia.blocks.BlockDefinition;
import com.gaia.blocks.BlockRegistry;
import com.gaia.blocks.ItemFormDefinition;
import com.overlord.assets.ResourceLocation;
import com.overlord.renderer.material.MaterialDefinition;
import com.overlord.renderer.material.RenderType;
import com.overlord.renderer.feedback.WorldItemFaceRegions;
import com.overlord.renderer.texture.TextureAtlasMetadata;
import com.overlord.renderer.texture.TextureRegion;
import com.overlord.voxel.BlockFace;
import com.overlord.voxel.BlockRenderInfo;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GaiaVisualRegionResolverTest {
    private static final ResourceLocation ATLAS = ResourceLocation.parse("gaia:blocks");
    private static final ResourceLocation MISSING = ResourceLocation.parse("gaia:missing");
    private static final ResourceLocation STONE_SIDE = ResourceLocation.parse("gaia:stone_side");
    private static final ResourceLocation STONE_TOP = ResourceLocation.parse("gaia:stone_top");
    private static final ResourceLocation STONE_ITEM = ResourceLocation.parse("gaia:stone_item");
    private static final ResourceLocation AIR_ITEM = ResourceLocation.parse("gaia:air_item");
    private static final ResourceLocation UNKNOWN_ITEM =
            ResourceLocation.parse("gaia:unknown_item");
    private static final MaterialDefinition MATERIAL =
            new MaterialDefinition(
                    ResourceLocation.parse("gaia:opaque"),
                    ATLAS,
                    RenderType.OPAQUE,
                    0.5f,
                    MISSING);
    private static final TextureRegion MISSING_REGION =
            new TextureRegion(MISSING, 0, 0, 16, 16, 48, 16);
    private static final TextureRegion SIDE_REGION =
            new TextureRegion(STONE_SIDE, 16, 0, 16, 16, 48, 16);
    private static final TextureRegion TOP_REGION =
            new TextureRegion(STONE_TOP, 32, 0, 16, 16, 48, 16);

    @Test
    void resolvesCanonicalItemThroughOwningBlockUpFaceAndBlockAtlas() {
        GaiaVisualRegionResolver resolver = resolver(STONE_TOP);

        assertEquals(TOP_REGION, resolver.resolve(STONE_ITEM));
    }

    @Test
    void worldItemResolverUsesOwningRenderInfoForEveryCubeFace() {
        GaiaWorldItemFaceResolver resolver = faceResolver(STONE_TOP);

        WorldItemFaceRegions faces = resolver.resolve(STONE_ITEM);

        for (BlockFace face : BlockFace.values()) {
            assertEquals(
                    face == BlockFace.UP ? TOP_REGION : SIDE_REGION,
                    faces.region(face));
        }
    }

    @Test
    void unknownItemOrUnresolvableTopTextureUsesExplicitMissingRegion() {
        GaiaVisualRegionResolver resolver = resolver(ResourceLocation.parse("gaia:not_in_atlas"));

        assertEquals(MISSING_REGION, resolver.resolve(ResourceLocation.parse("gaia:unknown_item")));
        assertEquals(MISSING_REGION, resolver.resolve(STONE_ITEM));
    }

    @Test
    void canonicalItemOwnedByNonRenderableBlockUsesExplicitMissingRegion() {
        EnumMap<BlockFace, ResourceLocation> airTextures = textures(MISSING);
        airTextures.put(BlockFace.UP, STONE_TOP);
        BlockDefinition air =
                definition(
                        0,
                        "gaia:air",
                        airTextures,
                        new ItemFormDefinition(AIR_ITEM, 64, false, false));
        BlockRegistry registry =
                BlockRegistry.create(
                        List.of(air),
                        Map.of(0, BlockRenderInfo.nonRenderable(MATERIAL, MISSING_REGION)));
        TextureAtlasMetadata atlas =
                new TextureAtlasMetadata(
                        ATLAS,
                        ResourceLocation.parse("gaia:textures/atlas.png"),
                        48,
                        16,
                        Map.of(
                                MISSING, MISSING_REGION,
                                STONE_SIDE, SIDE_REGION,
                                STONE_TOP, TOP_REGION));

        assertEquals(
                MISSING_REGION,
                new GaiaVisualRegionResolver(
                                registry,
                                atlas,
                                (item, cause) -> {})
                        .resolve(AIR_ITEM));
    }

    @Test
    void unknownItemFallsBackWithExactlyOneRequestedItemDiagnostic() {
        List<Diagnostic> diagnostics = new java.util.ArrayList<>();
        GaiaVisualRegionResolver resolver =
                resolver(STONE_TOP, (item, cause) -> diagnostics.add(new Diagnostic(item, cause)));

        assertEquals(MISSING_REGION, resolver.resolve(UNKNOWN_ITEM));

        assertDiagnostic(diagnostics, UNKNOWN_ITEM, "Unknown item");
    }

    @Test
    void nonRenderableOwningBlockFallsBackWithExactlyOneRequestedItemDiagnostic() {
        List<Diagnostic> diagnostics = new java.util.ArrayList<>();
        EnumMap<BlockFace, ResourceLocation> airTextures = textures(MISSING);
        airTextures.put(BlockFace.UP, STONE_TOP);
        BlockDefinition air =
                definition(
                        0,
                        "gaia:air",
                        airTextures,
                        new ItemFormDefinition(AIR_ITEM, 64, false, false));
        BlockRegistry registry = BlockRegistry.create(
                List.of(air),
                Map.of(0, BlockRenderInfo.nonRenderable(MATERIAL, MISSING_REGION)));
        GaiaVisualRegionResolver resolver = new GaiaVisualRegionResolver(
                registry,
                atlas(),
                (item, cause) -> diagnostics.add(new Diagnostic(item, cause)));

        assertEquals(MISSING_REGION, resolver.resolve(AIR_ITEM));

        assertDiagnostic(diagnostics, AIR_ITEM, "Non-renderable block");
    }

    @Test
    void missingUpTextureFallsBackWithExactlyOneRequestedItemDiagnostic() {
        List<Diagnostic> diagnostics = new java.util.ArrayList<>();
        EnumMap<BlockFace, ResourceLocation> stoneTextures = textures(STONE_SIDE);
        stoneTextures.remove(BlockFace.UP);
        BlockDefinition air = definition(0, "gaia:air", textures(MISSING), null);
        BlockDefinition stone = definition(
                1,
                "gaia:stone",
                stoneTextures,
                new ItemFormDefinition(STONE_ITEM, 64, false, false));
        BlockRegistry registry = BlockRegistry.create(
                List.of(air, stone),
                Map.of(
                        0, BlockRenderInfo.nonRenderable(MATERIAL, MISSING_REGION),
                        1, renderInfo(TOP_REGION)));
        GaiaVisualRegionResolver resolver = new GaiaVisualRegionResolver(
                registry,
                atlas(),
                (item, cause) -> diagnostics.add(new Diagnostic(item, cause)));

        assertEquals(MISSING_REGION, resolver.resolve(STONE_ITEM));

        assertDiagnostic(diagnostics, STONE_ITEM, "Missing UP texture");
    }

    @Test
    void missingAtlasRegionFallsBackWithExactlyOneRequestedItemDiagnostic() {
        List<Diagnostic> diagnostics = new java.util.ArrayList<>();
        GaiaVisualRegionResolver resolver = resolver(
                ResourceLocation.parse("gaia:not_in_atlas"),
                (item, cause) -> diagnostics.add(new Diagnostic(item, cause)));

        assertEquals(MISSING_REGION, resolver.resolve(STONE_ITEM));

        assertDiagnostic(diagnostics, STONE_ITEM, "Missing atlas region");
    }

    @Test
    void safeDiagnosticWrapperContainsSinkFailureAndPreservesItsCause() {
        RuntimeException diagnosticFailure = new IllegalStateException("diagnostic");
        Throwable[] reported = {null};
        GaiaVisualRegionResolver resolver = resolver(
                STONE_TOP,
                VisualRegionDiagnostics.safe((item, cause) -> {
                    reported[0] = cause;
                    throw diagnosticFailure;
                }));

        TextureRegion resolved = assertDoesNotThrow(() -> resolver.resolve(UNKNOWN_ITEM));

        assertEquals(MISSING_REGION, resolved);
        assertEquals(1, reported[0].getSuppressed().length);
        assertSame(diagnosticFailure, reported[0].getSuppressed()[0]);
    }

    private static GaiaVisualRegionResolver resolver(ResourceLocation stoneTop) {
        return resolver(stoneTop, (item, cause) -> {});
    }

    private static GaiaWorldItemFaceResolver faceResolver(ResourceLocation stoneTop) {
        EnumMap<BlockFace, ResourceLocation> stoneTextures = textures(STONE_SIDE);
        stoneTextures.put(BlockFace.UP, stoneTop);
        BlockDefinition air = definition(0, "gaia:air", textures(MISSING), null);
        BlockDefinition stone = definition(
                1,
                "gaia:stone",
                stoneTextures,
                new ItemFormDefinition(STONE_ITEM, 64, false, false));
        BlockRegistry registry = BlockRegistry.create(
                List.of(air, stone),
                Map.of(
                        0, BlockRenderInfo.nonRenderable(MATERIAL, MISSING_REGION),
                        1, renderInfo(TOP_REGION)));
        return new GaiaWorldItemFaceResolver(registry, MISSING_REGION, (item, cause) -> {});
    }

    private static GaiaVisualRegionResolver resolver(
            ResourceLocation stoneTop,
            VisualRegionDiagnostics diagnostics) {
        EnumMap<BlockFace, ResourceLocation> stoneTextures = textures(STONE_SIDE);
        stoneTextures.put(BlockFace.UP, stoneTop);
        BlockDefinition air = definition(0, "gaia:air", textures(MISSING), null);
        BlockDefinition stone =
                definition(
                        1,
                        "gaia:stone",
                        stoneTextures,
                        new ItemFormDefinition(STONE_ITEM, 64, false, false));
        BlockRegistry registry =
                BlockRegistry.create(
                        List.of(air, stone),
                        Map.of(
                                0, BlockRenderInfo.nonRenderable(MATERIAL, MISSING_REGION),
                                1, renderInfo(TOP_REGION)));
        return new GaiaVisualRegionResolver(registry, atlas(), diagnostics);
    }

    private static TextureAtlasMetadata atlas() {
        return new TextureAtlasMetadata(
                ATLAS,
                ResourceLocation.parse("gaia:textures/atlas.png"),
                48,
                16,
                Map.of(
                        MISSING, MISSING_REGION,
                        STONE_SIDE, SIDE_REGION,
                        STONE_TOP, TOP_REGION));
    }

    private static void assertDiagnostic(
            List<Diagnostic> diagnostics,
            ResourceLocation item,
            String causeFragment) {
        assertEquals(1, diagnostics.size());
        assertEquals(item, diagnostics.get(0).item());
        assertTrue(diagnostics.get(0).cause().getMessage().contains(causeFragment));
    }

    private static BlockDefinition definition(
            int id,
            String name,
            Map<BlockFace, ResourceLocation> textures,
            ItemFormDefinition item) {
        return new BlockDefinition(
                id,
                ResourceLocation.parse(name),
                MATERIAL.id(),
                textures,
                1.0f,
                1.0f,
                1.0f,
                false,
                false,
                1.0f,
                item);
    }

    private static EnumMap<BlockFace, ResourceLocation> textures(ResourceLocation region) {
        EnumMap<BlockFace, ResourceLocation> textures = new EnumMap<>(BlockFace.class);
        for (BlockFace face : BlockFace.values()) {
            textures.put(face, region);
        }
        return textures;
    }

    private static BlockRenderInfo renderInfo(TextureRegion top) {
        EnumMap<BlockFace, TextureRegion> regions = new EnumMap<>(BlockFace.class);
        for (BlockFace face : BlockFace.values()) {
            regions.put(face, face == BlockFace.UP ? top : SIDE_REGION);
        }
        return new BlockRenderInfo(MATERIAL, regions, true);
    }

    private record Diagnostic(ResourceLocation item, Throwable cause) {}
}

package com.gaia.interaction.feedback;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gaia.blocks.BlockDefinition;
import com.gaia.blocks.BlockRegistry;
import com.gaia.blocks.ItemFormDefinition;
import com.gaia.blocks.ItemVisualReference;
import com.gaia.blocks.ItemVisualType;
import com.gaia.blocks.StandaloneItemDefinition;
import com.overlord.assets.ResourceLocation;
import com.overlord.renderer.feedback.WorldItemFaceRegions;
import com.overlord.renderer.material.MaterialDefinition;
import com.overlord.renderer.material.RenderType;
import com.overlord.renderer.texture.TextureAtlasMetadata;
import com.overlord.renderer.texture.TextureRegion;
import com.overlord.voxel.BlockFace;
import com.overlord.voxel.BlockRenderInfo;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class GaiaWorldItemFaceResolverTest {
    private static final ResourceLocation ATLAS =
            ResourceLocation.parse("gaia:blocks");
    private static final ResourceLocation MISSING =
            ResourceLocation.parse("gaia:missing");
    private static final ResourceLocation SIDE =
            ResourceLocation.parse("gaia:stone_side");
    private static final ResourceLocation TOP =
            ResourceLocation.parse("gaia:stone_top");
    private static final ResourceLocation STANDALONE =
            ResourceLocation.parse("gaia:chisel");
    private static final ResourceLocation BLOCK_ITEM =
            ResourceLocation.parse("gaia:stone_item");
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
            new TextureRegion(SIDE, 16, 0, 16, 16, 48, 16);
    private static final TextureRegion TOP_REGION =
            new TextureRegion(TOP, 32, 0, 16, 16, 48, 16);

    @Test
    void standaloneAtlasVisualBecomesUniformWorldItemFaces() {
        BlockRegistry registry = registry();
        GaiaWorldItemFaceResolver resolver =
                new GaiaWorldItemFaceResolver(
                        registry, atlas(), (item, cause) -> {});

        WorldItemFaceRegions faces = resolver.resolve(STANDALONE);

        for (BlockFace face : BlockFace.values()) {
            assertEquals(TOP_REGION, faces.region(face));
        }
        assertTrue(registry.blockForItem(STANDALONE).isEmpty());
    }

    @Test
    void blockBackedItemKeepsItsAsymmetricCubeFaces() {
        WorldItemFaceRegions faces =
                new GaiaWorldItemFaceResolver(
                                registry(), atlas(), (item, cause) -> {})
                        .resolve(BLOCK_ITEM);

        for (BlockFace face : BlockFace.values()) {
            assertEquals(
                    face == BlockFace.UP ? TOP_REGION : SIDE_REGION,
                    faces.region(face));
        }
    }

    private static BlockRegistry registry() {
        BlockDefinition air =
                block(0, "gaia:air", null);
        BlockDefinition stone =
                block(
                        1,
                        "gaia:stone",
                        new ItemFormDefinition(
                                BLOCK_ITEM, 64, false, false));
        StandaloneItemDefinition standalone =
                new StandaloneItemDefinition(
                        new ItemFormDefinition(
                                STANDALONE, 1, false, false),
                        Set.of(),
                        new ItemVisualReference(
                                ItemVisualType.ATLAS_REGION,
                                ATLAS,
                                TOP));
        return BlockRegistry.create(
                List.of(air, stone),
                List.of(standalone),
                Map.of(
                        0,
                        BlockRenderInfo.nonRenderable(
                                MATERIAL, MISSING_REGION),
                        1,
                        renderInfo()));
    }

    private static BlockDefinition block(
            int id, String name, ItemFormDefinition item) {
        EnumMap<BlockFace, ResourceLocation> textures =
                new EnumMap<>(BlockFace.class);
        for (BlockFace face : BlockFace.values()) {
            textures.put(face, face == BlockFace.UP ? TOP : SIDE);
        }
        return new BlockDefinition(
                id,
                ResourceLocation.parse(name),
                MATERIAL.id(),
                textures,
                id == 0 ? 0.0f : 1.0f,
                id == 0 ? 0.0f : 1.0f,
                id == 0 ? 0.0f : 1.0f,
                false,
                false,
                id == 0 ? 0.0f : 1.0f,
                item);
    }

    private static BlockRenderInfo renderInfo() {
        EnumMap<BlockFace, TextureRegion> regions =
                new EnumMap<>(BlockFace.class);
        for (BlockFace face : BlockFace.values()) {
            regions.put(
                    face,
                    face == BlockFace.UP ? TOP_REGION : SIDE_REGION);
        }
        return new BlockRenderInfo(MATERIAL, regions, true);
    }

    private static TextureAtlasMetadata atlas() {
        return new TextureAtlasMetadata(
                ATLAS,
                ResourceLocation.parse("gaia:textures/atlas.png"),
                48,
                16,
                Map.of(
                        MISSING, MISSING_REGION,
                        SIDE, SIDE_REGION,
                        TOP, TOP_REGION));
    }
}

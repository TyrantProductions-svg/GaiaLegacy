package com.gaia.blocks;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.overlord.assets.ResourceLocation;
import com.overlord.renderer.material.MaterialDefinition;
import com.overlord.renderer.material.RenderType;
import com.overlord.renderer.texture.TextureRegion;
import com.overlord.voxel.BlockFace;
import com.overlord.voxel.BlockRenderInfo;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BlockRegistryTest {
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
    void indexesDefinitionsByNameAndUnsignedId() {
        BlockDefinition air = definition(0, "gaia:air");
        BlockDefinition high = definition(200, "gaia:high");
        BlockRegistry registry =
                BlockRegistry.create(
                        List.of(air, high),
                        Map.of(
                                0, renderInfo(false),
                                200, renderInfo(true)));

        assertAll(
                () ->
                        assertEquals(
                                ResourceLocation.parse("gaia:high"),
                                registry.require((byte) 200).name()),
                () -> assertSame(high, registry.require(200)),
                () ->
                        assertEquals(
                                (byte) 200,
                                registry.requireStoredId(
                                        ResourceLocation.parse(
                                                "gaia:high"))),
                () -> assertTrue(registry.resolve(200).renderable()));
    }

    @Test
    void rejectsDuplicateNumericAndLogicalIds() {
        assertAll(
                () ->
                        assertThrows(
                                IllegalArgumentException.class,
                                () ->
                                        BlockRegistry.create(
                                                List.of(
                                                        definition(
                                                                1,
                                                                "gaia:first"),
                                                        definition(
                                                                1,
                                                                "gaia:second")),
                                                Map.of())),
                () ->
                        assertThrows(
                                IllegalArgumentException.class,
                                () ->
                                        BlockRegistry.create(
                                                List.of(
                                                        definition(
                                                                1,
                                                                "gaia:same"),
                                                        definition(
                                                                2,
                                                                "gaia:same")),
                                                Map.of())));
    }

    @Test
    void requiresIdZeroAirDefinition() {
        IllegalArgumentException error =
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                BlockRegistry.create(
                                        List.of(
                                                definition(
                                                        1,
                                                        "gaia:stone")),
                                        Map.of(1, renderInfo(true))));

        assertTrue(error.getMessage().contains("id 0 air"));
    }

    @Test
    void requiresRenderInfoForEveryAndOnlyRegisteredDefinition() {
        BlockDefinition air = definition(0, "gaia:air");
        BlockDefinition stone = definition(1, "gaia:stone");

        assertAll(
                () ->
                        assertThrows(
                                IllegalArgumentException.class,
                                () ->
                                        BlockRegistry.create(
                                                List.of(air, stone),
                                                Map.of(
                                                        0,
                                                        renderInfo(
                                                                false)))),
                () ->
                        assertThrows(
                                IllegalArgumentException.class,
                                () ->
                                        BlockRegistry.create(
                                                List.of(air),
                                                Map.of(
                                                        0,
                                                        renderInfo(false),
                                                        99,
                                                        renderInfo(true)))));
    }

    @Test
    void resolvesUnknownIdAsAirAndReportsUnsignedMissingId() {
        BlockRenderInfo airRenderInfo = renderInfo(false);
        BlockRegistry registry =
                BlockRegistry.create(
                        List.of(definition(0, "gaia:air")),
                        Map.of(0, airRenderInfo));

        IllegalArgumentException error =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> registry.require((byte) 255));

        assertAll(
                () -> assertSame(airRenderInfo, registry.resolve(255)),
                () -> assertSame(airRenderInfo, registry.resolve(256)),
                () -> assertTrue(error.getMessage().contains("255")));
    }

    @Test
    void defensivelyCopiesDefinitionTexturesAndRegistryInputs() {
        EnumMap<BlockFace, ResourceLocation> textures = textures();
        BlockDefinition air =
                new BlockDefinition(
                        0,
                        ResourceLocation.parse("gaia:air"),
                        MATERIAL.id(),
                        textures,
                        0.0f,
                        0.0f,
                        0.0f,
                        false,
                        false,
                        0.0f,
                        null);
        ArrayList<BlockDefinition> definitions =
                new ArrayList<>(
                        List.of(
                                air,
                                definition(
                                        1,
                                        "gaia:stone")));
        BlockRenderInfo stoneRenderInfo = renderInfo(true);
        HashMap<Integer, BlockRenderInfo> renderInfos =
                new HashMap<>(
                        Map.of(
                                0, renderInfo(false),
                                1, stoneRenderInfo));
        BlockRegistry registry =
                BlockRegistry.create(definitions, renderInfos);

        textures.put(
                BlockFace.NORTH,
                ResourceLocation.parse("gaia:changed"));
        definitions.clear();
        renderInfos.clear();

        assertAll(
                () ->
                        assertEquals(
                                MISSING,
                                air.textures().get(BlockFace.NORTH)),
                () ->
                        assertThrows(
                                UnsupportedOperationException.class,
                                () ->
                                        air.textures()
                                                .put(
                                                        BlockFace.NORTH,
                                                        MISSING)),
                () -> assertSame(air, registry.require(0)),
                () -> assertFalse(registry.resolve(0).renderable()),
                () -> assertSame(stoneRenderInfo, registry.resolve(1)));
    }

    @Test
    void rejectsOutOfRangeIdsAndInvalidPhysicalValues() {
        assertAll(
                () ->
                        assertThrows(
                                IllegalArgumentException.class,
                                () -> definition(-1, "gaia:negative")),
                () ->
                        assertThrows(
                                IllegalArgumentException.class,
                                () -> definition(256, "gaia:overflow")),
                () ->
                        assertThrows(
                                IllegalArgumentException.class,
                                () ->
                                        definitionWithPhysicalValues(
                                                -0.1f,
                                                1.0f,
                                                1.0f,
                                                1.0f)),
                () ->
                        assertThrows(
                                IllegalArgumentException.class,
                                () ->
                                        definitionWithPhysicalValues(
                                                1.0f,
                                                Float.NaN,
                                                1.0f,
                                                1.0f)),
                () ->
                        assertThrows(
                                IllegalArgumentException.class,
                                () ->
                                        definitionWithPhysicalValues(
                                                1.0f,
                                                1.0f,
                                                Float.POSITIVE_INFINITY,
                                                1.0f)),
                () ->
                        assertThrows(
                                IllegalArgumentException.class,
                                () ->
                                        definitionWithPhysicalValues(
                                                1.0f,
                                                1.0f,
                                                1.0f,
                                                Float.NEGATIVE_INFINITY)));
    }

    @Test
    void validatesItemFormStackSize() {
        ResourceLocation itemId =
                ResourceLocation.parse("gaia:stone");

        assertAll(
                () ->
                        assertThrows(
                                IllegalArgumentException.class,
                                () ->
                                        new ItemFormDefinition(
                                                itemId,
                                                0,
                                                false,
                                                false)),
                () ->
                        assertThrows(
                                IllegalArgumentException.class,
                                () ->
                                        new ItemFormDefinition(
                                                itemId,
                                                65,
                                                false,
                                                false)));
    }

    private static BlockDefinition definition(int id, String name) {
        return new BlockDefinition(
                id,
                ResourceLocation.parse(name),
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
                                ResourceLocation.parse(name),
                                64,
                                false,
                                false));
    }

    private static BlockDefinition definitionWithPhysicalValues(
            float hardness,
            float structuralIntegrity,
            float tolerance,
            float blastResistance) {
        return new BlockDefinition(
                1,
                ResourceLocation.parse("gaia:test"),
                MATERIAL.id(),
                textures(),
                hardness,
                structuralIntegrity,
                tolerance,
                false,
                false,
                blastResistance,
                new ItemFormDefinition(
                        ResourceLocation.parse("gaia:test"),
                        64,
                        false,
                        false));
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

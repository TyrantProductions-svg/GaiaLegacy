package com.gaia.interaction;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gaia.blocks.BlockDefinition;
import com.gaia.blocks.BlockRegistry;
import com.gaia.blocks.DetailSupportDefinition;
import com.gaia.blocks.ItemFormDefinition;
import com.gaia.blocks.ItemVisualReference;
import com.gaia.blocks.ItemVisualType;
import com.gaia.blocks.StandaloneItemDefinition;
import com.overlord.assets.ResourceLocation;
import com.overlord.renderer.material.MaterialDefinition;
import com.overlord.renderer.material.RenderType;
import com.overlord.renderer.texture.TextureRegion;
import com.overlord.voxel.BlockFace;
import com.overlord.voxel.BlockRenderInfo;
import com.overlord.voxel.DetailCellState;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DetailCoarseHardnessTest {
    private static final byte DIRT = 2;
    private static final byte STONE = 3;

    @Test
    void usesMaximumOccupiedCanonicalHardnessWithoutOccupancyScaling() {
        Fixture fixture = fixture();
        DetailParentComposition dirt = composition(fixture.registry, new byte[] {DIRT});
        DetailParentComposition stone = composition(fixture.registry, new byte[] {STONE});
        DetailParentComposition mixed = composition(
                fixture.registry, new byte[] {DIRT, DIRT, STONE});
        DetailParentComposition fullStone = DetailParentComposition.from(
                DetailCellState.uniform(STONE), fixture.registry);

        assertAll(
                () -> assertEquals(0.5f, DetailCoarseHardness.resolve(dirt), 0.0f),
                () -> assertEquals(1.5f, DetailCoarseHardness.resolve(stone), 0.0f),
                () -> assertEquals(1.5f, DetailCoarseHardness.resolve(mixed), 0.0f),
                () -> assertEquals(
                        DetailCoarseHardness.resolve(stone),
                        DetailCoarseHardness.resolve(fullStone),
                        0.0f),
                () -> assertSame(fixture.stone, mixed.hardestMaterial()),
                () -> assertTrue(mixed.uniformMaterial().isEmpty()),
                () -> assertTrue(fullStone.fullCompatible()));
    }

    @Test
    void canonicalIndexScanIsIndependentOfMaterialInsertionOrder() {
        Fixture fixture = fixture();
        DetailParentComposition first = composition(
                fixture.registry, new byte[] {DIRT, STONE, DIRT, STONE});
        DetailParentComposition second = composition(
                fixture.registry, new byte[] {STONE, DIRT, STONE, DIRT});

        assertAll(
                () -> assertEquals(first.occupiedCount(), second.occupiedCount()),
                () -> assertEquals(
                        DetailCoarseHardness.resolve(first),
                        DetailCoarseHardness.resolve(second),
                        0.0f),
                () -> assertEquals(
                        first.hardestMaterial().name(),
                        second.hardestMaterial().name()));
    }

    @Test
    void unknownOccupiedRuntimeMaterialFailsClosed() {
        Fixture fixture = fixture();

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> composition(fixture.registry, new byte[] {(byte) 99}));

        assertTrue(failure.getMessage().contains("Unknown occupied detail material"));
    }

    private static DetailParentComposition composition(
            BlockRegistry registry, byte[] occupied) {
        byte[] ids = new byte[DetailCellState.CELL_COUNT];
        long mask = 0L;
        for (int index = 0; index < occupied.length; index++) {
            ids[index] = occupied[index];
            mask |= 1L << index;
        }
        return DetailParentComposition.from(new DetailCellState(mask, ids), registry);
    }

    private static Fixture fixture() {
        BlockDefinition air = block(0, "gaia:air", 0.0f, null);
        BlockDefinition dirt = block(
                Byte.toUnsignedInt(DIRT),
                "gaia:dirt",
                0.5f,
                ResourceLocation.parse("gaia:dirt_detail_unit"));
        BlockDefinition stone = block(
                Byte.toUnsignedInt(STONE),
                "gaia:stone",
                1.5f,
                ResourceLocation.parse("gaia:stone_detail_unit"));
        BlockRegistry registry = BlockRegistry.create(
                List.of(air, dirt, stone),
                List.of(
                        unit("gaia:dirt_detail_unit", "gaia:dirt"),
                        unit("gaia:stone_detail_unit", "gaia:stone")),
                Map.of(
                        0, renderInfo(false),
                        2, renderInfo(true),
                        3, renderInfo(true)));
        return new Fixture(registry, stone);
    }

    private static BlockDefinition block(
            int id, String name, float hardness, ResourceLocation unit) {
        ResourceLocation blockId = ResourceLocation.parse(name);
        return new BlockDefinition(
                id,
                blockId,
                ResourceLocation.parse("gaia:opaque"),
                textures(),
                hardness,
                1.0f,
                1.0f,
                false,
                false,
                1.0f,
                id == 0 ? null : new ItemFormDefinition(blockId, 64, false, false),
                unit == null ? null : new DetailSupportDefinition(unit));
    }

    private static StandaloneItemDefinition unit(String id, String region) {
        ResourceLocation itemId = ResourceLocation.parse(id);
        return new StandaloneItemDefinition(
                new ItemFormDefinition(itemId, 64, false, false),
                Set.of(),
                new ItemVisualReference(
                        ItemVisualType.ATLAS_REGION,
                        ResourceLocation.parse("gaia:blocks"),
                        ResourceLocation.parse(region)));
    }

    private static EnumMap<BlockFace, ResourceLocation> textures() {
        EnumMap<BlockFace, ResourceLocation> result = new EnumMap<>(BlockFace.class);
        for (BlockFace face : BlockFace.values()) {
            result.put(face, ResourceLocation.parse("gaia:stone"));
        }
        return result;
    }

    private static BlockRenderInfo renderInfo(boolean renderable) {
        MaterialDefinition material = new MaterialDefinition(
                ResourceLocation.parse("gaia:opaque"),
                ResourceLocation.parse("gaia:blocks"),
                RenderType.OPAQUE,
                0.5f,
                ResourceLocation.parse("gaia:stone"));
        TextureRegion region = new TextureRegion(
                ResourceLocation.parse("gaia:stone"), 0, 0, 1, 1, 1, 1);
        EnumMap<BlockFace, TextureRegion> faces = new EnumMap<>(BlockFace.class);
        for (BlockFace face : BlockFace.values()) {
            faces.put(face, region);
        }
        return renderable
                ? new BlockRenderInfo(material, faces, true)
                : BlockRenderInfo.nonRenderable(material, region);
    }

    private record Fixture(BlockRegistry registry, BlockDefinition stone) {}
}

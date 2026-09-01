package com.gaia.interaction;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gaia.blocks.BlockDefinition;
import com.gaia.blocks.BlockRegistry;
import com.gaia.blocks.DetailSupportDefinition;
import com.gaia.blocks.ItemCapability;
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
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DetailActionPolicyTest {
    private static final ResourceLocation CHISEL = ResourceLocation.parse("gaia:chisel");
    private static final ResourceLocation STONE = ResourceLocation.parse("gaia:stone");
    private static final ResourceLocation STONE_UNIT =
            ResourceLocation.parse("gaia:stone_detail_unit");

    @Test
    void precisionRecoveryRequiresCapabilityAndSupportedMaterial() {
        Fixture fixture = fixture();

        DetailActionDecision allowed = fixture.policy.decide(
                GameMode.SURVIVAL,
                DetailAction.PRECISION_REMOVE,
                Optional.of(CHISEL),
                fixture.stone,
                false);
        DetailActionDecision wrongTool = fixture.policy.decide(
                GameMode.SURVIVAL,
                DetailAction.PRECISION_REMOVE,
                Optional.of(STONE),
                fixture.stone,
                false);
        DetailActionDecision unsupported = fixture.policy.decide(
                GameMode.SURVIVAL,
                DetailAction.PRECISION_REMOVE,
                Optional.of(CHISEL),
                fixture.unsupported,
                false);

        assertAll(
                () -> assertTrue(allowed.allowed()),
                () -> assertEquals(DetailRecoveryKind.DETAIL_UNIT, allowed.recoveryKind()),
                () -> assertEquals(STONE_UNIT, allowed.outputItem().orElseThrow()),
                () -> assertFalse(wrongTool.allowed()),
                () -> assertEquals("precision_tool_required", wrongTool.reason()),
                () -> assertFalse(unsupported.allowed()),
                () -> assertEquals("unsupported_material", unsupported.reason()));
    }

    @Test
    void creativePrecisionIsAllowedWithoutRecovery() {
        Fixture fixture = fixture();

        DetailActionDecision decision = fixture.policy.decide(
                GameMode.CREATIVE,
                DetailAction.PRECISION_PLACE,
                Optional.of(CHISEL),
                fixture.stone,
                false);

        assertAll(
                () -> assertTrue(decision.allowed()),
                () -> assertEquals(DetailRecoveryKind.NONE, decision.recoveryKind()),
                () -> assertTrue(decision.outputItem().isEmpty()));
    }

    @Test
    void coarseMatrixProducesOneFullItemOnlyForUniformCompatibleSurvival() {
        Fixture fixture = fixture();

        DetailActionDecision creative = fixture.policy.decide(
                GameMode.CREATIVE,
                DetailAction.COARSE_REMOVE,
                Optional.empty(),
                fixture.stone,
                true);
        DetailActionDecision uniform = fixture.policy.decide(
                GameMode.SURVIVAL,
                DetailAction.COARSE_REMOVE,
                Optional.empty(),
                fixture.stone,
                true);
        DetailActionDecision partial = fixture.policy.decide(
                GameMode.SURVIVAL,
                DetailAction.COARSE_REMOVE,
                Optional.empty(),
                fixture.stone,
                false);

        assertAll(
                () -> assertEquals(DetailRecoveryKind.NONE, creative.recoveryKind()),
                () -> assertEquals(DetailRecoveryKind.FULL_BLOCK, uniform.recoveryKind()),
                () -> assertEquals(STONE, uniform.outputItem().orElseThrow()),
                () -> assertEquals(DetailRecoveryKind.NONE, partial.recoveryKind()),
                () -> assertTrue(partial.outputItem().isEmpty()));
    }

    private static Fixture fixture() {
        BlockDefinition air = block(0, "gaia:air", null);
        BlockDefinition stone = block(1, "gaia:stone", STONE_UNIT);
        BlockDefinition unsupported = block(2, "gaia:grass", null);
        BlockRegistry registry = BlockRegistry.create(
                List.of(air, stone, unsupported),
                List.of(
                        standalone(CHISEL, 1, Set.of(ItemCapability.DETAIL_PRECISION)),
                        standalone(STONE_UNIT, 64, Set.of())),
                Map.of(
                        0, renderInfo(false),
                        1, renderInfo(true),
                        2, renderInfo(true)));
        return new Fixture(
                stone,
                unsupported,
                new Phase17DetailActionPolicy(registry));
    }

    private static BlockDefinition block(
            int id, String name, ResourceLocation detailUnit) {
        ResourceLocation blockId = ResourceLocation.parse(name);
        return new BlockDefinition(
                id,
                blockId,
                ResourceLocation.parse("gaia:opaque"),
                textures(),
                id == 0 ? 0.0f : 1.5f,
                1.0f,
                1.0f,
                false,
                false,
                1.0f,
                id == 0 ? null : new ItemFormDefinition(blockId, 64, false, false),
                detailUnit == null ? null : new DetailSupportDefinition(detailUnit));
    }

    private static StandaloneItemDefinition standalone(
            ResourceLocation id, int maxStack, Set<ItemCapability> capabilities) {
        return new StandaloneItemDefinition(
                new ItemFormDefinition(id, maxStack, false, false),
                capabilities,
                new ItemVisualReference(
                        ItemVisualType.ATLAS_REGION,
                        ResourceLocation.parse("gaia:blocks"),
                        ResourceLocation.parse("gaia:stone")));
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

    private record Fixture(
            BlockDefinition stone,
            BlockDefinition unsupported,
            DetailActionPolicy policy) {}
}

package com.gaia.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gaia.blocks.ItemFormDefinition;
import com.gaia.interaction.BlockInteractionRoute;
import com.gaia.interaction.BlockInteractionRouteDecision;
import com.gaia.interaction.BlockInteractionSnapshot;
import com.gaia.interaction.DetailPlacementCandidate;
import com.gaia.interaction.DetailPlacementPreview;
import com.gaia.interaction.DetailPrecisionTarget;
import com.gaia.interaction.GameMode;
import com.overlord.assets.ResourceLocation;
import com.overlord.core.input.InputSnapshot;
import com.overlord.interaction.api.BlockFace;
import com.overlord.interaction.api.BlockHitResult;
import com.overlord.interaction.api.EntityRef;
import com.overlord.interaction.api.InteractionFailureReason;
import com.overlord.interaction.api.InteractionMode;
import com.overlord.inventory.api.BodyInventoryViewModel;
import com.overlord.inventory.api.BodySlot;
import com.overlord.inventory.api.InventoryView;
import com.overlord.inventory.api.ItemStack;
import com.overlord.inventory.api.ItemStackView;
import com.overlord.physics.FullRaycastTarget;
import com.overlord.voxel.ChunkKey;
import com.overlord.voxel.FullCellState;
import com.overlord.voxel.LocalSubVoxelPosition;
import com.overlord.voxel.ParentCellObservation;
import com.overlord.voxel.ParentCellObservationResult;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DetailHudPresentationTest {
    private static final ResourceLocation CHISEL = ResourceLocation.parse("gaia:chisel");
    private static final ResourceLocation STONE = ResourceLocation.parse("gaia:stone");
    private static final ResourceLocation STONE_UNIT =
            ResourceLocation.parse("gaia:stone_detail_unit");

    @Test
    void projectsCurrentSurvivalPrecisionStateWithoutHistoryOrAuthority() {
        DetailPlacementPreview preview = placementPreview();
        BlockInteractionSnapshot interaction = new BlockInteractionSnapshot(
                Optional.of(hit()), Optional.of(BlockFace.EAST), 0, InteractionMode.NONE,
                Optional.of(new ItemStack(CHISEL, 1)),
                Optional.of(new InteractionFailureReason(
                        ResourceLocation.parse("gaia:interaction/detail_inventory_full"))),
                0, GameMode.SURVIVAL,
                BlockInteractionRouteDecision.routed(BlockInteractionRoute.DETAIL_PRECISION_PLACE),
                Optional.of(preview), Optional.of(STONE), OptionalInt.of(7));

        HudPresentationSnapshot snapshot = presenter().capture(frame(interaction, true));
        HudPresentationSnapshot.DetailToolPresentation detail = snapshot.detailTool();

        assertEquals(HudPresentationSnapshot.DetailToolMode.PRECISION_PLACE, detail.mode());
        assertEquals(Optional.of(STONE), detail.selectedMaterial());
        assertEquals(OptionalInt.of(7), detail.availableUnits());
        assertEquals(Optional.of(new LocalSubVoxelPosition(2, 1, 3)), detail.localTarget());
        assertEquals(Optional.of(preview.validity()), detail.previewValidity());
        assertEquals(Optional.of("occupied"), detail.previewReason());
        assertEquals(interaction.failureReason(), detail.latestFailure());
        assertTrue(HudPresentationSnapshot.DetailToolPresentation.class.getRecordComponents().length <= 7);
    }

    @Test
    void creativeOmitsInventoryQuantityAndFocusLossClearsTransientDetailProjection() {
        BlockInteractionSnapshot interaction = new BlockInteractionSnapshot(
                Optional.of(hit()), Optional.of(BlockFace.EAST), 0, InteractionMode.NONE,
                Optional.of(new ItemStack(CHISEL, 1)), Optional.empty(), 0, GameMode.CREATIVE,
                BlockInteractionRouteDecision.routed(BlockInteractionRoute.DETAIL_PRECISION_PLACE),
                Optional.of(placementPreview()), Optional.of(STONE), OptionalInt.empty());

        HudPresentationSnapshot visible = presenter().capture(frame(interaction, true));
        HudPresentationSnapshot hidden = presenter().capture(frame(interaction, false));

        assertTrue(visible.detailTool().active());
        assertTrue(visible.detailTool().availableUnits().isEmpty());
        assertEquals(HudPresentationSnapshot.DetailToolPresentation.cleared(), hidden.detailTool());
    }

    private static HudPresenter presenter() {
        return new HudPresenter(Map.of(
                CHISEL, new ItemFormDefinition(CHISEL, 1, false, false),
                STONE_UNIT, new ItemFormDefinition(STONE_UNIT, 64, false, false)));
    }

    private static HudPresenter.FrameInput frame(
            BlockInteractionSnapshot interaction, boolean focused) {
        Inventory inventory = new Inventory();
        inventory.put(BodySlot.LEFT_HAND, new ItemStack(CHISEL, 1));
        return new HudPresenter.FrameInput(
                inventory, interaction, Optional.empty(),
                new HudDebugSnapshot.FeetPosition(0, 0, 0),
                new HudDebugSnapshot.Counts(0, 0, 0, 0, 0, 0),
                new InputSnapshot(Set.of(), Set.of()), 1, true, 0,
                HudVisibility.Lifecycle.RUNNING, focused, focused, false);
    }

    private static DetailPlacementPreview placementPreview() {
        DetailPrecisionTarget source = new DetailPrecisionTarget(
                1, 2, 3, new LocalSubVoxelPosition(1, 1, 3), BlockFace.EAST,
                STONE, 17, FullRaycastTarget.INSTANCE);
        ParentCellObservation observation = new ParentCellObservation(
                new ChunkKey(0, 0), 2, 3, 3, 17, new FullCellState((byte) 1));
        DetailPlacementCandidate candidate = new DetailPlacementCandidate(
                source, 2, 2, 3, new LocalSubVoxelPosition(2, 1, 3), STONE,
                ParentCellObservationResult.available(observation),
                DetailPlacementCandidate.Status.OCCUPIED);
        return DetailPlacementPreview.forPlacement(CHISEL, candidate);
    }

    private static BlockHitResult hit() {
        return new BlockHitResult(
                1, 2, 3, 2, 2, 3, STONE, 1, 0, 0,
                2.0f, 2.5f, 3.5f, 1.0f,
                2.0, 2.5, 3.5, 17, FullRaycastTarget.INSTANCE);
    }

    private static final class Inventory implements BodyInventoryViewModel, InventoryView {
        private final EnumMap<BodySlot, ItemStackView> stacks = new EnumMap<>(BodySlot.class);

        void put(BodySlot slot, ItemStackView stack) {
            stacks.put(slot, stack);
        }

        @Override public EntityRef owner() { return new EntityRef(1); }
        @Override public BodySlot activeSlot() { return BodySlot.LEFT_HAND; }
        @Override public InventoryView inventory() { return this; }
        @Override public long revision() { return 0; }
        @Override public Optional<ItemStackView> stack(BodySlot slot) {
            return Optional.ofNullable(stacks.get(slot));
        }
    }
}

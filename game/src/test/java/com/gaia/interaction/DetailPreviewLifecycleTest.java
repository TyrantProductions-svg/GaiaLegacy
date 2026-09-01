package com.gaia.interaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.overlord.assets.ResourceLocation;
import com.overlord.interaction.api.BlockFace;
import com.overlord.physics.Aabb;
import com.overlord.physics.FullRaycastTarget;
import com.overlord.voxel.LocalSubVoxelPosition;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class DetailPreviewLifecycleTest {
    private static final ResourceLocation CHISEL = ResourceLocation.parse("gaia:chisel");
    private static final ResourceLocation STONE = ResourceLocation.parse("gaia:stone");
    private static final ResourceLocation DIRT = ResourceLocation.parse("gaia:dirt");

    @Test
    void ownsOnlyOneCurrentPreviewAndNeverRetainsHistory() {
        DetailPreviewController controller = new DetailPreviewController();
        DetailPlacementPreview first = preview(3L, STONE);
        DetailPlacementPreview replacement = preview(4L, DIRT);

        controller.publish(first);
        controller.publish(replacement);

        assertEquals(Optional.of(replacement), controller.current());
        controller.clear();
        assertTrue(controller.current().isEmpty());
    }

    @Test
    void eligibilityFocusAndLoadTransitionsClearImmediately() {
        DetailPreviewController controller = new DetailPreviewController();
        controller.publish(preview(3L, STONE));
        controller.onEligibilityChanged(false);
        assertTrue(controller.current().isEmpty());

        controller.publish(preview(3L, STONE));
        controller.onFocusLost();
        assertTrue(controller.current().isEmpty());

        controller.publish(preview(3L, STONE));
        controller.onSessionTransition();
        assertTrue(controller.current().isEmpty());
    }

    @Test
    void materialSelectionCyclesOnlyOnPressedEdgeWhilePrecisionIsActive() {
        DetailMaterialSelection selection = new DetailMaterialSelection(STONE, DIRT);

        assertFalse(selection.handleCycle(false, true));
        assertFalse(selection.handleCycle(true, false));
        assertEquals(STONE, selection.selected());
        assertTrue(selection.handleCycle(true, true));
        assertEquals(DIRT, selection.selected());
        assertTrue(selection.handleCycle(true, true));
        assertEquals(STONE, selection.selected());
    }

    private static DetailPlacementPreview preview(long revision, ResourceLocation material) {
        DetailPrecisionTarget source = new DetailPrecisionTarget(
                1, 2, 3, new LocalSubVoxelPosition(1, 1, 1), BlockFace.UP,
                material, revision, FullRaycastTarget.INSTANCE);
        return new DetailPlacementPreview(
                BlockInteractionRoute.DETAIL_PRECISION_REMOVE,
                CHISEL,
                source,
                1, 2, 3,
                source.localPosition(),
                BlockFace.UP,
                material,
                revision,
                DetailPreviewValidity.VALID,
                Optional.empty(),
                new Aabb(.25f, .25f, .25f, .5f, .5f, .5f));
    }
}

package com.gaia.shell.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER;
import static org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LEFT;

import com.gaia.save.format.SaveGameId;
import com.gaia.shell.ScreenCommand;
import com.overlord.core.input.UiInputSnapshot;
import com.overlord.renderer.RenderSurfaceMetrics;
import com.overlord.renderer.ui.UiFrame;
import com.overlord.renderer.ui.UiLayoutContext;
import com.overlord.renderer.ui.UiRect;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DynamicSaveControlInputTest {
    private static final SaveGameId SAVE_A = SaveGameId.parse(
            "00000000-0000-0000-0000-00000000000a");
    private static final SaveGameId SAVE_B = SaveGameId.parse(
            "00000000-0000-0000-0000-00000000000b");
    private static final UiLayoutContext CONTEXT = new UiLayoutContext(
            new RenderSurfaceMetrics(200, 120, 200, 120, 1.0f, 1.0f));

    @Test
    void pointerActivationReturnsTheCommandCarriedByTheDynamicRegion() {
        ProductScreenInputController controller = new ProductScreenInputController();
        ProductUiLayout layout = layout(List.of(SAVE_A, SAVE_B));
        UiHitRegion rowB = layout.region(
                new WorldSlotControlId(SAVE_B, WorldSlotControlId.WorldSlotAction.LOAD));

        assertEquals(
                new ScreenCommand.LoadWorld(SAVE_B),
                controller.route(
                                clickAt(rowB.centerX(), rowB.centerY(), 1L),
                                layout)
                        .orElseThrow());
        assertEquals(
                new WorldSlotControlId(
                        SAVE_B, WorldSlotControlId.WorldSlotAction.LOAD),
                controller.highlightedControl().orElseThrow());
    }

    @Test
    void focusFollowsStableControlIdentityWhenRowsReorder() {
        ProductScreenInputController controller = new ProductScreenInputController();
        ProductUiLayout first = layout(List.of(SAVE_A, SAVE_B));
        UiHitRegion rowB = first.region(
                new WorldSlotControlId(SAVE_B, WorldSlotControlId.WorldSlotAction.LOAD));
        assertTrue(controller.route(
                pointerAt(rowB.centerX(), rowB.centerY(), 1L), first).isEmpty());

        ProductUiLayout reordered = layout(List.of(SAVE_B, SAVE_A));

        assertEquals(
                new ScreenCommand.LoadWorld(SAVE_B),
                controller.route(keyAt(GLFW_KEY_ENTER, 2L), reordered).orElseThrow());
        assertEquals(
                new WorldSlotControlId(
                        SAVE_B, WorldSlotControlId.WorldSlotAction.LOAD),
                controller.highlightedControl().orElseThrow());
    }

    @Test
    void removedFocusedRowClearsInsteadOfActivatingItsOldIndex() {
        ProductScreenInputController controller = new ProductScreenInputController();
        ProductUiLayout first = layout(List.of(SAVE_A, SAVE_B));
        UiHitRegion rowB = first.region(
                new WorldSlotControlId(SAVE_B, WorldSlotControlId.WorldSlotAction.LOAD));
        controller.route(pointerAt(rowB.centerX(), rowB.centerY(), 1L), first);

        ProductUiLayout onlyA = layout(List.of(SAVE_A));

        assertTrue(controller.route(keyAt(GLFW_KEY_ENTER, 2L), onlyA).isEmpty());
        assertTrue(controller.highlightedControl().isEmpty());
    }

    @Test
    void disabledDynamicRegionCannotFocusOrActivate() {
        WorldSlotControlId id = new WorldSlotControlId(
                SAVE_A, WorldSlotControlId.WorldSlotAction.LOAD);
        UiHitRegion disabled = region(id, 10.0d, false);
        ProductUiLayout layout = new ProductUiLayout(
                UiFrame.empty(), List.of(disabled), CONTEXT);
        ProductScreenInputController controller = new ProductScreenInputController();

        assertTrue(controller.route(
                clickAt(disabled.centerX(), disabled.centerY(), 1L), layout).isEmpty());
        assertTrue(controller.highlightedControl().isEmpty());
    }

    private static ProductUiLayout layout(List<SaveGameId> ids) {
        java.util.ArrayList<UiHitRegion> regions = new java.util.ArrayList<>();
        for (int index = 0; index < ids.size(); index++) {
            WorldSlotControlId id = new WorldSlotControlId(
                    ids.get(index), WorldSlotControlId.WorldSlotAction.LOAD);
            regions.add(region(id, 10.0d + 30.0d * index, true));
        }
        return new ProductUiLayout(UiFrame.empty(), regions, CONTEXT);
    }

    private static UiHitRegion region(
            WorldSlotControlId id,
            double top,
            boolean enabled) {
        return new UiHitRegion(
                id,
                new ScreenCommand.LoadWorld(id.saveGameId()),
                new UiRect(10.0d, top, 190.0d, top + 20.0d),
                CONTEXT.safeArea(),
                enabled,
                1.0f,
                1.0f);
    }

    private static UiInputSnapshot pointerAt(double x, double y, long sampleId) {
        return input(Set.of(), Set.of(), x, y, sampleId);
    }

    private static UiInputSnapshot clickAt(double x, double y, long sampleId) {
        return input(Set.of(), Set.of(GLFW_MOUSE_BUTTON_LEFT), x, y, sampleId);
    }

    private static UiInputSnapshot keyAt(int key, long sampleId) {
        return input(Set.of(key), Set.of(), -1.0d, -1.0d, sampleId);
    }

    private static UiInputSnapshot input(
            Set<Integer> pressedKeys,
            Set<Integer> pressedMouseButtons,
            double x,
            double y,
            long sampleId) {
        return new UiInputSnapshot(
                pressedKeys,
                pressedKeys,
                pressedMouseButtons,
                pressedMouseButtons,
                List.of(),
                List.of(),
                x,
                y,
                true,
                sampleId);
    }
}

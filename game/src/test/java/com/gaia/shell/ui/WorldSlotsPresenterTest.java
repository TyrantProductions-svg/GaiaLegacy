package com.gaia.shell.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gaia.save.format.SaveGameId;
import com.gaia.settings.SettingsDefaults;
import com.gaia.settings.SettingsDraftSnapshot;
import com.gaia.shell.ModalId;
import com.gaia.shell.ProductShellSnapshot;
import com.gaia.shell.ScreenCommand;
import com.gaia.shell.ScreenId;
import com.gaia.shell.save.SaveCatalog;
import com.gaia.shell.save.SaveSummary;
import com.gaia.shell.world.NewWorldDraftController;
import com.gaia.shell.world.WorldSlotsController;
import com.overlord.core.input.UiInputSnapshot;
import com.overlord.renderer.RenderSurfaceMetrics;
import com.overlord.renderer.ui.UiLayoutContext;
import com.overlord.renderer.ui.UiDrawCommand;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import java.util.Set;
import static org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LEFT;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class WorldSlotsPresenterTest {
    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {480, 649, 650, 700, 711, 712, 720})
    void compactFullArchivePageKeepsRowsPagingAndBackDisjoint(int height) {
        var context = new UiLayoutContext(new RenderSurfaceMetrics(
                854, height, 1281, Math.round(height * 1.5f), 1.5f, 1.5f));
        var rows = java.util.stream.IntStream.rangeClosed(1, 5)
                .mapToObj(i -> summary(i, SaveSummary.Health.VALID)).toList();
        var layout = presenter(rows).present(
                ProductScreenPresenterTest.snapshot(ScreenId.WORLD_SLOTS), context);
        assertEquals(11, layout.hitRegions().size());
        for (int i = 0; i < layout.hitRegions().size(); i++) {
            var a = layout.hitRegions().get(i).logicalBounds();
            assertTrue(a.top() >= 0 && a.bottom() <= height, a.toString());
            for (int j = i + 1; j < layout.hitRegions().size(); j++) {
                var b = layout.hitRegions().get(j).logicalBounds();
                assertFalse(a.left() < b.right() && b.left() < a.right()
                        && a.top() < b.bottom() && b.top() < a.bottom(),
                        "overlapping archive controls " + a + " / " + b);
            }
        }
    }

    @Test
    void mainMenuLoadWorldIsEnabledOnlyForARealDisplayableCatalogRow() {
        ProductUiLayout empty = presenter(List.of()).present(
                ProductScreenPresenterTest.snapshot(ScreenId.MAIN_MENU),
                ProductScreenPresenterTest.context());
        assertFalse(empty.region(UiActionId.LOAD_WORLD).enabled());

        ProductUiLayout populated = presenter(List.of(
                summary(1, SaveSummary.Health.CORRUPT))).present(
                ProductScreenPresenterTest.snapshot(ScreenId.MAIN_MENU),
                ProductScreenPresenterTest.context());
        assertTrue(populated.region(UiActionId.LOAD_WORLD).enabled());
        assertEquals(
                new ScreenCommand.OpenWorldSlots(),
                populated.region(UiActionId.LOAD_WORLD).command());
    }

    @Test
    void emptyCatalogPresentsBackWithoutInventingAWorldAction() {
        ProductUiLayout layout = presenter(List.of()).present(
                ProductScreenPresenterTest.snapshot(ScreenId.WORLD_SLOTS),
                ProductScreenPresenterTest.context());

        assertEquals(List.of(UiActionId.BACK),
                layout.hitRegions().stream().map(UiHitRegion::id).toList());
    }

    @Test
    void rowsCarryStableHealthSpecificCommandsAndDeleteCommands() {
        List<SaveSummary> rows = List.of(
                summary(1, SaveSummary.Health.VALID),
                summary(2, SaveSummary.Health.RECOVERABLE_BACKUP),
                summary(3, SaveSummary.Health.CORRUPT),
                summary(4, SaveSummary.Health.UNSUPPORTED_VERSION));
        ProductUiLayout layout = presenter(rows).present(
                ProductScreenPresenterTest.snapshot(ScreenId.WORLD_SLOTS),
                ProductScreenPresenterTest.context());

        assertEquals(new ScreenCommand.LoadWorld(id(1)), layout.region(new WorldSlotControlId(
                id(1), WorldSlotControlId.WorldSlotAction.LOAD)).command());
        assertEquals(new ScreenCommand.RecoverBackup(id(2)), layout.region(
                new WorldSlotControlId(id(2), WorldSlotControlId.WorldSlotAction.RECOVER))
                .command());
        assertFalse(layout.hitRegions().stream().anyMatch(region ->
                region.id().equals(new WorldSlotControlId(
                        id(3), WorldSlotControlId.WorldSlotAction.LOAD))));
        for (SaveSummary row : rows) {
            assertEquals(new ScreenCommand.DeleteWorld(row.id()), layout.region(
                    new WorldSlotControlId(
                            row.id(), WorldSlotControlId.WorldSlotAction.DELETE)).command());
        }
    }

    @Test
    void stableDynamicFocusChangesThePaintedRowActionWithoutUsingAnIndex() {
        ProductScreenPresenter presenter = presenter(List.of(
                summary(1, SaveSummary.Health.VALID)));
        ProductShellSnapshot snapshot = ProductScreenPresenterTest.snapshot(
                ScreenId.WORLD_SLOTS);
        WorldSlotControlId loadId = new WorldSlotControlId(
                id(1), WorldSlotControlId.WorldSlotAction.LOAD);

        ProductUiLayout idle = presenter.presentFocused(
                snapshot, ProductScreenPresenterTest.context(), Optional.empty());
        ProductUiLayout focused = presenter.presentFocused(
                snapshot, ProductScreenPresenterTest.context(), Optional.of(loadId));

        assertFalse(solidFor(idle, loadId).tint().equals(solidFor(focused, loadId).tint()));
    }

    @Test
    void pointerPagingUsesControllerStateAndDynamicActivationSelectsTheStableRow() {
        List<SaveSummary> rows = List.of(
                summary(1, SaveSummary.Health.VALID),
                summary(2, SaveSummary.Health.VALID),
                summary(3, SaveSummary.Health.VALID),
                summary(4, SaveSummary.Health.VALID),
                summary(5, SaveSummary.Health.VALID));
        SaveCatalog catalog = () -> rows;
        WorldSlotsController slots = new WorldSlotsController(catalog, 4);
        ProductScreenPresenter presenter = presenter(catalog, slots);
        ProductScreenInputController input = new ProductScreenInputController();
        ProductUiLayout first = presenter.present(
                ProductScreenPresenterTest.snapshot(ScreenId.WORLD_SLOTS),
                ProductScreenPresenterTest.context());
        UiHitRegion next = first.region(UiActionId.WORLD_SLOTS_NEXT);

        assertTrue(input.routeWorldSlots(pointer(next, 1L), first, slots).isEmpty());
        assertEquals(0, slots.snapshot().pageIndex(), "hover must not activate paging");

        assertTrue(input.routeWorldSlots(click(next, 2L), first, slots).isEmpty());
        assertEquals(1, slots.snapshot().pageIndex());

        ProductUiLayout second = presenter.present(
                ProductScreenPresenterTest.snapshot(ScreenId.WORLD_SLOTS),
                ProductScreenPresenterTest.context());
        SaveGameId remaining = slots.snapshot().rows().get(0).id();
        UiHitRegion load = second.region(new WorldSlotControlId(
                remaining, WorldSlotControlId.WorldSlotAction.LOAD));
        assertEquals(
                new ScreenCommand.LoadWorld(remaining),
                input.routeWorldSlots(click(load, 3L), second, slots).orElseThrow());
        assertEquals(Optional.of(remaining), slots.snapshot().selectedId());
    }

    @ParameterizedTest(name = "scale {0} x {1}")
    @MethodSource("scales")
    void paintedDynamicControlsAndPointerBoundsAlignAcrossDpiMatrix(
            float scaleX, float scaleY) {
        RenderSurfaceMetrics surface = new RenderSurfaceMetrics(
                1001, 751,
                Math.round(1001 * scaleX), Math.round(751 * scaleY),
                scaleX, scaleY);
        UiLayoutContext context = new UiLayoutContext(surface);
        ProductUiLayout layout = presenter(List.of(summary(1, SaveSummary.Health.VALID)))
                .present(ProductScreenPresenterTest.snapshot(ScreenId.WORLD_SLOTS), context);
        UiHitRegion load = layout.region(new WorldSlotControlId(
                id(1), WorldSlotControlId.WorldSlotAction.LOAD));

        assertTrue(load.contains(load.centerX(), load.centerY()));
        assertTrue(layout.frame().commands().stream().anyMatch(command ->
                command.framebufferBounds().equals(context.toFramebuffer(load.logicalBounds()))));
    }

    @Test
    void deleteAndRecoverConfirmationModalsExcludeAllUnderlyingWorldControls() {
        ProductScreenPresenter presenter = presenter(List.of(
                summary(1, SaveSummary.Health.RECOVERABLE_BACKUP)));
        for (ModalId modal : List.of(
                ModalId.DELETE_WORLD_CONFIRMATION,
                ModalId.RECOVER_BACKUP_CONFIRMATION)) {
            ProductUiLayout layout = presenter.present(
                    new ProductShellSnapshot(
                            ScreenId.WORLD_SLOTS, Optional.of(modal), Optional.empty()),
                    ProductScreenPresenterTest.context());

            assertEquals(List.of(UiActionId.CONFIRM, UiActionId.DISMISS),
                    layout.hitRegions().stream().map(UiHitRegion::id).toList());
            assertFalse(layout.hitRegions().stream()
                    .anyMatch(region -> region.id() instanceof WorldSlotControlId));
        }
    }

    private static ProductScreenPresenter presenter(List<SaveSummary> rows) {
        SaveCatalog catalog = () -> rows;
        return presenter(catalog, new WorldSlotsController(catalog, 4));
    }

    private static UiDrawCommand solidFor(ProductUiLayout layout, UiControlId id) {
        var bounds = layout.layoutContext().toFramebuffer(layout.region(id).logicalBounds());
        return layout.frame().commands().stream()
                .filter(command -> command.framebufferBounds().equals(bounds))
                .findFirst()
                .orElseThrow();
    }

    private static ProductScreenPresenter presenter(
            SaveCatalog catalog, WorldSlotsController slots) {
        var defaults = SettingsDefaults.schemaV1();
        return new ProductScreenPresenter(
                catalog,
                ProductScreenPresenterTest.textRenderer(),
                () -> new SettingsDraftSnapshot(
                        defaults, defaults, false, Optional.empty()),
                new NewWorldDraftController(catalog),
                slots);
    }

    private static UiInputSnapshot click(UiHitRegion region, long sampleId) {
        return pointer(region, Set.of(GLFW_MOUSE_BUTTON_LEFT), sampleId);
    }

    private static UiInputSnapshot pointer(UiHitRegion region, long sampleId) {
        return pointer(region, Set.of(), sampleId);
    }

    private static UiInputSnapshot pointer(
            UiHitRegion region, Set<Integer> pressedMouse, long sampleId) {
        return new UiInputSnapshot(
                Set.of(),
                Set.of(),
                pressedMouse,
                pressedMouse,
                List.of(),
                List.of(),
                region.centerX(),
                region.centerY(),
                true,
                sampleId);
    }

    private static Stream<Arguments> scales() {
        return Stream.of(
                Arguments.of(1.0f, 1.0f),
                Arguments.of(1.25f, 1.25f),
                Arguments.of(1.5f, 1.5f),
                Arguments.of(2.0f, 2.0f),
                Arguments.of(1.25f, 2.0f));
    }

    private static SaveSummary summary(int suffix, SaveSummary.Health health) {
        return new SaveSummary(
                id(suffix),
                "World " + suffix,
                Optional.empty(),
                Instant.parse("2026-08-12T00:00:00Z").minusSeconds(suffix),
                Optional.of(12345L + suffix),
                Optional.empty(),
                health,
                List.of());
    }

    private static SaveGameId id(int suffix) {
        return SaveGameId.parse(String.format(
                "00000000-0000-0000-0000-%012d", suffix));
    }
}

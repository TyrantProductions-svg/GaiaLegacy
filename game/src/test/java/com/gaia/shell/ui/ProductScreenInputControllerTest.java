package com.gaia.shell.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_DOWN;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_SPACE;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_TAB;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_UP;
import static org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LEFT;

import com.gaia.shell.ModalId;
import com.gaia.shell.ProductShellSnapshot;
import com.gaia.shell.ScreenCommand;
import com.gaia.shell.ScreenId;
import com.gaia.shell.ScreenReturnTarget;
import com.gaia.shell.save.EmptySaveCatalog;
import com.overlord.core.input.UiInputSnapshot;
import com.overlord.renderer.ui.UiLayoutContext;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ProductScreenInputControllerTest {
    private static final UiLayoutContext CONTEXT = ProductScreenPresenterTest.context();
    private static final ProductShellSnapshot MAIN_MENU = ProductScreenPresenterTest.snapshot(
            ScreenId.MAIN_MENU);
    private static final ProductShellSnapshot PAUSED = ProductScreenPresenterTest.snapshot(
            ScreenId.PAUSED);

    private ProductScreenPresenter presenter;
    private ProductScreenInputController controller;

    @BeforeEach
    void setUp() {
        presenter = new ProductScreenPresenter(
                new EmptySaveCatalog(), ProductScreenPresenterTest.textRenderer());
        controller = new ProductScreenInputController();
    }

    @Test
    void hoverSelectsAnEnabledActionAndPointerClickRoutesIt() {
        ProductUiLayout layout = presenter.present(MAIN_MENU, CONTEXT);
        UiHitRegion settings = layout.region(UiActionId.SETTINGS);

        assertTrue(controller.route(
                input(Set.of(), Set.of(), settings.centerX(), settings.centerY(), 1L), layout)
                .isEmpty());
        assertEquals(
                new ScreenCommand.OpenSettings(),
                controller.route(keyAt(GLFW_KEY_ENTER, -1.0d, -1.0d, 2L), layout).orElseThrow());

        ProductScreenInputController clickController = new ProductScreenInputController();
        assertEquals(
                new ScreenCommand.OpenSettings(),
                clickController.route(
                        clickAt(settings.centerX(), settings.centerY(), 3L), layout).orElseThrow());
    }

    @Test
    void disabledLoadWorldCannotBeHoveredFocusedOrClicked() {
        ProductUiLayout layout = presenter.present(MAIN_MENU, CONTEXT);
        UiHitRegion load = layout.region(UiActionId.LOAD_WORLD);

        assertFalse(load.enabled());
        assertTrue(controller.route(
                clickAt(load.centerX(), load.centerY(), 4L), layout).isEmpty());
        assertEquals(
                new ScreenCommand.OpenNewWorldSetup(),
                controller.route(keyAt(GLFW_KEY_ENTER, -1.0d, -1.0d, 5L), layout).orElseThrow());
    }

    @Test
    void deterministicFocusOrderSkipsDisabledRegionsForTabAndArrows() {
        ProductUiLayout layout = presenter.present(MAIN_MENU, CONTEXT);

        assertEquals(
                new ScreenCommand.OpenNewWorldSetup(),
                new ProductScreenInputController()
                        .route(keyAt(GLFW_KEY_ENTER, -1.0d, -1.0d, 6L), layout)
                        .orElseThrow());

        ProductScreenInputController tabController = new ProductScreenInputController();
        assertTrue(tabController.route(keyAt(GLFW_KEY_TAB, -1.0d, -1.0d, 7L), layout).isEmpty());
        assertEquals(
                new ScreenCommand.OpenSettings(),
                tabController.route(keyAt(GLFW_KEY_ENTER, -1.0d, -1.0d, 8L), layout)
                        .orElseThrow());

        ProductScreenInputController downController = new ProductScreenInputController();
        assertTrue(downController.route(keyAt(GLFW_KEY_DOWN, -1.0d, -1.0d, 9L), layout).isEmpty());
        assertEquals(
                new ScreenCommand.OpenSettings(),
                downController.route(keyAt(GLFW_KEY_ENTER, -1.0d, -1.0d, 10L), layout)
                        .orElseThrow());

        ProductScreenInputController upController = new ProductScreenInputController();
        assertTrue(upController.route(keyAt(GLFW_KEY_UP, -1.0d, -1.0d, 11L), layout).isEmpty());
        assertEquals(
                new ScreenCommand.Quit(),
                upController.route(keyAt(GLFW_KEY_ENTER, -1.0d, -1.0d, 12L), layout)
                        .orElseThrow());
    }

    @Test
    void spaceActivatesTheFocusedAction() {
        ProductUiLayout layout = presenter.present(MAIN_MENU, CONTEXT);

        controller.route(keyAt(GLFW_KEY_DOWN, -1.0d, -1.0d, 13L), layout);
        controller.route(keyAt(GLFW_KEY_DOWN, -1.0d, -1.0d, 14L), layout);

        assertEquals(
                new ScreenCommand.OpenControls(),
                controller.route(keyAt(GLFW_KEY_SPACE, -1.0d, -1.0d, 15L), layout)
                        .orElseThrow());
    }

    @Test
    void clickingEnabledActionsProducesTheirExactCommands() {
        assertClickCommands(
                presenter.present(MAIN_MENU, CONTEXT),
                Map.of(
                        UiActionId.NEW_WORLD, new ScreenCommand.OpenNewWorldSetup(),
                        UiActionId.SETTINGS, new ScreenCommand.OpenSettings(),
                        UiActionId.CONTROLS, new ScreenCommand.OpenControls(),
                        UiActionId.QUIT, new ScreenCommand.Quit()));
        assertClickCommands(
                presenter.present(PAUSED, CONTEXT),
                Map.of(
                        UiActionId.RESUME, new ScreenCommand.Resume(),
                        UiActionId.SETTINGS, new ScreenCommand.OpenSettings(),
                        UiActionId.CONTROLS, new ScreenCommand.OpenControls(),
                        UiActionId.RETURN_TO_MAIN_MENU, new ScreenCommand.ReturnToMainMenu()));

        ProductShellSnapshot controls = new ProductShellSnapshot(
                ScreenId.CONTROLS,
                Optional.empty(),
                Optional.of(ScreenReturnTarget.PAUSED));
        assertClickCommands(
                presenter.present(controls, CONTEXT),
                Map.of(UiActionId.BACK, new ScreenCommand.Back()));
    }

    @Test
    void escapeUsesTheTopScreenOrModalNavigationCommand() {
        assertEquals(
                Optional.of(new ScreenCommand.Dismiss()),
                new ProductScreenInputController()
                        .route(keyAt(GLFW_KEY_ESCAPE, -1.0d, -1.0d, 19L),
                                presenter.present(
                                        ProductScreenPresenterTest.snapshot(
                                                ScreenId.LOADING),
                                        CONTEXT)));

        assertEquals(
                new ScreenCommand.Resume(),
                new ProductScreenInputController()
                        .route(keyAt(GLFW_KEY_ESCAPE, -1.0d, -1.0d, 20L),
                                presenter.present(PAUSED, CONTEXT))
                        .orElseThrow());

        ProductShellSnapshot controls = new ProductShellSnapshot(
                ScreenId.CONTROLS,
                Optional.empty(),
                Optional.of(ScreenReturnTarget.MAIN_MENU));
        assertEquals(
                new ScreenCommand.Back(),
                new ProductScreenInputController()
                        .route(keyAt(GLFW_KEY_ESCAPE, -1.0d, -1.0d, 21L),
                                presenter.present(controls, CONTEXT))
                        .orElseThrow());

        ProductShellSnapshot quitModal = quitModal();
        assertEquals(
                new ScreenCommand.Dismiss(),
                new ProductScreenInputController()
                        .route(keyAt(GLFW_KEY_ESCAPE, -1.0d, -1.0d, 22L),
                                presenter.present(quitModal, CONTEXT))
                        .orElseThrow());
    }

    @Test
    void confirmationModalOwnsPointerAndKeyboardInput() {
        ProductShellSnapshot quitModal = quitModal();
        ProductUiLayout layout = presenter.present(quitModal, CONTEXT);
        UiHitRegion confirm = layout.region(UiActionId.CONFIRM);
        UiInputSnapshot click = inputAt(confirm.centerX(), confirm.centerY(), 12L);
        assertEquals(new ScreenCommand.Confirm(), controller.route(click, layout).orElseThrow());
        assertFalse(layout.hitRegions().stream().anyMatch(r -> r.action() == UiActionId.NEW_WORLD));
    }

    @Test
    void confirmationModalDefaultFocusConfirmsWithEnter() {
        ProductUiLayout layout = presenter.present(quitModal(), CONTEXT);

        assertEquals(
                new ScreenCommand.Confirm(),
                controller.route(keyAt(GLFW_KEY_ENTER, -1.0d, -1.0d, 23L), layout)
                        .orElseThrow());
    }

    @Test
    void oneInputSampleProducesAtMostOneCommand() {
        ProductUiLayout layout = presenter.present(MAIN_MENU, CONTEXT);
        UiHitRegion newWorld = layout.region(UiActionId.NEW_WORLD);
        UiInputSnapshot combined = input(
                Set.of(GLFW_KEY_ENTER),
                Set.of(GLFW_MOUSE_BUTTON_LEFT),
                newWorld.centerX(),
                newWorld.centerY(),
                30L);

        assertEquals(
                new ScreenCommand.OpenNewWorldSetup(),
                controller.route(combined, layout).orElseThrow());
        assertTrue(controller.route(combined, layout).isEmpty());
    }

    @Test
    void outsideWindowPointerCannotHitOrReplaceKeyboardFocus() {
        ProductUiLayout layout = presenter.present(MAIN_MENU, CONTEXT);

        assertTrue(controller.route(clickAt(-0.01d, 10.0d, 31L), layout).isEmpty());
        assertTrue(controller.route(clickAt(1280.01d, 10.0d, 32L), layout).isEmpty());
        assertEquals(
                new ScreenCommand.OpenNewWorldSetup(),
                controller.route(keyAt(GLFW_KEY_ENTER, -1.0d, -1.0d, 33L), layout)
                        .orElseThrow());
    }

    @Test
    void unfocusedInputCannotNavigateOrActivate() {
        ProductUiLayout layout = presenter.present(MAIN_MENU, CONTEXT);
        UiHitRegion newWorld = layout.region(UiActionId.NEW_WORLD);
        UiInputSnapshot unfocused = new UiInputSnapshot(
                Set.of(GLFW_KEY_ENTER),
                Set.of(GLFW_KEY_ENTER),
                Set.of(GLFW_MOUSE_BUTTON_LEFT),
                Set.of(GLFW_MOUSE_BUTTON_LEFT),
                List.of(),
                newWorld.centerX(),
                newWorld.centerY(),
                false,
                34L);

        assertTrue(controller.route(unfocused, layout).isEmpty());
    }

    private static ProductShellSnapshot quitModal() {
        return new ProductShellSnapshot(
                ScreenId.MAIN_MENU,
                Optional.of(ModalId.QUIT_CONFIRMATION),
                Optional.empty());
    }

    private static void assertClickCommands(
            ProductUiLayout layout, Map<UiActionId, ScreenCommand> expected) {
        long sampleId = 100L;
        for (Map.Entry<UiActionId, ScreenCommand> entry : expected.entrySet()) {
            UiHitRegion region = layout.region(entry.getKey());
            assertEquals(
                    entry.getValue(),
                    new ProductScreenInputController()
                            .route(clickAt(region.centerX(), region.centerY(), sampleId++), layout)
                            .orElseThrow(),
                    entry.getKey().name());
        }
    }

    private static UiInputSnapshot inputAt(double x, double y, long sampleId) {
        return clickAt(x, y, sampleId);
    }

    private static UiInputSnapshot clickAt(double x, double y, long sampleId) {
        return input(Set.of(), Set.of(GLFW_MOUSE_BUTTON_LEFT), x, y, sampleId);
    }

    private static UiInputSnapshot keyAt(int key, double x, double y, long sampleId) {
        return input(Set.of(key), Set.of(), x, y, sampleId);
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
                x,
                y,
                true,
                sampleId);
    }
}

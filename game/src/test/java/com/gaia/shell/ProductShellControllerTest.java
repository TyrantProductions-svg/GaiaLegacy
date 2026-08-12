package com.gaia.shell;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LEFT;

import com.gaia.save.format.SaveGameId;
import com.gaia.session.NewWorldRequest;
import com.gaia.shell.save.EmptySaveCatalog;
import com.gaia.shell.ui.ProductScreenInputController;
import com.gaia.shell.ui.ProductScreenPresenter;
import com.gaia.shell.ui.ProductUiLayout;
import com.gaia.shell.ui.UiActionId;
import com.gaia.shell.ui.UiHitRegion;
import com.overlord.core.input.UiInputSnapshot;
import com.overlord.renderer.RenderSurfaceMetrics;
import com.overlord.renderer.ui.BitmapFont;
import com.overlord.renderer.ui.BitmapGlyph;
import com.overlord.renderer.ui.TextRenderer;
import com.overlord.renderer.ui.UiLayoutContext;
import com.overlord.renderer.ui.UiUvRect;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class ProductShellControllerTest {
    private static final NewWorldRequest NEW_WORLD_REQUEST = new NewWorldRequest(
            SaveGameId.parse("00000000-0000-0000-0000-000000000014"),
            "New World",
            12345L);

    @Test
    void newWorldRoutesToLoadingAndProducesTypedSessionStartIntent() {
        Fixture fixture = new Fixture();

        fixture.controller().handle(new ScreenCommand.OpenNewWorldSetup());
        ProductLifecycleIntent intent = fixture.controller().handle(
                new ScreenCommand.CreateWorld(NEW_WORLD_REQUEST));

        assertEquals(new ProductLifecycleIntent.StartNewWorld(NEW_WORLD_REQUEST), intent);
        assertEquals(ScreenId.LOADING, fixture.snapshot().screen());
        assertTrue(fixture.snapshot().modal().isEmpty());
    }

    @Test
    void dismissingLoadingReturnsToMainMenuAndRequestsSessionCleanup() {
        Fixture fixture = new Fixture();
        fixture.controller().handle(new ScreenCommand.OpenNewWorldSetup());
        assertEquals(
                new ProductLifecycleIntent.StartNewWorld(NEW_WORLD_REQUEST),
                fixture.controller().handle(
                        new ScreenCommand.CreateWorld(NEW_WORLD_REQUEST)));

        ProductLifecycleIntent intent =
                fixture.controller().handle(new ScreenCommand.Dismiss());

        assertEquals(new ProductLifecycleIntent.CloseActiveSession(), intent);
        assertEquals(ScreenId.MAIN_MENU, fixture.snapshot().screen());
        assertTrue(fixture.snapshot().modal().isEmpty());
    }

    @Test
    void disabledLoadWorldCannotProduceAControllerCommandOrLifecycleIntent() {
        Fixture fixture = new Fixture();
        ProductUiLayout layout = fixture.presenter().present(
                fixture.snapshot(), context());
        UiHitRegion load = layout.region(UiActionId.LOAD_WORLD);

        Optional<ScreenCommand> command =
                new ProductScreenInputController().route(
                        click(load.centerX(), load.centerY(), 1L), layout);

        assertTrue(command.isEmpty());
        assertFalse(load.enabled());
        assertEquals(ScreenId.MAIN_MENU, fixture.snapshot().screen());
    }

    @Test
    void returningToMainMenuRequiresWarningBeforeClosingTheActiveSession() {
        Fixture fixture = new Fixture();
        fixture.enterPaused();

        ProductLifecycleIntent warning = fixture.controller().handle(
                new ScreenCommand.ReturnToMainMenu());

        assertEquals(ProductLifecycleIntent.none(), warning);
        assertEquals(ScreenId.PAUSED, fixture.snapshot().screen());
        assertEquals(
                ModalId.UNSAVED_PROGRESS_CONFIRMATION,
                fixture.snapshot().modal().orElseThrow());

        ProductLifecycleIntent confirmed =
                fixture.controller().handle(new ScreenCommand.Confirm());

        assertEquals(new ProductLifecycleIntent.CloseActiveSession(), confirmed);
        assertEquals(ScreenId.MAIN_MENU, fixture.snapshot().screen());
        assertTrue(fixture.snapshot().modal().isEmpty());
    }

    @Test
    void quitRequiresConfirmationAndRepeatedConfirmationIsIdempotent() {
        Fixture fixture = new Fixture();

        assertEquals(
                ProductLifecycleIntent.none(),
                fixture.controller().handle(new ScreenCommand.Quit()));
        assertEquals(
                ModalId.QUIT_CONFIRMATION,
                fixture.snapshot().modal().orElseThrow());

        assertEquals(
                new ProductLifecycleIntent.ExitProduct(),
                fixture.controller().handle(new ScreenCommand.Confirm()));
        assertTrue(fixture.snapshot().modal().isEmpty());
        assertEquals(
                ProductLifecycleIntent.none(),
                fixture.controller().handle(new ScreenCommand.Confirm()));
        assertEquals(ScreenId.MAIN_MENU, fixture.snapshot().screen());
    }

    @Test
    void dirtySettingsConfirmationRoutesTheGate13APlaceholderWithoutASettingsService() {
        Fixture fixture = new Fixture();
        fixture.controller().handle(new ScreenCommand.OpenSettings());
        fixture.router().openModal(ModalId.DIRTY_SETTINGS_CONFIRMATION);

        assertEquals(
                ProductLifecycleIntent.none(),
                fixture.controller().handle(new ScreenCommand.Dismiss()));
        assertEquals(ScreenId.SETTINGS, fixture.snapshot().screen());
        assertTrue(fixture.snapshot().modal().isEmpty());

        fixture.router().openModal(ModalId.DIRTY_SETTINGS_CONFIRMATION);
        assertEquals(
                ProductLifecycleIntent.none(),
                fixture.controller().handle(new ScreenCommand.Confirm()));
        assertEquals(ScreenId.MAIN_MENU, fixture.snapshot().screen());
        assertTrue(fixture.snapshot().modal().isEmpty());
        assertEquals(
                ProductLifecycleIntent.none(),
                fixture.controller().handle(new ScreenCommand.Confirm()));
    }

    @Test
    void controllerPublicSurfaceAndStateHaveNoGameplayDomainDependency() {
        Stream<Class<?>> stateTypes = Arrays.stream(
                        ProductShellController.class.getDeclaredFields())
                .filter(field -> !Modifier.isStatic(field.getModifiers()))
                .map(Field::getType);
        Stream<Class<?>> executableTypes = Stream.concat(
                        Arrays.stream(ProductShellController.class.getDeclaredConstructors()),
                        Arrays.stream(ProductShellController.class.getDeclaredMethods()))
                .flatMap(ProductShellControllerTest::signatureTypes);

        List<String> forbidden = Stream.concat(stateTypes, executableTypes)
                .map(Class::getName)
                .filter(ProductShellControllerTest::isGameplayDomainType)
                .sorted()
                .toList();

        assertEquals(List.of(), forbidden);
    }

    private static Stream<Class<?>> signatureTypes(Executable executable) {
        Stream<Class<?>> parameters = Arrays.stream(executable.getParameterTypes());
        if (executable instanceof Method method) {
            return Stream.concat(parameters, Stream.of(method.getReturnType()));
        }
        return parameters;
    }

    private static boolean isGameplayDomainType(String name) {
        return name.startsWith("com.gaia.interaction.")
                || name.startsWith("com.gaia.inventory.")
                || name.startsWith("com.gaia.session.")
                || name.startsWith("com.gaia.world.")
                || name.startsWith("com.gaia.worlditem.")
                || name.startsWith("com.overlord.inventory.")
                || name.startsWith("com.overlord.voxel.")
                || name.startsWith("com.overlord.worlditem.");
    }

    private static UiInputSnapshot click(double x, double y, long sampleId) {
        return new UiInputSnapshot(
                Set.of(),
                Set.of(),
                Set.of(GLFW_MOUSE_BUTTON_LEFT),
                Set.of(GLFW_MOUSE_BUTTON_LEFT),
                List.of(),
                x,
                y,
                true,
                sampleId);
    }

    private static UiLayoutContext context() {
        return new UiLayoutContext(new RenderSurfaceMetrics(
                1280, 720, 1280, 720, 1.0f, 1.0f));
    }

    private static TextRenderer textRenderer() {
        UiUvRect uv = new UiUvRect(0.0f, 0.0f, 1.0f, 1.0f);
        BitmapGlyph missing = new BitmapGlyph(0xfffd, uv, 8, 0, 8);
        return new TextRenderer(new BitmapFont(8, 8, Map.of(), missing));
    }

    private static final class Fixture {
        private final ScreenRouter router = ScreenRouter.mainMenu();
        private final ProductShellController controller =
                new ProductShellController(router);
        private final ProductScreenPresenter presenter =
                new ProductScreenPresenter(new EmptySaveCatalog(), textRenderer());

        ScreenRouter router() {
            return router;
        }

        ProductShellController controller() {
            return controller;
        }

        ProductScreenPresenter presenter() {
            return presenter;
        }

        ProductShellSnapshot snapshot() {
            return controller.snapshot();
        }

        void enterPaused() {
            assertEquals(
                    ProductLifecycleIntent.none(),
                    controller.handle(new ScreenCommand.OpenNewWorldSetup()));
            assertEquals(
                    new ProductLifecycleIntent.StartNewWorld(NEW_WORLD_REQUEST),
                    controller.handle(new ScreenCommand.CreateWorld(NEW_WORLD_REQUEST)));
            controller.loadingSucceeded();
            controller.togglePlaying();
            assertEquals(ScreenId.PAUSED, snapshot().screen());
        }
    }
}

package com.gaia.shell.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LEFT;

import com.gaia.shell.ProductShellSnapshot;
import com.gaia.shell.ScreenCommand;
import com.gaia.shell.ScreenId;
import com.gaia.shell.save.EmptySaveCatalog;
import com.overlord.core.input.UiInputSnapshot;
import com.overlord.renderer.RenderSurfaceMetrics;
import com.overlord.renderer.ui.UiFrame;
import com.overlord.renderer.ui.UiLayoutContext;
import com.overlord.renderer.ui.UiRect;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class ProductUiDpiMatrixTest {
    private final ProductShellSnapshot mainMenu = ProductScreenPresenterTest.snapshot(
            ScreenId.MAIN_MENU);
    private ProductScreenPresenter presenter;

    @BeforeEach
    void setUp() {
        presenter = new ProductScreenPresenter(
                new EmptySaveCatalog(), ProductScreenPresenterTest.textRenderer());
    }

    @ParameterizedTest
    @ValueSource(floats = {1.0f, 1.25f, 1.5f, 2.0f, 3.0f})
    void emblemIsOneTransparentTexturedQuadAlignedWithWordmark(float scale) {
        RenderSurfaceMetrics surface = new RenderSurfaceMetrics(
                1280, 720, Math.round(1280 * scale), Math.round(720 * scale), scale, scale);
        UiLayoutContext context = new UiLayoutContext(surface);
        ProductUiLayout layout = presenter.present(mainMenu, context);
        var emblems = layout.frame().commands().stream().filter(command ->
                command.texture() == com.overlord.renderer.ui.UiTextureId.BRAND_EMBLEM).toList();
        assertEquals(1, emblems.size());
        assertEquals(context.toFramebuffer(new UiRect(70, 46, 166, 142)),
                emblems.get(0).framebufferBounds());
        assertEquals(5, layout.hitRegions().size(), "brand must not add a hit region");
    }

    @ParameterizedTest
    @ValueSource(floats = {1.0f, 1.25f, 1.5f, 2.0f})
    void newWorldPaintAndHitRegionRemainAligned(float scale) {
        RenderSurfaceMetrics surface = new RenderSurfaceMetrics(
                1280, 720, Math.round(1280 * scale), Math.round(720 * scale), scale, scale);
        ProductUiLayout layout = presenter.present(mainMenu, new UiLayoutContext(surface));
        UiHitRegion hit = layout.hitRegions().stream()
                .filter(region -> region.action() == UiActionId.NEW_WORLD)
                .findFirst().orElseThrow();
        assertTrue(hit.contains(hit.centerX(), hit.centerY()));
        assertTrue(layout.frame().commands().stream()
                .anyMatch(command -> command.framebufferBounds().equals(
                        new UiLayoutContext(surface).toFramebuffer(hit.logicalBounds()))));
    }

    @ParameterizedTest
    @ValueSource(floats = {1.0f, 1.25f, 1.5f, 2.0f})
    void logicalGlfwPointerHitsPaintedRegionAtEveryContentScale(float scale) {
        RenderSurfaceMetrics surface = new RenderSurfaceMetrics(
                1280, 720, Math.round(1280 * scale), Math.round(720 * scale), scale, scale);
        ProductUiLayout layout = presenter.present(mainMenu, new UiLayoutContext(surface));
        UiHitRegion hit = layout.region(UiActionId.NEW_WORLD);
        UiInputSnapshot click = clickAt(
                hit.centerX(),
                hit.centerY(),
                1L);

        assertEquals(
                new ScreenCommand.OpenNewWorldSetup(),
                new ProductScreenInputController().route(click, layout).orElseThrow());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("windowCoordinateSurfaces")
    void windowPointerAtPaintedButtonCenterRoutesNewWorldAcrossPlatformMetrics(
            String scenario,
            RenderSurfaceMetrics surface) {
        UiLayoutContext context = new UiLayoutContext(surface);
        ProductUiLayout layout = presenter.present(mainMenu, context);
        UiHitRegion hit = layout.region(UiActionId.NEW_WORLD);
        double windowX = hit.centerX()
                * context.logicalWindowWidth() / context.logicalWidth();
        double windowY = hit.centerY()
                * context.logicalWindowHeight() / context.logicalHeight();

        assertEquals(
                new ScreenCommand.OpenNewWorldSetup(),
                new ProductScreenInputController()
                        .route(clickAt(windowX, windowY, 50L), layout)
                        .orElseThrow(),
                scenario);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("outsideWindowCoordinates")
    void outsideWindowPointerCannotHitAfterCoordinateMapping(
            String scenario,
            RenderSurfaceMetrics surface,
            double windowX,
            double windowY) {
        UiLayoutContext context = new UiLayoutContext(surface);
        ProductUiLayout layout = presenter.present(mainMenu, context);

        assertTrue(
                new ProductScreenInputController()
                        .route(clickAt(windowX, windowY, 51L), layout)
                        .isEmpty(),
                scenario);
    }

    @ParameterizedTest(name = "scale {0} x {1}")
    @MethodSource("asymmetricScales")
    void asymmetricContentScaleKeepsLogicalPointerAndPaintedHitAligned(
            float scaleX,
            float scaleY) {
        RenderSurfaceMetrics surface = new RenderSurfaceMetrics(
                1001,
                751,
                Math.round(1001 * scaleX),
                Math.round(751 * scaleY),
                scaleX,
                scaleY);
        ProductUiLayout layout = presenter.present(mainMenu, new UiLayoutContext(surface));
        UiHitRegion hit = layout.region(UiActionId.NEW_WORLD);

        UiInputSnapshot click = clickAt(
                hit.centerX(),
                hit.centerY(),
                2L);

        assertEquals(
                new ScreenCommand.OpenNewWorldSetup(),
                new ProductScreenInputController().route(click, layout).orElseThrow());
    }

    @ParameterizedTest
    @ValueSource(floats = {1.0f, 1.25f, 1.5f, 2.0f})
    void logicalRightExtentCannotHitRegionAtViewportEdge(float scale) {
        RenderSurfaceMetrics surface = new RenderSurfaceMetrics(
                300, 720, Math.round(300 * scale), Math.round(720 * scale), scale, scale);
        UiLayoutContext context = new UiLayoutContext(surface);
        ProductUiLayout layout = presenter.present(mainMenu, context);
        UiHitRegion hit = layout.region(UiActionId.NEW_WORLD);
        UiInputSnapshot outsideClick = clickAt(
                context.logicalWidth(),
                hit.centerY(),
                40L);

        assertTrue(new ProductScreenInputController().route(outsideClick, layout).isEmpty());
    }

    @ParameterizedTest
    @ValueSource(floats = {1.0f, 1.25f, 1.5f, 2.0f})
    void logicalBottomExtentCannotHitRegionAtViewportEdge(float scale) {
        RenderSurfaceMetrics surface = new RenderSurfaceMetrics(
                300, 720, Math.round(300 * scale), Math.round(720 * scale), scale, scale);
        UiLayoutContext context = new UiLayoutContext(surface);
        UiRect viewport = context.safeArea();
        UiHitRegion hit = new UiHitRegion(
                UiActionId.NEW_WORLD,
                new UiRect(0.0d, 678.0d, 300.0d, 720.0d),
                viewport,
                true,
                scale,
                scale);
        ProductUiLayout layout = new ProductUiLayout(UiFrame.empty(), List.of(hit), context);
        UiInputSnapshot outsideClick = clickAt(
                hit.centerX(),
                context.logicalHeight(),
                41L);

        assertTrue(new ProductScreenInputController().route(outsideClick, layout).isEmpty());
    }

    @Test
    void hitRegionIncludesItsEdgesAndRejectsCoordinatesBeyondThem() {
        ProductUiLayout layout = presenter.present(mainMenu, ProductScreenPresenterTest.context());
        UiHitRegion hit = layout.region(UiActionId.NEW_WORLD);

        assertTrue(hit.contains(hit.logicalBounds().left(), hit.logicalBounds().top()));
        assertTrue(hit.contains(hit.logicalBounds().right(), hit.logicalBounds().bottom()));
        assertTrue(!hit.contains(hit.logicalBounds().left() - 0.001d, hit.centerY()));
        assertTrue(!hit.contains(hit.centerX(), hit.logicalBounds().bottom() + 0.001d));
    }

    private static Stream<Arguments> asymmetricScales() {
        return Stream.of(
                Arguments.of(1.25f, 1.5f),
                Arguments.of(1.5f, 1.25f),
                Arguments.of(1.0f, 2.0f),
                Arguments.of(2.0f, 1.0f));
    }

    private static Stream<Arguments> windowCoordinateSurfaces() {
        return Stream.of(
                Arguments.of(
                        "Windows 150 percent with equal window and framebuffer extents",
                        new RenderSurfaceMetrics(1920, 1080, 1920, 1080, 1.5f, 1.5f)),
                Arguments.of(
                        "Retina 2x with a doubled framebuffer",
                        new RenderSurfaceMetrics(1280, 720, 2560, 1440, 2.0f, 2.0f)),
                Arguments.of(
                        "asymmetric mismatched window framebuffer and scale axes",
                        new RenderSurfaceMetrics(2000, 1000, 2400, 1800, 1.5f, 2.0f)));
    }

    private static Stream<Arguments> outsideWindowCoordinates() {
        RenderSurfaceMetrics windows =
                new RenderSurfaceMetrics(1920, 1080, 1920, 1080, 1.5f, 1.5f);
        return Stream.of(
                Arguments.of("left of Windows content area", windows, -0.01d, 247.0d),
                Arguments.of("above Windows content area", windows, 960.0d, -0.01d),
                Arguments.of("at exclusive Windows right edge", windows, 1920.0d, 247.0d),
                Arguments.of("at exclusive Windows bottom edge", windows, 960.0d, 1080.0d));
    }

    private static UiInputSnapshot clickAt(double logicalX, double logicalY, long sampleId) {
        return new UiInputSnapshot(
                Set.of(),
                Set.of(),
                Set.of(GLFW_MOUSE_BUTTON_LEFT),
                Set.of(GLFW_MOUSE_BUTTON_LEFT),
                List.of(),
                logicalX,
                logicalY,
                true,
                sampleId);
    }
}

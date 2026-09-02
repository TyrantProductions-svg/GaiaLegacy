package com.gaia.shell.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gaia.shell.ModalId;
import com.gaia.shell.OperationProgressSnapshot;
import com.gaia.shell.ProductShellSnapshot;
import com.gaia.shell.ScreenId;
import com.gaia.shell.ScreenReturnTarget;
import com.gaia.shell.save.EmptySaveCatalog;
import com.gaia.shell.save.SaveCatalog;
import com.overlord.renderer.RenderSurfaceMetrics;
import com.overlord.renderer.ui.BitmapFont;
import com.overlord.renderer.ui.BitmapGlyph;
import com.overlord.renderer.ui.TextRenderer;
import com.overlord.renderer.ui.TypographyCatalog;
import com.overlord.renderer.ui.TypographyRole;
import com.overlord.renderer.ui.UiLayoutContext;
import com.overlord.renderer.ui.UiTextureId;
import com.overlord.renderer.ui.UiUvRect;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ProductScreenPresenterTest {
    private static final ProductShellSnapshot MAIN_MENU = snapshot(ScreenId.MAIN_MENU);
    private static final ProductShellSnapshot PAUSED = snapshot(ScreenId.PAUSED);
    private static final UiLayoutContext CONTEXT = context();

    private ProductScreenPresenter presenter;

    @BeforeEach
    void setUp() {
        presenter = new ProductScreenPresenter(new EmptySaveCatalog(), textRenderer());
    }

    @Test
    void mainMenuExposesExactOrderedActionsWithLoadWorldDisabled() {
        ProductUiLayout layout = presenter.present(MAIN_MENU, CONTEXT);

        assertEquals(
                List.of(
                        UiActionId.NEW_WORLD,
                        UiActionId.LOAD_WORLD,
                        UiActionId.SETTINGS,
                        UiActionId.CONTROLS,
                        UiActionId.QUIT),
                actions(layout));
        assertEquals(
                List.of(
                        UiActionId.NEW_WORLD,
                        UiActionId.SETTINGS,
                        UiActionId.CONTROLS,
                        UiActionId.QUIT),
                enabledActions(layout));
        assertFalse(layout.region(UiActionId.LOAD_WORLD).enabled());
    }

    @Test
    void mainMenuUsesWorldFirstHeroRoleAwareWordmarkAndMinimalLeftNavigation() {
        ProductScreenPresenter styled = new ProductScreenPresenter(
                new EmptySaveCatalog(), roleAwareTextRenderer());

        ProductUiLayout layout = styled.present(
                MAIN_MENU, CONTEXT, Optional.of(UiActionId.NEW_WORLD));

        assertEquals(UiTextureId.HERO_BACKGROUND,
                layout.frame().commands().get(0).texture());
        assertTrue(layout.frame().commands().stream()
                .anyMatch(command -> command.texture() == UiTextureId.FONT_DISPLAY));
        assertTrue(layout.frame().commands().stream()
                .anyMatch(command -> command.texture() == UiTextureId.FONT_BODY));
        assertTrue(layout.hitRegions().stream()
                .allMatch(region -> region.logicalBounds().right()
                        < CONTEXT.logicalWidth() / 2.0d));
        assertFalse(layout.frame().commands().stream()
                .anyMatch(command -> command.texture() == UiTextureId.SOLID
                        && command.framebufferBounds().equals(
                                CONTEXT.toFramebuffer(CONTEXT.safeArea()))
                        && command.tint().alpha() >= 0.9f));
    }

    @Test
    void settingsAndModalRetainHeroUnderDarkerStructuredProductShell() {
        ProductScreenPresenter styled = new ProductScreenPresenter(
                new EmptySaveCatalog(), roleAwareTextRenderer());

        ProductUiLayout settings = styled.present(snapshot(ScreenId.SETTINGS), CONTEXT);
        ProductUiLayout modal = styled.present(new ProductShellSnapshot(
                ScreenId.MAIN_MENU,
                Optional.of(ModalId.QUIT_CONFIRMATION),
                Optional.empty()), CONTEXT);

        for (ProductUiLayout layout : List.of(settings, modal)) {
            assertEquals(UiTextureId.HERO_BACKGROUND,
                    layout.frame().commands().get(0).texture());
            assertTrue(layout.frame().commands().stream()
                    .anyMatch(command -> command.texture() == UiTextureId.SOLID
                            && command.framebufferBounds().equals(
                                    CONTEXT.toFramebuffer(CONTEXT.safeArea()))
                            && command.tint().alpha() >= 0.75f));
            assertTrue(layout.frame().commands().stream()
                    .anyMatch(command -> command.texture() == UiTextureId.FONT_DISPLAY));
        }
    }

    @Test
    void mainMenuNavigationFitsTheDefaultSmallRuntimeSurfaceAboveItsFooter() {
        UiLayoutContext small = new UiLayoutContext(
                new RenderSurfaceMetrics(854, 480, 854, 480, 1.0f, 1.0f));

        ProductUiLayout layout = presenter.present(
                MAIN_MENU, small, Optional.of(UiActionId.NEW_WORLD));

        assertEquals(5, layout.hitRegions().size());
        assertTrue(layout.hitRegions().stream()
                .allMatch(region -> region.logicalBounds().bottom()
                        <= small.logicalHeight() - 48.0d));
        assertTrue(layout.hitRegions().stream()
                .allMatch(region -> region.logicalBounds().top() >= 0.0d));
    }

    @Test
    void repeatedPresentationDoesNotRediscoverSavesWhileLoadWorldIsDisabled() {
        AtomicInteger discoveries = new AtomicInteger();
        SaveCatalog catalog = () -> {
            discoveries.incrementAndGet();
            return List.of();
        };
        ProductScreenPresenter observed =
                new ProductScreenPresenter(catalog, textRenderer());
        discoveries.set(0);

        observed.present(MAIN_MENU, CONTEXT);
        observed.present(MAIN_MENU, CONTEXT);
        observed.present(MAIN_MENU, CONTEXT);

        assertEquals(0, discoveries.get(),
                "presentation frames must consume a cached immutable snapshot");
    }

    @Test
    void emptyCatalogLabelsLoadWorldWithoutEnablingIt() {
        ProductScreenPresenter capturingPresenter = new ProductScreenPresenter(
                new EmptySaveCatalog(), capturingTextRenderer());

        ProductUiLayout layout = capturingPresenter.present(MAIN_MENU, CONTEXT);

        assertEquals(
                "WORLD ARCHIVE",
                textInside(layout, UiActionId.LOAD_WORLD));
        assertFalse(layout.region(UiActionId.LOAD_WORLD).enabled());
    }

    @Test
    void pauseMenuExposesExactOrderedEnabledActions() {
        ProductUiLayout layout = presenter.present(PAUSED, CONTEXT);

        assertEquals(
                List.of(
                        UiActionId.RESUME,
                        UiActionId.SAVE,
                        UiActionId.SAVE_AND_QUIT,
                        UiActionId.SETTINGS,
                        UiActionId.CONTROLS,
                        UiActionId.RETURN_TO_MAIN_MENU),
                actions(layout));
        assertEquals(actions(layout), enabledActions(layout));
    }

    @Test
    void controlsScreenIsReadOnlyExceptForBackNavigation() {
        ProductShellSnapshot controls = new ProductShellSnapshot(
                ScreenId.CONTROLS,
                Optional.empty(),
                Optional.of(ScreenReturnTarget.MAIN_MENU));

        ProductUiLayout layout = presenter.present(controls, CONTEXT);

        assertEquals(List.of(UiActionId.BACK), actions(layout));
        assertTrue(layout.region(UiActionId.BACK).enabled());
    }

    @Test
    void loadingExposesOneEnabledCancellationAction() {
        ProductUiLayout layout = presenter.present(
                snapshot(ScreenId.LOADING), CONTEXT);

        assertEquals(List.of(UiActionId.DISMISS), actions(layout));
        assertEquals(actions(layout), enabledActions(layout));
    }

    @Test
    void loadingPresentsTruthfulExactUnitsAndOnlyProvenCancellation() {
        ProductScreenPresenter capturing = new ProductScreenPresenter(
                new EmptySaveCatalog(), capturingTextRenderer());
        OperationProgressSnapshot progress = new OperationProgressSnapshot(
                OperationProgressSnapshot.Kind.LOAD_WORLD,
                "RESTORING CHUNKS",
                "Preparing simulation neighborhood",
                java.util.OptionalLong.of(13),
                java.util.OptionalLong.of(25),
                OperationProgressSnapshot.TerminalState.RUNNING,
                true,
                Optional.empty());

        ProductUiLayout layout = capturing.present(
                snapshot(ScreenId.LOADING, progress), CONTEXT);

        assertTrue(allText(layout).contains("LOADING WORLD"));
        assertTrue(allText(layout).contains("RESTORING CHUNKS"));
        assertTrue(allText(layout).contains("13 / 25"));
        assertEquals(List.of(UiActionId.DISMISS), enabledActions(layout));
    }

    @Test
    void savingUnknownTotalUsesAnimatedIndeterminateBarAndNoCancelAction() {
        ProductScreenPresenter capturing = new ProductScreenPresenter(
                new EmptySaveCatalog(), capturingTextRenderer());
        OperationProgressSnapshot first = OperationProgressSnapshot.indeterminate(
                OperationProgressSnapshot.Kind.SAVE_AND_QUIT,
                "VALIDATING CANDIDATE",
                "Checking the complete save root",
                false);
        ProductUiLayout firstLayout = capturing.present(
                snapshot(ScreenId.SAVING, first, 0), CONTEXT);
        ProductUiLayout laterLayout = capturing.present(
                snapshot(ScreenId.SAVING, first, 20), CONTEXT);

        assertTrue(allText(firstLayout).contains("SAVING WORLD"));
        assertTrue(allText(firstLayout).contains("VALIDATING CANDIDATE"));
        assertTrue(actions(firstLayout).isEmpty());
        assertFalse(firstLayout.frame().commands().equals(
                laterLayout.frame().commands()));
    }

    @Test
    void everyHitRegionUsesBoundsPaintedByTheSameLayout() {
        for (ProductShellSnapshot snapshot : List.of(MAIN_MENU, PAUSED)) {
            ProductUiLayout layout = presenter.present(snapshot, CONTEXT);

            for (UiHitRegion region : layout.hitRegions()) {
                assertTrue(
                        layout.frame().commands().stream().anyMatch(command ->
                                command.framebufferBounds().equals(
                                        CONTEXT.toFramebuffer(region.logicalBounds()))),
                        region.action().name());
            }
        }
    }

    @Test
    void modalPresentationReplacesUnderlyingActionsAndLayoutOutputsAreImmutable() {
        ProductShellSnapshot quitModal = new ProductShellSnapshot(
                ScreenId.MAIN_MENU,
                Optional.of(ModalId.QUIT_CONFIRMATION),
                Optional.empty());

        ProductUiLayout layout = presenter.present(quitModal, CONTEXT);

        assertEquals(List.of(UiActionId.CONFIRM, UiActionId.DISMISS), actions(layout));
        assertFalse(layout.hitRegions().stream()
                .anyMatch(region -> region.action() == UiActionId.NEW_WORLD));
        assertThrows(UnsupportedOperationException.class, layout.hitRegions()::clear);
        assertThrows(UnsupportedOperationException.class, layout.frame().commands()::clear);
    }

    static TextRenderer textRenderer() {
        UiUvRect uv = new UiUvRect(0.0f, 0.0f, 1.0f, 1.0f);
        BitmapGlyph missing = new BitmapGlyph(0xfffd, uv, 8, 0, 8);
        return new TextRenderer(new BitmapFont(8, 8, Map.of(), missing));
    }

    private static TextRenderer capturingTextRenderer() {
        Map<Integer, BitmapGlyph> glyphs = new LinkedHashMap<>();
        for (int codePoint = 32; codePoint <= 126; codePoint++) {
            UiUvRect uv = new UiUvRect(
                    codePoint / 128.0f,
                    0.0f,
                    (codePoint + 1) / 128.0f,
                    1.0f);
            glyphs.put(codePoint, new BitmapGlyph(codePoint, uv, 1, 0, 8));
        }
        BitmapGlyph missing = glyphs.get((int) '?');
        return new TextRenderer(new BitmapFont(128, 8, glyphs, missing));
    }

    private static TextRenderer roleAwareTextRenderer() {
        Map<Integer, BitmapGlyph> glyphs = new LinkedHashMap<>();
        for (int codePoint = 32; codePoint <= 126; codePoint++) {
            glyphs.put(codePoint, new BitmapGlyph(
                    codePoint,
                    new UiUvRect(codePoint / 128.0f, 0.0f,
                            (codePoint + 1) / 128.0f, 1.0f),
                    1, 0, 8));
        }
        BitmapFont font = new BitmapFont(128, 8, glyphs, glyphs.get((int) '?'));
        TypographyCatalog.Face display = new TypographyCatalog.Face(
                font, UiTextureId.FONT_DISPLAY);
        TypographyCatalog.Face body = new TypographyCatalog.Face(
                font, UiTextureId.FONT_BODY);
        return new TextRenderer(new TypographyCatalog(
                Map.of(
                        TypographyRole.DISPLAY_TITLE, display,
                        TypographyRole.HEADING_LARGE, display,
                        TypographyRole.BODY, body,
                        TypographyRole.FUNCTIONAL, body,
                        TypographyRole.HUD, body),
                TypographyRole.BODY));
    }

    private static String textInside(ProductUiLayout layout, UiActionId action) {
        var bounds = CONTEXT.toFramebuffer(layout.region(action).logicalBounds());
        StringBuilder text = new StringBuilder();
        layout.frame().commands().stream()
                .filter(command -> command.texture() == UiTextureId.FONT_ATLAS)
                .filter(command -> command.framebufferBounds().top() >= bounds.top())
                .filter(command -> command.framebufferBounds().bottom() <= bounds.bottom())
                .forEach(command -> text.append((char) Math.round(
                        command.uv().left() * 128.0f)));
        return text.toString();
    }

    private static String allText(ProductUiLayout layout) {
        StringBuilder text = new StringBuilder();
        layout.frame().commands().stream()
                .filter(command -> command.texture() == UiTextureId.FONT_ATLAS)
                .forEach(command -> text.append((char) Math.round(
                        command.uv().left() * 128.0f)));
        return text.toString();
    }

    static UiLayoutContext context() {
        return new UiLayoutContext(new RenderSurfaceMetrics(1280, 720, 1280, 720, 1.0f, 1.0f));
    }

    static ProductShellSnapshot snapshot(ScreenId screen) {
        return new ProductShellSnapshot(screen, Optional.empty(), Optional.empty());
    }

    private static ProductShellSnapshot snapshot(
            ScreenId screen, OperationProgressSnapshot progress) {
        return new ProductShellSnapshot(
                screen, Optional.empty(), Optional.empty(), Optional.of(progress));
    }

    private static ProductShellSnapshot snapshot(
            ScreenId screen, OperationProgressSnapshot progress, int animationStep) {
        return new ProductShellSnapshot(
                screen,
                Optional.empty(),
                Optional.empty(),
                Optional.of(progress),
                animationStep);
    }

    private static List<UiActionId> actions(ProductUiLayout layout) {
        return layout.hitRegions().stream().map(UiHitRegion::action).toList();
    }

    private static List<UiActionId> enabledActions(ProductUiLayout layout) {
        return layout.hitRegions().stream()
                .filter(UiHitRegion::enabled)
                .map(UiHitRegion::action)
                .toList();
    }
}

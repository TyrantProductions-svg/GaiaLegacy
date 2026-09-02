package com.gaia.ui.widget;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gaia.interaction.GameMode;
import com.gaia.ui.GaiaUiTheme;
import com.gaia.ui.HudPresentationSnapshot;
import com.gaia.ui.HudVisibility;
import com.overlord.renderer.ui.TextRenderer;
import com.overlord.renderer.ui.UiColor;
import com.overlord.renderer.ui.UiDrawCommand;
import com.overlord.renderer.ui.UiDrawList;
import com.overlord.renderer.ui.UiFrame;
import com.overlord.renderer.ui.UiLayoutContext;
import com.overlord.renderer.ui.UiRect;
import com.overlord.renderer.ui.UiTextureId;
import com.overlord.renderer.ui.UiUvRect;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class GameModeWidgetTest {
    @Test
    void rendersExactPersistentSurvivalAndCreativeMarkersAtFramebufferDerivedTopRight() {
        UiLayoutContext layout = Task10WidgetTestSupport.layout(320, 240, 800, 600, 1, 1);

        UiFrame survival = render(
                new GameModeWidget(Task10WidgetTestSupport.textRenderer()),
                Task10WidgetTestSupport.basic(GameMode.SURVIVAL,
                        Task10WidgetTestSupport.visible(false)),
                layout);
        assertEquals(List.of("SURVIVAL"), Task10WidgetTestSupport.fontLines(survival));
        assertEquals(new UiDrawCommand(
                        UiTextureId.SOLID,
                        new UiRect(732, 12, 788, 26),
                        new com.overlord.renderer.ui.UiUvRect(0, 0, 1, 1),
                        GaiaUiTheme.VOID_BACKGROUND,
                        Optional.empty()),
                survival.commands().get(0));
        assertEquals(new UiRect(736, 16, 742, 22), survival.commands().get(1).framebufferBounds());
        assertTrue(Task10WidgetTestSupport.fontCommands(survival).stream()
                .allMatch(command -> command.tint().equals(GaiaUiTheme.PRIMARY_TEXT)));

        UiFrame creative = render(
                new GameModeWidget(Task10WidgetTestSupport.textRenderer()),
                Task10WidgetTestSupport.basic(GameMode.CREATIVE,
                        Task10WidgetTestSupport.visible(false)),
                layout);
        assertEquals(List.of("CREATIVE ∞"), Task10WidgetTestSupport.fontLines(creative));
        assertEquals(new UiRect(720, 12, 788, 26), creative.commands().get(0).framebufferBounds());
        assertEquals(new UiRect(724, 16, 730, 22), creative.commands().get(1).framebufferBounds());
        assertTrue(Task10WidgetTestSupport.fontCommands(creative).stream()
                .allMatch(command -> command.tint().equals(GaiaUiTheme.CREATIVE_ACCENT)));
    }

    @Test
    void snapsTheMarkerFromFramebufferLogicalWidthAtFractionalDpi() {
        UiLayoutContext layout = Task10WidgetTestSupport.layout(
                333, 222, 1000, 750, 1.25f, 1.25f);

        UiFrame frame = render(
                new GameModeWidget(Task10WidgetTestSupport.textRenderer()),
                Task10WidgetTestSupport.basic(GameMode.SURVIVAL,
                        Task10WidgetTestSupport.visible(false)),
                layout);

        assertEquals(new UiRect(915, 15, 985, 33), frame.commands().get(0).framebufferBounds());
        assertEquals(new UiRect(920, 21, 928, 28), frame.commands().get(1).framebufferBounds());
        assertEquals(new UiRect(973, 21, 980, 28), frame.commands().get(8).framebufferBounds());
    }

    @Test
    void scalesMarkerTextIndependentlyAcrossAsymmetricContentScaleAxes() {
        HudPresentationSnapshot snapshot = Task10WidgetTestSupport.basic(
                GameMode.SURVIVAL, Task10WidgetTestSupport.visible(false));

        UiFrame tallerPixels = render(
                new GameModeWidget(Task10WidgetTestSupport.textRenderer()),
                snapshot,
                Task10WidgetTestSupport.layout(800, 600, 1000, 900, 1.25f, 1.5f));
        assertEquals(new UiRect(915, 18, 985, 39),
                tallerPixels.commands().get(0).framebufferBounds());
        assertEquals(new UiRect(920, 24, 928, 33),
                tallerPixels.commands().get(1).framebufferBounds());

        UiFrame widerPixels = render(
                new GameModeWidget(Task10WidgetTestSupport.textRenderer()),
                snapshot,
                Task10WidgetTestSupport.layout(800, 600, 1200, 750, 1.5f, 1.25f));
        assertEquals(new UiRect(1098, 15, 1182, 33),
                widerPixels.commands().get(0).framebufferBounds());
        assertEquals(new UiRect(1104, 21, 1113, 28),
                widerPixels.commands().get(1).framebufferBounds());
    }

    @Test
    void rendersAuthoritativeFullAndFadeNoticeValuesWithoutAdvancingThem() {
        GameModeWidget widget = new GameModeWidget(Task10WidgetTestSupport.textRenderer());
        UiLayoutContext layout = Task10WidgetTestSupport.layout(800, 600, 800, 600, 1, 1);
        HudPresentationSnapshot full = withNotice(
                GameMode.SURVIVAL,
                new HudPresentationSnapshot.ModeNotice(GameMode.SURVIVAL, 1.25, 1));
        HudPresentationSnapshot fade = withNotice(
                GameMode.SURVIVAL,
                new HudPresentationSnapshot.ModeNotice(GameMode.SURVIVAL, 0.10, 0.4));

        UiFrame fullFirst = render(widget, full, layout);
        UiFrame fullAgain = render(widget, full, layout);
        UiFrame fading = render(widget, fade, layout);

        assertEquals(fullFirst, fullAgain);
        assertEquals(List.of("SURVIVAL", "SURVIVAL"),
                Task10WidgetTestSupport.fontLines(fullFirst));
        assertEquals(new UiRect(716, 30, 788, 46), fullFirst.commands().get(9).framebufferBounds());
        assertEquals(GaiaUiTheme.VOID_BACKGROUND, fullFirst.commands().get(9).tint());
        assertEquals(GaiaUiTheme.PRIMARY_TEXT, fullFirst.commands().get(10).tint());
        assertEquals(new UiRect(720, 34, 728, 42), fullFirst.commands().get(10).framebufferBounds());

        assertEquals(new UiColor(
                        GaiaUiTheme.VOID_BACKGROUND.red(),
                        GaiaUiTheme.VOID_BACKGROUND.green(),
                        GaiaUiTheme.VOID_BACKGROUND.blue(),
                        GaiaUiTheme.VOID_BACKGROUND.alpha() * 0.4f),
                fading.commands().get(9).tint());
        assertEquals(new UiColor(
                        GaiaUiTheme.PRIMARY_TEXT.red(),
                        GaiaUiTheme.PRIMARY_TEXT.green(),
                        GaiaUiTheme.PRIMARY_TEXT.blue(),
                        0.4f),
                fading.commands().get(10).tint());
    }

    @Test
    void sameInstanceUsesCurrentModeAndCurrentNoticeInTheSameFrame() {
        GameModeWidget widget = new GameModeWidget(Task10WidgetTestSupport.textRenderer());
        UiLayoutContext layout = Task10WidgetTestSupport.layout(800, 600, 800, 600, 1, 1);

        assertEquals(List.of("SURVIVAL", "SURVIVAL"), Task10WidgetTestSupport.fontLines(render(
                widget,
                withNotice(GameMode.SURVIVAL,
                        new HudPresentationSnapshot.ModeNotice(GameMode.SURVIVAL, 1.25, 1)),
                layout)));
        UiFrame switched = render(
                widget,
                withNotice(GameMode.CREATIVE,
                        new HudPresentationSnapshot.ModeNotice(GameMode.CREATIVE, 1.25, 1)),
                layout);
        assertEquals(List.of("CREATIVE ∞", "CREATIVE ∞"),
                Task10WidgetTestSupport.fontLines(switched));
        assertTrue(Task10WidgetTestSupport.fontCommands(switched).stream()
                .allMatch(command -> command.tint().equals(GaiaUiTheme.CREATIVE_ACCENT)));
    }

    @Test
    void gameplayHudVisibilityAloneControlsImmediateHiding() {
        GameModeWidget widget = new GameModeWidget(Task10WidgetTestSupport.textRenderer());
        UiLayoutContext layout = Task10WidgetTestSupport.layout(800, 600, 800, 600, 1, 1);
        for (HudVisibility.Reason reason : List.of(
                HudVisibility.Reason.HUD_DISABLED,
                HudVisibility.Reason.CURSOR_RELEASED,
                HudVisibility.Reason.FOCUS_LOST,
                HudVisibility.Reason.LOADING,
                HudVisibility.Reason.SHUTDOWN,
                HudVisibility.Reason.BLOCKING_UI)) {
            assertEquals(List.of(), render(
                    widget,
                    Task10WidgetTestSupport.basic(
                            GameMode.SURVIVAL, Task10WidgetTestSupport.hidden(reason)),
                    layout).commands(), reason.name());
        }
        assertTrue(render(
                widget,
                Task10WidgetTestSupport.basic(
                        GameMode.SURVIVAL, Task10WidgetTestSupport.visible(true)),
                layout).commands().size() > 1);
    }

    @Test
    void outputIsCompactImmutableDeterministicAndTheWidgetRetainsOnlyTextRenderer()
            throws Exception {
        GameModeWidget widget = new GameModeWidget(Task10WidgetTestSupport.textRenderer());
        UiLayoutContext layout = Task10WidgetTestSupport.layout(800, 600, 800, 600, 1, 1);
        HudPresentationSnapshot snapshot = Task10WidgetTestSupport.basic(
                GameMode.SURVIVAL, Task10WidgetTestSupport.visible(false));
        UiFrame first = render(widget, snapshot, layout);

        assertEquals(first, render(widget, snapshot, layout));
        assertTrue(first.commands().stream().allMatch(command ->
                command.framebufferBounds().left() >= 0
                        && command.framebufferBounds().top() >= 0
                        && command.framebufferBounds().right() <= 800
                        && command.framebufferBounds().bottom() <= 600));
        assertEquals(new UiRect(732, 12, 788, 26), first.commands().get(0).framebufferBounds());
        assertThrows(UnsupportedOperationException.class, () -> first.commands().clear());

        int constant = Modifier.PRIVATE | Modifier.STATIC | Modifier.FINAL;
        int dependency = Modifier.PRIVATE | Modifier.FINAL;
        Task10WidgetTestSupport.assertExactProductionBoundary(
                GameModeWidget.class,
                "GameModeWidget.java",
                Map.ofEntries(
                        Map.entry("MARGIN", Task10WidgetTestSupport.field(double.class, constant)),
                        Map.entry("PADDING", Task10WidgetTestSupport.field(double.class, constant)),
                        Map.entry("PERSISTENT_SCALE",
                                Task10WidgetTestSupport.field(double.class, constant)),
                        Map.entry("NOTICE_SCALE",
                                Task10WidgetTestSupport.field(double.class, constant)),
                        Map.entry("NOTICE_GAP",
                                Task10WidgetTestSupport.field(double.class, constant)),
                        Map.entry("SOLID_UV", Task10WidgetTestSupport.field(UiUvRect.class, constant)),
                        Map.entry("text", Task10WidgetTestSupport.field(
                                TextRenderer.class, dependency))),
                Set.of(
                        "com.gaia.interaction.GameMode",
                        "com.gaia.ui.GaiaUiTheme",
                        "com.gaia.ui.HudPresentationSnapshot",
                        "com.overlord.renderer.ui.TextRenderer",
                        "com.overlord.renderer.ui.TypographyRole",
                        "com.overlord.renderer.ui.UiColor",
                        "com.overlord.renderer.ui.UiDrawCommand",
                        "com.overlord.renderer.ui.UiDrawList",
                        "com.overlord.renderer.ui.UiLayoutContext",
                        "com.overlord.renderer.ui.UiRect",
                        "com.overlord.renderer.ui.UiTextureId",
                        "com.overlord.renderer.ui.UiUvRect",
                        "java.util.Objects",
                        "java.util.Optional"),
                Set.of(
                        double.class,
                        void.class,
                        String.class,
                        GameMode.class,
                        HudPresentationSnapshot.class,
                        HudPresentationSnapshot.ModeNotice.class,
                        TextRenderer.class,
                        UiColor.class,
                        UiDrawList.class,
                        UiLayoutContext.class,
                        UiUvRect.class));
    }

    private static HudPresentationSnapshot withNotice(
            GameMode mode, HudPresentationSnapshot.ModeNotice notice) {
        return Task10WidgetTestSupport.snapshot(
                mode,
                HudPresentationSnapshot.InteractionPresentation.cleared(),
                Task10WidgetTestSupport.visible(false),
                Optional.of(notice),
                Task10WidgetTestSupport.emptyDebug());
    }

    private static UiFrame render(
            GameModeWidget widget,
            HudPresentationSnapshot snapshot,
            UiLayoutContext layout) {
        UiDrawList out = new UiDrawList();
        widget.append(snapshot, layout, out);
        return out.seal();
    }
}

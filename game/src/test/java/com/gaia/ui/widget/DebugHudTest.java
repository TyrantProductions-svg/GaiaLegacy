package com.gaia.ui.widget;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gaia.ui.HudDebugSnapshot;
import com.gaia.ui.HudPresentationSnapshot;
import com.gaia.ui.HudVisibility;
import com.overlord.renderer.metrics.RenderMetricsSnapshot;
import com.overlord.renderer.ui.TextRenderer;
import com.overlord.renderer.ui.UiDrawCommand;
import com.overlord.renderer.ui.UiDrawList;
import com.overlord.renderer.ui.UiFrame;
import com.overlord.renderer.ui.UiLayoutContext;
import com.overlord.renderer.ui.UiRect;
import com.overlord.renderer.ui.UiTextureId;
import com.overlord.renderer.ui.UiUvRect;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

class DebugHudTest {
    private static final List<String> POPULATED_LINES = List.of(
            "FRAME (PREV): FPS 59.9",
            "FRAME TIME: 16.68 ms",
            "DRAW CALLS: 23",
            "TRIANGLES: 1234567",
            "VISIBLE CHUNKS: 17",
            "LOADED CHUNKS: 31",
            "MESH QUEUE: 4",
            "PHYSICS BODIES: 7",
            "PLAYER FEET: 1.23, -2.35, 9.88",
            "WORLD ITEMS: 9",
            "TARGET: YES",
            "FEEDBACK: DAMAGE 1 | ITEMS 2 | PARTICLES 3");

    @Test
    @ResourceLock(Resources.LOCALE)
    void rendersExactTwelveOrderedPreviousFrameAndAuthoritativeSnapshotLines() {
        Locale previous = Locale.getDefault(Locale.Category.FORMAT);
        Locale.setDefault(Locale.Category.FORMAT, Locale.GERMANY);
        try {
            UiFrame frame = render(
                    new DebugHud(Task10WidgetTestSupport.textRenderer()),
                    populated(true, Task10WidgetTestSupport.visible(true)),
                    Task10WidgetTestSupport.layout(800, 600, 800, 600, 1, 1));

            assertEquals(POPULATED_LINES, Task10WidgetTestSupport.fontLines(frame));
            assertEquals(UiTextureId.SOLID, frame.commands().get(0).texture());
            assertEquals(com.gaia.ui.GaiaUiTheme.DEBUG_BACKGROUND,
                    frame.commands().get(0).tint());
            assertEquals(new UiRect(12, 12, 276, 132),
                    frame.commands().get(0).framebufferBounds());
            assertTrue(Task10WidgetTestSupport.fontCommands(frame).stream().allMatch(command ->
                    command.tint().equals(com.gaia.ui.GaiaUiTheme.DEBUG_TEXT)));
            assertEquals(new UiRect(18, 18, 24, 24),
                    Task10WidgetTestSupport.fontCommands(frame).get(0).framebufferBounds());
        } finally {
            Locale.setDefault(Locale.Category.FORMAT, previous);
        }
    }

    @Test
    void missingPreviousMetricsUseOnlyTheSixRequiredNaValuesWhileRealZerosRemainZero() {
        HudDebugSnapshot debug = new HudDebugSnapshot(
                Optional.empty(),
                new HudDebugSnapshot.FeetPosition(0, 0, 0),
                new HudDebugSnapshot.Counts(0, 0, 0, 0, 0, 0));
        UiFrame frame = render(
                new DebugHud(Task10WidgetTestSupport.textRenderer()),
                Task10WidgetTestSupport.debug(
                        Task10WidgetTestSupport.visible(true), debug, false),
                Task10WidgetTestSupport.layout(800, 600, 800, 600, 1, 1));

        assertEquals(List.of(
                        "FRAME (PREV): FPS N/A",
                        "FRAME TIME: N/A ms",
                        "DRAW CALLS: N/A",
                        "TRIANGLES: N/A",
                        "VISIBLE CHUNKS: N/A",
                        "LOADED CHUNKS: 0",
                        "MESH QUEUE: N/A",
                        "PHYSICS BODIES: 0",
                        "PLAYER FEET: 0.00, 0.00, 0.00",
                        "WORLD ITEMS: 0",
                        "TARGET: NO",
                        "FEEDBACK: DAMAGE 0 | ITEMS 0 | PARTICLES 0"),
                Task10WidgetTestSupport.fontLines(frame));
    }

    @Test
    void targetAndEachDistinctFeedbackAndGameplayCountComeFromTheirOwnCurrentFields() {
        DebugHud widget = new DebugHud(Task10WidgetTestSupport.textRenderer());
        UiLayoutContext layout = Task10WidgetTestSupport.layout(800, 600, 800, 600, 1, 1);
        UiFrame withoutTarget = render(widget, populated(false,
                Task10WidgetTestSupport.visible(true)), layout);
        HudDebugSnapshot changedDebug = new HudDebugSnapshot(
                Optional.of(new RenderMetricsSnapshot(1, 2, 3, 4, 5, 6)),
                new HudDebugSnapshot.FeetPosition(7, 8, 9),
                new HudDebugSnapshot.Counts(10, 11, 12, 13, 14, 15));
        UiFrame changed = render(widget, Task10WidgetTestSupport.debug(
                Task10WidgetTestSupport.visible(true), changedDebug, true), layout);

        assertEquals("TARGET: NO", Task10WidgetTestSupport.fontLines(withoutTarget).get(10));
        assertEquals("LOADED CHUNKS: 10", Task10WidgetTestSupport.fontLines(changed).get(5));
        assertEquals("PHYSICS BODIES: 11", Task10WidgetTestSupport.fontLines(changed).get(7));
        assertEquals("WORLD ITEMS: 12", Task10WidgetTestSupport.fontLines(changed).get(9));
        assertEquals("TARGET: YES", Task10WidgetTestSupport.fontLines(changed).get(10));
        assertEquals("FEEDBACK: DAMAGE 13 | ITEMS 14 | PARTICLES 15",
                Task10WidgetTestSupport.fontLines(changed).get(11));
        assertEquals("PLAYER FEET: 7.00, 8.00, 9.00",
                Task10WidgetTestSupport.fontLines(changed).get(8));
    }

    @Test
    void f3TruthControlsVisibilityWhileF2RemainsIndependentAndUnsafeStatesHide() {
        DebugHud widget = new DebugHud(Task10WidgetTestSupport.textRenderer());
        UiLayoutContext layout = Task10WidgetTestSupport.layout(800, 600, 800, 600, 1, 1);
        assertEquals(List.of(), render(widget,
                populated(false, Task10WidgetTestSupport.visible(false)), layout).commands());
        assertEquals(POPULATED_LINES, Task10WidgetTestSupport.fontLines(render(widget,
                populated(true, Task10WidgetTestSupport.hudDisabledDebugVisible()), layout)));

        for (HudVisibility.Reason reason : List.of(
                HudVisibility.Reason.CURSOR_RELEASED,
                HudVisibility.Reason.FOCUS_LOST,
                HudVisibility.Reason.LOADING,
                HudVisibility.Reason.SHUTDOWN,
                HudVisibility.Reason.BLOCKING_UI)) {
            assertEquals(List.of(), render(widget,
                    populated(true, Task10WidgetTestSupport.hidden(reason)), layout).commands(),
                    reason.name());
        }
    }

    @Test
    void snapsExactTopLeftPanelAndTextAtAllRequiredScalesAndWindowMismatch() {
        DebugHud widget = new DebugHud(Task10WidgetTestSupport.textRenderer());
        HudPresentationSnapshot snapshot = populated(true, Task10WidgetTestSupport.visible(true));
        List<UiLayoutContext> layouts = List.of(
                Task10WidgetTestSupport.layout(800, 600, 800, 600, 1, 1),
                Task10WidgetTestSupport.layout(320, 240, 1000, 750, 1.25f, 1.25f),
                Task10WidgetTestSupport.layout(640, 480, 1200, 900, 1.5f, 1.5f),
                Task10WidgetTestSupport.layout(400, 300, 1600, 1200, 2, 2));
        List<UiRect> expectedPanels = List.of(
                new UiRect(12, 12, 276, 132),
                new UiRect(15, 15, 345, 165),
                new UiRect(18, 18, 414, 198),
                new UiRect(24, 24, 552, 264));
        List<UiRect> expectedFirstGlyphs = List.of(
                new UiRect(18, 18, 24, 24),
                new UiRect(23, 23, 31, 30),
                new UiRect(27, 27, 36, 36),
                new UiRect(36, 36, 48, 48));

        for (int index = 0; index < layouts.size(); index++) {
            UiFrame frame = render(widget, snapshot, layouts.get(index));
            assertEquals(expectedPanels.get(index), frame.commands().get(0).framebufferBounds());
            assertEquals(expectedFirstGlyphs.get(index),
                    Task10WidgetTestSupport.fontCommands(frame).get(0).framebufferBounds());
            assertEquals(POPULATED_LINES, Task10WidgetTestSupport.fontLines(frame));
        }
    }

    @Test
    void positionsAllTwelveDecodedLinesAtTheExactNineLogicalPixelStep() {
        DebugHud widget = new DebugHud(Task10WidgetTestSupport.textRenderer());
        HudPresentationSnapshot snapshot = populated(true, Task10WidgetTestSupport.visible(true));
        UiFrame identity = render(widget, snapshot,
                Task10WidgetTestSupport.layout(800, 600, 800, 600, 1, 1));
        UiFrame fractional = render(widget, snapshot,
                Task10WidgetTestSupport.layout(320, 240, 1000, 750, 1.25f, 1.25f));

        assertEquals(POPULATED_LINES, Task10WidgetTestSupport.fontLines(identity));
        assertEquals(List.of(
                        new UiRect(18, 18, 24, 24),
                        new UiRect(18, 27, 24, 33),
                        new UiRect(18, 36, 24, 42),
                        new UiRect(18, 45, 24, 51),
                        new UiRect(18, 54, 24, 60),
                        new UiRect(18, 63, 24, 69),
                        new UiRect(18, 72, 24, 78),
                        new UiRect(18, 81, 24, 87),
                        new UiRect(18, 90, 24, 96),
                        new UiRect(18, 99, 24, 105),
                        new UiRect(18, 108, 24, 114),
                        new UiRect(18, 117, 24, 123)),
                firstGlyphBoundsByLine(identity));

        assertEquals(POPULATED_LINES, Task10WidgetTestSupport.fontLines(fractional));
        assertEquals(List.of(
                        new UiRect(23, 23, 31, 30),
                        new UiRect(23, 34, 31, 41),
                        new UiRect(23, 46, 31, 53),
                        new UiRect(23, 57, 31, 64),
                        new UiRect(23, 68, 31, 75),
                        new UiRect(23, 79, 31, 86),
                        new UiRect(23, 91, 31, 98),
                        new UiRect(23, 102, 31, 109),
                        new UiRect(23, 113, 31, 120),
                        new UiRect(23, 124, 31, 131),
                        new UiRect(23, 136, 31, 143),
                        new UiRect(23, 147, 31, 154)),
                firstGlyphBoundsByLine(fractional));
    }

    @Test
    void rejectsWidthOnlyBelowTheExactMinimumBeforeEmittingCommands() {
        DebugHud widget = new DebugHud(Task10WidgetTestSupport.textRenderer());
        HudPresentationSnapshot snapshot = populated(true, Task10WidgetTestSupport.visible(true));
        UiDrawList out = new UiDrawList();

        assertThrows(IllegalArgumentException.class, () -> widget.append(
                snapshot,
                Task10WidgetTestSupport.layout(287, 144, 287, 144, 1, 1),
                out));
        assertEquals(List.of(), out.seal().commands());
    }

    @Test
    void rejectsHeightOnlyBelowTheExactMinimumBeforeEmittingCommands() {
        DebugHud widget = new DebugHud(Task10WidgetTestSupport.textRenderer());
        HudPresentationSnapshot snapshot = populated(true, Task10WidgetTestSupport.visible(true));
        UiDrawList out = new UiDrawList();

        assertThrows(IllegalArgumentException.class, () -> widget.append(
                snapshot,
                Task10WidgetTestSupport.layout(288, 143, 288, 143, 1, 1),
                out));
        assertEquals(List.of(), out.seal().commands());
    }

    @Test
    void exactMinimumSurfaceContainsEveryCommandWithinBounds() {
        UiFrame frame = render(
                new DebugHud(Task10WidgetTestSupport.textRenderer()),
                populated(true, Task10WidgetTestSupport.visible(true)),
                Task10WidgetTestSupport.layout(288, 144, 288, 144, 1, 1));

        assertEquals(new UiRect(12, 12, 276, 132),
                frame.commands().get(0).framebufferBounds());
        assertEquals(POPULATED_LINES, Task10WidgetTestSupport.fontLines(frame));
        assertTrue(frame.commands().stream().allMatch(command ->
                command.framebufferBounds().left() >= 0
                        && command.framebufferBounds().top() >= 0
                        && command.framebufferBounds().right() <= 288
                        && command.framebufferBounds().bottom() <= 144));
    }

    @Test
    void outputIsImmutableDeterministicAndTheWidgetRetainsNoMetricsTargetOrSnapshot()
            throws Exception {
        DebugHud widget = new DebugHud(Task10WidgetTestSupport.textRenderer());
        HudPresentationSnapshot snapshot = populated(true, Task10WidgetTestSupport.visible(true));
        UiLayoutContext layout = Task10WidgetTestSupport.layout(800, 600, 800, 600, 1, 1);
        UiFrame first = render(widget, snapshot, layout);

        assertEquals(first, render(widget, snapshot, layout));
        assertThrows(UnsupportedOperationException.class, () -> first.commands().clear());
        assertTrue(first.commands().stream().allMatch(command ->
                command.framebufferBounds().left() >= 0
                        && command.framebufferBounds().top() >= 0
                        && command.framebufferBounds().right() <= 800
                        && command.framebufferBounds().bottom() <= 600));

        int constant = Modifier.PRIVATE | Modifier.STATIC | Modifier.FINAL;
        int dependency = Modifier.PRIVATE | Modifier.FINAL;
        Task10WidgetTestSupport.assertExactProductionBoundary(
                DebugHud.class,
                "DebugHud.java",
                Map.ofEntries(
                        Map.entry("MARGIN", Task10WidgetTestSupport.field(double.class, constant)),
                        Map.entry("PADDING", Task10WidgetTestSupport.field(double.class, constant)),
                        Map.entry("SCALE", Task10WidgetTestSupport.field(double.class, constant)),
                        Map.entry("GLYPH_HEIGHT",
                                Task10WidgetTestSupport.field(double.class, constant)),
                        Map.entry("LINE_STEP",
                                Task10WidgetTestSupport.field(double.class, constant)),
                        Map.entry("LINE_COUNT", Task10WidgetTestSupport.field(int.class, constant)),
                        Map.entry("SOLID_UV", Task10WidgetTestSupport.field(UiUvRect.class, constant)),
                        Map.entry("text", Task10WidgetTestSupport.field(
                                TextRenderer.class, dependency))),
                Set.of(
                        "com.gaia.ui.GaiaUiTheme",
                        "com.gaia.ui.HudDebugSnapshot",
                        "com.gaia.ui.HudPresentationSnapshot",
                        "com.overlord.renderer.metrics.RenderMetricsSnapshot",
                        "com.overlord.renderer.ui.TextRenderer",
                        "com.overlord.renderer.ui.UiDrawCommand",
                        "com.overlord.renderer.ui.UiDrawList",
                        "com.overlord.renderer.ui.UiLayoutContext",
                        "com.overlord.renderer.ui.UiRect",
                        "com.overlord.renderer.ui.UiTextureId",
                        "com.overlord.renderer.ui.UiUvRect",
                        "java.util.List",
                        "java.util.Locale",
                        "java.util.Objects",
                        "java.util.Optional"),
                Set.of(
                        double.class,
                        int.class,
                        void.class,
                        String.class,
                        List.class,
                        HudPresentationSnapshot.class,
                        RenderMetricsSnapshot.class,
                        TextRenderer.class,
                        UiDrawList.class,
                        UiLayoutContext.class,
                        UiUvRect.class));
    }

    private static HudPresentationSnapshot populated(boolean target, HudVisibility visibility) {
        HudDebugSnapshot debug = new HudDebugSnapshot(
                Optional.of(new RenderMetricsSnapshot(59.94, 16.678, 17, 23, 1_234_567, 4)),
                new HudDebugSnapshot.FeetPosition(1.234, -2.346, 9.876),
                new HudDebugSnapshot.Counts(31, 7, 9, 1, 2, 3));
        return Task10WidgetTestSupport.debug(visibility, debug, target);
    }

    private static UiFrame render(
            DebugHud widget,
            HudPresentationSnapshot snapshot,
            UiLayoutContext layout) {
        UiDrawList out = new UiDrawList();
        widget.append(snapshot, layout, out);
        return out.seal();
    }

    private static List<UiRect> firstGlyphBoundsByLine(UiFrame frame) {
        List<UiRect> firstGlyphs = new ArrayList<>();
        Double previousTop = null;
        for (UiDrawCommand command : Task10WidgetTestSupport.fontCommands(frame)) {
            if (previousTop == null
                    || command.framebufferBounds().top() != previousTop.doubleValue()) {
                firstGlyphs.add(command.framebufferBounds());
                previousTop = command.framebufferBounds().top();
            }
        }
        return List.copyOf(firstGlyphs);
    }
}

package com.gaia.ui.widget;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gaia.interaction.GameMode;
import com.gaia.ui.HudPresentationSnapshot;
import com.gaia.ui.HudVisibility;
import com.overlord.interaction.api.InteractionMode;
import com.overlord.renderer.ui.UiColor;
import com.overlord.renderer.ui.UiDrawCommand;
import com.overlord.renderer.ui.UiDrawList;
import com.overlord.renderer.ui.UiFrame;
import com.overlord.renderer.ui.UiLayoutContext;
import com.overlord.renderer.ui.UiRect;
import com.overlord.renderer.ui.UiTextureId;
import com.overlord.renderer.ui.UiUvRect;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class BreakProgressWidgetTest {
    private static final UiColor WHITE = new UiColor(1, 1, 1, 1);
    private static final UiUvRect FULL_UV = new UiUvRect(0, 0, 1, 1);

    @Test
    void drawsExactEvenFramebufferTrackAndLeftToRightFillAtRepresentativeProgress() {
        UiLayoutContext layout = WidgetTestSnapshots.layout(1024, 768, 1024, 768, 1, 1);

        assertEquals(
                List.of(
                        new UiRect(498, 399, 526, 401),
                        new UiRect(498, 399, 505, 401)),
                bounds(render(breaking(4, 0.25, WidgetTestSnapshots.visible()), layout)));
        assertEquals(
                List.of(
                        new UiRect(498, 399, 526, 401),
                        new UiRect(498, 399, 512, 401)),
                bounds(render(breaking(4, 0.5, WidgetTestSnapshots.visible()), layout)));
        assertEquals(
                List.of(
                        new UiRect(498, 399, 526, 401),
                        new UiRect(498, 399, 519, 401)),
                bounds(render(breaking(4, 0.75, WidgetTestSnapshots.visible()), layout)));
    }

    @Test
    void preservesOddHalfPixelCenterAndExactFractionalFill() {
        UiLayoutContext layout = WidgetTestSnapshots.layout(1001, 701, 1001, 701, 1, 1);

        assertEquals(
                List.of(
                        new UiRect(486.5, 365.5, 514.5, 367.5),
                        new UiRect(486.5, 365.5, 493.5, 367.5)),
                bounds(render(breaking(4, 0.25, WidgetTestSnapshots.visible()), layout)));
        assertEquals(
                List.of(
                        new UiRect(486.5, 365.5, 514.5, 367.5),
                        new UiRect(486.5, 365.5, 507.5, 367.5)),
                bounds(render(breaking(4, 0.75, WidgetTestSnapshots.visible()), layout)));
    }

    @Test
    void usesCurrentFramebufferCenterAcrossLogicalWindowAndDpiMismatch() {
        UiLayoutContext layout =
                WidgetTestSnapshots.layout(640, 480, 1024, 768, 1.25f, 1.5f);

        assertEquals(
                List.of(
                        new UiRect(498, 399, 526, 401),
                        new UiRect(498, 399, 512, 401)),
                bounds(render(breaking(4, 0.5, WidgetTestSnapshots.visible()), layout)));
    }

    @Test
    void emitsTrackThenWhiteFillWithExactTintUvClipAndOffsetContract() {
        UiFrame frame = render(
                breaking(4, 0.5, WidgetTestSnapshots.visible()),
                WidgetTestSnapshots.layout(1024, 768, 1024, 768, 1, 1));

        assertEquals(2, frame.commands().size());
        UiDrawCommand track = frame.commands().get(0);
        UiDrawCommand fill = frame.commands().get(1);
        assertEquals(UiTextureId.SOLID, track.texture());
        assertEquals(new UiRect(498, 399, 526, 401), track.framebufferBounds());
        assertEquals(new UiColor(1, 1, 1, 0.22f), track.tint());
        assertEquals(FULL_UV, track.uv());
        assertEquals(Optional.empty(), track.clip());
        assertEquals(UiTextureId.SOLID, fill.texture());
        assertEquals(new UiRect(498, 399, 512, 401), fill.framebufferBounds());
        assertEquals(WHITE, fill.tint());
        assertEquals(FULL_UV, fill.uv());
        assertEquals(Optional.empty(), fill.clip());
    }

    @Test
    void hidesUnlessThereIsACurrentSurvivalTimedBreakStrictlyBetweenZeroAndOne() {
        UiLayoutContext layout = WidgetTestSnapshots.layout(800, 600, 800, 600, 1, 1);
        List<HudPresentationSnapshot> hidden = List.of(
                WidgetTestSnapshots.interaction(
                        GameMode.SURVIVAL, false, 1, 0.5, InteractionMode.BREAKING,
                        WidgetTestSnapshots.visible()),
                WidgetTestSnapshots.visibleCleared(),
                breaking(1, 0, WidgetTestSnapshots.visible()),
                breaking(1, 1, WidgetTestSnapshots.visible()),
                WidgetTestSnapshots.interaction(
                        GameMode.CREATIVE, true, 1, 0.5, InteractionMode.BREAKING,
                        WidgetTestSnapshots.visible()),
                WidgetTestSnapshots.interaction(
                        GameMode.SURVIVAL, true, 1, 0.5, InteractionMode.NONE,
                        WidgetTestSnapshots.visible()),
                WidgetTestSnapshots.interaction(
                        GameMode.SURVIVAL, true, 1, 0.5, InteractionMode.PLACING,
                        WidgetTestSnapshots.visible()),
                WidgetTestSnapshots.interaction(
                        GameMode.SURVIVAL, true, 1, 0.5, InteractionMode.USING,
                        WidgetTestSnapshots.visible()));

        for (HudPresentationSnapshot snapshot : hidden) {
            assertEquals(List.of(), render(snapshot, layout).commands());
        }
        assertEquals(
                2,
                render(breaking(1, 0.0001, WidgetTestSnapshots.visible()), layout)
                        .commands().size());
        assertEquals(
                2,
                render(breaking(1, 0.9999, WidgetTestSnapshots.visible()), layout)
                        .commands().size());
    }

    @Test
    void hidesAtEveryUnsafeLifecycleBoundaryAndRestoresOnlyCurrentProgress() {
        List<HudVisibility.Reason> hiddenReasons = List.of(
                HudVisibility.Reason.CURSOR_RELEASED,
                HudVisibility.Reason.FOCUS_LOST,
                HudVisibility.Reason.LOADING,
                HudVisibility.Reason.SHUTDOWN,
                HudVisibility.Reason.BLOCKING_UI,
                HudVisibility.Reason.HUD_DISABLED);
        UiLayoutContext layout = WidgetTestSnapshots.layout(800, 600, 800, 600, 1, 1);

        for (HudVisibility.Reason reason : hiddenReasons) {
            assertEquals(
                    List.of(),
                    render(breaking(1, 0.5, WidgetTestSnapshots.hidden(reason)), layout)
                            .commands(),
                    reason.name());
            assertEquals(
                    new UiRect(386, 315, 400, 317),
                    render(breaking(1, 0.5, WidgetTestSnapshots.visible()), layout)
                            .commands().get(1).framebufferBounds(),
                    reason.name());
        }
    }

    @Test
    void targetChangeAtZeroHidesThenNewProgressAppearsWithoutStaleState() {
        UiLayoutContext layout = WidgetTestSnapshots.layout(800, 600, 800, 600, 1, 1);
        BreakProgressWidget widget = new BreakProgressWidget();
        UiFrame oldTarget =
                render(widget, breaking(1, 0.75, WidgetTestSnapshots.visible()), layout);
        UiFrame resetTarget =
                render(widget, breaking(99, 0, WidgetTestSnapshots.visible()), layout);
        UiFrame hiddenTarget = render(
                widget,
                breaking(
                        99,
                        0.25,
                        WidgetTestSnapshots.hidden(HudVisibility.Reason.FOCUS_LOST)),
                layout);
        UiFrame newTarget =
                render(widget, breaking(99, 0.25, WidgetTestSnapshots.visible()), layout);

        assertEquals(new UiRect(386, 315, 407, 317),
                oldTarget.commands().get(1).framebufferBounds());
        assertEquals(List.of(), resetTarget.commands());
        assertEquals(List.of(), hiddenTarget.commands());
        assertEquals(new UiRect(386, 315, 393, 317),
                newTarget.commands().get(1).framebufferBounds());
    }

    @Test
    void rejectsAnyStateBeyondTheExactImmutableConstantWhitelist()
            throws Exception {
        UiLayoutContext layout = WidgetTestSnapshots.layout(800, 600, 800, 600, 1, 1);
        HudPresentationSnapshot snapshot = breaking(1, 0.5, WidgetTestSnapshots.visible());
        BreakProgressWidget widget = new BreakProgressWidget();
        UiFrame first = render(widget, snapshot, layout);

        assertEquals(first, render(widget, snapshot, layout));
        assertThrows(UnsupportedOperationException.class, () -> first.commands().clear());
        var fields = BreakProgressWidget.class.getDeclaredFields();
        assertEquals(
                Set.of(
                        "TRACK_WIDTH",
                        "TRACK_HEIGHT",
                        "TRACK_HALF_WIDTH",
                        "TRACK_TOP_OFFSET",
                        "TRACK_TINT",
                        "WHITE",
                        "SOLID_UV"),
                Arrays.stream(fields).map(field -> field.getName()).collect(java.util.stream.Collectors.toSet()));
        for (var field : fields) {
            assertEquals(
                    Modifier.PRIVATE | Modifier.STATIC | Modifier.FINAL,
                    field.getModifiers(),
                    field.getName());
            assertTrue(field.trySetAccessible(), field.getName());
            switch (field.getName()) {
                case "TRACK_WIDTH" -> assertConstant(field, double.class, 28.0);
                case "TRACK_HEIGHT" -> assertConstant(field, double.class, 2.0);
                case "TRACK_HALF_WIDTH" -> assertConstant(field, double.class, 14.0);
                case "TRACK_TOP_OFFSET" -> assertConstant(field, double.class, 15.0);
                case "TRACK_TINT" ->
                        assertConstant(field, UiColor.class, new UiColor(1, 1, 1, 0.22f));
                case "WHITE" -> assertConstant(field, UiColor.class, WHITE);
                case "SOLID_UV" -> assertConstant(field, UiUvRect.class, FULL_UV);
                default -> throw new AssertionError("unexpected break-progress field " + field.getName());
            }
        }
        assertEquals(0, BreakProgressWidget.class.getConstructor().getParameterCount());
        assertEquals(
                void.class,
                BreakProgressWidget.class.getMethod(
                        "append",
                        HudPresentationSnapshot.class,
                        UiLayoutContext.class,
                        UiDrawList.class).getReturnType());
    }

    private static HudPresentationSnapshot breaking(
            int targetX, double progress, HudVisibility visibility) {
        return WidgetTestSnapshots.interaction(
                GameMode.SURVIVAL,
                true,
                targetX,
                progress,
                InteractionMode.BREAKING,
                visibility);
    }

    private static UiFrame render(
            HudPresentationSnapshot snapshot, UiLayoutContext layout) {
        return render(new BreakProgressWidget(), snapshot, layout);
    }

    private static UiFrame render(
            BreakProgressWidget widget,
            HudPresentationSnapshot snapshot,
            UiLayoutContext layout) {
        UiDrawList out = new UiDrawList();
        widget.append(snapshot, layout, out);
        return out.seal();
    }

    private static void assertConstant(
            java.lang.reflect.Field field, Class<?> type, Object value)
            throws IllegalAccessException {
        assertEquals(type, field.getType(), field.getName());
        assertEquals(value, field.get(null), field.getName());
    }

    private static List<UiRect> bounds(UiFrame frame) {
        return frame.commands().stream().map(UiDrawCommand::framebufferBounds).toList();
    }
}

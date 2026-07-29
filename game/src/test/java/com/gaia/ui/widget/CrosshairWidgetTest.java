package com.gaia.ui.widget;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gaia.interaction.GameMode;
import com.gaia.ui.HudPresentationSnapshot;
import com.gaia.ui.HudVisibility;
import com.overlord.interaction.api.InteractionMode;
import com.overlord.renderer.feedback.CrosshairGeometry;
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

class CrosshairWidgetTest {
    private static final UiColor WHITE = new UiColor(1, 1, 1, 1);
    private static final UiUvRect FULL_UV = new UiUvRect(0, 0, 1, 1);

    @Test
    void projectsExactEvenFramebufferGeometryAndMatchesPhaseNineAuthority() {
        UiFrame frame = render(
                WidgetTestSnapshots.visibleCleared(),
                WidgetTestSnapshots.layout(1024, 768, 1024, 768, 1, 1));

        assertEquals(
                List.of(
                        new UiRect(504, 383, 510, 385),
                        new UiRect(514, 383, 520, 385),
                        new UiRect(511, 376, 513, 382),
                        new UiRect(511, 386, 513, 392)),
                bounds(frame));
        assertEquals(
                CrosshairGeometry.quads(1024, 768).stream()
                        .map(quad -> new UiRect(
                                quad.xMin(), quad.yMin(), quad.xMax(), quad.yMax()))
                        .toList(),
                bounds(frame));
    }

    @Test
    void preservesOddFramebufferHalfPixelCenter() {
        UiFrame frame = render(
                WidgetTestSnapshots.visibleCleared(),
                WidgetTestSnapshots.layout(1001, 701, 1001, 701, 1, 1));

        assertEquals(
                List.of(
                        new UiRect(492.5, 349.5, 498.5, 351.5),
                        new UiRect(502.5, 349.5, 508.5, 351.5),
                        new UiRect(499.5, 342.5, 501.5, 348.5),
                        new UiRect(499.5, 352.5, 501.5, 358.5)),
                bounds(frame));
    }

    @Test
    void ignoresLogicalWindowAndContentScaleForFramebufferPixelGeometry() {
        List<UiRect> expected = List.of(
                new UiRect(504, 383, 510, 385),
                new UiRect(514, 383, 520, 385),
                new UiRect(511, 376, 513, 382),
                new UiRect(511, 386, 513, 392));
        List<UiLayoutContext> layouts = List.of(
                WidgetTestSnapshots.layout(1024, 768, 1024, 768, 1, 1),
                WidgetTestSnapshots.layout(640, 480, 1024, 768, 1.25f, 1.25f),
                WidgetTestSnapshots.layout(800, 450, 1024, 768, 1.5f, 1.5f),
                WidgetTestSnapshots.layout(320, 240, 1024, 768, 2, 2));

        for (UiLayoutContext layout : layouts) {
            assertEquals(expected, bounds(render(WidgetTestSnapshots.visibleCleared(), layout)));
        }
    }

    @Test
    void recomputesCenterFromEveryCurrentFramebufferTransition() {
        assertEquals(
                new UiRect(392, 299, 398, 301),
                bounds(render(
                        WidgetTestSnapshots.visibleCleared(),
                        WidgetTestSnapshots.layout(800, 600, 800, 600, 1, 1))).get(0));
        assertEquals(
                new UiRect(1432, 809, 1438, 811),
                bounds(render(
                        WidgetTestSnapshots.visibleCleared(),
                        WidgetTestSnapshots.layout(1920, 1080, 2880, 1620, 1.5f, 1.5f))).get(0));
        assertEquals(
                new UiRect(1912, 1079, 1918, 1081),
                bounds(render(
                        WidgetTestSnapshots.visibleCleared(),
                        WidgetTestSnapshots.layout(3072, 1440, 3840, 2160, 1.25f, 1.5f))).get(0));
    }

    @Test
    void hidesImmediatelyAtEveryUnsafeLifecycleBoundaryAndRestoresOnEligibleSnapshot() {
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
                    render(WidgetTestSnapshots.withVisibility(
                            WidgetTestSnapshots.hidden(reason)), layout).commands(),
                    reason.name());
            assertEquals(
                    4,
                    render(WidgetTestSnapshots.visibleCleared(), layout).commands().size(),
                    reason.name());
        }
    }

    @Test
    void emitsExactlyFourDeterministicImmutableWhiteSolidCommandsWithoutTargetDependency() {
        UiLayoutContext layout = WidgetTestSnapshots.layout(800, 600, 800, 600, 1, 1);
        CrosshairWidget widget = new CrosshairWidget();
        HudPresentationSnapshot targetA = WidgetTestSnapshots.interaction(
                GameMode.SURVIVAL,
                true,
                9,
                0.75,
                InteractionMode.BREAKING,
                WidgetTestSnapshots.visible());
        HudPresentationSnapshot targetBReset = WidgetTestSnapshots.interaction(
                GameMode.SURVIVAL,
                true,
                99,
                0,
                InteractionMode.BREAKING,
                WidgetTestSnapshots.visible());
        HudPresentationSnapshot targetBHidden = WidgetTestSnapshots.interaction(
                GameMode.SURVIVAL,
                true,
                99,
                0.25,
                InteractionMode.BREAKING,
                WidgetTestSnapshots.hidden(HudVisibility.Reason.FOCUS_LOST));
        HudPresentationSnapshot targetBRecaptured = WidgetTestSnapshots.interaction(
                GameMode.SURVIVAL,
                true,
                99,
                0.25,
                InteractionMode.BREAKING,
                WidgetTestSnapshots.visible());
        UiFrame first = render(widget, targetA, layout);
        UiFrame reset = render(widget, targetBReset, layout);
        UiFrame hidden = render(widget, targetBHidden, layout);
        UiFrame recaptured = render(widget, targetBRecaptured, layout);

        assertEquals(first, reset);
        assertEquals(List.of(), hidden.commands());
        assertEquals(first, recaptured);
        assertEquals(first, render(widget, targetBRecaptured, layout));
        assertEquals(4, first.commands().size());
        for (UiDrawCommand command : first.commands()) {
            assertEquals(UiTextureId.SOLID, command.texture());
            assertEquals(WHITE, command.tint());
            assertEquals(FULL_UV, command.uv());
            assertEquals(Optional.empty(), command.clip());
        }
        assertThrows(UnsupportedOperationException.class, () -> first.commands().clear());
    }

    @Test
    void rejectsAnyStateBeyondTheExactImmutableConstantWhitelist() throws Exception {
        var fields = CrosshairWidget.class.getDeclaredFields();
        assertEquals(
                Set.of("WHITE", "SOLID_UV"),
                Arrays.stream(fields).map(field -> field.getName()).collect(java.util.stream.Collectors.toSet()));
        for (var field : fields) {
            assertEquals(
                    Modifier.PRIVATE | Modifier.STATIC | Modifier.FINAL,
                    field.getModifiers(),
                    field.getName());
            assertTrue(field.trySetAccessible(), field.getName());
            switch (field.getName()) {
                case "WHITE" -> {
                    assertEquals(UiColor.class, field.getType());
                    assertEquals(WHITE, field.get(null));
                }
                case "SOLID_UV" -> {
                    assertEquals(UiUvRect.class, field.getType());
                    assertEquals(FULL_UV, field.get(null));
                }
                default -> throw new AssertionError("unexpected crosshair field " + field.getName());
            }
        }
        assertEquals(0, CrosshairWidget.class.getConstructor().getParameterCount());
        assertEquals(
                void.class,
                CrosshairWidget.class.getMethod(
                        "append",
                        HudPresentationSnapshot.class,
                        UiLayoutContext.class,
                        UiDrawList.class).getReturnType());
    }

    private static UiFrame render(
            HudPresentationSnapshot snapshot, UiLayoutContext layout) {
        return render(new CrosshairWidget(), snapshot, layout);
    }

    private static UiFrame render(
            CrosshairWidget widget,
            HudPresentationSnapshot snapshot,
            UiLayoutContext layout) {
        UiDrawList out = new UiDrawList();
        widget.append(snapshot, layout, out);
        return out.seal();
    }

    private static List<UiRect> bounds(UiFrame frame) {
        return frame.commands().stream().map(UiDrawCommand::framebufferBounds).toList();
    }
}

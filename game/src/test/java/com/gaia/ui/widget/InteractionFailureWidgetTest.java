package com.gaia.ui.widget;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gaia.interaction.GameMode;
import com.gaia.ui.GaiaUiTheme;
import com.gaia.ui.HudPresentationSnapshot;
import com.gaia.ui.HudVisibility;
import com.overlord.renderer.ui.TextRenderer;
import com.overlord.renderer.ui.UiDrawCommand;
import com.overlord.renderer.ui.UiDrawList;
import com.overlord.renderer.ui.UiFrame;
import com.overlord.renderer.ui.UiLayoutContext;
import com.overlord.renderer.ui.UiRect;
import com.overlord.renderer.ui.UiTextureId;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class InteractionFailureWidgetTest {
    @Test
    void rendersExactFailureCodeInStandardRedCenteredBelowEvenProgressGeometry() {
        UiFrame frame = render(
                new InteractionFailureWidget(Task10WidgetTestSupport.textRenderer()),
                Task10WidgetTestSupport.failure(
                        "gaia:blocked", Task10WidgetTestSupport.visible(false)),
                Task10WidgetTestSupport.layout(1024, 768, 1024, 768, 1, 1));

        assertEquals(List.of("FAILED: gaia:blocked"), Task10WidgetTestSupport.fontLines(frame));
        assertEquals(20, frame.commands().size());
        assertEquals(new UiRect(432, 407, 440, 415),
                frame.commands().get(0).framebufferBounds());
        assertEquals(new UiRect(584, 407, 592, 415),
                frame.commands().get(19).framebufferBounds());
        assertTrue(frame.commands().stream().allMatch(command ->
                command.texture() == UiTextureId.FONT_ATLAS
                        && command.tint().equals(GaiaUiTheme.FAILURE_TEXT)));
        assertEquals(6, frame.commands().get(0).framebufferBounds().top() - 401);
    }

    @Test
    void preservesFramebufferCenterAndProgressClearanceOnOddAndDpiMismatchedSurfaces() {
        InteractionFailureWidget widget =
                new InteractionFailureWidget(Task10WidgetTestSupport.textRenderer());
        HudPresentationSnapshot snapshot = Task10WidgetTestSupport.failure(
                "gaia:blocked", Task10WidgetTestSupport.visible(false));

        UiFrame odd = render(widget, snapshot,
                Task10WidgetTestSupport.layout(1001, 701, 1001, 701, 1, 1));
        assertEquals(new UiRect(421, 374, 429, 382),
                odd.commands().get(0).framebufferBounds());
        assertEquals(new UiRect(573, 374, 581, 382),
                odd.commands().get(19).framebufferBounds());
        assertTrue(odd.commands().get(0).framebufferBounds().top() >= 367.5 + 6);

        UiFrame mismatch = render(widget, snapshot,
                Task10WidgetTestSupport.layout(320, 240, 1024, 768, 2, 1.25f));
        assertEquals(
                Task10WidgetTestSupport.fontCommands(render(
                        widget,
                        snapshot,
                        Task10WidgetTestSupport.layout(1024, 768, 1024, 768, 1, 1)))
                        .stream().map(UiDrawCommand::framebufferBounds).toList(),
                mismatch.commands().stream().map(UiDrawCommand::framebufferBounds).toList());
    }

    @Test
    void absenceClearingAndEveryIneligibleLifecycleHideImmediately() {
        InteractionFailureWidget widget =
                new InteractionFailureWidget(Task10WidgetTestSupport.textRenderer());
        UiLayoutContext layout = Task10WidgetTestSupport.layout(800, 600, 800, 600, 1, 1);
        assertEquals(List.of(), render(
                widget,
                Task10WidgetTestSupport.basic(
                        GameMode.SURVIVAL, Task10WidgetTestSupport.visible(false)),
                layout).commands());

        for (HudVisibility.Reason reason : List.of(
                HudVisibility.Reason.HUD_DISABLED,
                HudVisibility.Reason.CURSOR_RELEASED,
                HudVisibility.Reason.FOCUS_LOST,
                HudVisibility.Reason.LOADING,
                HudVisibility.Reason.SHUTDOWN,
                HudVisibility.Reason.BLOCKING_UI)) {
            assertEquals(List.of(), render(
                    widget,
                    Task10WidgetTestSupport.failure(
                            "gaia:blocked", Task10WidgetTestSupport.hidden(reason)),
                    layout).commands(), reason.name());
        }
    }

    @Test
    void sameInstanceDisplaysOnlyTheCurrentFailureWithoutRetainingOrRetryingTheOldOne() {
        InteractionFailureWidget widget =
                new InteractionFailureWidget(Task10WidgetTestSupport.textRenderer());
        UiLayoutContext layout = Task10WidgetTestSupport.layout(800, 600, 800, 600, 1, 1);
        UiFrame first = render(widget, Task10WidgetTestSupport.failure(
                "gaia:blocked", Task10WidgetTestSupport.visible(false)), layout);
        UiFrame cleared = render(widget, Task10WidgetTestSupport.basic(
                GameMode.SURVIVAL, Task10WidgetTestSupport.visible(false)), layout);
        UiFrame current = render(widget, Task10WidgetTestSupport.failure(
                "gaia:reservation_conflict", Task10WidgetTestSupport.visible(false)), layout);

        assertEquals(List.of("FAILED: gaia:blocked"), Task10WidgetTestSupport.fontLines(first));
        assertEquals(List.of(), cleared.commands());
        assertEquals(List.of("FAILED: gaia:reservation_conflict"),
                Task10WidgetTestSupport.fontLines(current));
        assertEquals(current, render(widget, Task10WidgetTestSupport.failure(
                "gaia:reservation_conflict", Task10WidgetTestSupport.visible(false)), layout));
    }

    @Test
    void outputIsImmutableAndTheWidgetHasNoGameplayServiceOrRetainedFailureState()
            throws Exception {
        InteractionFailureWidget widget =
                new InteractionFailureWidget(Task10WidgetTestSupport.textRenderer());
        UiFrame frame = render(widget, Task10WidgetTestSupport.failure(
                        "gaia:blocked", Task10WidgetTestSupport.visible(false)),
                Task10WidgetTestSupport.layout(800, 600, 800, 600, 1, 1));
        assertThrows(UnsupportedOperationException.class, () -> frame.commands().clear());

        int constant = Modifier.PRIVATE | Modifier.STATIC | Modifier.FINAL;
        int dependency = Modifier.PRIVATE | Modifier.FINAL;
        Task10WidgetTestSupport.assertExactProductionBoundary(
                InteractionFailureWidget.class,
                "InteractionFailureWidget.java",
                Map.of(
                        "SCALE", Task10WidgetTestSupport.field(double.class, constant),
                        "BASELINE_OFFSET", Task10WidgetTestSupport.field(double.class, constant),
                        "text", Task10WidgetTestSupport.field(TextRenderer.class, dependency)),
                Set.of(
                        "com.gaia.ui.GaiaUiTheme",
                        "com.gaia.ui.HudPresentationSnapshot",
                        "com.overlord.interaction.api.InteractionFailureReason",
                        "com.overlord.renderer.ui.TextRenderer",
                        "com.overlord.renderer.ui.UiDrawList",
                        "com.overlord.renderer.ui.UiLayoutContext",
                        "java.util.Objects",
                        "java.util.Optional"),
                Set.of(
                        double.class,
                        void.class,
                        HudPresentationSnapshot.class,
                        TextRenderer.class,
                        UiDrawList.class,
                        UiLayoutContext.class));
        Set<Class<?>> allowedParameterTypes = Set.of(
                HudPresentationSnapshot.class,
                UiLayoutContext.class,
                UiDrawList.class);
        assertEquals(allowedParameterTypes,
                Set.of(InteractionFailureWidget.class.getMethod(
                        "append",
                        HudPresentationSnapshot.class,
                        UiLayoutContext.class,
                        UiDrawList.class).getParameterTypes()));
    }

    private static UiFrame render(
            InteractionFailureWidget widget,
            HudPresentationSnapshot snapshot,
            UiLayoutContext layout) {
        UiDrawList out = new UiDrawList();
        widget.append(snapshot, layout, out);
        return out.seal();
    }
}

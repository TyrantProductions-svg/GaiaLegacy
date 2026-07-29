package com.overlord.renderer.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.overlord.renderer.RenderSurfaceMetrics;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class HudScreenTest {
    @Test
    void preservesWidgetAndWithinWidgetCommandOrderExactly() {
        UiDrawCommand first = command(UiTextureId.SOLID, 0.0d);
        UiDrawCommand second = command(UiTextureId.ICON_ATLAS, 20.0d);
        UiDrawCommand third = command(UiTextureId.FONT_ATLAS, 40.0d);
        Widget firstWidget = (layout, out) -> {
            out.append(first);
            out.append(second);
        };
        Widget secondWidget = (layout, out) -> out.append(third);
        HudScreen screen = new HudScreen(List.of(firstWidget, secondWidget));

        UiFrame frame = screen.layout(layout());

        assertEquals(List.of(first, second, third), frame.commands());
    }

    @Test
    void defensivelyCopiesWidgetsAndReturnsAnImmutableSealedFrame() {
        UiDrawCommand command = command(UiTextureId.SOLID, 0.0d);
        List<Widget> source = new ArrayList<>();
        source.add((layout, out) -> out.append(command));
        HudScreen screen = new HudScreen(source);
        source.clear();

        UiFrame frame = screen.layout(layout());

        assertEquals(List.of(command), frame.commands());
        assertThrows(UnsupportedOperationException.class, () -> frame.commands().add(command));
    }

    @Test
    void rejectsMissingWidgetCollectionsElementsAndLayout() {
        assertThrows(NullPointerException.class, () -> new HudScreen(null));
        assertThrows(
                NullPointerException.class,
                () -> new HudScreen(Arrays.asList((Widget) null)));
        assertThrows(NullPointerException.class, () -> new HudScreen(List.of()).layout(null));
    }

    private static UiLayoutContext layout() {
        return new UiLayoutContext(
                new RenderSurfaceMetrics(800, 600, 800, 600, 1.0f, 1.0f));
    }

    private static UiDrawCommand command(UiTextureId texture, double left) {
        return new UiDrawCommand(
                texture,
                new UiRect(left, 0.0d, left + 10.0d, 10.0d),
                new UiUvRect(0.0f, 0.0f, 1.0f, 1.0f),
                new UiColor(1.0f, 1.0f, 1.0f, 1.0f),
                Optional.empty());
    }
}

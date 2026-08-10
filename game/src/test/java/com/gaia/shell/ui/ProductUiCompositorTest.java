package com.gaia.shell.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.overlord.renderer.ui.UiColor;
import com.overlord.renderer.ui.UiDrawCommand;
import com.overlord.renderer.ui.UiFrame;
import com.overlord.renderer.ui.UiRect;
import com.overlord.renderer.ui.UiTextureId;
import com.overlord.renderer.ui.UiUvRect;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ProductUiCompositorTest {
    @Test
    void combinesImmutableSessionCommandsBeforeProductCommands() {
        UiDrawCommand session = command(1.0d, new UiColor(1.0f, 0.0f, 0.0f, 1.0f));
        UiDrawCommand product = command(2.0d, new UiColor(0.0f, 1.0f, 0.0f, 1.0f));

        UiFrame combined = ProductUiCompositor.combine(
                new UiFrame(List.of(session)),
                new UiFrame(List.of(product)));

        assertEquals(List.of(session, product), combined.commands());
        assertThrows(UnsupportedOperationException.class, () -> combined.commands().clear());
    }

    private static UiDrawCommand command(double left, UiColor color) {
        return new UiDrawCommand(
                UiTextureId.SOLID,
                new UiRect(left, 0.0d, left + 1.0d, 1.0d),
                new UiUvRect(0.0f, 0.0f, 1.0f, 1.0f),
                color,
                Optional.empty());
    }
}

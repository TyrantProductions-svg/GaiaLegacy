package com.overlord.renderer.ui;

import java.util.List;
import java.util.Objects;

public final class HudScreen {
    private final List<Widget> widgets;

    public HudScreen(List<Widget> widgets) {
        this.widgets = List.copyOf(Objects.requireNonNull(widgets, "widgets"));
    }

    public UiFrame layout(UiLayoutContext layout) {
        Objects.requireNonNull(layout, "layout");
        UiDrawList out = new UiDrawList();
        for (Widget widget : widgets) {
            widget.append(layout, out);
        }
        return out.seal();
    }
}

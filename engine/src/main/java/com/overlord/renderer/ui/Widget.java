package com.overlord.renderer.ui;

@FunctionalInterface
public interface Widget {
    void append(UiLayoutContext layout, UiDrawList out);
}

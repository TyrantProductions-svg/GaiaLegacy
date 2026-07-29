package com.overlord.renderer.ui;

public record UiColor(float red, float green, float blue, float alpha) {
    public UiColor {
        if (!Float.isFinite(red)
                || !Float.isFinite(green)
                || !Float.isFinite(blue)
                || !Float.isFinite(alpha)) {
            throw new IllegalArgumentException("UI colour channels must be finite");
        }
        if (red < 0.0f
                || red > 1.0f
                || green < 0.0f
                || green > 1.0f
                || blue < 0.0f
                || blue > 1.0f
                || alpha < 0.0f
                || alpha > 1.0f) {
            throw new IllegalArgumentException("UI colour channels must be within [0, 1]");
        }
    }
}

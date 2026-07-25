package com.overlord.renderer.visual;

public record LinearColor(float red, float green, float blue) {
    public LinearColor {
        validateComponent(red, "red");
        validateComponent(green, "green");
        validateComponent(blue, "blue");
    }

    private static void validateComponent(float component, String name) {
        if (!Float.isFinite(component) || component < 0.0f || component > 1.0f) {
            throw new IllegalArgumentException(name + " must be finite and within [0, 1]");
        }
    }
}

package com.overlord.renderer.feedback;

/** Immutable shader tint in normalized display-color components. */
public record ParticleTint(float red, float green, float blue, float alpha) {
    private static final ParticleTint WHITE = new ParticleTint(1, 1, 1, 1);

    public ParticleTint {
        requireUnit(red, "red");
        requireUnit(green, "green");
        requireUnit(blue, "blue");
        requireUnit(alpha, "alpha");
    }

    public static ParticleTint white() {
        return WHITE;
    }

    private static void requireUnit(float value, String name) {
        if (!Float.isFinite(value) || value < 0 || value > 1) {
            throw new IllegalArgumentException(name + " must be finite and in [0, 1]");
        }
    }
}

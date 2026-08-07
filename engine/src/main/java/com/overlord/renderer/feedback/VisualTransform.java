package com.overlord.renderer.feedback;

/** Immutable render-only transform values. Angles are expressed in degrees. */
public record VisualTransform(
        float translationX,
        float translationY,
        float translationZ,
        float pitchDegrees,
        float yawDegrees,
        float rollDegrees,
        float scale,
        float alpha) {
    private static final VisualTransform IDENTITY = new VisualTransform(
            0, 0, 0, 0, 0, 0, 1, 1);

    public VisualTransform {
        requireFinite(translationX, "translationX");
        requireFinite(translationY, "translationY");
        requireFinite(translationZ, "translationZ");
        requireFinite(pitchDegrees, "pitchDegrees");
        requireFinite(yawDegrees, "yawDegrees");
        requireFinite(rollDegrees, "rollDegrees");
        if (!Float.isFinite(scale) || scale < 0) {
            throw new IllegalArgumentException("scale must be finite and non-negative");
        }
        if (!Float.isFinite(alpha) || alpha < 0 || alpha > 1) {
            throw new IllegalArgumentException("alpha must be finite and in [0, 1]");
        }
    }

    public static VisualTransform identity() {
        return IDENTITY;
    }

    private static void requireFinite(float value, String name) {
        if (!Float.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite");
        }
    }
}

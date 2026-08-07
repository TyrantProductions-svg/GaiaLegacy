package com.overlord.renderer.feedback;

/** Immutable view-only first-person movement offset. */
public record FirstPersonMovementVisual(
        float translationX,
        float translationY,
        float rollDegrees) {
    private static final FirstPersonMovementVisual IDENTITY =
            new FirstPersonMovementVisual(0.0f, 0.0f, 0.0f);

    public FirstPersonMovementVisual {
        requireFinite(translationX, "translationX");
        requireFinite(translationY, "translationY");
        requireFinite(rollDegrees, "rollDegrees");
    }

    public static FirstPersonMovementVisual identity() {
        return IDENTITY;
    }

    public FirstPersonMovementVisual interpolate(
            FirstPersonMovementVisual current, float alpha) {
        if (current == null) {
            throw new NullPointerException("current");
        }
        if (!Float.isFinite(alpha) || alpha < 0.0f || alpha > 1.0f) {
            throw new IllegalArgumentException("alpha must be finite and between 0 and 1");
        }
        return new FirstPersonMovementVisual(
                lerp(translationX, current.translationX, alpha),
                lerp(translationY, current.translationY, alpha),
                lerp(rollDegrees, current.rollDegrees, alpha));
    }

    private static float lerp(float previous, float current, float alpha) {
        return previous + (current - previous) * alpha;
    }

    private static void requireFinite(float value, String label) {
        if (!Float.isFinite(value)) {
            throw new IllegalArgumentException(label + " must be finite");
        }
    }
}

package com.overlord.renderer.feedback;

/** Immutable view-only camera offset; never canonical camera state. */
public record CameraImpulseVisual(
        float pitchDegrees,
        float yawDegrees,
        float translationY) {
    private static final CameraImpulseVisual IDENTITY =
            new CameraImpulseVisual(0, 0, 0);

    public CameraImpulseVisual {
        requireFinite(pitchDegrees, "pitchDegrees");
        requireFinite(yawDegrees, "yawDegrees");
        requireFinite(translationY, "translationY");
    }

    public static CameraImpulseVisual identity() {
        return IDENTITY;
    }

    private static void requireFinite(float value, String name) {
        if (!Float.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite");
        }
    }
}

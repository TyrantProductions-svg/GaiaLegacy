package com.gaia.interaction.feedback;

/** Immutable authoritative player-motion observation for presentation only. */
public record FirstPersonMovementState(
        float feetY,
        float horizontalSpeed,
        float verticalSpeed,
        boolean grounded,
        boolean noclip) {
    public FirstPersonMovementState {
        requireFinite(feetY, "feetY");
        requireFinite(horizontalSpeed, "horizontalSpeed");
        requireFinite(verticalSpeed, "verticalSpeed");
        if (horizontalSpeed < 0.0f) {
            throw new IllegalArgumentException("horizontalSpeed must not be negative");
        }
    }

    private static void requireFinite(float value, String label) {
        if (!Float.isFinite(value)) {
            throw new IllegalArgumentException(label + " must be finite");
        }
    }
}

package com.overlord.physics;

import java.util.List;
import org.joml.Vector3f;

public record MotionResult(
        float x, float y, float z,
        float appliedX, float appliedY, float appliedZ,
        List<SweepResult> contacts) {
    public MotionResult {
        validateFinite(x, "x");
        validateFinite(y, "y");
        validateFinite(z, "z");
        validateFinite(appliedX, "appliedX");
        validateFinite(appliedY, "appliedY");
        validateFinite(appliedZ, "appliedZ");
        contacts = List.copyOf(contacts);
    }

    public Vector3f position(Vector3f destination) {
        return destination.set(x, y, z);
    }

    public Vector3f appliedDisplacement(Vector3f destination) {
        return destination.set(appliedX, appliedY, appliedZ);
    }

    private static void validateFinite(float value, String name) {
        if (!Float.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite");
        }
    }
}

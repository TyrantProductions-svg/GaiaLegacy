package com.overlord.physics;

import java.util.Objects;
import org.joml.Vector3f;

public record SweepResult(
        float fraction,
        float normalX, float normalY, float normalZ,
        float pointX, float pointY, float pointZ,
        int blockX, int blockY, int blockZ,
        Aabb blockShape) {
    public SweepResult {
        validateFinite(fraction, "fraction");
        validateFinite(normalX, "normalX");
        validateFinite(normalY, "normalY");
        validateFinite(normalZ, "normalZ");
        validateFinite(pointX, "pointX");
        validateFinite(pointY, "pointY");
        validateFinite(pointZ, "pointZ");
        if (fraction < 0 || fraction > 1) {
            throw new IllegalArgumentException("fraction must be between 0 and 1");
        }
        if (!isUnitAxisNormal(normalX, normalY, normalZ)) {
            throw new IllegalArgumentException("normal must be a unit axis vector");
        }
        blockShape = Objects.requireNonNull(blockShape, "blockShape");
    }

    public Vector3f normal(Vector3f destination) {
        return destination.set(normalX, normalY, normalZ);
    }

    public Vector3f point(Vector3f destination) {
        return destination.set(pointX, pointY, pointZ);
    }

    private static boolean isUnitAxisNormal(float x, float y, float z) {
        return (Math.abs(x) == 1 && y == 0 && z == 0)
                || (x == 0 && Math.abs(y) == 1 && z == 0)
                || (x == 0 && y == 0 && Math.abs(z) == 1);
    }

    private static void validateFinite(float value, String name) {
        if (!Float.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite");
        }
    }
}

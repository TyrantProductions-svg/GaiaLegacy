package com.overlord.renderer.frustum;

import com.overlord.renderer.AxisAlignedBounds;
import java.util.Objects;

public record FrustumPlane(
        float normalX,
        float normalY,
        float normalZ,
        float distance) {
    public FrustumPlane {
        requireFinite(normalX, "normalX");
        requireFinite(normalY, "normalY");
        requireFinite(normalZ, "normalZ");
        requireFinite(distance, "distance");

        double length =
                Math.sqrt(
                        (double) normalX * normalX
                                + (double) normalY * normalY
                                + (double) normalZ * normalZ);
        if (!Double.isFinite(length) || length == 0.0) {
            throw new IllegalArgumentException(
                    "normal must have a finite non-zero length");
        }

        normalX = (float) (normalX / length);
        normalY = (float) (normalY / length);
        normalZ = (float) (normalZ / length);
        distance = (float) (distance / length);
        requireFinite(normalX, "normalX");
        requireFinite(normalY, "normalY");
        requireFinite(normalZ, "normalZ");
        requireFinite(distance, "distance");
    }

    public float signedDistance(float x, float y, float z) {
        return normalX * x + normalY * y + normalZ * z + distance;
    }

    boolean isOutside(AxisAlignedBounds bounds, float epsilon) {
        Objects.requireNonNull(bounds, "bounds");
        float x = normalX >= 0.0f ? bounds.maxX() : bounds.minX();
        float y = normalY >= 0.0f ? bounds.maxY() : bounds.minY();
        float z = normalZ >= 0.0f ? bounds.maxZ() : bounds.minZ();
        return signedDistance(x, y, z) < -epsilon;
    }

    private static void requireFinite(float value, String field) {
        if (!Float.isFinite(value)) {
            throw new IllegalArgumentException(field + " must be finite");
        }
    }
}

package com.overlord.renderer;

public record AxisAlignedBounds(
        float minX, float minY, float minZ,
        float maxX, float maxY, float maxZ) {
    public AxisAlignedBounds {
        validateFinite(minX, "minX");
        validateFinite(minY, "minY");
        validateFinite(minZ, "minZ");
        validateFinite(maxX, "maxX");
        validateFinite(maxY, "maxY");
        validateFinite(maxZ, "maxZ");
        if (maxX < minX || maxY < minY || maxZ < minZ) {
            throw new IllegalArgumentException(
                    "Maximum bounds must be greater than or equal to minimum bounds");
        }
    }

    public AxisAlignedBounds translate(float x, float y, float z) {
        return new AxisAlignedBounds(
                minX + x, minY + y, minZ + z,
                maxX + x, maxY + y, maxZ + z);
    }

    private static void validateFinite(float value, String name) {
        if (!Float.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite");
        }
    }
}

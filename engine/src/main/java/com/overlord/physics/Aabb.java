package com.overlord.physics;

import org.joml.Vector3fc;

public record Aabb(
        float minX, float minY, float minZ,
        float maxX, float maxY, float maxZ) {
    public Aabb {
        validateFinite(minX, "minX");
        validateFinite(minY, "minY");
        validateFinite(minZ, "minZ");
        validateFinite(maxX, "maxX");
        validateFinite(maxY, "maxY");
        validateFinite(maxZ, "maxZ");
        if (maxX < minX || maxY < minY || maxZ < minZ) {
            throw new IllegalArgumentException("Maximum bounds must not be below minimum bounds");
        }
    }

    public Aabb translated(Vector3fc offset) {
        return new Aabb(
                minX + offset.x(), minY + offset.y(), minZ + offset.z(),
                maxX + offset.x(), maxY + offset.y(), maxZ + offset.z());
    }

    public Aabb sweptBounds(Vector3fc displacement) {
        return new Aabb(
                minX + Math.min(0, displacement.x()),
                minY + Math.min(0, displacement.y()),
                minZ + Math.min(0, displacement.z()),
                maxX + Math.max(0, displacement.x()),
                maxY + Math.max(0, displacement.y()),
                maxZ + Math.max(0, displacement.z()));
    }

    public boolean intersects(Aabb other) {
        return minX < other.maxX && maxX > other.minX
                && minY < other.maxY && maxY > other.minY
                && minZ < other.maxZ && maxZ > other.minZ;
    }

    public float overlapX(Aabb other) {
        return Math.max(
                0, Math.min(maxX, other.maxX) - Math.max(minX, other.minX));
    }

    public float overlapY(Aabb other) {
        return Math.max(
                0, Math.min(maxY, other.maxY) - Math.max(minY, other.minY));
    }

    public float overlapZ(Aabb other) {
        return Math.max(
                0, Math.min(maxZ, other.maxZ) - Math.max(minZ, other.minZ));
    }

    private static void validateFinite(float value, String name) {
        if (!Float.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite");
        }
    }
}

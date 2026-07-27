package com.overlord.voxel;

import java.util.Objects;

public final class BlockPlacement {
    private static final float EPSILON = 1e-6f;
    private final BlockSize size;
    private final float offsetX;
    private final float offsetY;
    private final float offsetZ;

    private BlockPlacement(
            BlockSize size,
            float offsetX,
            float offsetY,
            float offsetZ) {
        this.size = Objects.requireNonNull(size, "size");
        validateOffset(offsetX, size, "offsetX");
        validateOffset(offsetY, size, "offsetY");
        validateOffset(offsetZ, size, "offsetZ");
        this.offsetX = offsetX;
        this.offsetY = offsetY;
        this.offsetZ = offsetZ;
    }

    public static BlockPlacement of(BlockSize size) {
        return new BlockPlacement(size, 0.0f, 0.0f, 0.0f);
    }

    public static BlockPlacement of(
            BlockSize size,
            float offsetX,
            float offsetY,
            float offsetZ) {
        return new BlockPlacement(size, offsetX, offsetY, offsetZ);
    }

    public static BlockPlacement full() {
        return new BlockPlacement(BlockSize.SIZE_16, 0.0f, 0.0f, 0.0f);
    }

    public BlockSize size() {
        return size;
    }

    public float offsetX() {
        return offsetX;
    }

    public float offsetY() {
        return offsetY;
    }

    public float offsetZ() {
        return offsetZ;
    }

    public float minX() {
        return offsetX;
    }

    public float minY() {
        return offsetY;
    }

    public float minZ() {
        return offsetZ;
    }

    public float maxX() {
        return offsetX + size.units();
    }

    public float maxY() {
        return offsetY + size.units();
    }

    public float maxZ() {
        return offsetZ + size.units();
    }

    public boolean isFullBlock() {
        return size == BlockSize.SIZE_16
                && offsetX == 0.0f
                && offsetY == 0.0f
                && offsetZ == 0.0f;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof BlockPlacement that)) {
            return false;
        }
        return size == that.size
                && floatEquals(offsetX, that.offsetX)
                && floatEquals(offsetY, that.offsetY)
                && floatEquals(offsetZ, that.offsetZ);
    }

    @Override
    public int hashCode() {
        int result = size.hashCode();
        result = 31 * result + Float.hashCode(offsetX);
        result = 31 * result + Float.hashCode(offsetY);
        result = 31 * result + Float.hashCode(offsetZ);
        return result;
    }

    private static boolean floatEquals(float a, float b) {
        return Math.abs(a - b) < EPSILON;
    }

    private static void validateOffset(
            float offset,
            BlockSize size,
            String name) {
        if (offset < -EPSILON
                || offset > 1.0f - size.units() + EPSILON) {
            throw new IllegalArgumentException(
                    name + " must be between 0 and "
                            + (1.0f - size.units()));
        }
    }
}

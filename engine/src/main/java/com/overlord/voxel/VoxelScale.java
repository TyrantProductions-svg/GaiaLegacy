package com.overlord.voxel;

public enum VoxelScale {
    DETAIL_4(4);

    private final int subdivisionsPerAxis;
    private final int cellCount;
    private final double cellSize;

    VoxelScale(int subdivisionsPerAxis) {
        this.subdivisionsPerAxis = subdivisionsPerAxis;
        this.cellCount =
                Math.multiplyExact(
                        Math.multiplyExact(
                                subdivisionsPerAxis,
                                subdivisionsPerAxis),
                        subdivisionsPerAxis);
        this.cellSize = 1.0 / subdivisionsPerAxis;
    }

    public int subdivisionsPerAxis() {
        return subdivisionsPerAxis;
    }

    public int cellCount() {
        return cellCount;
    }

    public double cellSize() {
        return cellSize;
    }
}

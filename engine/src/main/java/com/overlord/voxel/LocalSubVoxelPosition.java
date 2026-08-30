package com.overlord.voxel;

public record LocalSubVoxelPosition(int x, int y, int z) {
    private static final int SUBDIVISIONS =
            VoxelScale.DETAIL_4.subdivisionsPerAxis();

    public LocalSubVoxelPosition {
        requireCoordinate(x, "x");
        requireCoordinate(y, "y");
        requireCoordinate(z, "z");
    }

    public int index() {
        return x + SUBDIVISIONS * y + SUBDIVISIONS * SUBDIVISIONS * z;
    }

    public static LocalSubVoxelPosition fromIndex(int index) {
        if (index < 0 || index >= VoxelScale.DETAIL_4.cellCount()) {
            throw new IllegalArgumentException(
                    "index must be between 0 and 63");
        }
        return new LocalSubVoxelPosition(
                index % SUBDIVISIONS,
                (index / SUBDIVISIONS) % SUBDIVISIONS,
                index / (SUBDIVISIONS * SUBDIVISIONS));
    }

    private static void requireCoordinate(int coordinate, String name) {
        if (coordinate < 0 || coordinate >= SUBDIVISIONS) {
            throw new IllegalArgumentException(
                    name + " must be between 0 and 3");
        }
    }
}

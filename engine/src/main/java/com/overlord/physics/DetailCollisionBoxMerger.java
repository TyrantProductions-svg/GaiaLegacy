package com.overlord.physics;

import com.overlord.voxel.DetailCellState;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Derives a bounded deterministic static collision shape from DETAIL_4 occupancy. */
public final class DetailCollisionBoxMerger {
    private static final int SUBDIVISIONS = 4;
    private static final float QUARTER = 0.25f;

    public BlockCollisionShape merge(DetailCellState detail) {
        DetailCellState required = Objects.requireNonNull(detail, "detail");
        long remaining = required.occupancyMask();
        List<Aabb> boxes = new ArrayList<>(Long.bitCount(remaining));
        while (remaining != 0L) {
            int seed = Long.numberOfTrailingZeros(remaining);
            int seedX = seed & 3;
            int seedY = (seed >>> 2) & 3;
            int seedZ = seed >>> 4;

            int maxX = seedX + 1;
            while (maxX < SUBDIVISIONS
                    && occupied(remaining, maxX, seedY, seedZ)) {
                maxX++;
            }

            int maxY = seedY + 1;
            while (maxY < SUBDIVISIONS
                    && occupiedRectangle(
                            remaining,
                            seedX,
                            maxX,
                            maxY,
                            maxY + 1,
                            seedZ)) {
                maxY++;
            }

            int maxZ = seedZ + 1;
            while (maxZ < SUBDIVISIONS
                    && occupiedVolume(
                            remaining,
                            seedX,
                            maxX,
                            seedY,
                            maxY,
                            maxZ)) {
                maxZ++;
            }

            boxes.add(new Aabb(
                    seedX * QUARTER,
                    seedY * QUARTER,
                    seedZ * QUARTER,
                    maxX * QUARTER,
                    maxY * QUARTER,
                    maxZ * QUARTER));
            remaining = clearVolume(
                    remaining,
                    seedX,
                    maxX,
                    seedY,
                    maxY,
                    seedZ,
                    maxZ);
        }
        return BlockCollisionShape.of(boxes);
    }

    private static boolean occupiedVolume(
            long mask,
            int minX,
            int maxX,
            int minY,
            int maxY,
            int z) {
        return occupiedRectangle(mask, minX, maxX, minY, maxY, z);
    }

    private static boolean occupiedRectangle(
            long mask,
            int minX,
            int maxX,
            int minY,
            int maxY,
            int z) {
        for (int y = minY; y < maxY; y++) {
            for (int x = minX; x < maxX; x++) {
                if (!occupied(mask, x, y, z)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static long clearVolume(
            long mask,
            int minX,
            int maxX,
            int minY,
            int maxY,
            int minZ,
            int maxZ) {
        long remaining = mask;
        for (int z = minZ; z < maxZ; z++) {
            for (int y = minY; y < maxY; y++) {
                for (int x = minX; x < maxX; x++) {
                    remaining &= ~(1L << index(x, y, z));
                }
            }
        }
        return remaining;
    }

    private static boolean occupied(long mask, int x, int y, int z) {
        return (mask & (1L << index(x, y, z))) != 0L;
    }

    private static int index(int x, int y, int z) {
        return x + SUBDIVISIONS * y + SUBDIVISIONS * SUBDIVISIONS * z;
    }
}

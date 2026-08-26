package com.overlord.voxel;

import com.overlord.config.GameConfig;
import java.util.Comparator;
import java.util.Objects;

/** Checked global-coordinate operations for Chunk keys. */
public final class ChunkCoordinatePolicy {
    public static final int MAX_SAFE_CHUNK_COORDINATE = 134217727;
    private static final int MIN_SAFE_CHUNK_COORDINATE = -MAX_SAFE_CHUNK_COORDINATE;
    private static final Comparator<ChunkKey> CANONICAL_COMPARATOR =
            ChunkCoordinatePolicy::compareCanonical;

    private ChunkCoordinatePolicy() {}

    public static ChunkKey requireSafe(ChunkKey key) {
        ChunkKey checkedKey = Objects.requireNonNull(key, "key");
        requireSafeAxis(checkedKey.x());
        requireSafeAxis(checkedKey.z());
        return checkedKey;
    }

    public static long worldOriginX(ChunkKey key) {
        return worldOrigin(requireSafe(key).x());
    }

    public static long worldOriginZ(ChunkKey key) {
        return worldOrigin(requireSafe(key).z());
    }

    public static ChunkKey neighbor(ChunkKey key, int deltaX, int deltaZ) {
        ChunkKey checkedKey = requireSafe(key);
        return new ChunkKey(
                requireSafeAxis(Math.addExact((long) checkedKey.x(), deltaX)),
                requireSafeAxis(Math.addExact((long) checkedKey.z(), deltaZ)));
    }

    public static long squaredDistance(ChunkKey first, ChunkKey second) {
        ChunkKey checkedFirst = requireSafe(first);
        ChunkKey checkedSecond = requireSafe(second);
        long deltaX = (long) checkedFirst.x() - checkedSecond.x();
        long deltaZ = (long) checkedFirst.z() - checkedSecond.z();
        return Math.addExact(
                Math.multiplyExact(deltaX, deltaX),
                Math.multiplyExact(deltaZ, deltaZ));
    }

    public static Comparator<ChunkKey> canonicalComparator() {
        return CANONICAL_COMPARATOR;
    }

    private static long worldOrigin(int coordinate) {
        return Math.multiplyExact((long) coordinate, (long) GameConfig.Chunk.SIZE);
    }

    private static int compareCanonical(ChunkKey first, ChunkKey second) {
        ChunkKey checkedFirst = requireSafe(first);
        ChunkKey checkedSecond = requireSafe(second);
        int xComparison = Integer.compare(checkedFirst.x(), checkedSecond.x());
        return xComparison != 0
                ? xComparison
                : Integer.compare(checkedFirst.z(), checkedSecond.z());
    }

    private static int requireSafeAxis(long coordinate) {
        if (coordinate < MIN_SAFE_CHUNK_COORDINATE
                || coordinate > MAX_SAFE_CHUNK_COORDINATE) {
            throw new IllegalArgumentException("Chunk coordinate is outside the safe envelope");
        }
        return (int) coordinate;
    }
}

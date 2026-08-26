package com.gaia.world.streaming;

import com.overlord.voxel.ChunkCoordinatePolicy;
import com.overlord.voxel.ChunkKey;
import java.util.Objects;

/** Stable work priority: desired class, distance, then canonical key. */
public record ChunkPriority(
        int priorityClass,
        long squaredDistance,
        ChunkKey key) implements Comparable<ChunkPriority> {
    public ChunkPriority {
        if (priorityClass < 0 || priorityClass > 2) {
            throw new IllegalArgumentException("priorityClass must be within [0,2]");
        }
        if (squaredDistance < 0L) {
            throw new IllegalArgumentException("squaredDistance must be non-negative");
        }
        key = ChunkCoordinatePolicy.requireSafe(key);
    }

    public static ChunkPriority of(
            ChunkKey center, ChunkKey key, ChunkDesiredSets desiredSets) {
        ChunkKey checkedCenter = ChunkCoordinatePolicy.requireSafe(center);
        ChunkKey checkedKey = ChunkCoordinatePolicy.requireSafe(key);
        ChunkDesiredSets desired = Objects.requireNonNull(desiredSets, "desiredSets");
        int priorityClass;
        if (desired.simulation().contains(checkedKey)) {
            priorityClass = 0;
        } else if (desired.render().contains(checkedKey)) {
            priorityClass = 1;
        } else if (desired.preload().contains(checkedKey)) {
            priorityClass = 2;
        } else {
            throw new IllegalArgumentException("key is outside the desired preload set");
        }
        return new ChunkPriority(
                priorityClass,
                ChunkCoordinatePolicy.squaredDistance(checkedCenter, checkedKey),
                checkedKey);
    }

    @Override
    public int compareTo(ChunkPriority other) {
        ChunkPriority checked = Objects.requireNonNull(other, "other");
        int classComparison = Integer.compare(priorityClass, checked.priorityClass);
        if (classComparison != 0) {
            return classComparison;
        }
        int distanceComparison = Long.compare(squaredDistance, checked.squaredDistance);
        if (distanceComparison != 0) {
            return distanceComparison;
        }
        return ChunkCoordinatePolicy.canonicalComparator().compare(key, checked.key);
    }
}

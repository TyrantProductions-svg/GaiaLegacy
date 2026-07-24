package com.overlord.voxel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Set;
import org.junit.jupiter.api.Test;

class ChunkDirtyTrackerTest {
    private final ChunkDirtyTracker tracker = new ChunkDirtyTracker();
    private final ChunkKey center = new ChunkKey(0, 0);

    @Test
    void interiorChangeDirtiesOnlyTarget() {
        assertEquals(Set.of(center), tracker.affectedByBlock(center, 8, 8));
    }

    @Test
    void edgeChangesDirtyTheirMatchingNeighbor() {
        assertEquals(
                Set.of(center, center.west()),
                tracker.affectedByBlock(center, 0, 8));
        assertEquals(
                Set.of(center, center.east()),
                tracker.affectedByBlock(center, 15, 8));
        assertEquals(
                Set.of(center, center.north()),
                tracker.affectedByBlock(center, 8, 0));
        assertEquals(
                Set.of(center, center.south()),
                tracker.affectedByBlock(center, 8, 15));
    }

    @Test
    void cornerChangeDirtiesOnlyTwoOrthogonalNeighbors() {
        assertEquals(
                Set.of(center, center.west(), center.north()),
                tracker.affectedByBlock(center, 0, 0));
        assertEquals(
                Set.of(center, center.east(), center.north()),
                tracker.affectedByBlock(center, 15, 0));
        assertEquals(
                Set.of(center, center.west(), center.south()),
                tracker.affectedByBlock(center, 0, 15));
        assertEquals(
                Set.of(center, center.east(), center.south()),
                tracker.affectedByBlock(center, 15, 15));
    }

    @Test
    void rejectsInvalidLocalCoordinates() {
        assertThrows(
                IllegalArgumentException.class,
                () -> tracker.affectedByBlock(center, -1, 0));
        assertThrows(
                IllegalArgumentException.class,
                () -> tracker.affectedByBlock(center, 0, -1));
        assertThrows(
                IllegalArgumentException.class,
                () -> tracker.affectedByBlock(center, 16, 0));
        assertThrows(
                IllegalArgumentException.class,
                () -> tracker.affectedByBlock(center, 0, 16));
    }

    @Test
    void returnsAllHorizontalNeighbors() {
        assertEquals(
                Set.of(center.north(), center.south(), center.west(), center.east()),
                tracker.horizontalNeighbors(center));
    }
}

package com.overlord.voxel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.overlord.config.GameConfig;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ChunkDirtyTrackerTest {
    private static final int CHUNK_SIZE = GameConfig.Chunk.SIZE;
    private static final int INTERIOR_LOCAL_COORDINATE = CHUNK_SIZE / 2;
    private static final int LAST_LOCAL_COORDINATE = CHUNK_SIZE - 1;

    private final ChunkDirtyTracker tracker = new ChunkDirtyTracker();
    private final ChunkKey center = new ChunkKey(0, 0);

    @Test
    void interiorChangeDirtiesOnlyTarget() {
        assertEquals(
                Set.of(center),
                tracker.affectedByBlock(
                        center,
                        INTERIOR_LOCAL_COORDINATE,
                        INTERIOR_LOCAL_COORDINATE));
    }

    @Test
    void edgeChangesDirtyTheirMatchingNeighbor() {
        assertEquals(
                Set.of(center, center.west()),
                tracker.affectedByBlock(center, 0, INTERIOR_LOCAL_COORDINATE));
        assertEquals(
                Set.of(center, center.east()),
                tracker.affectedByBlock(
                        center,
                        LAST_LOCAL_COORDINATE,
                        INTERIOR_LOCAL_COORDINATE));
        assertEquals(
                Set.of(center, center.north()),
                tracker.affectedByBlock(center, INTERIOR_LOCAL_COORDINATE, 0));
        assertEquals(
                Set.of(center, center.south()),
                tracker.affectedByBlock(
                        center,
                        INTERIOR_LOCAL_COORDINATE,
                        LAST_LOCAL_COORDINATE));
    }

    @Test
    void cornerChangeDirtiesTwoCardinalsAndOneDiagonal() {
        assertEquals(
                List.of(
                        center,
                        center.west(),
                        center.north(),
                        new ChunkKey(-1, -1)),
                List.copyOf(tracker.affectedByBlock(center, 0, 0)));
        assertEquals(
                List.of(
                        center,
                        center.east(),
                        center.north(),
                        new ChunkKey(1, -1)),
                List.copyOf(
                        tracker.affectedByBlock(
                                center, LAST_LOCAL_COORDINATE, 0)));
        assertEquals(
                List.of(
                        center,
                        center.west(),
                        center.south(),
                        new ChunkKey(-1, 1)),
                List.copyOf(
                        tracker.affectedByBlock(
                                center, 0, LAST_LOCAL_COORDINATE)));
        assertEquals(
                List.of(
                        center,
                        center.east(),
                        center.south(),
                        new ChunkKey(1, 1)),
                List.copyOf(
                        tracker.affectedByBlock(
                                center,
                                LAST_LOCAL_COORDINATE,
                                LAST_LOCAL_COORDINATE)));
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
                () -> tracker.affectedByBlock(center, CHUNK_SIZE, 0));
        assertThrows(
                IllegalArgumentException.class,
                () -> tracker.affectedByBlock(center, 0, CHUNK_SIZE));
    }

    @Test
    void returnsAllHorizontalNeighbors() {
        assertEquals(
                Set.of(center.north(), center.south(), center.west(), center.east()),
                tracker.horizontalNeighbors(center));
    }

    @Test
    void returnsAllEightMeshingNeighbors() {
        assertEquals(
                Set.of(
                        center.north(),
                        new ChunkKey(1, -1),
                        center.east(),
                        new ChunkKey(1, 1),
                        center.south(),
                        new ChunkKey(-1, 1),
                        center.west(),
                        new ChunkKey(-1, -1)),
                tracker.meshingNeighbors(center));
    }
}

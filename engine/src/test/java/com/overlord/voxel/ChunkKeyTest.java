package com.overlord.voxel;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ChunkKeyTest {
    @Test
    void convertsPositiveWorldCoordinates() {
        assertEquals(new ChunkKey(2, 3), ChunkKey.fromWorld(47, 63));
        assertEquals(15, ChunkKey.localCoordinate(47));
        assertEquals(15, ChunkKey.localCoordinate(63));
    }

    @Test
    void convertsNegativeWorldCoordinatesWithFloorRules() {
        assertEquals(new ChunkKey(-1, -1), ChunkKey.fromWorld(-1, -1));
        assertEquals(15, ChunkKey.localCoordinate(-1));
        assertEquals(-16, new ChunkKey(-1, 0).worldOriginX());
    }

    @Test
    void preservesNegativeExactChunkMultiples() {
        assertEquals(new ChunkKey(-1, -2), ChunkKey.fromWorld(-16, -32));
        assertEquals(0, ChunkKey.localCoordinate(-16));
        assertEquals(0, ChunkKey.localCoordinate(-32));
        assertEquals(-32, new ChunkKey(0, -2).worldOriginZ());
    }

    @Test
    void returnsCardinalNeighborKeys() {
        ChunkKey center = new ChunkKey(4, -7);

        assertEquals(new ChunkKey(4, -8), center.north());
        assertEquals(new ChunkKey(4, -6), center.south());
        assertEquals(new ChunkKey(3, -7), center.west());
        assertEquals(new ChunkKey(5, -7), center.east());
    }
}

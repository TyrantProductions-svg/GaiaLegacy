package com.overlord.voxel;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.overlord.config.GameConfig;
import org.junit.jupiter.api.Test;

class ChunkKeyTest {
    private static final int CHUNK_SIZE = GameConfig.Chunk.SIZE;
    private static final int LAST_LOCAL_COORDINATE = CHUNK_SIZE - 1;

    @Test
    void convertsPositiveWorldCoordinates() {
        int worldX = 3 * CHUNK_SIZE - 1;
        int worldZ = 4 * CHUNK_SIZE - 1;

        assertEquals(new ChunkKey(2, 3), ChunkKey.fromWorld(worldX, worldZ));
        assertEquals(LAST_LOCAL_COORDINATE, ChunkKey.localCoordinate(worldX));
        assertEquals(LAST_LOCAL_COORDINATE, ChunkKey.localCoordinate(worldZ));
    }

    @Test
    void convertsNegativeWorldCoordinatesWithFloorRules() {
        assertEquals(new ChunkKey(-1, -1), ChunkKey.fromWorld(-1, -1));
        assertEquals(LAST_LOCAL_COORDINATE, ChunkKey.localCoordinate(-1));
        assertEquals(-CHUNK_SIZE, new ChunkKey(-1, 0).worldOriginX());
    }

    @Test
    void preservesNegativeExactChunkMultiples() {
        assertEquals(
                new ChunkKey(-1, -2),
                ChunkKey.fromWorld(-CHUNK_SIZE, -2 * CHUNK_SIZE));
        assertEquals(0, ChunkKey.localCoordinate(-CHUNK_SIZE));
        assertEquals(0, ChunkKey.localCoordinate(-2 * CHUNK_SIZE));
        assertEquals(-2 * CHUNK_SIZE, new ChunkKey(0, -2).worldOriginZ());
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

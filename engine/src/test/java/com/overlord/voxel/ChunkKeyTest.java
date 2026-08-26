package com.overlord.voxel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.overlord.config.GameConfig;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

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

    @ParameterizedTest(name = "world coordinate {0} maps to chunk {1} and local {2}")
    @MethodSource("worldCoordinateCases")
    void preservesFloorChunkAndLocalCoordinates(
            int worldCoordinate, int expectedChunkCoordinate, int expectedLocalCoordinate) {
        ChunkKey key = ChunkKey.fromWorld(worldCoordinate, worldCoordinate);

        assertEquals(
                new ChunkKey(expectedChunkCoordinate, expectedChunkCoordinate), key);
        assertEquals(expectedLocalCoordinate, ChunkKey.localCoordinate(worldCoordinate));
        assertEquals(worldCoordinate, key.worldOriginX() + expectedLocalCoordinate);
        assertEquals(worldCoordinate, key.worldOriginZ() + expectedLocalCoordinate);
    }

    @ParameterizedTest(name = "unsafe chunk {0}, {1} rejects checked origin access")
    @MethodSource("unsafeKeys")
    void rejectsUnsafeKeysBeforeCalculatingWorldOrigins(int x, int z) {
        ChunkKey key = new ChunkKey(x, z);

        assertThrows(IllegalArgumentException.class, key::worldOriginX);
        assertThrows(IllegalArgumentException.class, key::worldOriginZ);
    }

    private static Stream<Arguments> worldCoordinateCases() {
        return Stream.of(
                Arguments.of(-33, -3, 15),
                Arguments.of(-32, -2, 0),
                Arguments.of(-17, -2, 15),
                Arguments.of(-16, -1, 0),
                Arguments.of(-1, -1, 15),
                Arguments.of(0, 0, 0),
                Arguments.of(1, 0, 1),
                Arguments.of(15, 0, 15),
                Arguments.of(16, 1, 0),
                Arguments.of(17, 1, 1),
                Arguments.of(31, 1, 15),
                Arguments.of(32, 2, 0),
                Arguments.of(33, 2, 1));
    }

    private static Stream<Arguments> unsafeKeys() {
        return Stream.of(
                Arguments.of(134217728, 0), Arguments.of(0, -134217728));
    }
}

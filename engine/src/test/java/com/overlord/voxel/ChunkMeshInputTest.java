package com.overlord.voxel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.overlord.config.GameConfig;
import java.util.List;
import org.junit.jupiter.api.Test;

class ChunkMeshInputTest {
    private static final int CHUNK_SIZE = GameConfig.Chunk.SIZE;
    private static final int WORLD_HEIGHT = 4;
    private static final byte NORTH_MARKER = 11;
    private static final byte NORTH_EAST_MARKER = 12;
    private static final byte EAST_MARKER = 13;
    private static final byte SOUTH_EAST_MARKER = 14;
    private static final byte SOUTH_MARKER = 15;
    private static final byte SOUTH_WEST_MARKER = 16;
    private static final byte WEST_MARKER = 17;
    private static final byte NORTH_WEST_MARKER = 18;

    @Test
    void typedSamplingCarriesCenterAndNeighborDetailSnapshots() {
        ChunkKey centerKey = new ChunkKey(0, 0);
        ChunkSnapshot center =
                detailSnapshot(centerKey, 1L, 0, 2, 0, (byte) 21);
        ChunkSnapshot west =
                detailSnapshot(
                        centerKey.west(),
                        2L,
                        CHUNK_SIZE - 1,
                        2,
                        0,
                        (byte) 22);
        ChunkMeshInput input =
                new ChunkMeshInput(
                        center,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        west,
                        null);

        DetailCellState centerDetail =
                assertInstanceOf(
                        DetailCellState.class,
                        input.cellState(0, 2, 0));
        DetailCellState westDetail =
                assertInstanceOf(
                        DetailCellState.class,
                        input.cellState(-1, 2, 0));

        assertEquals(
                21,
                Byte.toUnsignedInt(
                        centerDetail.blockId(
                                new LocalSubVoxelPosition(0, 0, 0))));
        assertEquals(
                22,
                Byte.toUnsignedInt(
                        westDetail.blockId(
                                new LocalSubVoxelPosition(0, 0, 0))));
    }

    @Test
    void preservesEveryNeighborInFixedThreeByThreeOrder() {
        ChunkMeshInput input = inputWithAllNeighbors(new ChunkKey(0, 0));

        assertEquals(new ChunkKey(-1, -1), input.northWest().key());
        assertEquals(new ChunkKey(0, -1), input.north().key());
        assertEquals(new ChunkKey(1, -1), input.northEast().key());
        assertEquals(new ChunkKey(-1, 0), input.west().key());
        assertEquals(new ChunkKey(1, 0), input.east().key());
        assertEquals(new ChunkKey(-1, 1), input.southWest().key());
        assertEquals(new ChunkKey(0, 1), input.south().key());
        assertEquals(new ChunkKey(1, 1), input.southEast().key());
    }

    @Test
    void replacesMissingNeighborsWithCorrectlyKeyedEmptySnapshots() {
        ChunkKey centerKey = new ChunkKey(3, -5);
        ChunkMeshInput input =
                new ChunkMeshInput(
                        empty(centerKey),
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null);

        for (ChunkSnapshot neighbor : neighbors(input)) {
            assertEquals(0, neighbor.revision());
            assertEquals(0, neighbor.getBlock(0, 0, 0));
        }
        assertEquals(centerKey.north(), input.north().key());
        assertEquals(new ChunkKey(4, -6), input.northEast().key());
        assertEquals(centerKey.east(), input.east().key());
        assertEquals(new ChunkKey(4, -4), input.southEast().key());
        assertEquals(centerKey.south(), input.south().key());
        assertEquals(new ChunkKey(2, -4), input.southWest().key());
        assertEquals(centerKey.west(), input.west().key());
        assertEquals(new ChunkKey(2, -6), input.northWest().key());
    }

    @Test
    void rejectsWrongKeysAndWorldHeightsForEveryNeighborPosition() {
        ChunkKey centerKey = new ChunkKey(0, 0);
        List<ChunkSnapshot> expected = neighborSnapshots(centerKey);

        for (int index = 0; index < expected.size(); index++) {
            List<ChunkSnapshot> wrongKey =
                    new java.util.ArrayList<>(expected);
            wrongKey.set(index, empty(new ChunkKey(9, 9)));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> input(centerKey, wrongKey));

            List<ChunkSnapshot> wrongHeight =
                    new java.util.ArrayList<>(expected);
            wrongHeight.set(
                    index,
                    ChunkSnapshot.empty(
                            expected.get(index).key(), 0, WORLD_HEIGHT + 1));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> input(centerKey, wrongHeight));
        }
    }

    @Test
    void routesOneBlockHaloToCardinalAndDiagonalSnapshots() {
        ChunkMeshInput input = markerInput(new ChunkKey(0, 0));
        int y = 2;

        assertEquals(NORTH_MARKER, input.getBlock(5, y, -1));
        assertEquals(EAST_MARKER, input.getBlock(CHUNK_SIZE, y, 5));
        assertEquals(SOUTH_MARKER, input.getBlock(5, y, CHUNK_SIZE));
        assertEquals(WEST_MARKER, input.getBlock(-1, y, 5));
        assertEquals(NORTH_WEST_MARKER, input.getBlock(-1, y, -1));
        assertEquals(NORTH_EAST_MARKER, input.getBlock(CHUNK_SIZE, y, -1));
        assertEquals(SOUTH_WEST_MARKER, input.getBlock(-1, y, CHUNK_SIZE));
        assertEquals(
                SOUTH_EAST_MARKER,
                input.getBlock(CHUNK_SIZE, y, CHUNK_SIZE));
    }

    @Test
    void routesHaloForNegativeCenterAndRejectsCoordinatesBeyondIt() {
        ChunkMeshInput input = markerInput(new ChunkKey(-4, -7));
        int y = 2;

        assertEquals(NORTH_WEST_MARKER, input.getBlock(-1, y, -1));
        assertEquals(NORTH_EAST_MARKER, input.getBlock(CHUNK_SIZE, y, -1));
        assertEquals(SOUTH_WEST_MARKER, input.getBlock(-1, y, CHUNK_SIZE));
        assertEquals(
                SOUTH_EAST_MARKER,
                input.getBlock(CHUNK_SIZE, y, CHUNK_SIZE));
        assertThrows(
                IllegalArgumentException.class,
                () -> input.getBlock(-2, y, 0));
        assertThrows(
                IllegalArgumentException.class,
                () -> input.getBlock(CHUNK_SIZE + 1, y, 0));
        assertThrows(
                IllegalArgumentException.class,
                () -> input.getBlock(0, y, -2));
        assertThrows(
                IllegalArgumentException.class,
                () -> input.getBlock(0, y, CHUNK_SIZE + 1));
        assertEquals(0, input.getBlock(0, -1, 0));
        assertEquals(0, input.getBlock(0, WORLD_HEIGHT, 0));
    }

    private static ChunkMeshInput inputWithAllNeighbors(ChunkKey centerKey) {
        return input(centerKey, neighborSnapshots(centerKey));
    }

    private static ChunkMeshInput markerInput(ChunkKey centerKey) {
        List<ChunkSnapshot> neighbors = neighborSnapshots(centerKey);
        neighbors.set(
                0,
                snapshot(
                        centerKey.north(),
                        5,
                        CHUNK_SIZE - 1,
                        NORTH_MARKER));
        neighbors.set(
                1,
                snapshot(
                        centerKey.north().east(),
                        0,
                        CHUNK_SIZE - 1,
                        NORTH_EAST_MARKER));
        neighbors.set(2, snapshot(centerKey.east(), 0, 5, EAST_MARKER));
        neighbors.set(
                3,
                snapshot(
                        centerKey.south().east(),
                        0,
                        0,
                        SOUTH_EAST_MARKER));
        neighbors.set(4, snapshot(centerKey.south(), 5, 0, SOUTH_MARKER));
        neighbors.set(
                5,
                snapshot(
                        centerKey.south().west(),
                        CHUNK_SIZE - 1,
                        0,
                        SOUTH_WEST_MARKER));
        neighbors.set(
                6,
                snapshot(
                        centerKey.west(),
                        CHUNK_SIZE - 1,
                        5,
                        WEST_MARKER));
        neighbors.set(
                7,
                snapshot(
                        centerKey.north().west(),
                        CHUNK_SIZE - 1,
                        CHUNK_SIZE - 1,
                        NORTH_WEST_MARKER));
        return input(centerKey, neighbors);
    }

    private static ChunkMeshInput input(
            ChunkKey centerKey, List<ChunkSnapshot> neighbors) {
        return new ChunkMeshInput(
                empty(centerKey),
                neighbors.get(0),
                neighbors.get(1),
                neighbors.get(2),
                neighbors.get(3),
                neighbors.get(4),
                neighbors.get(5),
                neighbors.get(6),
                neighbors.get(7));
    }

    private static List<ChunkSnapshot> neighborSnapshots(ChunkKey centerKey) {
        return new java.util.ArrayList<>(
                List.of(
                        empty(centerKey.north()),
                        empty(centerKey.north().east()),
                        empty(centerKey.east()),
                        empty(centerKey.south().east()),
                        empty(centerKey.south()),
                        empty(centerKey.south().west()),
                        empty(centerKey.west()),
                        empty(centerKey.north().west())));
    }

    private static List<ChunkSnapshot> neighbors(ChunkMeshInput input) {
        return List.of(
                input.north(),
                input.northEast(),
                input.east(),
                input.southEast(),
                input.south(),
                input.southWest(),
                input.west(),
                input.northWest());
    }

    private static ChunkSnapshot snapshot(
            ChunkKey key, int x, int z, byte marker) {
        byte[] blocks = new byte[CHUNK_SIZE * WORLD_HEIGHT * CHUNK_SIZE];
        blocks[x + 2 * CHUNK_SIZE + z * CHUNK_SIZE * WORLD_HEIGHT] = marker;
        return ChunkSnapshot.of(key, 1, WORLD_HEIGHT, blocks);
    }

    private static ChunkSnapshot detailSnapshot(
            ChunkKey key,
            long revision,
            int localX,
            int y,
            int localZ,
            byte blockId) {
        int parentIndex =
                localX
                        + y * CHUNK_SIZE
                        + localZ * CHUNK_SIZE * WORLD_HEIGHT;
        byte[] ids = new byte[64];
        ids[0] = blockId;
        return ChunkSnapshot.of(
                key,
                revision,
                WORLD_HEIGHT,
                new byte[CHUNK_SIZE * WORLD_HEIGHT * CHUNK_SIZE],
                DetailChunkSnapshot.of(
                        new int[] {parentIndex},
                        new long[] {1L},
                        ids));
    }

    private static ChunkSnapshot empty(ChunkKey key) {
        return ChunkSnapshot.empty(key, 0, WORLD_HEIGHT);
    }
}

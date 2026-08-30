package com.overlord.voxel;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.overlord.config.GameConfig;
import java.lang.reflect.Field;
import org.junit.jupiter.api.Test;

class ChunkDetailStorageTest {
    private static final int WORLD_HEIGHT = 32;

    @Test
    void fullOnlyChunkAllocatesNoDetailStorage() {
        Chunk chunk = new Chunk(WORLD_HEIGHT);

        assertNull(detailStorageField(chunk));
        assertEquals(0, chunk.detailParentCount());
        assertEquals(
                new FullCellState((byte) 0),
                chunk.cellState(2, 3, 4));
        assertSame(
                DetailChunkSnapshot.emptyView(),
                chunk.detailSnapshotForCapture());
    }

    @Test
    void detailMembershipIsThePhysicalDiscriminatorAndZerosBackingByte() {
        Chunk chunk = new Chunk(WORLD_HEIGHT);
        chunk.setBlock(2, 3, 4, (byte) 9);

        chunk.replaceCanonicalCell(
                2, 3, 4, DetailCellState.uniform((byte) 9));

        assertInstanceOf(
                DetailCellState.class,
                chunk.cellState(2, 3, 4));
        assertEquals(0, chunk.rawFullBlockForInvariant(2, 3, 4));
        assertEquals(1, chunk.detailParentCount());
        assertThrows(
                IllegalStateException.class,
                () -> chunk.getBlock(2, 3, 4));
        assertThrows(
                IllegalStateException.class,
                () -> chunk.setBlock(2, 3, 4, (byte) 7));
    }

    @Test
    void replacingLastDetailWithFullAirRemovesPhysicalStorage() {
        Chunk chunk = new Chunk(WORLD_HEIGHT);
        chunk.replaceCanonicalCell(
                2, 3, 4, oneOccupiedCell((byte) 7));

        chunk.replaceCanonicalCell(2, 3, 4, new FullCellState((byte) 0));

        assertEquals(
                new FullCellState((byte) 0),
                chunk.cellState(2, 3, 4));
        assertEquals(0, chunk.getBlock(2, 3, 4));
        assertEquals(0, chunk.detailParentCount());
        assertNull(detailStorageField(chunk));
    }

    @Test
    void detailParentsAreStoredInAscendingCanonicalParentIndexOrder() {
        Chunk chunk = new Chunk(WORLD_HEIGHT);
        chunk.replaceCanonicalCell(
                15, 31, 15, oneOccupiedCell((byte) 3));
        chunk.replaceCanonicalCell(
                0, 0, 0, oneOccupiedCell((byte) 4));
        chunk.replaceCanonicalCell(
                2, 3, 4, oneOccupiedCell((byte) 5));

        assertArrayEquals(
                new int[] {0, parentIndex(2, 3, 4), 8191},
                chunk.copyDetailParentIndicesForSnapshot());
    }

    @Test
    void replacingDetailStateKeepsOneCanonicalEntry() {
        Chunk chunk = new Chunk(WORLD_HEIGHT);
        chunk.replaceCanonicalCell(
                2, 3, 4, oneOccupiedCell((byte) 3));

        chunk.replaceCanonicalCell(
                2, 3, 4, oneOccupiedCell((byte) 8));

        DetailCellState state =
                assertInstanceOf(
                        DetailCellState.class,
                        chunk.cellState(2, 3, 4));
        assertEquals(
                8,
                Byte.toUnsignedInt(
                        state.blockId(new LocalSubVoxelPosition(0, 0, 0))));
        assertEquals(1, chunk.detailParentCount());
        assertEquals(0, chunk.rawFullBlockForInvariant(2, 3, 4));
    }

    @Test
    void hardCapRejectsEntryOneThousandTwentyFiveWithoutPartialChange() {
        Chunk chunk = new Chunk(WORLD_HEIGHT);
        DetailCellState detail = oneOccupiedCell((byte) 6);
        for (int parentIndex = 0;
                parentIndex < Chunk.MAX_DETAIL_PARENTS_PER_CHUNK;
                parentIndex++) {
            replaceAtParentIndex(chunk, parentIndex, detail);
        }

        assertEquals(1024, chunk.detailParentCount());
        IllegalStateException failure =
                assertThrows(
                        IllegalStateException.class,
                        () -> replaceAtParentIndex(chunk, 1024, detail));

        assertEquals("Chunk DETAIL parent capacity exceeded", failure.getMessage());
        assertEquals(1024, chunk.detailParentCount());
        int[] indices = chunk.copyDetailParentIndicesForSnapshot();
        assertEquals(1023, indices[indices.length - 1]);
        assertEquals(
                new FullCellState((byte) 0),
                cellStateAtParentIndex(chunk, 1024));
    }

    @Test
    void canonicalReplacementRejectsOutOfRangeParentCoordinates() {
        Chunk chunk = new Chunk(WORLD_HEIGHT);
        DetailCellState detail = oneOccupiedCell((byte) 7);

        assertThrows(
                IllegalArgumentException.class,
                () -> chunk.replaceCanonicalCell(-1, 0, 0, detail));
        assertThrows(
                IllegalArgumentException.class,
                () -> chunk.replaceCanonicalCell(
                        GameConfig.Chunk.SIZE, 0, 0, detail));
        assertThrows(
                IllegalArgumentException.class,
                () -> chunk.replaceCanonicalCell(0, -1, 0, detail));
        assertThrows(
                IllegalArgumentException.class,
                () -> chunk.replaceCanonicalCell(0, WORLD_HEIGHT, 0, detail));
        assertThrows(
                IllegalArgumentException.class,
                () -> chunk.replaceCanonicalCell(0, 0, -1, detail));
        assertThrows(
                IllegalArgumentException.class,
                () -> chunk.replaceCanonicalCell(
                        0, 0, GameConfig.Chunk.SIZE, detail));

        assertEquals(0, chunk.detailParentCount());
        assertNull(detailStorageField(chunk));
    }

    private static DetailCellState oneOccupiedCell(byte blockId) {
        byte[] blockIds = new byte[64];
        blockIds[0] = blockId;
        return new DetailCellState(1L, blockIds);
    }

    private static void replaceAtParentIndex(
            Chunk chunk, int index, DetailCellState state) {
        int localZ = index / (GameConfig.Chunk.SIZE * WORLD_HEIGHT);
        int remainder = index % (GameConfig.Chunk.SIZE * WORLD_HEIGHT);
        int y = remainder / GameConfig.Chunk.SIZE;
        int localX = remainder % GameConfig.Chunk.SIZE;
        chunk.replaceCanonicalCell(localX, y, localZ, state);
    }

    private static ParentCellState cellStateAtParentIndex(
            Chunk chunk, int index) {
        int localZ = index / (GameConfig.Chunk.SIZE * WORLD_HEIGHT);
        int remainder = index % (GameConfig.Chunk.SIZE * WORLD_HEIGHT);
        int y = remainder / GameConfig.Chunk.SIZE;
        int localX = remainder % GameConfig.Chunk.SIZE;
        return chunk.cellState(localX, y, localZ);
    }

    private static int parentIndex(int localX, int y, int localZ) {
        return localX
                + y * GameConfig.Chunk.SIZE
                + localZ * GameConfig.Chunk.SIZE * WORLD_HEIGHT;
    }

    private static Object detailStorageField(Chunk chunk) {
        try {
            Field field = Chunk.class.getDeclaredField("detailStorage");
            field.setAccessible(true);
            return field.get(chunk);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }
}

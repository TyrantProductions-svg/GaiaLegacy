package com.overlord.voxel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.overlord.config.GameConfig;
import java.lang.reflect.Field;
import org.junit.jupiter.api.Test;

class ChunkSnapshotTest {
    @Test
    void ownsDefensiveCopyOfFullChunkBytes() {
        int worldHeight = 32;
        int x = 4;
        int y = 17;
        int z = 6;
        int index =
                x
                        + y * GameConfig.Chunk.SIZE
                        + z * GameConfig.Chunk.SIZE * worldHeight;
        byte[] blocks =
                new byte[
                        GameConfig.Chunk.SIZE
                                * worldHeight
                                * GameConfig.Chunk.SIZE];
        blocks[index] = 11;

        ChunkKey key = new ChunkKey(-2, 3);
        ChunkSnapshot snapshot =
                ChunkSnapshot.of(key, 9L, worldHeight, blocks);
        blocks[index] = 27;

        assertEquals(key, snapshot.key());
        assertEquals(9L, snapshot.revision());
        assertEquals(worldHeight, snapshot.worldHeight());
        assertEquals(11, Byte.toUnsignedInt(snapshot.getBlock(x, y, z)));
    }

    @Test
    void outOfRangeCoordinatesReadAsAir() {
        ChunkSnapshot snapshot =
                ChunkSnapshot.empty(new ChunkKey(0, 0), 1L, 32);

        assertEquals(0, snapshot.getBlock(0, -1, 0));
        assertEquals(0, snapshot.getBlock(0, 32, 0));
        assertEquals(0, snapshot.getBlock(-1, 0, 0));
        assertEquals(0, snapshot.getBlock(GameConfig.Chunk.SIZE, 0, 0));
        assertEquals(0, snapshot.getBlock(0, 0, -1));
        assertEquals(0, snapshot.getBlock(0, 0, GameConfig.Chunk.SIZE));
    }

    @Test
    void emptySnapshotContainsOnlyAir() {
        ChunkSnapshot snapshot =
                ChunkSnapshot.empty(new ChunkKey(2, -4), 3L, 32);

        assertEquals(0, snapshot.getBlock(0, 0, 0));
        assertEquals(
                0,
                snapshot.getBlock(
                        GameConfig.Chunk.SIZE - 1,
                        snapshot.worldHeight() - 1,
                        GameConfig.Chunk.SIZE - 1));
    }

    @Test
    void typedStateAndByteGuardUseDetailMembershipNotBackingAir() {
        int worldHeight = 32;
        int parentIndex = canonicalIndex(2, 3, 4, worldHeight);
        byte[] fullBlocks = blocks(worldHeight);
        ChunkSnapshot snapshot =
                ChunkSnapshot.of(
                        new ChunkKey(-2, 3),
                        9L,
                        worldHeight,
                        fullBlocks,
                        detailAt(parentIndex, (byte) 7));

        DetailCellState detail =
                assertInstanceOf(
                        DetailCellState.class,
                        snapshot.cellState(2, 3, 4));
        assertEquals(
                7,
                Byte.toUnsignedInt(
                        detail.blockId(new LocalSubVoxelPosition(0, 0, 0))));
        assertThrows(
                IllegalStateException.class,
                () -> snapshot.getBlock(2, 3, 4));
        assertThrows(
                IllegalStateException.class,
                snapshot::copyBlocks);
        assertEquals(0, snapshot.copyFullBlocks()[parentIndex]);
    }

    @Test
    void snapshotRejectsDetailOverNonairBackingByte() {
        int worldHeight = 32;
        int parentIndex = canonicalIndex(2, 3, 4, worldHeight);
        byte[] fullBlocks = blocks(worldHeight);
        fullBlocks[parentIndex] = 8;

        assertThrows(
                IllegalArgumentException.class,
                () ->
                        ChunkSnapshot.of(
                                new ChunkKey(0, 0),
                                1L,
                                worldHeight,
                                fullBlocks,
                                detailAt(parentIndex, (byte) 7)));
    }

    @Test
    void equalityAndCanonicalHashIncludeDetailWhileContentIgnoresIdentity() {
        int worldHeight = 32;
        int parentIndex = canonicalIndex(2, 3, 4, worldHeight);
        byte[] fullBlocks = blocks(worldHeight);
        ChunkSnapshot first =
                ChunkSnapshot.of(
                        new ChunkKey(0, 0),
                        1L,
                        worldHeight,
                        fullBlocks,
                        detailAt(parentIndex, (byte) 7));
        ChunkSnapshot sameContent =
                ChunkSnapshot.of(
                        new ChunkKey(9, -3),
                        22L,
                        worldHeight,
                        fullBlocks,
                        detailAt(parentIndex, (byte) 7));
        ChunkSnapshot changedDetail =
                ChunkSnapshot.of(
                        new ChunkKey(0, 0),
                        1L,
                        worldHeight,
                        fullBlocks,
                        detailAt(parentIndex, (byte) 8));

        assertFalse(first.equals(sameContent));
        assertEquals(first, ChunkSnapshot.of(
                first.key(), first.revision(), worldHeight, fullBlocks,
                detailAt(parentIndex, (byte) 7)));
        assertFalse(first.equals(changedDetail));
        assertEquals(true, first.canonicalContentEquals(sameContent));
        assertArrayEquals(
                first.canonicalContentHash(),
                sameContent.canonicalContentHash());
        assertFalse(
                java.util.Arrays.equals(
                        first.canonicalContentHash(),
                        changedDetail.canonicalContentHash()));
    }

    @Test
    void fullOnlySnapshotStoresNoEmptyDetailSentinel() {
        ChunkSnapshot snapshot =
                ChunkSnapshot.empty(new ChunkKey(2, -4), 3L, 32);

        assertNull(internalDetails(snapshot));
        assertEquals(0, snapshot.details().entryCount());
        assertInstanceOf(FullCellState.class, snapshot.cellState(0, 0, 0));
    }

    private static byte[] blocks(int worldHeight) {
        return new byte[
                GameConfig.Chunk.SIZE
                        * worldHeight
                        * GameConfig.Chunk.SIZE];
    }

    private static int canonicalIndex(
            int localX, int y, int localZ, int worldHeight) {
        return localX
                + y * GameConfig.Chunk.SIZE
                + localZ * GameConfig.Chunk.SIZE * worldHeight;
    }

    private static DetailChunkSnapshot detailAt(
            int parentIndex, byte blockId) {
        byte[] ids = new byte[64];
        ids[0] = blockId;
        return DetailChunkSnapshot.of(
                new int[] {parentIndex}, new long[] {1L}, ids);
    }

    private static Object internalDetails(ChunkSnapshot snapshot) {
        try {
            Field field = ChunkSnapshot.class.getDeclaredField("details");
            field.setAccessible(true);
            return field.get(snapshot);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }
}

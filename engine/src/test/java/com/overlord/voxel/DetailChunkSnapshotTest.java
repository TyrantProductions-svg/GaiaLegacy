package com.overlord.voxel;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

class DetailChunkSnapshotTest {
    @Test
    void ownsCanonicalOrderedEntryArrays() {
        int[] parentIndices = {1, 42, 65535};
        long[] masks = {1L, 2L, Long.MIN_VALUE};
        byte[] ids = new byte[3 * 64];
        ids[0] = 3;
        ids[64 + 1] = 4;
        ids[128 + 63] = 5;

        DetailChunkSnapshot snapshot =
                DetailChunkSnapshot.of(parentIndices, masks, ids);
        parentIndices[0] = 9;
        masks[0] = 2L;
        ids[0] = 0;

        assertEquals(3, snapshot.entryCount());
        assertArrayEquals(new int[] {1, 42, 65535}, snapshot.copyParentIndices());
        assertArrayEquals(new long[] {1L, 2L, Long.MIN_VALUE}, snapshot.copyOccupancyMasks());
        assertEquals(
                3,
                Byte.toUnsignedInt(
                        snapshot
                                .stateAtParentIndex(1)
                                .orElseThrow()
                                .blockId(new LocalSubVoxelPosition(0, 0, 0))));
        assertTrue(snapshot.stateAtParentIndex(42).isPresent());
        assertFalse(snapshot.stateAtParentIndex(43).isPresent());
    }

    @Test
    void rejectsNoncanonicalOrderingDuplicatesAndMalformedEntries() {
        byte[] twoEntries = new byte[128];
        twoEntries[0] = 3;
        twoEntries[64] = 4;

        assertThrows(
                IllegalArgumentException.class,
                () ->
                        DetailChunkSnapshot.of(
                                new int[] {2, 1},
                                new long[] {1L, 1L},
                                twoEntries));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        DetailChunkSnapshot.of(
                                new int[] {2, 2},
                                new long[] {1L, 1L},
                                twoEntries));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        DetailChunkSnapshot.of(
                                new int[] {-1},
                                new long[] {1L},
                                oneEntryIds((byte) 3)));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        DetailChunkSnapshot.of(
                                new int[] {65536},
                                new long[] {1L},
                                oneEntryIds((byte) 3)));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        DetailChunkSnapshot.of(
                                new int[] {1},
                                new long[] {0L},
                                new byte[64]));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        DetailChunkSnapshot.of(
                                new int[] {1},
                                new long[] {1L},
                                new byte[64]));
    }

    @Test
    void rejectsEntryOneThousandTwentyFive() {
        int count = Chunk.MAX_DETAIL_PARENTS_PER_CHUNK + 1;
        int[] parentIndices = new int[count];
        long[] masks = new long[count];
        byte[] ids = new byte[count * 64];
        for (int entry = 0; entry < count; entry++) {
            parentIndices[entry] = entry;
            masks[entry] = 1L;
            ids[entry * 64] = 3;
        }

        assertThrows(
                IllegalArgumentException.class,
                () -> DetailChunkSnapshot.of(parentIndices, masks, ids));
    }

    @Test
    void emptyViewIsNonowningAndContainsNoCanonicalDetailEntry() {
        DetailChunkSnapshot empty = DetailChunkSnapshot.emptyView();

        assertTrue(empty.isEmpty());
        assertEquals(0, empty.entryCount());
        assertArrayEquals(new int[0], empty.copyParentIndices());
        assertFalse(empty.stateAtParentIndex(0).isPresent());
        assertEquals(empty, DetailChunkSnapshot.of(
                new int[0], new long[0], new byte[0]));
    }

    @Test
    void equalityAndHashUseCanonicalArrayContent() {
        DetailChunkSnapshot first =
                DetailChunkSnapshot.of(
                        new int[] {7},
                        new long[] {1L},
                        oneEntryIds((byte) 3));
        DetailChunkSnapshot equal =
                DetailChunkSnapshot.of(
                        new int[] {7},
                        new long[] {1L},
                        oneEntryIds((byte) 3));
        byte[] changedIds = oneEntryIds((byte) 4);
        DetailChunkSnapshot changed =
                DetailChunkSnapshot.of(
                        new int[] {7},
                        new long[] {1L},
                        changedIds);

        assertEquals(first, equal);
        assertEquals(first.hashCode(), equal.hashCode());
        assertFalse(first.equals(changed));

        byte[] returned = first.copyBlockIds();
        Arrays.fill(returned, (byte) 0);
        assertEquals(
                3,
                Byte.toUnsignedInt(
                        first.stateAtParentIndex(7)
                                .orElseThrow()
                                .blockId(new LocalSubVoxelPosition(0, 0, 0))));
    }

    private static byte[] oneEntryIds(byte blockId) {
        byte[] ids = new byte[64];
        ids[0] = blockId;
        return ids;
    }
}

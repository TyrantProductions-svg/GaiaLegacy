package com.overlord.voxel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import org.junit.jupiter.api.Test;

class DetailSnapshotBoundTest {
    @Test
    void maximumSnapshotUsesSeventyFourBackingBytesPerEntry() {
        int count = Chunk.MAX_DETAIL_PARENTS_PER_CHUNK;
        int[] parentIndices = new int[count];
        long[] masks = new long[count];
        byte[] ids = new byte[count * DetailCellState.CELL_COUNT];
        for (int entry = 0; entry < count; entry++) {
            parentIndices[entry] = entry;
            masks[entry] = 1L;
            ids[entry * DetailCellState.CELL_COUNT] = 3;
        }
        DetailChunkSnapshot snapshot =
                DetailChunkSnapshot.of(parentIndices, masks, ids);

        short[] storedIndices =
                (short[]) field(snapshot, "parentIndices");
        long[] storedMasks =
                (long[]) field(snapshot, "occupancyMasks");
        byte[] storedIds = (byte[]) field(snapshot, "blockIds");
        int backingBytes =
                storedIndices.length * Short.BYTES
                        + storedMasks.length * Long.BYTES
                        + storedIds.length;

        assertEquals(75_776, backingBytes);
        assertTrue(backingBytes + 256 <= 76_032);
    }

    private static Object field(Object target, String name) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }
}

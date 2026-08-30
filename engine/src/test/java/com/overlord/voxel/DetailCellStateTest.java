package com.overlord.voxel;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

class DetailCellStateTest {
    @Test
    void detailFourIsTheOnlyProductionScale() {
        assertArrayEquals(
                new VoxelScale[] {VoxelScale.DETAIL_4},
                VoxelScale.values());
        assertEquals(4, VoxelScale.DETAIL_4.subdivisionsPerAxis());
        assertEquals(64, VoxelScale.DETAIL_4.cellCount());
        assertEquals(0.25, VoxelScale.DETAIL_4.cellSize());
    }

    @Test
    void indexIsXFastestAndRoundTripsEveryCoordinate() {
        boolean[] observed = new boolean[DetailCellState.CELL_COUNT];

        for (int z = 0; z < 4; z++) {
            for (int y = 0; y < 4; y++) {
                for (int x = 0; x < 4; x++) {
                    LocalSubVoxelPosition position =
                            new LocalSubVoxelPosition(x, y, z);
                    int expected = x + 4 * y + 16 * z;

                    assertEquals(expected, position.index());
                    assertEquals(
                            position,
                            LocalSubVoxelPosition.fromIndex(expected));
                    assertFalse(observed[expected]);
                    observed[expected] = true;
                }
            }
        }

        for (boolean value : observed) {
            assertTrue(value);
        }
    }

    @Test
    void localPositionRejectsEveryOutOfRangeCoordinateAndIndex() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new LocalSubVoxelPosition(-1, 0, 0));
        assertThrows(
                IllegalArgumentException.class,
                () -> new LocalSubVoxelPosition(4, 0, 0));
        assertThrows(
                IllegalArgumentException.class,
                () -> new LocalSubVoxelPosition(0, -1, 0));
        assertThrows(
                IllegalArgumentException.class,
                () -> new LocalSubVoxelPosition(0, 4, 0));
        assertThrows(
                IllegalArgumentException.class,
                () -> new LocalSubVoxelPosition(0, 0, -1));
        assertThrows(
                IllegalArgumentException.class,
                () -> new LocalSubVoxelPosition(0, 0, 4));
        assertThrows(
                IllegalArgumentException.class,
                () -> LocalSubVoxelPosition.fromIndex(-1));
        assertThrows(
                IllegalArgumentException.class,
                () -> LocalSubVoxelPosition.fromIndex(64));
    }

    @Test
    void detailRequiresNonzeroMaskAndExactOccupancyMaterialAgreement() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new DetailCellState(0L, new byte[64]));

        byte[] missingOccupiedMaterial = new byte[64];
        assertThrows(
                IllegalArgumentException.class,
                () -> new DetailCellState(1L, missingOccupiedMaterial));

        byte[] materialOutsideMask = new byte[64];
        materialOutsideMask[1] = 7;
        assertThrows(
                IllegalArgumentException.class,
                () -> new DetailCellState(1L, materialOutsideMask));

        assertThrows(
                IllegalArgumentException.class,
                () -> new DetailCellState(1L, new byte[63]));
    }

    @Test
    void detailOwnsItsMaterialArrayAndReturnsDefensiveCopies() {
        byte[] ids = new byte[64];
        ids[0] = 7;
        DetailCellState state = new DetailCellState(1L, ids);

        ids[0] = 0;
        byte[] copied = state.copyBlockIds();
        copied[0] = 0;

        LocalSubVoxelPosition origin = new LocalSubVoxelPosition(0, 0, 0);
        assertEquals(7, Byte.toUnsignedInt(state.blockId(origin)));
        assertTrue(state.occupied(origin));
        assertFalse(state.occupied(new LocalSubVoxelPosition(1, 0, 0)));
    }

    @Test
    void checkedIndexMaterialReadAvoidsPerCellPositionAllocation() {
        byte[] ids = new byte[64];
        ids[0] = 7;
        ids[63] = 9;
        DetailCellState state = new DetailCellState((1L << 63) | 1L, ids);

        assertEquals(7, Byte.toUnsignedInt(state.blockIdAtIndex(0)));
        assertEquals(0, Byte.toUnsignedInt(state.blockIdAtIndex(1)));
        assertEquals(9, Byte.toUnsignedInt(state.blockIdAtIndex(63)));
        assertThrows(IllegalArgumentException.class,
                () -> state.blockIdAtIndex(-1));
        assertThrows(IllegalArgumentException.class,
                () -> state.blockIdAtIndex(64));
    }

    @Test
    void equalityAndHashIncludeMaskAndAllMaterialIds() {
        byte[] firstIds = new byte[64];
        firstIds[0] = 7;
        byte[] equalIds = Arrays.copyOf(firstIds, firstIds.length);
        byte[] differentIds = Arrays.copyOf(firstIds, firstIds.length);
        differentIds[0] = 8;

        DetailCellState first = new DetailCellState(1L, firstIds);
        DetailCellState equal = new DetailCellState(1L, equalIds);
        DetailCellState differentMaterial =
                new DetailCellState(1L, differentIds);
        byte[] secondCellIds = new byte[64];
        secondCellIds[1] = 7;
        DetailCellState differentMask =
                new DetailCellState(2L, secondCellIds);

        assertEquals(first, equal);
        assertEquals(first.hashCode(), equal.hashCode());
        assertNotEquals(first, differentMaterial);
        assertNotEquals(first, differentMask);
    }

    @Test
    void uniformStateContainsAllSixtyFourOccupiedCells() {
        DetailCellState state = DetailCellState.uniform((byte) 9);

        assertEquals(-1L, state.occupancyMask());
        for (int index = 0; index < 64; index++) {
            LocalSubVoxelPosition position =
                    LocalSubVoxelPosition.fromIndex(index);
            assertTrue(state.occupied(position));
            assertEquals(9, Byte.toUnsignedInt(state.blockId(position)));
        }
        assertThrows(
                IllegalArgumentException.class,
                () -> DetailCellState.uniform((byte) 0));
    }

    @Test
    void sealedParentBoundaryDistinguishesFullAirFullSolidAndDetail() {
        ParentCellState fullAir = new FullCellState((byte) 0);
        ParentCellState fullSolid = new FullCellState((byte) 12);
        ParentCellState detail = DetailCellState.uniform((byte) 12);

        assertTrue(fullAir instanceof FullCellState);
        assertEquals(0, Byte.toUnsignedInt(((FullCellState) fullAir).blockId()));
        assertTrue(fullSolid instanceof FullCellState);
        assertTrue(detail instanceof DetailCellState);
        assertNotEquals(fullSolid, detail);
    }
}

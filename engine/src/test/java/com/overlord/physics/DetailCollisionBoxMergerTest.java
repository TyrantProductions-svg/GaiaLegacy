package com.overlord.physics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.overlord.voxel.DetailCellState;
import java.util.List;
import org.junit.jupiter.api.Test;

class DetailCollisionBoxMergerTest {
    private final DetailCollisionBoxMerger merger =
            new DetailCollisionBoxMerger();

    @Test
    void singleOccupiedCellProducesOneExactQuarterCube() {
        DetailCellState state = state(bit(2, 1, 3));

        BlockCollisionShape shape = merger.merge(state);

        assertEquals(
                List.of(new Aabb(0.5f, 0.25f, 0.75f,
                        0.75f, 0.5f, 1.0f)),
                shape.boxes());
        assertExactCellUnion(state, shape);
    }

    @Test
    void adjacentCellsMergeButSeparatedCellsRemainSeparate() {
        DetailCellState adjacent = state(bit(1, 2, 0) | bit(2, 2, 0));
        DetailCellState separated = state(bit(0, 2, 0) | bit(2, 2, 0));

        assertEquals(
                List.of(new Aabb(0.25f, 0.5f, 0.0f,
                        0.75f, 0.75f, 0.25f)),
                merger.merge(adjacent).boxes());
        assertEquals(
                List.of(
                        new Aabb(0.0f, 0.5f, 0.0f,
                                0.25f, 0.75f, 0.25f),
                        new Aabb(0.5f, 0.5f, 0.0f,
                                0.75f, 0.75f, 0.25f)),
                merger.merge(separated).boxes());
        assertExactCellUnion(adjacent, merger.merge(adjacent));
        assertExactCellUnion(separated, merger.merge(separated));
    }

    @Test
    void fullCubeWallAndSlabUseExactParentAndQuarterExtents() {
        DetailCellState full = DetailCellState.uniform((byte) 7);
        DetailCellState wall = state(maskFor((x, y, z) -> x == 0));
        DetailCellState slab = state(maskFor((x, y, z) -> y == 0));

        assertEquals(
                List.of(new Aabb(0, 0, 0, 1, 1, 1)),
                merger.merge(full).boxes());
        assertEquals(
                List.of(new Aabb(0, 0, 0, 0.25f, 1, 1)),
                merger.merge(wall).boxes());
        assertEquals(
                List.of(new Aabb(0, 0, 0, 1, 0.25f, 1)),
                merger.merge(slab).boxes());
        assertExactCellUnion(full, merger.merge(full));
        assertExactCellUnion(wall, merger.merge(wall));
        assertExactCellUnion(slab, merger.merge(slab));
    }

    @Test
    void staircaseUsesAscendingSeedsAndXThenYThenZGrowth() {
        DetailCellState staircase = state(maskFor(
                (x, y, z) -> z == 0 && y <= x));

        BlockCollisionShape shape = merger.merge(staircase);

        assertEquals(
                List.of(
                        new Aabb(0, 0, 0, 1, 0.25f, 0.25f),
                        new Aabb(0.25f, 0.25f, 0, 1, 0.5f, 0.25f),
                        new Aabb(0.5f, 0.5f, 0, 1, 0.75f, 0.25f),
                        new Aabb(0.75f, 0.75f, 0, 1, 1, 0.25f)),
                shape.boxes());
        assertExactCellUnion(staircase, shape);
    }

    @Test
    void materialsDoNotSplitCollisionGeometry() {
        long mask = bit(0, 0, 0) | bit(1, 0, 0);
        byte[] ids = new byte[DetailCellState.CELL_COUNT];
        ids[index(0, 0, 0)] = 7;
        ids[index(1, 0, 0)] = 9;
        DetailCellState mixed = new DetailCellState(mask, ids);

        assertEquals(
                List.of(new Aabb(0, 0, 0, 0.5f, 0.25f, 0.25f)),
                merger.merge(mixed).boxes());
    }

    @Test
    void hollowAsymmetricAndCheckerboardPatternsCoverEachOccupiedCellExactlyOnce() {
        DetailCellState hollow = state(maskFor(
                (x, y, z) -> !(x >= 1 && x <= 2 && y >= 1 && y <= 2)));
        DetailCellState asymmetric = state(
                bit(0, 0, 0)
                        | bit(1, 0, 0)
                        | bit(1, 1, 0)
                        | bit(3, 2, 1)
                        | bit(0, 3, 2)
                        | bit(2, 1, 3));
        DetailCellState checkerboard = state(maskFor(
                (x, y, z) -> ((x + y + z) & 1) == 0));

        assertExactCellUnion(hollow, merger.merge(hollow));
        assertExactCellUnion(asymmetric, merger.merge(asymmetric));
        assertExactCellUnion(checkerboard, merger.merge(checkerboard));
        assertEquals(32, merger.merge(checkerboard).boxes().size());
    }

    @Test
    void outputIsDeterministicAndNeverExceedsOccupiedCellOrAbsoluteBound() {
        long[] masks = {
            1L,
            -1L,
            0x5555_5555_5555_5555L,
            0xAAAA_AAAA_AAAA_AAAAL,
            0x8421_1248_4812_2184L,
            0xF00F_0FF0_55AA_A55AL
        };
        for (long mask : masks) {
            DetailCellState state = state(mask);
            BlockCollisionShape first = merger.merge(state);
            BlockCollisionShape second = merger.merge(state);

            assertEquals(first.boxes(), second.boxes());
            assertTrue(first.boxes().size() <= Long.bitCount(mask));
            assertTrue(first.boxes().size() <= 64);
            assertExactCellUnion(state, first);
        }
    }

    @Test
    void nullStateIsRejected() {
        assertThrows(NullPointerException.class, () -> merger.merge(null));
    }

    private static DetailCellState state(long mask) {
        byte[] ids = new byte[DetailCellState.CELL_COUNT];
        for (int cell = 0; cell < ids.length; cell++) {
            if ((mask & (1L << cell)) != 0L) {
                ids[cell] = 7;
            }
        }
        return new DetailCellState(mask, ids);
    }

    private static long maskFor(CellPredicate predicate) {
        long mask = 0L;
        for (int z = 0; z < 4; z++) {
            for (int y = 0; y < 4; y++) {
                for (int x = 0; x < 4; x++) {
                    if (predicate.test(x, y, z)) {
                        mask |= bit(x, y, z);
                    }
                }
            }
        }
        return mask;
    }

    private static void assertExactCellUnion(
            DetailCellState state, BlockCollisionShape shape) {
        for (int z = 0; z < 4; z++) {
            for (int y = 0; y < 4; y++) {
                for (int x = 0; x < 4; x++) {
                    float centerX = (x + 0.5f) * 0.25f;
                    float centerY = (y + 0.5f) * 0.25f;
                    float centerZ = (z + 0.5f) * 0.25f;
                    long coveringBoxes = shape.boxes().stream()
                            .filter(box -> centerX >= box.minX()
                                    && centerX < box.maxX()
                                    && centerY >= box.minY()
                                    && centerY < box.maxY()
                                    && centerZ >= box.minZ()
                                    && centerZ < box.maxZ())
                            .count();
                    long expected = (state.occupancyMask() & bit(x, y, z)) != 0L
                            ? 1L
                            : 0L;
                    assertEquals(expected, coveringBoxes,
                            "coverage at " + x + "," + y + "," + z);
                }
            }
        }
    }

    private static long bit(int x, int y, int z) {
        return 1L << index(x, y, z);
    }

    private static int index(int x, int y, int z) {
        return x + 4 * y + 16 * z;
    }

    @FunctionalInterface
    private interface CellPredicate {
        boolean test(int x, int y, int z);
    }
}

package com.overlord.physics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import com.overlord.voxel.DetailCellState;
import com.overlord.voxel.FullCellState;
import com.overlord.voxel.LocalSubVoxelPosition;
import org.junit.jupiter.api.Test;

class BlockCollisionShapeTest {
    private static final Aabb FULL_CUBE = new Aabb(0, 0, 0, 1, 1, 1);

    @Test
    void emptyShapeContainsNoBoxes() {
        BlockCollisionShape shape = BlockCollisionShape.empty();

        assertTrue(shape.isEmpty());
        assertEquals(List.of(), shape.boxes());
        assertSame(shape, BlockCollisionShape.empty());
    }

    @Test
    void fullCubeContainsOneUnitBox() {
        BlockCollisionShape shape = BlockCollisionShape.fullCube();

        assertFalse(shape.isEmpty());
        assertEquals(List.of(FULL_CUBE), shape.boxes());
        assertSame(shape, BlockCollisionShape.fullCube());
    }

    @Test
    void customShapePreservesDeclaredOrderAndCopiesInput() {
        Aabb lower = new Aabb(0, 0, 0, 1, 0.25f, 1);
        Aabb upper = new Aabb(0, 0.75f, 0, 1, 1, 1);
        List<Aabb> input = new ArrayList<>(List.of(lower, upper));

        BlockCollisionShape shape = BlockCollisionShape.of(input);
        input.clear();

        assertEquals(List.of(lower, upper), shape.boxes());
        assertThrows(
                UnsupportedOperationException.class,
                () -> shape.boxes().add(FULL_CUBE));
    }

    @Test
    void customShapeRejectsNullListsAndBoxes() {
        assertThrows(
                NullPointerException.class,
                () -> BlockCollisionShape.of(null));
        assertThrows(
                NullPointerException.class,
                () ->
                        BlockCollisionShape.of(
                                new ArrayList<>(
                                        java.util.Arrays.asList(
                                                FULL_CUBE, null))));
    }

    @Test
    void customShapeRejectsBoxesOutsideTheUnitBlock() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        BlockCollisionShape.of(
                                List.of(
                                        new Aabb(
                                                -0.01f,
                                                0,
                                                0,
                                                1,
                                                1,
                                                1))));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        BlockCollisionShape.of(
                                List.of(
                                        new Aabb(
                                                0,
                                                0,
                                                0,
                                                1,
                                                1.01f,
                                                1))));
    }

    @Test
    void defaultResolverUsesEmptyAirAndFullNonAirBlocks() {
        BlockCollisionShapeResolver resolver =
                BlockCollisionShapeResolver.fullCubesForNonAir();

        assertSame(BlockCollisionShape.empty(), resolver.shapeFor((byte) 0));
        assertSame(BlockCollisionShape.fullCube(), resolver.shapeFor((byte) 1));
        assertSame(BlockCollisionShape.fullCube(), resolver.shapeFor((byte) -1));
    }

    @Test
    void resolverUsesTypedFullSemanticsAndOccupancyOnlyDetailGeometry() {
        BlockCollisionShapeResolver resolver = blockId ->
                blockId == 9
                        ? BlockCollisionShape.of(
                                List.of(new Aabb(0, 0, 0, 1, 0.5f, 1)))
                        : BlockCollisionShape.empty();
        byte[] ids = new byte[DetailCellState.CELL_COUNT];
        LocalSubVoxelPosition first = new LocalSubVoxelPosition(0, 0, 0);
        LocalSubVoxelPosition second = new LocalSubVoxelPosition(1, 0, 0);
        ids[first.index()] = 7;
        ids[second.index()] = 9;
        DetailCellState detail = new DetailCellState(
                (1L << first.index()) | (1L << second.index()), ids);
        DetailCollisionBoxMerger merger = new DetailCollisionBoxMerger();

        assertEquals(
                List.of(new Aabb(0, 0, 0, 1, 0.5f, 1)),
                resolver.shapeFor(new FullCellState((byte) 9), merger).boxes());
        assertEquals(
                List.of(new Aabb(0, 0, 0, 0.5f, 0.25f, 0.25f)),
                resolver.shapeFor(detail, merger).boxes());
    }
}

package com.overlord.interaction.api;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.overlord.assets.ResourceLocation;
import com.overlord.inventory.api.BodySlot;
import com.overlord.inventory.api.ItemStackView;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class InteractionContractTest {
    private static final ResourceLocation STONE =
            ResourceLocation.parse("gaia:stone");

    @Test
    void blockHitRequiresMatchingAdjacentCellAndAxisNormal() {
        BlockHitResult hit =
                new BlockHitResult(
                        4,
                        5,
                        6,
                        5,
                        5,
                        6,
                        STONE,
                        1,
                        0,
                        0,
                        5.0f,
                        5.5f,
                        6.5f,
                        3.0f);

        assertEquals(5, hit.adjacentX());
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new BlockHitResult(
                                4,
                                5,
                                6,
                                4,
                                5,
                                6,
                                STONE,
                                1,
                                0,
                                0,
                                5.0f,
                                5.5f,
                                6.5f,
                                3.0f));
    }

    @Test
    void blockHitAcceptsExactlyTheSixAxisFaceNormals() {
        int[][] normals = {
            {1, 0, 0},
            {-1, 0, 0},
            {0, 1, 0},
            {0, -1, 0},
            {0, 0, 1},
            {0, 0, -1}
        };

        for (int[] normal : normals) {
            assertDoesNotThrow(
                    () -> hitWithNormal(
                            normal[0], normal[1], normal[2]));
        }
    }

    @Test
    void blockHitRejectsInvalidAndExtremeFaceNormals() {
        int[][] invalidNormals = {
            {0, 0, 0},
            {1, 1, 0},
            {2, 0, 0},
            {Integer.MIN_VALUE, Integer.MIN_VALUE, 1}
        };

        for (int[] normal : invalidNormals) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> hitWithNormal(
                            normal[0], normal[1], normal[2]));
        }
    }

    @Test
    void blockHitRejectsNonFinitePointAndDistance() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        hitWithValues(
                                STONE,
                                Float.NaN,
                                0.5f,
                                0.5f,
                                1.0f));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        hitWithValues(
                                STONE,
                                0.5f,
                                Float.POSITIVE_INFINITY,
                                0.5f,
                                1.0f));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        hitWithValues(
                                STONE,
                                0.5f,
                                0.5f,
                                Float.NEGATIVE_INFINITY,
                                1.0f));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        hitWithValues(
                                STONE,
                                0.5f,
                                0.5f,
                                0.5f,
                                Float.NaN));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        hitWithValues(
                                STONE,
                                0.5f,
                                0.5f,
                                0.5f,
                                Float.POSITIVE_INFINITY));
    }

    @Test
    void blockHitRejectsNegativeDistanceAndNullBlockIdentity() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        hitWithValues(
                                STONE,
                                0.5f,
                                0.5f,
                                0.5f,
                                -0.01f));
        assertThrows(
                NullPointerException.class,
                () ->
                        hitWithValues(
                                null,
                                0.5f,
                                0.5f,
                                0.5f,
                                1.0f));
    }

    @Test
    void itemUseContextCarriesEmptyHandAndMissWithoutNulls() {
        ItemUseContext context =
                new ItemUseContext(
                        new EntityRef(9),
                        BodySlot.MOUTH,
                        Optional.empty(),
                        Optional.empty(),
                        InteractionAction.USE,
                        12,
                        900);

        assertEquals(12, context.tick());
        assertEquals(Optional.empty(), context.heldStack());
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new ItemUseContext(
                                new EntityRef(9),
                                BodySlot.MOUTH,
                                Optional.empty(),
                                Optional.empty(),
                                InteractionAction.USE,
                                -1,
                                900));
    }

    @Test
    void itemUseContextRejectsNullOptionalContainersAndNegativeTimestamp() {
        assertThrows(
                NullPointerException.class,
                () ->
                        new ItemUseContext(
                                new EntityRef(9),
                                BodySlot.MOUTH,
                                null,
                                Optional.empty(),
                                InteractionAction.USE,
                                12,
                                900));
        assertThrows(
                NullPointerException.class,
                () ->
                        new ItemUseContext(
                                new EntityRef(9),
                                BodySlot.MOUTH,
                                Optional.empty(),
                                null,
                                InteractionAction.USE,
                                12,
                                900));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new ItemUseContext(
                                new EntityRef(9),
                                BodySlot.MOUTH,
                                Optional.empty(),
                                Optional.empty(),
                                InteractionAction.USE,
                                12,
                                -1));
    }

    @Test
    void itemUseContextRejectsInvalidHeldStackIdentityAndCount() {
        assertThrows(
                NullPointerException.class,
                () -> itemUseContext(stack(null, 1)));
        assertThrows(
                IllegalArgumentException.class,
                () -> itemUseContext(stack(STONE, 0)));
        assertThrows(
                IllegalArgumentException.class,
                () -> itemUseContext(stack(STONE, -1)));
    }

    private static BlockHitResult hitWithNormal(
            int normalX, int normalY, int normalZ) {
        return new BlockHitResult(
                0,
                0,
                0,
                normalX,
                normalY,
                normalZ,
                STONE,
                normalX,
                normalY,
                normalZ,
                0.5f,
                0.5f,
                0.5f,
                1.0f);
    }

    private static BlockHitResult hitWithValues(
            ResourceLocation block,
            float pointX,
            float pointY,
            float pointZ,
            float distance) {
        return new BlockHitResult(
                0,
                0,
                0,
                1,
                0,
                0,
                block,
                1,
                0,
                0,
                pointX,
                pointY,
                pointZ,
                distance);
    }

    private static ItemUseContext itemUseContext(
            ItemStackView heldStack) {
        return new ItemUseContext(
                new EntityRef(9),
                BodySlot.MOUTH,
                Optional.of(heldStack),
                Optional.empty(),
                InteractionAction.USE,
                12,
                900);
    }

    private static ItemStackView stack(
            ResourceLocation itemId, int count) {
        return new ItemStackView() {
            @Override
            public ResourceLocation itemId() {
                return itemId;
            }

            @Override
            public int count() {
                return count;
            }
        };
    }
}

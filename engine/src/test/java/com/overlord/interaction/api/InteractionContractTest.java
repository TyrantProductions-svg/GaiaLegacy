package com.overlord.interaction.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.overlord.assets.ResourceLocation;
import com.overlord.inventory.api.BodySlot;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class InteractionContractTest {
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
                        ResourceLocation.parse("gaia:stone"),
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
                                ResourceLocation.parse("gaia:stone"),
                                1,
                                0,
                                0,
                                5.0f,
                                5.5f,
                                6.5f,
                                3.0f));
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
}

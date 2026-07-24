package com.overlord.inventory.api;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.overlord.assets.ResourceLocation;
import com.overlord.interaction.api.EntityRef;
import java.lang.reflect.Method;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class InventoryContractTest {
    private static final ItemStackView STONE =
            new ItemStackView() {
                @Override
                public ResourceLocation itemId() {
                    return ResourceLocation.parse("gaia:stone");
                }

                @Override
                public int count() {
                    return 2;
                }
            };

    @Test
    void bodySlotsHaveStableThreeSlotOrder() {
        assertArrayEquals(
                new BodySlot[] {
                    BodySlot.LEFT_HAND,
                    BodySlot.RIGHT_HAND,
                    BodySlot.MOUTH
                },
                BodySlot.values());
    }

    @Test
    void entityReferencesRejectNegativeIds() {
        assertEquals(7, new EntityRef(7).id());
        assertThrows(IllegalArgumentException.class, () -> new EntityRef(-1));
    }

    @Test
    void inventoryChangeRequiresValidRevisionAndReferences() {
        EntityRef owner = new EntityRef(4);
        assertEquals(
                Optional.of(STONE),
                new InventoryChangeRequest(
                                owner,
                                BodySlot.RIGHT_HAND,
                                3,
                                Optional.of(STONE))
                        .replacement());
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new InventoryChangeRequest(
                                owner,
                                BodySlot.RIGHT_HAND,
                                -1,
                                Optional.empty()));
        assertThrows(
                NullPointerException.class,
                () ->
                        new InventoryChangeRequest(
                                owner,
                                BodySlot.RIGHT_HAND,
                                0,
                                null));
    }

    @Test
    void uiViewModelDoesNotExposeInventoryService() {
        for (Method method : BodyInventoryViewModel.class.getMethods()) {
            assertFalse(
                    InventoryService.class.isAssignableFrom(
                            method.getReturnType()),
                    method.toString());
        }
    }
}

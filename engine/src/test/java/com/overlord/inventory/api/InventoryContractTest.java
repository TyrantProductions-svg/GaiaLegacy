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
    private static final EntityRef OWNER = new EntityRef(4);

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
        assertEquals(
                Optional.of(STONE),
                new InventoryChangeRequest(
                                OWNER,
                                BodySlot.RIGHT_HAND,
                                3,
                                Optional.of(STONE))
                        .replacement());
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new InventoryChangeRequest(
                                OWNER,
                                BodySlot.RIGHT_HAND,
                                -1,
                                Optional.empty()));
        assertThrows(
                NullPointerException.class,
                () ->
                        new InventoryChangeRequest(
                                OWNER,
                                BodySlot.RIGHT_HAND,
                                0,
                                null));
    }

    @Test
    void unknownInventoryOwnerIsRepresentedByEmptyOptional() {
        InventoryChangeResult unknownOwner =
                new InventoryChangeResult(
                        InventoryChangeResult.Status.UNKNOWN_OWNER,
                        Optional.empty());
        InventoryService service =
                new InventoryService() {
                    @Override
                    public Optional<InventoryView> snapshot(
                            EntityRef owner) {
                        assertEquals(OWNER, owner);
                        return Optional.empty();
                    }

                    @Override
                    public InventoryChangeResult replaceSlot(
                            InventoryChangeRequest request) {
                        return unknownOwner;
                    }
                };

        assertEquals(Optional.empty(), service.snapshot(OWNER));
        assertEquals(Optional.empty(), unknownOwner.inventory());
    }

    @Test
    void inventoryChangeResultEnforcesStatusAndRevisionInvariants() {
        InventoryView current = inventory(3);

        assertEquals(
                Optional.of(current),
                new InventoryChangeResult(
                                InventoryChangeResult.Status.APPLIED,
                                Optional.of(current))
                        .inventory());
        assertThrows(
                NullPointerException.class,
                () ->
                        new InventoryChangeResult(
                                null, Optional.of(current)));
        assertThrows(
                NullPointerException.class,
                () ->
                        new InventoryChangeResult(
                                InventoryChangeResult.Status.APPLIED,
                                null));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new InventoryChangeResult(
                                InventoryChangeResult.Status.UNKNOWN_OWNER,
                                Optional.of(current)));
        for (InventoryChangeResult.Status status :
                new InventoryChangeResult.Status[] {
                    InventoryChangeResult.Status.APPLIED,
                    InventoryChangeResult.Status.CONFLICT,
                    InventoryChangeResult.Status.INVALID_STACK
                }) {
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            new InventoryChangeResult(
                                    status, Optional.empty()));
        }
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new InventoryChangeResult(
                                InventoryChangeResult.Status.CONFLICT,
                                Optional.of(inventory(-1))));
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

    private static InventoryView inventory(long revision) {
        return new InventoryView() {
            @Override
            public EntityRef owner() {
                return OWNER;
            }

            @Override
            public long revision() {
                return revision;
            }

            @Override
            public Optional<ItemStackView> stack(BodySlot slot) {
                return Optional.empty();
            }
        };
    }
}

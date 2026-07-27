package com.gaia.inventory;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.gaia.blocks.ItemFormDefinition;
import com.overlord.assets.ResourceLocation;
import com.overlord.interaction.api.EntityRef;
import com.overlord.inventory.api.BodyInventoryViewModel;
import com.overlord.inventory.api.ItemStack;
import com.overlord.worlditem.api.WorldItemId;
import com.overlord.worlditem.api.WorldItemSnapshot;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class InventoryResultContractTest {
    private static final EntityRef OWNER = new EntityRef(21);
    private static final ResourceLocation DIRT = ResourceLocation.parse("gaia:dirt");
    private static final ItemStack STACK = new ItemStack(DIRT, 2);

    @Test
    void activeInsertExtractAndOperationResultsRejectIncoherentPayloads() {
        BodyInventoryViewModel view = view();

        for (ActiveSlotChangeResult.Status status : new ActiveSlotChangeResult.Status[] {
                ActiveSlotChangeResult.Status.SELECTED,
                ActiveSlotChangeResult.Status.UNCHANGED }) {
            assertDoesNotThrow(() -> new ActiveSlotChangeResult(
                    status, Optional.of(view)));
        }
        assertDoesNotThrow(() -> new ActiveSlotChangeResult(
                ActiveSlotChangeResult.Status.UNKNOWN_OWNER, Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> new ActiveSlotChangeResult(
                ActiveSlotChangeResult.Status.SELECTED, Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> new ActiveSlotChangeResult(
                ActiveSlotChangeResult.Status.UNKNOWN_OWNER, Optional.of(view)));

        assertDoesNotThrow(() -> new InventoryInsertResult(
                InventoryInsertResult.Status.INSERTED, Optional.empty(), Optional.of(view)));
        assertDoesNotThrow(() -> new InventoryInsertResult(
                InventoryInsertResult.Status.PARTIALLY_INSERTED,
                Optional.of(STACK), Optional.of(view)));
        assertDoesNotThrow(() -> new InventoryInsertResult(
                InventoryInsertResult.Status.REJECTED,
                Optional.of(STACK), Optional.of(view)));
        assertDoesNotThrow(() -> new InventoryInsertResult(
                InventoryInsertResult.Status.UNKNOWN_OWNER,
                Optional.of(STACK), Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> new InventoryInsertResult(
                InventoryInsertResult.Status.INSERTED,
                Optional.of(STACK), Optional.of(view)));
        assertThrows(IllegalArgumentException.class, () -> new InventoryInsertResult(
                InventoryInsertResult.Status.REJECTED, Optional.empty(), Optional.of(view)));
        assertThrows(IllegalArgumentException.class, () -> new InventoryInsertResult(
                InventoryInsertResult.Status.UNKNOWN_OWNER,
                Optional.of(STACK), Optional.of(view)));

        for (InventoryExtractResult.Status status : new InventoryExtractResult.Status[] {
                InventoryExtractResult.Status.EXTRACTED,
                InventoryExtractResult.Status.PARTIALLY_EXTRACTED }) {
            assertDoesNotThrow(() -> new InventoryExtractResult(
                    status, Optional.of(STACK), Optional.of(view)));
        }
        for (InventoryExtractResult.Status status : new InventoryExtractResult.Status[] {
                InventoryExtractResult.Status.EMPTY_SLOT,
                InventoryExtractResult.Status.INVALID_COUNT,
                InventoryExtractResult.Status.RESERVED }) {
            assertDoesNotThrow(() -> new InventoryExtractResult(
                    status, Optional.empty(), Optional.of(view)));
        }
        assertDoesNotThrow(() -> new InventoryExtractResult(
                InventoryExtractResult.Status.UNKNOWN_OWNER,
                Optional.empty(), Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> new InventoryExtractResult(
                InventoryExtractResult.Status.EXTRACTED,
                Optional.empty(), Optional.of(view)));
        assertThrows(IllegalArgumentException.class, () -> new InventoryExtractResult(
                InventoryExtractResult.Status.EMPTY_SLOT,
                Optional.of(STACK), Optional.of(view)));
        assertThrows(IllegalArgumentException.class, () -> new InventoryExtractResult(
                InventoryExtractResult.Status.UNKNOWN_OWNER,
                Optional.empty(), Optional.of(view)));

        for (InventoryOperationResult.Status status : new InventoryOperationResult.Status[] {
                InventoryOperationResult.Status.APPLIED,
                InventoryOperationResult.Status.NO_CHANGE,
                InventoryOperationResult.Status.REJECTED,
                InventoryOperationResult.Status.RESERVED }) {
            assertDoesNotThrow(() -> new InventoryOperationResult(
                    status, Optional.of(view)));
        }
        assertDoesNotThrow(() -> new InventoryOperationResult(
                InventoryOperationResult.Status.UNKNOWN_OWNER, Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> new InventoryOperationResult(
                InventoryOperationResult.Status.APPLIED, Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> new InventoryOperationResult(
                InventoryOperationResult.Status.UNKNOWN_OWNER, Optional.of(view)));
    }

    @Test
    void dropResultsRejectIncoherentWorldItemAndRemainderPayloads() {
        WorldItemSnapshot item = new WorldItemSnapshot(
                new WorldItemId(1), STACK,
                0, 0, 0, 0, 0, 0, 0);

        assertDoesNotThrow(() -> new InventoryDropResult(
                InventoryDropResult.Status.DROPPED,
                Optional.of(item), Optional.empty()));
        for (InventoryDropResult.Status status : new InventoryDropResult.Status[] {
                InventoryDropResult.Status.INVENTORY_RESERVATION_REJECTED,
                InventoryDropResult.Status.PARTIAL_RESERVATION_REJECTED,
                InventoryDropResult.Status.WORLD_ITEM_REJECTED }) {
            assertDoesNotThrow(() -> new InventoryDropResult(
                    status, Optional.empty(), Optional.of(STACK)));
        }
        for (InventoryDropResult.Status status : new InventoryDropResult.Status[] {
                InventoryDropResult.Status.EMPTY_SLOT,
                InventoryDropResult.Status.UNKNOWN_OWNER,
                InventoryDropResult.Status.WORLD_ITEM_UNAVAILABLE }) {
            assertDoesNotThrow(() -> new InventoryDropResult(
                    status, Optional.empty(), Optional.empty()));
        }
        assertDoesNotThrow(() -> new InventoryDropResult(
                InventoryDropResult.Status.COMMIT_GUARANTEE_BROKEN,
                Optional.of(item), Optional.empty()));

        assertThrows(IllegalArgumentException.class, () -> new InventoryDropResult(
                InventoryDropResult.Status.DROPPED,
                Optional.empty(), Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> new InventoryDropResult(
                InventoryDropResult.Status.WORLD_ITEM_REJECTED,
                Optional.of(item), Optional.of(STACK)));
        assertThrows(IllegalArgumentException.class, () -> new InventoryDropResult(
                InventoryDropResult.Status.WORLD_ITEM_REJECTED,
                Optional.empty(), Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> new InventoryDropResult(
                InventoryDropResult.Status.EMPTY_SLOT,
                Optional.empty(), Optional.of(STACK)));
    }

    @Test
    void splitResultsAcceptAndRejectEveryStatusPayloadCategory() {
        BodyInventoryViewModel view = view();

        assertDoesNotThrow(() -> new InventorySplitResult(
                InventorySplitResult.Status.SPLIT,
                Optional.of(STACK), Optional.of(view)));
        assertDoesNotThrow(() -> new InventorySplitResult(
                InventorySplitResult.Status.UNKNOWN_OWNER,
                Optional.empty(), Optional.empty()));
        for (InventorySplitResult.Status status : new InventorySplitResult.Status[] {
                InventorySplitResult.Status.INVALID_COUNT,
                InventorySplitResult.Status.SAME_SLOT,
                InventorySplitResult.Status.EMPTY_SOURCE,
                InventorySplitResult.Status.SOURCE_TOO_SMALL,
                InventorySplitResult.Status.RESERVED,
                InventorySplitResult.Status.REJECTED,
                InventorySplitResult.Status.DESTINATION_FULL }) {
            assertDoesNotThrow(() -> new InventorySplitResult(
                    status, Optional.empty(), Optional.of(view)));
        }

        assertThrows(IllegalArgumentException.class, () -> new InventorySplitResult(
                InventorySplitResult.Status.SPLIT,
                Optional.empty(), Optional.of(view)));
        assertThrows(IllegalArgumentException.class, () -> new InventorySplitResult(
                InventorySplitResult.Status.SPLIT,
                Optional.of(STACK), Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> new InventorySplitResult(
                InventorySplitResult.Status.UNKNOWN_OWNER,
                Optional.of(STACK), Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> new InventorySplitResult(
                InventorySplitResult.Status.UNKNOWN_OWNER,
                Optional.empty(), Optional.of(view)));
        assertThrows(IllegalArgumentException.class, () -> new InventorySplitResult(
                InventorySplitResult.Status.REJECTED,
                Optional.of(STACK), Optional.of(view)));
        assertThrows(IllegalArgumentException.class, () -> new InventorySplitResult(
                InventorySplitResult.Status.REJECTED,
                Optional.empty(), Optional.empty()));
    }

    private static BodyInventoryViewModel view() {
        BodyInventoryService service = new BodyInventoryService(
                OWNER,
                id -> Optional.ofNullable(Map.of(
                        DIRT, new ItemFormDefinition(DIRT, 64, false, false)).get(id)),
                event -> {});
        return service.viewModel(OWNER).orElseThrow();
    }
}

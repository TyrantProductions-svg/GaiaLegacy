package com.gaia.inventory;

import com.overlord.inventory.api.ItemStack;
import com.overlord.worlditem.api.WorldItemSnapshot;
import java.util.Objects;
import java.util.Optional;

/** Closed outcome of a Q-drop transaction. */
public record InventoryDropResult(
        Status status,
        Optional<WorldItemSnapshot> worldItem,
        Optional<ItemStack> remainder) {
    public InventoryDropResult {
        status = Objects.requireNonNull(status, "status");
        worldItem = Objects.requireNonNull(worldItem, "worldItem");
        remainder = Objects.requireNonNull(remainder, "remainder");
        switch (status) {
            case DROPPED, COMMIT_GUARANTEE_BROKEN -> {
                if (worldItem.isEmpty() || remainder.isPresent()) {
                    throw new IllegalArgumentException(
                            status + " requires a world item and no remainder");
                }
            }
            case INVENTORY_RESERVATION_REJECTED,
                    PARTIAL_RESERVATION_REJECTED,
                    WORLD_ITEM_REJECTED -> {
                if (worldItem.isPresent() || remainder.isEmpty()) {
                    throw new IllegalArgumentException(
                            status + " requires only a canonical remainder");
                }
            }
            case EMPTY_SLOT, UNKNOWN_OWNER, WORLD_ITEM_UNAVAILABLE -> {
                if (worldItem.isPresent() || remainder.isPresent()) {
                    throw new IllegalArgumentException(
                            status + " must not include item payloads");
                }
            }
        }
    }

    public enum Status {
        DROPPED,
        EMPTY_SLOT,
        UNKNOWN_OWNER,
        INVENTORY_RESERVATION_REJECTED,
        PARTIAL_RESERVATION_REJECTED,
        WORLD_ITEM_REJECTED,
        COMMIT_GUARANTEE_BROKEN,
        WORLD_ITEM_UNAVAILABLE
    }
}

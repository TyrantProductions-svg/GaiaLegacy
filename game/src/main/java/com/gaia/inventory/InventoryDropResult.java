package com.gaia.inventory;

import com.overlord.inventory.api.ItemStack;
import com.overlord.worlditem.api.WorldItemSnapshot;
import java.util.Objects;
import java.util.Optional;

/** Closed outcome of a Q-drop transaction. */
public record InventoryDropResult(
        Status status,
        Optional<WorldItemSnapshot> worldItem,
        Optional<ItemStack> remainder,
        Optional<Throwable> failure) {
    public InventoryDropResult {
        status = Objects.requireNonNull(status, "status");
        worldItem = Objects.requireNonNull(worldItem, "worldItem");
        remainder = Objects.requireNonNull(remainder, "remainder");
        failure = Objects.requireNonNull(failure, "failure");
        switch (status) {
            case DROPPED -> {
                if (worldItem.isEmpty() || remainder.isPresent() || failure.isPresent()) {
                    throw new IllegalArgumentException(
                            "DROPPED requires only a world item");
                }
            }
            case DROPPED_WITH_NOTIFICATION_FAILURE -> {
                if (worldItem.isEmpty() || remainder.isPresent() || failure.isEmpty()) {
                    throw new IllegalArgumentException(
                            "DROPPED_WITH_NOTIFICATION_FAILURE requires an item and failure");
                }
            }
            case COMMIT_GUARANTEE_BROKEN -> {
                if (worldItem.isEmpty() || remainder.isPresent()) {
                    throw new IllegalArgumentException(
                            status + " requires a world item and no remainder");
                }
            }
            case INVENTORY_RESERVATION_REJECTED,
                    PARTIAL_RESERVATION_REJECTED,
                    WORLD_ITEM_REJECTED -> {
                if (worldItem.isPresent() || remainder.isEmpty() || failure.isPresent()) {
                    throw new IllegalArgumentException(
                            status + " requires only a canonical remainder");
                }
            }
            case EMPTY_SLOT, UNKNOWN_OWNER, WORLD_ITEM_UNAVAILABLE -> {
                if (worldItem.isPresent() || remainder.isPresent() || failure.isPresent()) {
                    throw new IllegalArgumentException(
                            status + " must not include item payloads");
                }
            }
        }
    }

    public InventoryDropResult(
            Status status,
            Optional<WorldItemSnapshot> worldItem,
            Optional<ItemStack> remainder) {
        this(status, worldItem, remainder, Optional.empty());
    }

    public enum Status {
        DROPPED,
        DROPPED_WITH_NOTIFICATION_FAILURE,
        EMPTY_SLOT,
        UNKNOWN_OWNER,
        INVENTORY_RESERVATION_REJECTED,
        PARTIAL_RESERVATION_REJECTED,
        WORLD_ITEM_REJECTED,
        COMMIT_GUARANTEE_BROKEN,
        WORLD_ITEM_UNAVAILABLE
    }
}

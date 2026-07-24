package com.overlord.inventory.api;

import java.util.Objects;

public record InventoryChangeResult(
        Status status, InventoryView inventory) {
    public InventoryChangeResult {
        status = Objects.requireNonNull(status, "status");
        inventory = Objects.requireNonNull(inventory, "inventory");
        if (inventory.revision() < 0) {
            throw new IllegalArgumentException(
                    "inventory revision must be non-negative");
        }
    }

    public enum Status {
        APPLIED,
        CONFLICT,
        INVALID_STACK,
        UNKNOWN_OWNER
    }
}

package com.overlord.inventory.api;

import java.util.Objects;
import java.util.Optional;

public record InventoryChangeResult(
        Status status, Optional<InventoryView> inventory) {
    public InventoryChangeResult {
        status = Objects.requireNonNull(status, "status");
        inventory = Objects.requireNonNull(inventory, "inventory");
        if (status == Status.UNKNOWN_OWNER) {
            if (inventory.isPresent()) {
                throw new IllegalArgumentException(
                        "UNKNOWN_OWNER must not include an inventory");
            }
        } else {
            if (inventory.isEmpty()) {
                throw new IllegalArgumentException(
                        status + " requires an inventory");
            }
            InventoryView view = inventory.orElseThrow();
            if (view.revision() < 0) {
                throw new IllegalArgumentException(
                        "inventory revision must be non-negative");
            }
        }
    }

    public enum Status {
        APPLIED,
        CONFLICT,
        INVALID_STACK,
        UNKNOWN_OWNER
    }
}

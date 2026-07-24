package com.overlord.inventory.api;

import java.util.Objects;
import java.util.Optional;

public record InventoryReservationResult(
        InventoryReservationId reservationId,
        Status status,
        Optional<InventoryView> inventory) {
    public InventoryReservationResult {
        reservationId = Objects.requireNonNull(reservationId, "reservationId");
        status = Objects.requireNonNull(status, "status");
        inventory = Objects.requireNonNull(inventory, "inventory");
        if (status != Status.UNKNOWN_RESERVATION && inventory.isEmpty()) {
            throw new IllegalArgumentException(status + " requires an inventory");
        }
        if (inventory.isPresent() && inventory.orElseThrow().revision() < 0) {
            throw new IllegalArgumentException(
                    "inventory revision must be non-negative");
        }
    }

    public enum Status {
        COMMITTED,
        ROLLED_BACK,
        ALREADY_COMMITTED,
        ALREADY_ROLLED_BACK,
        TERMINAL_CONFLICT,
        UNKNOWN_RESERVATION
    }
}

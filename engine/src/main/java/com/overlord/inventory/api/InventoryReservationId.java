package com.overlord.inventory.api;

public record InventoryReservationId(long value) {
    public InventoryReservationId {
        if (value < 0) {
            throw new IllegalArgumentException("value must be non-negative");
        }
    }
}

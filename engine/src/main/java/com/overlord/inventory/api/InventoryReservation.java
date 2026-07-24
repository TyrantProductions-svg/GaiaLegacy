package com.overlord.inventory.api;

import java.util.Objects;

public record InventoryReservation(
        InventoryReservationId id,
        InventoryReservationRequest request,
        ItemStack reserved) {
    public InventoryReservation {
        id = Objects.requireNonNull(id, "id");
        request = Objects.requireNonNull(request, "request");
        reserved = Objects.requireNonNull(reserved, "reserved");
        if (!request.requested().itemId().equals(reserved.itemId())) {
            throw new IllegalArgumentException(
                    "reserved item must match the requested item");
        }
        if (reserved.count() > request.requested().count()) {
            throw new IllegalArgumentException(
                    "reserved count must not exceed the requested count");
        }
    }
}

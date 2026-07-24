package com.overlord.inventory.api;

import com.overlord.interaction.api.EntityRef;
import java.util.Objects;

public record InventoryReservationRequest(
        EntityRef owner,
        BodySlot slot,
        InventoryReservationOperation operation,
        ItemStack requested) {
    public InventoryReservationRequest {
        owner = Objects.requireNonNull(owner, "owner");
        slot = Objects.requireNonNull(slot, "slot");
        operation = Objects.requireNonNull(operation, "operation");
        requested = Objects.requireNonNull(requested, "requested");
    }
}

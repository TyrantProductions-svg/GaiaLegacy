package com.overlord.worlditem.api;

import com.overlord.inventory.api.ItemStack;
import java.util.Objects;

public record WorldItemReservation(
        WorldItemReservationId id,
        WorldItemId itemId,
        ItemStack reserved) {
    public WorldItemReservation {
        id = Objects.requireNonNull(id, "id");
        itemId = Objects.requireNonNull(itemId, "itemId");
        reserved = Objects.requireNonNull(reserved, "reserved");
    }
}

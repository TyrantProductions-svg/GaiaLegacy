package com.overlord.worlditem.api;

import java.util.Objects;

public record WorldItemSpawnReservation(
        WorldItemSpawnReservationId id,
        WorldItemId itemId,
        WorldItemSpawnRequest request) {
    public WorldItemSpawnReservation {
        id = Objects.requireNonNull(id, "id");
        itemId = Objects.requireNonNull(itemId, "itemId");
        request = Objects.requireNonNull(request, "request");
    }
}

package com.overlord.worlditem.api;

import java.util.Objects;

public record WorldItemSpawnReservation(
        WorldItemSpawnReservationId id,
        WorldItemId itemId,
        WorldItemSpawnRequest request,
        long pickupAvailableTick) {
    public WorldItemSpawnReservation(
            WorldItemSpawnReservationId id,
            WorldItemId itemId,
            WorldItemSpawnRequest request) {
        this(id, itemId, request, Objects.requireNonNull(request, "request").tick());
    }

    public WorldItemSpawnReservation {
        id = Objects.requireNonNull(id, "id");
        itemId = Objects.requireNonNull(itemId, "itemId");
        request = Objects.requireNonNull(request, "request");
        WorldItemSpawnIdentity.requireResolvedPickupTiming(
                request, pickupAvailableTick);
    }
}

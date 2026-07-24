package com.overlord.worlditem.api;

import java.util.Optional;

public interface WorldItemService {
    WorldItemSpawnResult spawn(WorldItemSpawnRequest request);

    Optional<WorldItemSnapshot> snapshot(WorldItemId itemId);

    WorldItemReservationResult reserve(WorldItemId itemId, int count);

    WorldItemReservationResult commit(WorldItemReservationId reservationId);

    WorldItemReservationResult rollback(WorldItemReservationId reservationId);
}

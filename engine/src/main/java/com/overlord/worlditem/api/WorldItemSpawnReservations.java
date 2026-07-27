package com.overlord.worlditem.api;

/** Future-spawn capacity capability implemented by the unique WorldItemService. */
public interface WorldItemSpawnReservations {
    WorldItemSpawnReserveResult reserveSpawn(WorldItemSpawnRequest request);

    WorldItemSpawnCommitResult commitSpawn(WorldItemSpawnReservationId reservationId);

    WorldItemSpawnCommitResult rollbackSpawn(WorldItemSpawnReservationId reservationId);
}

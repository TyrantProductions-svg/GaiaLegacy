package com.overlord.worlditem.api;

import java.util.Objects;

/** One exact identity contract for a reserved canonical world-item spawn. */
public final class WorldItemSpawnIdentity {
    public static final long INITIAL_REVISION = 0L;

    private WorldItemSpawnIdentity() {}

    public static void requireResolvedPickupTiming(
            WorldItemSpawnRequest request, long pickupAvailableTick) {
        Objects.requireNonNull(request, "request");
        if (pickupAvailableTick < request.tick()) {
            throw new IllegalArgumentException(
                    "resolved pickup availability cannot precede the spawn tick");
        }
    }

    public static void requireReservationMatchesRequest(
            WorldItemSpawnRequest request, WorldItemSpawnReservation reservation) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(reservation, "reservation");
        requireResolvedPickupTiming(
                reservation.request(), reservation.pickupAvailableTick());
        if (!reservation.request().equals(request)) {
            throw new IllegalArgumentException(
                    "spawn reservation must protect the exact request");
        }
    }

    public static void requireInitialItemMatchesRequest(
            WorldItemSpawnRequest request, WorldItemSnapshot item) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(item, "item");
        if (!item.stack().equals(request.stack())
                || item.positionX() != request.positionX()
                || item.positionY() != request.positionY()
                || item.positionZ() != request.positionZ()
                || item.velocityX() != request.velocityX()
                || item.velocityY() != request.velocityY()
                || item.velocityZ() != request.velocityZ()
                || item.revision() != INITIAL_REVISION) {
            throw new IllegalArgumentException(
                    "spawned item must match the exact request and initial revision");
        }
    }

    public static void requireRuntimeMatchesReservation(
            WorldItemSpawnReservation reservation, WorldItemRuntimeSnapshot runtime) {
        Objects.requireNonNull(reservation, "reservation");
        Objects.requireNonNull(runtime, "runtime");
        WorldItemSnapshot item = runtime.item();
        requireInitialItemMatchesRequest(reservation.request(), item);
        if (!item.id().equals(reservation.itemId())
                || !runtime.source().equals(reservation.request().source())
                || runtime.spawnTick() != reservation.request().tick()
                || runtime.pickupAvailableTick() != reservation.pickupAvailableTick()) {
            throw new IllegalArgumentException(
                    "committed spawn runtime must match its exact reservation identity");
        }
    }
}

package com.gaia.inventory;

import com.overlord.inventory.api.InventoryReservation;
import java.util.Objects;

/**
 * Reports that a world-item spawn threw after an inventory extraction was
 * reserved, so callers must reconcile the exposed reservation without retrying.
 */
public final class WorldItemSpawnIndeterminateException extends RuntimeException {
    private final InventoryReservation reservation;

    public WorldItemSpawnIndeterminateException(
            String message, Throwable cause, InventoryReservation reservation) {
        super(message, cause);
        this.reservation = Objects.requireNonNull(reservation, "reservation");
    }

    public InventoryReservation reservation() {
        return reservation;
    }

    public boolean spawnMayHaveApplied() {
        return true;
    }
}

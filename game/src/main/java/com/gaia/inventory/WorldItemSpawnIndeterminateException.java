package com.gaia.inventory;

import com.overlord.inventory.api.InventoryReservation;
import com.overlord.worlditem.api.WorldItemSpawnReservation;
import java.util.Objects;
import java.util.Optional;

/**
 * Reports that a world-item spawn threw after an inventory extraction was
 * reserved, so callers must reconcile the exposed reservation without retrying.
 */
public final class WorldItemSpawnIndeterminateException extends RuntimeException {
    private final Optional<InventoryReservation> inventoryReservation;
    private final Optional<WorldItemSpawnReservation> worldReservation;
    private final int expectedInventoryCount;

    public WorldItemSpawnIndeterminateException(
            String message, Throwable cause, InventoryReservation reservation) {
        this(message, cause, Optional.of(reservation), Optional.empty(),
                reservation.request().requested().count());
    }

    public WorldItemSpawnIndeterminateException(
            String message,
            Throwable cause,
            Optional<InventoryReservation> inventoryReservation,
            WorldItemSpawnReservation worldReservation,
            int expectedInventoryCount) {
        this(message, cause, inventoryReservation, Optional.of(worldReservation),
                expectedInventoryCount);
    }

    public WorldItemSpawnIndeterminateException(
            String message,
            Throwable cause,
            Optional<InventoryReservation> inventoryReservation,
            Optional<WorldItemSpawnReservation> worldReservation,
            int expectedInventoryCount) {
        super(message, cause);
        this.inventoryReservation = Objects.requireNonNull(
                inventoryReservation, "inventoryReservation");
        this.worldReservation = Objects.requireNonNull(worldReservation, "worldReservation");
        if (expectedInventoryCount < 0) {
            throw new IllegalArgumentException("expectedInventoryCount must be non-negative");
        }
        this.expectedInventoryCount = expectedInventoryCount;
    }

    public InventoryReservation reservation() {
        return inventoryReservation();
    }

    public InventoryReservation inventoryReservation() {
        return inventoryReservation.orElseThrow(
                () -> new IllegalStateException("transaction has no inventory reservation"));
    }

    public Optional<InventoryReservation> optionalInventoryReservation() {
        return inventoryReservation;
    }

    public WorldItemSpawnReservation worldReservation() {
        return worldReservation.orElseThrow(
                () -> new IllegalStateException("legacy transaction has no spawn reservation"));
    }

    public Optional<WorldItemSpawnReservation> optionalWorldReservation() {
        return worldReservation;
    }

    public int expectedInventoryCount() {
        return expectedInventoryCount;
    }

    public boolean spawnMayHaveApplied() {
        return true;
    }
}

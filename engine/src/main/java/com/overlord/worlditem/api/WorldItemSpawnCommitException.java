package com.overlord.worlditem.api;

import java.util.Objects;

/** Typed commit failure that states whether the reserved canonical spawn applied. */
public final class WorldItemSpawnCommitException extends RuntimeException {
    private final WorldItemSpawnReservationId reservationId;
    private final boolean stateChangeApplied;

    public WorldItemSpawnCommitException(
            String message,
            Throwable cause,
            WorldItemSpawnReservationId reservationId,
            boolean stateChangeApplied) {
        super(message, cause);
        this.reservationId = Objects.requireNonNull(reservationId, "reservationId");
        this.stateChangeApplied = stateChangeApplied;
    }

    public WorldItemSpawnReservationId reservationId() {
        return reservationId;
    }

    public boolean stateChangeApplied() {
        return stateChangeApplied;
    }
}

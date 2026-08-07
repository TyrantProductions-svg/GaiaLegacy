package com.overlord.worlditem.api;

import java.util.Objects;

/** Typed commit failure that states whether the canonical extraction applied. */
public final class WorldItemCommitException extends RuntimeException {
    private final WorldItemReservationId reservationId;
    private final boolean stateChangeApplied;

    public WorldItemCommitException(
            String message,
            Throwable cause,
            WorldItemReservationId reservationId,
            boolean stateChangeApplied) {
        super(message, cause);
        this.reservationId = Objects.requireNonNull(reservationId, "reservationId");
        this.stateChangeApplied = stateChangeApplied;
    }

    public WorldItemReservationId reservationId() {
        return reservationId;
    }

    public boolean stateChangeApplied() {
        return stateChangeApplied;
    }
}

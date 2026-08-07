package com.overlord.worlditem.api;

import com.overlord.core.transaction.ReservationTerminalState;
import java.util.Objects;

public record WorldItemReservationAuditSnapshot(
        WorldItemReservation reservation,
        ReservationTerminalState state) {
    public WorldItemReservationAuditSnapshot {
        reservation = Objects.requireNonNull(reservation, "reservation");
        state = Objects.requireNonNull(state, "state");
    }
}

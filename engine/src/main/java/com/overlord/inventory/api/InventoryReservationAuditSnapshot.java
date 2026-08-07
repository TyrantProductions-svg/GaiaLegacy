package com.overlord.inventory.api;

import com.overlord.core.transaction.ReservationTerminalState;
import java.util.Objects;

public record InventoryReservationAuditSnapshot(
        InventoryReservation reservation,
        ReservationTerminalState state) {
    public InventoryReservationAuditSnapshot {
        reservation = Objects.requireNonNull(reservation, "reservation");
        state = Objects.requireNonNull(state, "state");
    }
}

package com.overlord.worlditem.api;

import com.overlord.core.transaction.ReservationTerminalState;
import java.util.Objects;
import java.util.Optional;

public record WorldItemSpawnReservationAuditSnapshot(
        WorldItemSpawnReservation reservation,
        ReservationTerminalState state,
        Optional<WorldItemRuntimeSnapshot> runtime) {
    public WorldItemSpawnReservationAuditSnapshot(
            WorldItemSpawnReservation reservation, ReservationTerminalState state) {
        this(reservation, state, Optional.empty());
    }

    public WorldItemSpawnReservationAuditSnapshot {
        reservation = Objects.requireNonNull(reservation, "reservation");
        state = Objects.requireNonNull(state, "state");
        runtime = Objects.requireNonNull(runtime, "runtime");
        if (state == ReservationTerminalState.COMMITTED) {
            WorldItemSpawnIdentity.requireRuntimeMatchesReservation(
                    reservation, runtime.orElseThrow(() -> new IllegalArgumentException(
                            "COMMITTED spawn audit requires its canonical runtime")));
        } else if (runtime.isPresent()) {
            throw new IllegalArgumentException(
                    "only a COMMITTED spawn audit may contain canonical runtime");
        }
    }
}

package com.overlord.worlditem.api;

import java.util.Objects;
import java.util.Optional;

public record WorldItemSpawnCommitResult(
        Status status,
        Optional<WorldItemSpawnReservation> reservation,
        Optional<WorldItemRuntimeSnapshot> runtime) {
    public WorldItemSpawnCommitResult {
        status = Objects.requireNonNull(status, "status");
        reservation = Objects.requireNonNull(reservation, "reservation");
        runtime = Objects.requireNonNull(runtime, "runtime");
        switch (status) {
            case COMMITTED, ALREADY_COMMITTED -> {
                if (reservation.isEmpty() || runtime.isEmpty()) {
                    throw new IllegalArgumentException(
                            status + " requires a reservation and item");
                }
                WorldItemSpawnReservation held = reservation.get();
                WorldItemSpawnIdentity.requireRuntimeMatchesReservation(
                        held, runtime.orElseThrow());
            }
            case ROLLED_BACK, ALREADY_ROLLED_BACK -> {
                if (reservation.isEmpty() || runtime.isPresent()) {
                    throw new IllegalArgumentException(
                            status + " requires only the reservation");
                }
            }
            case TERMINAL_CONFLICT -> {
                if (reservation.isEmpty()) {
                    throw new IllegalArgumentException(
                            "TERMINAL_CONFLICT requires the known reservation");
                }
                if (runtime.isPresent()) {
                    WorldItemSpawnIdentity.requireRuntimeMatchesReservation(
                            reservation.orElseThrow(), runtime.orElseThrow());
                }
            }
            case UNKNOWN_RESERVATION -> {
                if (reservation.isPresent() || runtime.isPresent()) {
                    throw new IllegalArgumentException(
                            "UNKNOWN_RESERVATION must not include payloads");
                }
            }
        }
    }

    public Optional<WorldItemSnapshot> item() {
        return runtime.map(WorldItemRuntimeSnapshot::item);
    }

    public enum Status {
        COMMITTED,
        ROLLED_BACK,
        ALREADY_COMMITTED,
        ALREADY_ROLLED_BACK,
        TERMINAL_CONFLICT,
        UNKNOWN_RESERVATION
    }
}

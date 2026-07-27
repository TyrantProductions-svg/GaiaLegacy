package com.overlord.worlditem.api;

import java.util.Objects;
import java.util.Optional;

public record WorldItemSpawnCommitResult(
        Status status,
        Optional<WorldItemSpawnReservation> reservation,
        Optional<WorldItemSnapshot> item) {
    public WorldItemSpawnCommitResult {
        status = Objects.requireNonNull(status, "status");
        reservation = Objects.requireNonNull(reservation, "reservation");
        item = Objects.requireNonNull(item, "item");
        switch (status) {
            case COMMITTED, ALREADY_COMMITTED -> {
                if (reservation.isEmpty() || item.isEmpty()) {
                    throw new IllegalArgumentException(
                            status + " requires a reservation and item");
                }
                WorldItemSpawnReservation held = reservation.get();
                WorldItemSnapshot snapshot = item.get();
                if (!held.itemId().equals(snapshot.id())
                        || !held.request().stack().equals(snapshot.stack())) {
                    throw new IllegalArgumentException(
                            "committed spawn item must match its reservation");
                }
            }
            case ROLLED_BACK, ALREADY_ROLLED_BACK -> {
                if (reservation.isEmpty() || item.isPresent()) {
                    throw new IllegalArgumentException(
                            status + " requires only the reservation");
                }
            }
            case TERMINAL_CONFLICT -> {
                if (reservation.isEmpty()) {
                    throw new IllegalArgumentException(
                            "TERMINAL_CONFLICT requires the known reservation");
                }
                if (item.isPresent()
                        && !reservation.get().itemId().equals(item.get().id())) {
                    throw new IllegalArgumentException(
                            "conflict item must match its reservation");
                }
            }
            case UNKNOWN_RESERVATION -> {
                if (reservation.isPresent() || item.isPresent()) {
                    throw new IllegalArgumentException(
                            "UNKNOWN_RESERVATION must not include payloads");
                }
            }
        }
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

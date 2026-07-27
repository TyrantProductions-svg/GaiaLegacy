package com.overlord.worlditem.api;

import com.overlord.inventory.api.ItemStack;
import java.util.Objects;
import java.util.Optional;

public record WorldItemSpawnReserveResult(
        WorldItemSpawnRequest request,
        Status status,
        Optional<WorldItemSpawnReservation> reservation,
        Optional<ItemStack> remainder) {
    public WorldItemSpawnReserveResult {
        request = Objects.requireNonNull(request, "request");
        status = Objects.requireNonNull(status, "status");
        reservation = Objects.requireNonNull(reservation, "reservation");
        remainder = Objects.requireNonNull(remainder, "remainder");
        if (status == Status.RESERVED) {
            WorldItemSpawnReservation value = reservation.orElseThrow(
                    () -> new IllegalArgumentException("RESERVED requires a reservation"));
            if (!value.request().equals(request) || remainder.isPresent()) {
                throw new IllegalArgumentException(
                        "RESERVED must protect the complete spawn request");
            }
        } else if (reservation.isPresent()
                || !remainder.equals(Optional.of(request.stack()))) {
            throw new IllegalArgumentException(
                    "REJECTED must return the full request stack");
        }
    }

    public enum Status {
        RESERVED,
        REJECTED
    }
}

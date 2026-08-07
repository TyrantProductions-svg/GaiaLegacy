package com.gaia.inventory;

import com.overlord.inventory.api.InventoryReservation;
import com.overlord.inventory.api.ItemStack;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Immutable multi-slot insertion reservation result. */
public record InventoryReservationBatch(
        List<InventoryReservation> reservations,
        int acceptedCount,
        Optional<ItemStack> remainder) {
    public InventoryReservationBatch {
        reservations = List.copyOf(Objects.requireNonNull(reservations, "reservations"));
        remainder = Objects.requireNonNull(remainder, "remainder");
        int total = 0;
        for (InventoryReservation reservation : reservations) {
            Objects.requireNonNull(reservation, "reservation");
            total = Math.addExact(total, reservation.reserved().count());
        }
        if (acceptedCount != total) {
            throw new IllegalArgumentException(
                    "accepted count must equal the protected reservation counts");
        }
        if (remainder.isPresent() && !reservations.isEmpty()
                && !remainder.orElseThrow().itemId().equals(
                        reservations.get(0).reserved().itemId())) {
            throw new IllegalArgumentException(
                    "reservation and remainder item identities must match");
        }
    }
}

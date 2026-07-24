package com.overlord.inventory.api;

import java.util.Objects;
import java.util.Optional;

public record InventoryReserveResult(
        InventoryReservationRequest request,
        Status status,
        Optional<InventoryReservation> reservation,
        Optional<ItemStack> remainder,
        Optional<InventoryView> inventory) {
    public InventoryReserveResult {
        request = Objects.requireNonNull(request, "request");
        status = Objects.requireNonNull(status, "status");
        reservation = Objects.requireNonNull(reservation, "reservation");
        remainder = Objects.requireNonNull(remainder, "remainder");
        inventory = Objects.requireNonNull(inventory, "inventory");

        switch (status) {
            case RESERVED -> validateReserved(request, reservation, remainder);
            case PARTIALLY_RESERVED ->
                    validatePartial(request, reservation, remainder);
            case REJECTED, INVALID_STACK, UNKNOWN_OWNER ->
                    validateFailure(request, reservation, remainder);
        }
        validateInventory(request, status, inventory);
    }

    private static void validateReserved(
            InventoryReservationRequest request,
            Optional<InventoryReservation> reservation,
            Optional<ItemStack> remainder) {
        InventoryReservation protectedStack = reservation.orElseThrow(
                () -> new IllegalArgumentException("RESERVED requires a reservation"));
        if (remainder.isPresent()) {
            throw new IllegalArgumentException("RESERVED must not include a remainder");
        }
        if (!protectedStack.request().equals(request)
                || protectedStack.reserved().count() != request.requested().count()) {
            throw new IllegalArgumentException(
                    "RESERVED must protect the complete request");
        }
    }

    private static void validatePartial(
            InventoryReservationRequest request,
            Optional<InventoryReservation> reservation,
            Optional<ItemStack> remainder) {
        InventoryReservation protectedStack = reservation.orElseThrow(
                () -> new IllegalArgumentException(
                        "PARTIALLY_RESERVED requires a reservation"));
        ItemStack remaining = remainder.orElseThrow(
                () -> new IllegalArgumentException(
                        "PARTIALLY_RESERVED requires a remainder"));
        if (!protectedStack.request().equals(request)
                || !remaining.itemId().equals(request.requested().itemId())
                || (long) protectedStack.reserved().count() + remaining.count()
                        != request.requested().count()) {
            throw new IllegalArgumentException(
                    "PARTIALLY_RESERVED parts must exactly match the request");
        }
    }

    private static void validateFailure(
            InventoryReservationRequest request,
            Optional<InventoryReservation> reservation,
            Optional<ItemStack> remainder) {
        if (reservation.isPresent()) {
            throw new IllegalArgumentException(
                    "failed reservation result must not include a reservation");
        }
        if (!remainder.equals(Optional.of(request.requested()))) {
            throw new IllegalArgumentException(
                    "failed reservation result must include the full request remainder");
        }
    }

    private static void validateInventory(
            InventoryReservationRequest request,
            Status status,
            Optional<InventoryView> inventory) {
        if (status == Status.UNKNOWN_OWNER) {
            if (inventory.isPresent()) {
                throw new IllegalArgumentException(
                        "UNKNOWN_OWNER must not include an inventory");
            }
            return;
        }
        InventoryView view = inventory.orElseThrow(
                () -> new IllegalArgumentException(status + " requires an inventory"));
        if (!request.owner().equals(view.owner())) {
            throw new IllegalArgumentException(
                    "inventory owner must match the reservation request");
        }
        if (view.revision() < 0) {
            throw new IllegalArgumentException(
                    "inventory revision must be non-negative");
        }
    }

    public enum Status {
        RESERVED,
        PARTIALLY_RESERVED,
        REJECTED,
        UNKNOWN_OWNER,
        INVALID_STACK
    }
}

package com.overlord.worlditem.api;

import com.overlord.inventory.api.ItemStack;
import java.util.Objects;
import java.util.Optional;

public record WorldItemReservationResult(
        Status status,
        Optional<WorldItemReservation> reservation,
        Optional<WorldItemSnapshot> item,
        Optional<ItemStack> remainder) {
    public WorldItemReservationResult {
        status = Objects.requireNonNull(status, "status");
        reservation = Objects.requireNonNull(reservation, "reservation");
        item = Objects.requireNonNull(item, "item");
        remainder = Objects.requireNonNull(remainder, "remainder");
        switch (status) {
            case RESERVED -> validateReserved(reservation, item, remainder);
            case PARTIALLY_RESERVED -> validatePartial(reservation, item, remainder);
            case COMMITTED, ALREADY_COMMITTED, TERMINAL_CONFLICT ->
                    validateTerminal(reservation, item, remainder);
            case ROLLED_BACK, ALREADY_ROLLED_BACK ->
                    validateRollback(reservation, item, remainder);
            case UNAVAILABLE, INVALID_COUNT ->
                    validateKnownItemFailure(reservation, item, remainder);
            case UNKNOWN_ITEM, UNKNOWN_RESERVATION ->
                    validateUnknown(reservation, item, remainder);
        }
    }

    private static void validateReserved(
            Optional<WorldItemReservation> reservation,
            Optional<WorldItemSnapshot> item,
            Optional<ItemStack> remainder) {
        WorldItemReservation protectedItem = reservation.orElseThrow(
                () -> new IllegalArgumentException("RESERVED requires a reservation"));
        WorldItemSnapshot snapshot = item.orElseThrow(
                () -> new IllegalArgumentException("RESERVED requires an item"));
        if (remainder.isPresent()
                || !protectedItem.itemId().equals(snapshot.id())
                || !protectedItem.reserved().equals(snapshot.stack())) {
            throw new IllegalArgumentException(
                    "RESERVED requires the complete current item without a remainder");
        }
    }

    private static void validatePartial(
            Optional<WorldItemReservation> reservation,
            Optional<WorldItemSnapshot> item,
            Optional<ItemStack> remainder) {
        WorldItemReservation protectedItem = reservation.orElseThrow(
                () -> new IllegalArgumentException(
                        "PARTIALLY_RESERVED requires a reservation"));
        WorldItemSnapshot snapshot = item.orElseThrow(
                () -> new IllegalArgumentException(
                        "PARTIALLY_RESERVED requires an item"));
        ItemStack remaining = remainder.orElseThrow(
                () -> new IllegalArgumentException(
                        "PARTIALLY_RESERVED requires a remainder"));
        if (!protectedItem.itemId().equals(snapshot.id())
                || !protectedItem.reserved().itemId().equals(snapshot.stack().itemId())
                || !remaining.itemId().equals(snapshot.stack().itemId())
                || (long) protectedItem.reserved().count() + remaining.count()
                        != snapshot.stack().count()) {
            throw new IllegalArgumentException(
                    "PARTIALLY_RESERVED parts must exactly match the current item");
        }
    }

    private static void validateTerminal(
            Optional<WorldItemReservation> reservation,
            Optional<WorldItemSnapshot> item,
            Optional<ItemStack> remainder) {
        WorldItemReservation protectedItem = reservation.orElseThrow(
                () -> new IllegalArgumentException("terminal result requires a reservation"));
        if (remainder.isPresent()) {
            throw new IllegalArgumentException("terminal result must not include a remainder");
        }
        item.ifPresent(snapshot -> validateReservationItem(protectedItem, snapshot));
    }

    private static void validateRollback(
            Optional<WorldItemReservation> reservation,
            Optional<WorldItemSnapshot> item,
            Optional<ItemStack> remainder) {
        WorldItemReservation protectedItem = reservation.orElseThrow(
                () -> new IllegalArgumentException("rollback result requires a reservation"));
        WorldItemSnapshot snapshot = item.orElseThrow(
                () -> new IllegalArgumentException("rollback result requires an item"));
        if (remainder.isPresent()) {
            throw new IllegalArgumentException("rollback result must not include a remainder");
        }
        validateReservationItem(protectedItem, snapshot);
    }

    private static void validateKnownItemFailure(
            Optional<WorldItemReservation> reservation,
            Optional<WorldItemSnapshot> item,
            Optional<ItemStack> remainder) {
        if (reservation.isPresent() || item.isEmpty() || remainder.isPresent()) {
            throw new IllegalArgumentException(
                    "known-item failure requires only the current item");
        }
    }

    private static void validateUnknown(
            Optional<WorldItemReservation> reservation,
            Optional<WorldItemSnapshot> item,
            Optional<ItemStack> remainder) {
        if (reservation.isPresent() || item.isPresent() || remainder.isPresent()) {
            throw new IllegalArgumentException("unknown result must not include payloads");
        }
    }

    private static void validateReservationItem(
            WorldItemReservation reservation, WorldItemSnapshot item) {
        if (!reservation.itemId().equals(item.id())
                || !reservation.reserved().itemId().equals(item.stack().itemId())) {
            throw new IllegalArgumentException(
                    "reservation and item must have matching identities");
        }
    }

    public enum Status {
        RESERVED,
        PARTIALLY_RESERVED,
        COMMITTED,
        ROLLED_BACK,
        ALREADY_COMMITTED,
        ALREADY_ROLLED_BACK,
        TERMINAL_CONFLICT,
        UNAVAILABLE,
        UNKNOWN_ITEM,
        UNKNOWN_RESERVATION,
        INVALID_COUNT
    }
}

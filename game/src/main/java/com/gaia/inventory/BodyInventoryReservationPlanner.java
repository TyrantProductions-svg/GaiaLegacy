package com.gaia.inventory;

import com.overlord.interaction.api.EntityRef;
import com.overlord.inventory.api.BodySlot;
import com.overlord.inventory.api.InventoryReservation;
import com.overlord.inventory.api.InventoryReservationOperation;
import com.overlord.inventory.api.InventoryReservationRequest;
import com.overlord.inventory.api.InventoryReservationResult;
import com.overlord.inventory.api.InventoryReserveResult;
import com.overlord.inventory.api.InventoryService;
import com.overlord.inventory.api.ItemStack;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Active-slot-first deterministic acquisition for body inventory insertion. */
public final class BodyInventoryReservationPlanner {
    private final InventoryService inventory;

    public BodyInventoryReservationPlanner(InventoryService inventory) {
        this.inventory = Objects.requireNonNull(inventory, "inventory");
    }

    public InventoryReservationBatch reserveInsertion(
            EntityRef owner,
            BodySlot preferredSlot,
            ItemStack requested) {
        return reserve(owner, preferredSlot, requested, InventoryReservationOperation.INSERT);
    }

    public InventoryReservationBatch reserveExtraction(
            EntityRef owner,
            BodySlot preferredSlot,
            ItemStack requested) {
        return reserve(owner, preferredSlot, requested, InventoryReservationOperation.EXTRACT);
    }

    private InventoryReservationBatch reserve(
            EntityRef owner,
            BodySlot preferredSlot,
            ItemStack requested,
            InventoryReservationOperation operation) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(preferredSlot, "preferredSlot");
        Objects.requireNonNull(requested, "requested");
        Objects.requireNonNull(operation, "operation");
        List<InventoryReservation> acquired = new ArrayList<>();
        Optional<ItemStack> remaining = Optional.of(requested);
        Set<BodySlot> order = new LinkedHashSet<>();
        order.add(preferredSlot);
        order.addAll(List.of(BodySlot.values()));
        try {
            for (BodySlot slot : order) {
                if (remaining.isEmpty()) {
                    break;
                }
                InventoryReserveResult result = inventory.reserve(
                        new InventoryReservationRequest(
                                owner,
                                slot,
                                operation,
                                remaining.orElseThrow()));
                result.reservation().ifPresent(acquired::add);
                remaining = result.remainder();
            }
        } catch (RuntimeException | Error failure) {
            rollbackReverse(acquired).ifPresent(rollbackFailure -> {
                if (rollbackFailure != failure) {
                    failure.addSuppressed(rollbackFailure);
                }
            });
            throw failure;
        }
        int accepted = 0;
        for (InventoryReservation reservation : acquired) {
            accepted = Math.addExact(accepted, reservation.reserved().count());
        }
        return new InventoryReservationBatch(acquired, accepted, remaining);
    }

    public Optional<Throwable> rollbackReverse(InventoryReservationBatch batch) {
        Objects.requireNonNull(batch, "batch");
        return rollbackReverse(batch.reservations());
    }

    private Optional<Throwable> rollbackReverse(List<InventoryReservation> reservations) {
        Throwable primary = null;
        for (int index = reservations.size() - 1; index >= 0; index--) {
            InventoryReservation reservation = reservations.get(index);
            try {
                InventoryReservationResult result = inventory.rollback(reservation.id());
                if (result.status() != InventoryReservationResult.Status.ROLLED_BACK
                        && result.status()
                                != InventoryReservationResult.Status.ALREADY_ROLLED_BACK) {
                    throw new IllegalStateException(
                            "fresh inventory rollback failed: " + result.status());
                }
            } catch (RuntimeException | Error failure) {
                if (primary == null) {
                    primary = failure;
                } else if (primary != failure) {
                    primary.addSuppressed(failure);
                }
            }
        }
        return Optional.ofNullable(primary);
    }
}

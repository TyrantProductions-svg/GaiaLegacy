package com.overlord.inventory.testing;

import com.overlord.interaction.api.EntityRef;
import com.overlord.inventory.api.InventoryChangeRequest;
import com.overlord.inventory.api.InventoryChangeResult;
import com.overlord.inventory.api.InventoryReservation;
import com.overlord.inventory.api.InventoryReservationId;
import com.overlord.inventory.api.InventoryReservationRequest;
import com.overlord.inventory.api.InventoryReservationResult;
import com.overlord.inventory.api.InventoryReserveResult;
import com.overlord.inventory.api.InventoryService;
import com.overlord.inventory.api.InventoryView;
import com.overlord.inventory.api.ItemStack;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Stateful reservation fixture without production inventory semantics. */
public final class FakeInventoryReservationService implements InventoryService {
    private final Map<InventoryReservationId, ReservationState> states = new HashMap<>();
    private Optional<InventoryView> snapshot;
    private InventoryChangeResult replacementResult;
    private long nextReservationId;
    private int nextReservationLimit = Integer.MAX_VALUE;
    private int commitSideEffectCount;
    private int rollbackSideEffectCount;

    public FakeInventoryReservationService(Optional<InventoryView> snapshot) {
        this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
    }

    @Override
    public Optional<InventoryView> snapshot(EntityRef owner) {
        Objects.requireNonNull(owner, "owner");
        return snapshot;
    }

    @Override
    public InventoryChangeResult replaceSlot(InventoryChangeRequest request) {
        Objects.requireNonNull(request, "request");
        if (replacementResult != null) {
            return replacementResult;
        }
        return snapshot.map(view -> new InventoryChangeResult(
                        InventoryChangeResult.Status.CONFLICT, Optional.of(view)))
                .orElseGet(() -> new InventoryChangeResult(
                        InventoryChangeResult.Status.UNKNOWN_OWNER, Optional.empty()));
    }

    @Override
    public InventoryReserveResult reserve(InventoryReservationRequest request) {
        Objects.requireNonNull(request, "request");
        int limit = nextReservationLimit;
        nextReservationLimit = Integer.MAX_VALUE;
        if (snapshot.isEmpty()) {
            return failedReserve(request, InventoryReserveResult.Status.UNKNOWN_OWNER);
        }
        if (limit <= 0) {
            return failedReserve(request, InventoryReserveResult.Status.REJECTED);
        }
        int protectedCount = Math.min(limit, request.requested().count());
        InventoryReservation reservation = new InventoryReservation(
                nextReservationId(), request,
                new ItemStack(request.requested().itemId(), protectedCount));
        states.put(reservation.id(), ReservationState.RESERVED);
        if (protectedCount == request.requested().count()) {
            return new InventoryReserveResult(
                    request, InventoryReserveResult.Status.RESERVED,
                    Optional.of(reservation), Optional.empty(), snapshot);
        }
        return new InventoryReserveResult(
                request, InventoryReserveResult.Status.PARTIALLY_RESERVED,
                Optional.of(reservation),
                Optional.of(new ItemStack(
                        request.requested().itemId(),
                        request.requested().count() - protectedCount)),
                snapshot);
    }

    @Override
    public InventoryReservationResult commit(InventoryReservationId reservationId) {
        return complete(reservationId, ReservationState.COMMITTED);
    }

    @Override
    public InventoryReservationResult rollback(InventoryReservationId reservationId) {
        return complete(reservationId, ReservationState.ROLLED_BACK);
    }

    public void setNextReservationLimit(int limit) {
        if (limit < 0) {
            throw new IllegalArgumentException("limit must be non-negative");
        }
        nextReservationLimit = limit;
    }

    public void setReplacementResult(InventoryChangeResult result) {
        replacementResult = Objects.requireNonNull(result, "result");
    }

    public void simulateOrdinaryStateChange(InventoryView changedSnapshot) {
        snapshot = Optional.of(Objects.requireNonNull(changedSnapshot, "changedSnapshot"));
    }

    public int commitSideEffectCount() {
        return commitSideEffectCount;
    }

    public int rollbackSideEffectCount() {
        return rollbackSideEffectCount;
    }

    private InventoryReservationId nextReservationId() {
        InventoryReservationId id = new InventoryReservationId(nextReservationId);
        if (nextReservationId == Long.MAX_VALUE) {
            throw new IllegalStateException("reservation ID sequence exhausted");
        }
        nextReservationId++;
        return id;
    }

    private InventoryReserveResult failedReserve(
            InventoryReservationRequest request, InventoryReserveResult.Status status) {
        return new InventoryReserveResult(
                request, status, Optional.empty(), Optional.of(request.requested()), snapshot);
    }

    private InventoryReservationResult complete(
            InventoryReservationId reservationId, ReservationState target) {
        Objects.requireNonNull(reservationId, "reservationId");
        ReservationState state = states.get(reservationId);
        if (state == null) {
            return new InventoryReservationResult(
                    reservationId,
                    InventoryReservationResult.Status.UNKNOWN_RESERVATION,
                    Optional.empty());
        }
        if (state == target) {
            return result(reservationId, target == ReservationState.COMMITTED
                    ? InventoryReservationResult.Status.ALREADY_COMMITTED
                    : InventoryReservationResult.Status.ALREADY_ROLLED_BACK);
        }
        if (state != ReservationState.RESERVED) {
            return result(reservationId, InventoryReservationResult.Status.TERMINAL_CONFLICT);
        }
        states.put(reservationId, target);
        if (target == ReservationState.COMMITTED) {
            commitSideEffectCount++;
            return result(reservationId, InventoryReservationResult.Status.COMMITTED);
        }
        rollbackSideEffectCount++;
        return result(reservationId, InventoryReservationResult.Status.ROLLED_BACK);
    }

    private InventoryReservationResult result(
            InventoryReservationId reservationId, InventoryReservationResult.Status status) {
        return new InventoryReservationResult(reservationId, status, snapshot);
    }

    private enum ReservationState {
        RESERVED,
        COMMITTED,
        ROLLED_BACK
    }
}

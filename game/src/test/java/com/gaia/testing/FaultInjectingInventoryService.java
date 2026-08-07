package com.gaia.testing;

import com.gaia.inventory.BodyInventoryService;
import com.gaia.inventory.InventoryInsertResult;
import com.overlord.assets.ResourceLocation;
import com.overlord.interaction.api.EntityRef;
import com.overlord.inventory.api.InventoryChangeRequest;
import com.overlord.inventory.api.InventoryChangeResult;
import com.overlord.inventory.api.InventoryReservation;
import com.overlord.inventory.api.InventoryReservationAudit;
import com.overlord.inventory.api.InventoryReservationAuditSnapshot;
import com.overlord.inventory.api.InventoryReservationId;
import com.overlord.inventory.api.InventoryReservationRequest;
import com.overlord.inventory.api.InventoryReservationResult;
import com.overlord.inventory.api.InventoryReserveResult;
import com.overlord.inventory.api.InventoryService;
import com.overlord.inventory.api.InventoryView;
import com.overlord.inventory.api.ItemStack;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;

/** Test-only fault injector that never changes delegate state for synthetic rollback outcomes. */
public final class FaultInjectingInventoryService
        implements InventoryService, InventoryReservationAudit {
    private final BodyInventoryService delegate;
    private final Deque<RollbackFault> rollbackFaults = new ArrayDeque<>();
    private final List<InventoryReservationId> rollbackReservationIds = new ArrayList<>();

    public FaultInjectingInventoryService(BodyInventoryService delegate) {
        this.delegate = delegate;
    }

    public void failNextRollbackWith(Throwable failure) {
        rollbackFaults.addLast(new RollbackFault(failure, null, false));
    }

    public void returnNextRollbackAs(InventoryReservationResult.Status status) {
        rollbackFaults.addLast(new RollbackFault(null, status, false));
    }

    public void returnNextRollbackWithMismatchedIdentity() {
        rollbackFaults.addLast(new RollbackFault(
                null, InventoryReservationResult.Status.ROLLED_BACK, true));
    }

    public int rollbackCalls() {
        return rollbackReservationIds.size();
    }

    public List<InventoryReservationId> rollbackReservationIds() {
        return List.copyOf(rollbackReservationIds);
    }

    public int totalCount(EntityRef owner, ResourceLocation itemId) {
        return delegate.totalCount(owner, itemId);
    }

    public InventoryInsertResult insert(EntityRef owner, ItemStack stack) {
        return delegate.insert(owner, stack);
    }

    @Override
    public Optional<InventoryView> snapshot(EntityRef owner) {
        return delegate.snapshot(owner);
    }

    @Override
    public InventoryChangeResult replaceSlot(InventoryChangeRequest request) {
        return delegate.replaceSlot(request);
    }

    @Override
    public InventoryReserveResult reserve(InventoryReservationRequest request) {
        return delegate.reserve(request);
    }

    @Override
    public InventoryReservationResult commit(InventoryReservationId reservationId) {
        return delegate.commit(reservationId);
    }

    @Override
    public InventoryReservationResult rollback(InventoryReservationId reservationId) {
        rollbackReservationIds.add(reservationId);
        RollbackFault fault = rollbackFaults.pollFirst();
        if (fault == null) {
            return delegate.rollback(reservationId);
        }
        if (fault.failure != null) {
            if (fault.failure instanceof Error error) {
                throw error;
            }
            throw (RuntimeException) fault.failure;
        }
        InventoryReservationId returnedId = fault.mismatchedIdentity
                ? new InventoryReservationId(reservationId.value() + 10_000L)
                : reservationId;
        Optional<InventoryView> inventory = fault.status
                        == InventoryReservationResult.Status.UNKNOWN_RESERVATION
                ? Optional.empty()
                : reservationAudit(reservationId)
                        .map(InventoryReservationAuditSnapshot::reservation)
                        .map(InventoryReservation::request)
                        .flatMap(request -> delegate.snapshot(request.owner()));
        return new InventoryReservationResult(returnedId, fault.status, inventory);
    }

    @Override
    public Optional<InventoryReservationAuditSnapshot> reservationAudit(
            InventoryReservationId reservationId) {
        return delegate.reservationAudit(reservationId);
    }

    private record RollbackFault(
            Throwable failure,
            InventoryReservationResult.Status status,
            boolean mismatchedIdentity) {}
}

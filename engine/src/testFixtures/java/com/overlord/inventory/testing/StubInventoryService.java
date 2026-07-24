package com.overlord.inventory.testing;

import com.overlord.interaction.api.EntityRef;
import com.overlord.inventory.api.InventoryChangeRequest;
import com.overlord.inventory.api.InventoryChangeResult;
import com.overlord.inventory.api.InventoryReservationId;
import com.overlord.inventory.api.InventoryReservationRequest;
import com.overlord.inventory.api.InventoryReservationResult;
import com.overlord.inventory.api.InventoryReserveResult;
import com.overlord.inventory.api.InventoryService;
import com.overlord.inventory.api.InventoryView;
import java.util.Objects;
import java.util.Optional;

public final class StubInventoryService
        implements InventoryService {
    private Optional<InventoryView> snapshot;
    private InventoryChangeResult replacementResult;
    private InventoryReserveResult reserveResult;
    private InventoryReservationResult commitResult;
    private InventoryReservationResult rollbackResult;
    private InventoryChangeRequest lastRequest;

    public StubInventoryService(
            Optional<InventoryView> snapshot,
            InventoryChangeResult replacementResult) {
        this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
        this.replacementResult =
                Objects.requireNonNull(
                        replacementResult, "replacementResult");
    }

    @Override
    public Optional<InventoryView> snapshot(EntityRef owner) {
        Objects.requireNonNull(owner, "owner");
        return snapshot;
    }

    @Override
    public InventoryChangeResult replaceSlot(
            InventoryChangeRequest request) {
        lastRequest = Objects.requireNonNull(request, "request");
        return replacementResult;
    }

    public InventoryChangeRequest lastRequest() {
        return lastRequest;
    }

    @Override
    public InventoryReserveResult reserve(InventoryReservationRequest request) {
        Objects.requireNonNull(request, "request");
        if (reserveResult == null) {
            return new InventoryReserveResult(
                    request,
                    snapshot.isPresent()
                            ? InventoryReserveResult.Status.REJECTED
                            : InventoryReserveResult.Status.UNKNOWN_OWNER,
                    Optional.empty(), Optional.of(request.requested()), snapshot);
        }
        return reserveResult;
    }

    @Override
    public InventoryReservationResult commit(InventoryReservationId reservationId) {
        Objects.requireNonNull(reservationId, "reservationId");
        return commitResult != null ? commitResult : unknownReservation(reservationId);
    }

    @Override
    public InventoryReservationResult rollback(InventoryReservationId reservationId) {
        Objects.requireNonNull(reservationId, "reservationId");
        return rollbackResult != null ? rollbackResult : unknownReservation(reservationId);
    }

    public void setReserveResult(InventoryReserveResult result) {
        reserveResult = Objects.requireNonNull(result, "result");
    }

    public void setCommitResult(InventoryReservationResult result) {
        commitResult = Objects.requireNonNull(result, "result");
    }

    public void setRollbackResult(InventoryReservationResult result) {
        rollbackResult = Objects.requireNonNull(result, "result");
    }

    private static InventoryReservationResult unknownReservation(
            InventoryReservationId reservationId) {
        return new InventoryReservationResult(
                reservationId,
                InventoryReservationResult.Status.UNKNOWN_RESERVATION,
                Optional.empty());
    }
}

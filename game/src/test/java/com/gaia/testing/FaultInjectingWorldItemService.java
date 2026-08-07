package com.gaia.testing;

import com.overlord.core.thread.MainThreadGuard;
import com.overlord.core.transaction.ReservationTerminalState;
import com.overlord.worlditem.LogicalWorldItemService;
import com.overlord.worlditem.api.WorldItemId;
import com.overlord.worlditem.api.WorldItemReservationId;
import com.overlord.worlditem.api.WorldItemReservationResult;
import com.overlord.worlditem.api.WorldItemService;
import com.overlord.worlditem.api.WorldItemSnapshot;
import com.overlord.worlditem.api.WorldItemSpawnCommitException;
import com.overlord.worlditem.api.WorldItemSpawnCommitResult;
import com.overlord.worlditem.api.WorldItemSpawnRequest;
import com.overlord.worlditem.api.WorldItemSpawnReservation;
import com.overlord.worlditem.api.WorldItemSpawnReservationAudit;
import com.overlord.worlditem.api.WorldItemSpawnReservationAuditSnapshot;
import com.overlord.worlditem.api.WorldItemSpawnReservationId;
import com.overlord.worlditem.api.WorldItemSpawnReservations;
import com.overlord.worlditem.api.WorldItemSpawnReserveResult;
import com.overlord.worlditem.api.WorldItemSpawnResult;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Exact fault-injection boundary for spawn commit-barrier tests. */
public final class FaultInjectingWorldItemService
        implements WorldItemService, WorldItemSpawnReservations, WorldItemSpawnReservationAudit {
    public enum CommitFailureKind {
        NONE,
        TYPED_BEFORE_APPLY,
        TYPED_AFTER_APPLY,
        UNTYPED_BEFORE_APPLY,
        UNTYPED_AFTER_APPLY
    }

    private final LogicalWorldItemService delegate = new LogicalWorldItemService(
            MainThreadGuard.captureCurrentThread(), 32, 20);
    private Throwable reserveFailure;
    private Optional<WorldItemSpawnCommitResult> commitOverride = Optional.empty();
    private Throwable commitFailure;
    private CommitFailureKind commitFailureKind = CommitFailureKind.NONE;
    private Throwable auditFailure;
    private Throwable rollbackFailure;
    private Optional<WorldItemSpawnCommitResult.Status> rollbackOverride = Optional.empty();
    private Optional<ReservationTerminalState> auditOverride = Optional.empty();
    private WorldItemSpawnReservation lastReservation;
    private int reserveCalls;
    private int commitCalls;
    private int rollbackCalls;
    private boolean commitFailureDelivered;

    public void failReserveWith(Throwable failure) {
        reserveFailure = Objects.requireNonNull(failure, "failure");
    }

    public void failFirstCommit(CommitFailureKind kind, Throwable failure) {
        if (kind == CommitFailureKind.NONE) {
            throw new IllegalArgumentException("failure kind must throw");
        }
        commitFailureKind = Objects.requireNonNull(kind, "kind");
        commitFailure = Objects.requireNonNull(failure, "failure");
        commitFailureDelivered = false;
    }

    public void returnNextCommitAs(WorldItemSpawnCommitResult result) {
        commitOverride = Optional.of(Objects.requireNonNull(result, "result"));
    }

    public void failAuditWith(Throwable failure) {
        auditFailure = Objects.requireNonNull(failure, "failure");
    }

    public void clearAuditFailure() {
        auditFailure = null;
    }

    public void overrideAudit(ReservationTerminalState state) {
        auditOverride = Optional.of(Objects.requireNonNull(state, "state"));
    }

    public void clearAuditOverride() {
        auditOverride = Optional.empty();
    }

    public void failNextRollbackWith(Throwable failure) {
        rollbackFailure = Objects.requireNonNull(failure, "failure");
    }

    public void returnNextRollbackAs(WorldItemSpawnCommitResult.Status status) {
        rollbackOverride = Optional.of(Objects.requireNonNull(status, "status"));
    }

    public int reserveCalls() {
        return reserveCalls;
    }

    public int commitCalls() {
        return commitCalls;
    }

    public int rollbackCalls() {
        return rollbackCalls;
    }

    public WorldItemSpawnReservation lastReservation() {
        return Objects.requireNonNull(lastReservation, "no reservation created");
    }

    public List<WorldItemSnapshot> snapshots() {
        return delegate.snapshots();
    }

    @Override
    public WorldItemSpawnReserveResult reserveSpawn(WorldItemSpawnRequest request) {
        reserveCalls++;
        if (reserveFailure != null) {
            Throwable failure = reserveFailure;
            reserveFailure = null;
            throwUnchecked(failure);
        }
        WorldItemSpawnReserveResult result = delegate.reserveSpawn(request);
        result.reservation().ifPresent(reservation -> lastReservation = reservation);
        return result;
    }

    @Override
    public WorldItemSpawnCommitResult commitSpawn(WorldItemSpawnReservationId reservationId) {
        commitCalls++;
        if (commitOverride.isPresent()) {
            WorldItemSpawnCommitResult result = commitOverride.orElseThrow();
            commitOverride = Optional.empty();
            return result;
        }
        if (commitFailureKind == CommitFailureKind.NONE || commitFailureDelivered) {
            return delegate.commitSpawn(reservationId);
        }
        commitFailureDelivered = true;
        boolean apply = commitFailureKind == CommitFailureKind.TYPED_AFTER_APPLY
                || commitFailureKind == CommitFailureKind.UNTYPED_AFTER_APPLY;
        if (apply) {
            delegate.commitSpawn(reservationId);
        }
        Throwable failure = commitFailure;
        if (commitFailureKind == CommitFailureKind.TYPED_BEFORE_APPLY
                || commitFailureKind == CommitFailureKind.TYPED_AFTER_APPLY) {
            throw new WorldItemSpawnCommitException(
                    "injected spawn commit failure", failure, reservationId, apply);
        }
        throwUnchecked(failure);
        throw new AssertionError("unreachable");
    }

    @Override
    public WorldItemSpawnCommitResult rollbackSpawn(WorldItemSpawnReservationId reservationId) {
        rollbackCalls++;
        if (rollbackFailure != null) {
            Throwable failure = rollbackFailure;
            rollbackFailure = null;
            throwUnchecked(failure);
        }
        if (rollbackOverride.isPresent()) {
            WorldItemSpawnCommitResult.Status status = rollbackOverride.orElseThrow();
            rollbackOverride = Optional.empty();
            return switch (status) {
                case COMMITTED, ALREADY_COMMITTED -> delegate.commitSpawn(reservationId);
                case ROLLED_BACK -> delegate.rollbackSpawn(reservationId);
                case ALREADY_ROLLED_BACK -> {
                    delegate.rollbackSpawn(reservationId);
                    yield delegate.rollbackSpawn(reservationId);
                }
                case TERMINAL_CONFLICT -> {
                    delegate.commitSpawn(reservationId);
                    yield delegate.rollbackSpawn(reservationId);
                }
                case UNKNOWN_RESERVATION -> new WorldItemSpawnCommitResult(
                        WorldItemSpawnCommitResult.Status.UNKNOWN_RESERVATION,
                        Optional.empty(), Optional.empty());
            };
        }
        return delegate.rollbackSpawn(reservationId);
    }

    @Override
    public Optional<WorldItemSpawnReservationAuditSnapshot> spawnReservationAudit(
            WorldItemSpawnReservationId reservationId) {
        if (auditFailure != null) {
            throwUnchecked(auditFailure);
        }
        if (auditOverride.isPresent()) {
            ReservationTerminalState state = auditOverride.orElseThrow();
            return Optional.of(new WorldItemSpawnReservationAuditSnapshot(
                    lastReservation(),
                    state,
                    state == ReservationTerminalState.COMMITTED
                            ? delegate.physicalSnapshot(lastReservation().itemId())
                                    .map(com.overlord.worlditem.api.WorldItemPhysicalSnapshot::runtime)
                            : Optional.empty()));
        }
        return delegate.spawnReservationAudit(reservationId);
    }

    @Override
    public WorldItemSpawnResult spawn(WorldItemSpawnRequest request) {
        return delegate.spawn(request);
    }

    @Override
    public Optional<WorldItemSnapshot> snapshot(WorldItemId itemId) {
        return delegate.snapshot(itemId);
    }

    @Override
    public WorldItemReservationResult reserve(WorldItemId itemId, int count) {
        return delegate.reserve(itemId, count);
    }

    @Override
    public WorldItemReservationResult commit(WorldItemReservationId reservationId) {
        return delegate.commit(reservationId);
    }

    @Override
    public WorldItemReservationResult rollback(WorldItemReservationId reservationId) {
        return delegate.rollback(reservationId);
    }

    private static void throwUnchecked(Throwable failure) {
        if (failure instanceof Error error) {
            throw error;
        }
        if (failure instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
        throw new IllegalArgumentException("fault must be unchecked", failure);
    }
}

package com.gaia.inventory;

import com.gaia.worlditem.WorldItemSpawnCommitResolver;
import com.gaia.worlditem.WorldItemSpawnCommitResolver.Resolution;
import com.gaia.worlditem.WorldItemSpawnCommitResolver.Status;
import com.overlord.core.transaction.ReservationTerminalState;
import com.overlord.interaction.api.EntityRef;
import com.overlord.inventory.api.BodySlot;
import com.overlord.inventory.api.InventoryReservationOperation;
import com.overlord.inventory.api.InventoryReservationAudit;
import com.overlord.inventory.api.InventoryReservationAuditSnapshot;
import com.overlord.inventory.api.InventoryReservationRequest;
import com.overlord.inventory.api.InventoryReservationResult;
import com.overlord.inventory.api.InventoryReserveResult;
import com.overlord.inventory.api.InventoryService;
import com.overlord.inventory.api.InventoryView;
import com.overlord.inventory.api.ItemStack;
import com.overlord.inventory.api.ItemStackView;
import com.overlord.worlditem.api.WorldItemService;
import com.overlord.worlditem.api.WorldItemSnapshot;
import com.overlord.worlditem.api.WorldItemSpawnCommitResult;
import com.overlord.worlditem.api.WorldItemSpawnRequest;
import com.overlord.worlditem.api.WorldItemSpawnReservation;
import com.overlord.worlditem.api.WorldItemSpawnReservations;
import com.overlord.worlditem.api.WorldItemSpawnReserveResult;
import com.overlord.worlditem.api.WorldItemSpawnResult;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Coordinates the canonical inventory reservation contract with the single
 * Phase 7 world-item service. It never owns world entities itself.
 */
public final class InventoryDropController implements AutoCloseable {
    private final InventoryService inventory;
    private final WorldItemService worldItems;
    private final Optional<WorldItemSpawnReservations> spawnReservations;
    private final Optional<WorldItemSpawnCommitResolver> spawnCommitResolver;
    private final Optional<InventoryReservationAudit> inventoryAudit;
    private final Consumer<Throwable> fatalDiagnostic;
    private boolean closed;
    private UnresolvedDrop unresolved;

    public InventoryDropController(
            InventoryService inventory, WorldItemService worldItems) {
        this(inventory, worldItems, failure -> {});
    }

    public InventoryDropController(
            InventoryService inventory,
            WorldItemService worldItems,
            Consumer<Throwable> fatalDiagnostic) {
        this.inventory = Objects.requireNonNull(inventory, "inventory");
        this.worldItems = Objects.requireNonNull(worldItems, "worldItems");
        this.fatalDiagnostic = Objects.requireNonNull(fatalDiagnostic, "fatalDiagnostic");
        spawnReservations = worldItems instanceof WorldItemSpawnReservations reservations
                ? Optional.of(reservations)
                : Optional.empty();
        spawnCommitResolver = spawnReservations.isPresent()
                && worldItems instanceof com.overlord.worlditem.api.WorldItemSpawnReservationAudit
                        ? Optional.of(new WorldItemSpawnCommitResolver(worldItems))
                        : Optional.empty();
        inventoryAudit = inventory instanceof InventoryReservationAudit audit
                ? Optional.of(audit)
                : Optional.empty();
    }

    public InventoryDropResult drop(
            EntityRef owner,
            BodySlot slot,
            InventoryDropAmount amount,
            double positionX,
            double positionY,
            double positionZ,
            double velocityX,
            double velocityY,
            double velocityZ,
            long tick) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(slot, "slot");
        Objects.requireNonNull(amount, "amount");
        if (unresolved != null) {
            throw unresolved.failure;
        }
        if (closed) {
            return result(InventoryDropResult.Status.WORLD_ITEM_UNAVAILABLE);
        }
        Optional<InventoryView> view = inventory.snapshot(owner);
        if (view.isEmpty()) {
            return result(InventoryDropResult.Status.UNKNOWN_OWNER);
        }
        ItemStackView stackView = view.orElseThrow().stack(slot).orElse(null);
        if (stackView == null) {
            return result(InventoryDropResult.Status.EMPTY_SLOT);
        }
        ItemStack available = new ItemStack(stackView.itemId(), stackView.count());
        ItemStack requested = amount == InventoryDropAmount.ONE
                ? new ItemStack(available.itemId(), 1)
                : available;
        WorldItemSpawnRequest request = new WorldItemSpawnRequest(
                requested,
                positionX, positionY, positionZ,
                velocityX, velocityY, velocityZ,
                Optional.of(owner), tick);
        if (spawnReservations.isEmpty()
                || spawnCommitResolver.isEmpty()
                || inventoryAudit.isEmpty()) {
            return result(InventoryDropResult.Status.WORLD_ITEM_UNAVAILABLE);
        }

        InventoryReserveResult inventoryHold = inventory.reserve(
                new InventoryReservationRequest(
                        owner, slot, InventoryReservationOperation.EXTRACT, requested));
        if (inventoryHold.status() != InventoryReserveResult.Status.RESERVED) {
            if (inventoryHold.reservation().isPresent()) {
                com.overlord.inventory.api.InventoryReservation held =
                        inventoryHold.reservation().orElseThrow();
                Throwable primary = new IllegalStateException(
                        "partial inventory reservation was rejected");
                InventoryRollbackResolution rollback = rollbackInventory(held, primary);
                if (rollback.status != InventoryRollbackStatus.PROVEN) {
                    retainInventoryRollbackBarrier(
                            held, available.count(), primary);
                    rethrow(primary);
                }
            }
            return new InventoryDropResult(
                    inventoryHold.status() == InventoryReserveResult.Status.PARTIALLY_RESERVED
                            ? InventoryDropResult.Status.PARTIAL_RESERVATION_REJECTED
                            : InventoryDropResult.Status.INVENTORY_RESERVATION_REJECTED,
                    Optional.empty(), inventoryHold.remainder());
        }
        com.overlord.inventory.api.InventoryReservation inventoryReservation =
                inventoryHold.reservation().orElseThrow();

        WorldItemSpawnReservations spawns = spawnReservations.orElseThrow();
        WorldItemSpawnReserveResult worldHold;
        try {
            worldHold = spawns.reserveSpawn(request);
        } catch (RuntimeException | Error failure) {
            InventoryRollbackResolution rollback =
                    rollbackInventory(inventoryReservation, failure);
            if (rollback.status != InventoryRollbackStatus.PROVEN) {
                retainInventoryRollbackBarrier(
                        inventoryReservation,
                        available.count(),
                        failure);
            }
            throw failure;
        }
        if (worldHold.status() != WorldItemSpawnReserveResult.Status.RESERVED) {
            Throwable primary = new IllegalStateException(
                    "world-item spawn reservation was rejected");
            InventoryRollbackResolution rollback =
                    rollbackInventory(inventoryReservation, primary);
            if (rollback.status != InventoryRollbackStatus.PROVEN) {
                retainInventoryRollbackBarrier(
                        inventoryReservation,
                        available.count(),
                        primary);
                rethrow(primary);
            }
            return new InventoryDropResult(
                    InventoryDropResult.Status.WORLD_ITEM_REJECTED,
                    Optional.empty(), worldHold.remainder());
        }
        WorldItemSpawnReservation worldReservation = worldHold.reservation().orElseThrow();
        Resolution worldResolution = spawnCommitResolver.orElseThrow().commit(worldReservation);
        if (worldResolution.status() == Status.ROLLED_BACK) {
            Throwable failure = worldResolution.diagnostic().orElseGet(() ->
                    new IllegalStateException("spawn reservation rolled back inside commit barrier"));
            InventoryRollbackResolution rollback =
                    rollbackInventory(inventoryReservation, failure);
            if (rollback.status != InventoryRollbackStatus.PROVEN) {
                retainInventoryRollbackBarrier(
                        inventoryReservation,
                        worldReservation,
                        available.count(),
                        failure);
            }
            rethrow(failure);
        }
        if (worldResolution.status() == Status.UNRESOLVED) {
            throw registerUnresolved(
                    inventoryReservation,
                    worldReservation,
                    available.count(),
                    worldResolution.diagnostic().orElseGet(() ->
                            new IllegalStateException("spawn outcome is unresolved")),
                    worldResolution.fatalError().orElse(null));
        }
        WorldItemSnapshot spawned = worldResolution.item().orElseThrow();
        InventoryCommitResolution inventoryResolution = commitInventory(inventoryReservation);
        Throwable diagnostic = combine(
                worldResolution.diagnostic().orElse(null),
                inventoryResolution.diagnostic.orElse(null));
        Error fatalError = worldResolution.fatalError().orElse(
                inventoryResolution.fatalError.orElse(null));
        if (inventoryResolution.status != InventoryCommitStatus.APPLIED) {
            Throwable failure = diagnostic == null
                    ? new IllegalStateException("inventory extraction outcome is unresolved")
                    : diagnostic;
            throw registerUnresolved(
                    inventoryReservation, worldReservation, available.count(), failure,
                    inventoryResolution.fatalError.orElse(null));
        }
        verifyConservation(owner, slot, available, requested, spawned);
        if (fatalError != null) {
            if (diagnostic != null && diagnostic != fatalError) {
                suppress(fatalError, diagnostic);
            }
            reportFatalPreserving(diagnostic == null ? fatalError : diagnostic, fatalError);
            throw fatalError;
        }
        return diagnostic == null
                ? new InventoryDropResult(
                        InventoryDropResult.Status.DROPPED,
                        Optional.of(spawned), Optional.empty())
                : new InventoryDropResult(
                        InventoryDropResult.Status.DROPPED_WITH_NOTIFICATION_FAILURE,
                        Optional.of(spawned), Optional.empty(), Optional.of(diagnostic));
    }

    public boolean hasUnresolvedTransaction() {
        return unresolved != null;
    }

    @Override
    public void close() {
        closed = true;
        if (unresolved == null) {
            return;
        }
        UnresolvedDrop pending = unresolved;
        if (pending.worldReservation.isEmpty()) {
            InventoryRollbackResolution rollback = rollbackInventory(
                    pending.inventoryReservation, pending.failure.getCause());
            if (rollback.status != InventoryRollbackStatus.PROVEN) {
                throw pending.failure;
            }
            unresolved = null;
            return;
        }
        Resolution worldResolution = spawnCommitResolver.orElseThrow().resolve(
                pending.worldReservation.orElseThrow(), pending.failure.getCause());
        if (worldResolution.status() == Status.UNRESOLVED) {
            throw pending.failure;
        }
        if (worldResolution.status() == Status.ROLLED_BACK) {
            InventoryRollbackResolution rollback = rollbackInventory(
                    pending.inventoryReservation, pending.failure.getCause());
            if (rollback.status != InventoryRollbackStatus.PROVEN) {
                throw pending.failure;
            }
            unresolved = null;
            return;
        }
        InventoryCommitResolution inventoryResolution = commitInventory(
                pending.inventoryReservation);
        if (inventoryResolution.status != InventoryCommitStatus.APPLIED) {
            throw pending.failure;
        }
        verifyConservation(
                pending.inventoryReservation.request().owner(),
                pending.inventoryReservation.request().slot(),
                new ItemStack(
                        pending.inventoryReservation.reserved().itemId(),
                        pending.expectedInventoryCount),
                pending.inventoryReservation.reserved(),
                worldResolution.item().orElseThrow());
        unresolved = null;
        Error fatal = worldResolution.fatalError().orElse(
                inventoryResolution.fatalError.orElse(null));
        if (fatal != null) {
            throw fatal;
        }
    }

    private InventoryCommitResolution commitInventory(
            com.overlord.inventory.api.InventoryReservation reservation) {
        try {
            InventoryReservationResult result = inventory.commit(reservation.id());
            if (result.status() == InventoryReservationResult.Status.COMMITTED
                    || result.status() == InventoryReservationResult.Status.ALREADY_COMMITTED) {
                return InventoryCommitResolution.applied(null, null);
            }
            Throwable failure = new IllegalStateException(
                    "fresh inventory reservation did not commit: " + result.status());
            return auditInventory(reservation, failure, null, false);
        } catch (com.overlord.inventory.api.InventoryEventDispatchException failure) {
            Error fatal = failure.getCause() instanceof Error error ? error : null;
            if (failure.stateChangeApplied()) {
                return InventoryCommitResolution.applied(failure, fatal);
            }
            return auditInventory(reservation, failure, fatal, true);
        } catch (RuntimeException | Error failure) {
            return auditInventory(
                    reservation,
                    failure,
                    failure instanceof Error error ? error : null,
                    true);
        }
    }

    private InventoryCommitResolution auditInventory(
            com.overlord.inventory.api.InventoryReservation reservation,
            Throwable primary,
            Error fatal,
            boolean mayCommitPending) {
        Optional<InventoryReservationAuditSnapshot> audited;
        try {
            audited = inventoryAudit.orElseThrow().reservationAudit(reservation.id());
        } catch (RuntimeException | Error auditFailure) {
            suppress(primary, auditFailure);
            return InventoryCommitResolution.unresolved(
                    primary, fatal != null ? fatal
                            : auditFailure instanceof Error error ? error : null);
        }
        if (audited.isEmpty() || !audited.orElseThrow().reservation().equals(reservation)) {
            return InventoryCommitResolution.unresolved(primary, fatal);
        }
        if (audited.orElseThrow().state() == ReservationTerminalState.COMMITTED) {
            return InventoryCommitResolution.applied(primary, fatal);
        }
        if (audited.orElseThrow().state() == ReservationTerminalState.ROLLED_BACK) {
            return InventoryCommitResolution.rolledBack(primary, fatal);
        }
        if (!mayCommitPending) {
            return InventoryCommitResolution.unresolved(primary, fatal);
        }
        try {
            InventoryReservationResult retry = inventory.commit(reservation.id());
            if (retry.status() == InventoryReservationResult.Status.COMMITTED
                    || retry.status() == InventoryReservationResult.Status.ALREADY_COMMITTED) {
                return InventoryCommitResolution.applied(primary, fatal);
            }
            suppress(primary, new IllegalStateException(
                    "pending inventory reservation did not commit: " + retry.status()));
            return InventoryCommitResolution.unresolved(primary, fatal);
        } catch (com.overlord.inventory.api.InventoryEventDispatchException retryFailure) {
            suppress(primary, retryFailure);
            Error nextFatal = fatal != null ? fatal
                    : retryFailure.getCause() instanceof Error error ? error : null;
            return retryFailure.stateChangeApplied()
                    ? InventoryCommitResolution.applied(primary, nextFatal)
                    : InventoryCommitResolution.unresolved(primary, nextFatal);
        } catch (RuntimeException | Error retryFailure) {
            suppress(primary, retryFailure);
            return InventoryCommitResolution.unresolved(
                    primary, fatal != null ? fatal
                            : retryFailure instanceof Error error ? error : null);
        }
    }

    private WorldItemSpawnIndeterminateException registerUnresolved(
            com.overlord.inventory.api.InventoryReservation inventoryReservation,
            WorldItemSpawnReservation worldReservation,
            int expectedInventoryCount,
            Throwable primary,
            Error fatalError) {
        WorldItemSpawnIndeterminateException failure =
                new WorldItemSpawnIndeterminateException(
                        "canonical Q-drop barrier is unresolved; new drops are blocked",
                        primary,
                        Optional.of(inventoryReservation),
                        worldReservation,
                        expectedInventoryCount);
        unresolved = new UnresolvedDrop(
                inventoryReservation, Optional.of(worldReservation),
                expectedInventoryCount, failure);
        if (fatalError != null) {
            suppress(fatalError, failure);
            reportFatalPreserving(failure, fatalError);
            throw fatalError;
        }
        fatalDiagnostic.accept(failure);
        return failure;
    }

    private void retainInventoryRollbackBarrier(
            com.overlord.inventory.api.InventoryReservation inventoryReservation,
            int expectedInventoryCount,
            Throwable primary) {
        retainInventoryRollbackBarrier(
                inventoryReservation,
                Optional.empty(),
                expectedInventoryCount,
                primary);
    }

    private void retainInventoryRollbackBarrier(
            com.overlord.inventory.api.InventoryReservation inventoryReservation,
            WorldItemSpawnReservation worldReservation,
            int expectedInventoryCount,
            Throwable primary) {
        retainInventoryRollbackBarrier(
                inventoryReservation,
                Optional.of(worldReservation),
                expectedInventoryCount,
                primary);
    }

    private void retainInventoryRollbackBarrier(
            com.overlord.inventory.api.InventoryReservation inventoryReservation,
            Optional<WorldItemSpawnReservation> worldReservation,
            int expectedInventoryCount,
            Throwable primary) {
        WorldItemSpawnIndeterminateException failure =
                new WorldItemSpawnIndeterminateException(
                        "canonical Q-drop inventory rollback is unresolved; new drops are blocked",
                        primary,
                        Optional.of(inventoryReservation),
                        worldReservation,
                        expectedInventoryCount);
        unresolved = new UnresolvedDrop(
                inventoryReservation, worldReservation, expectedInventoryCount, failure);
        try {
            fatalDiagnostic.accept(failure);
        } catch (RuntimeException | Error reportingFailure) {
            suppress(primary, reportingFailure);
        }
    }

    private void verifyConservation(
            EntityRef owner,
            BodySlot slot,
            ItemStack original,
            ItemStack requested,
            WorldItemSnapshot spawned) {
        int remaining = inventory.snapshot(owner)
                .flatMap(view -> view.stack(slot))
                .map(ItemStackView::count)
                .orElse(0);
        if (!spawned.stack().equals(requested)
                || remaining + spawned.stack().count() != original.count()) {
            throw new IllegalStateException("canonical Q-drop conservation guarantee broken");
        }
    }

    private InventoryRollbackResolution rollbackInventory(
            com.overlord.inventory.api.InventoryReservation reservation,
            Throwable primary) {
        try {
            InventoryReservationResult rolledBack = inventory.rollback(reservation.id());
            if (!rolledBack.reservationId().equals(reservation.id())) {
                Throwable mismatch = new IllegalStateException(
                        "inventory rollback returned a different reservation identity");
                suppress(primary, mismatch);
                return InventoryRollbackResolution.unresolved(mismatch, null);
            }
            if (rolledBack.status() == InventoryReservationResult.Status.ROLLED_BACK
                    || rolledBack.status()
                            == InventoryReservationResult.Status.ALREADY_ROLLED_BACK) {
                return InventoryRollbackResolution.proven();
            }
            Throwable failure = new IllegalStateException(
                    "inventory reservation did not roll back: " + rolledBack.status());
            suppress(primary, failure);
            return InventoryRollbackResolution.unresolved(failure, null);
        } catch (RuntimeException | Error rollbackFailure) {
            suppress(primary, rollbackFailure);
            return InventoryRollbackResolution.unresolved(
                    rollbackFailure,
                    rollbackFailure instanceof Error error ? error : null);
        }
    }

    private void reportFatalPreserving(Throwable diagnostic, Error fatal) {
        try {
            fatalDiagnostic.accept(diagnostic);
        } catch (RuntimeException | Error reportingFailure) {
            suppress(fatal, reportingFailure);
        }
    }

    private static Throwable combine(Throwable primary, Throwable additional) {
        if (primary == null) {
            return additional;
        }
        if (additional != null) {
            suppress(primary, additional);
        }
        return primary;
    }

    private static void suppress(Throwable primary, Throwable additional) {
        if (primary != additional) {
            primary.addSuppressed(additional);
        }
    }

    private static void rethrow(Throwable failure) {
        if (failure instanceof Error error) {
            throw error;
        }
        if (failure instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
        throw new IllegalStateException(failure);
    }

    private enum InventoryCommitStatus {
        APPLIED,
        ROLLED_BACK,
        UNRESOLVED
    }

    private record InventoryCommitResolution(
            InventoryCommitStatus status,
            Optional<Throwable> diagnostic,
            Optional<Error> fatalError) {
        private static InventoryCommitResolution applied(Throwable diagnostic, Error fatal) {
            return new InventoryCommitResolution(
                    InventoryCommitStatus.APPLIED,
                    Optional.ofNullable(diagnostic), Optional.ofNullable(fatal));
        }

        private static InventoryCommitResolution rolledBack(Throwable diagnostic, Error fatal) {
            return new InventoryCommitResolution(
                    InventoryCommitStatus.ROLLED_BACK,
                    Optional.ofNullable(diagnostic), Optional.ofNullable(fatal));
        }

        private static InventoryCommitResolution unresolved(Throwable diagnostic, Error fatal) {
            return new InventoryCommitResolution(
                    InventoryCommitStatus.UNRESOLVED,
                    Optional.ofNullable(diagnostic), Optional.ofNullable(fatal));
        }
    }

    private enum InventoryRollbackStatus {
        PROVEN,
        UNRESOLVED
    }

    private record InventoryRollbackResolution(
            InventoryRollbackStatus status,
            Optional<Throwable> diagnostic,
            Optional<Error> fatalError) {
        private InventoryRollbackResolution {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(diagnostic, "diagnostic");
            Objects.requireNonNull(fatalError, "fatalError");
        }

        private static InventoryRollbackResolution proven() {
            return new InventoryRollbackResolution(
                    InventoryRollbackStatus.PROVEN, Optional.empty(), Optional.empty());
        }

        private static InventoryRollbackResolution unresolved(Throwable diagnostic, Error fatal) {
            return new InventoryRollbackResolution(
                    InventoryRollbackStatus.UNRESOLVED,
                    Optional.of(diagnostic),
                    Optional.ofNullable(fatal));
        }
    }

    private record UnresolvedDrop(
            com.overlord.inventory.api.InventoryReservation inventoryReservation,
            Optional<WorldItemSpawnReservation> worldReservation,
            int expectedInventoryCount,
            WorldItemSpawnIndeterminateException failure) {}

    public InventoryDropResult drop(
            EntityRef owner,
            BodySlot slot,
            double positionX,
            double positionY,
            double positionZ,
            double velocityX,
            double velocityY,
            double velocityZ,
            long tick) {
        return drop(
                owner,
                slot,
                InventoryDropAmount.COMPLETE_STACK,
                positionX,
                positionY,
                positionZ,
                velocityX,
                velocityY,
                velocityZ,
                tick);
    }

    private static InventoryDropResult result(InventoryDropResult.Status status) {
        return new InventoryDropResult(status, Optional.empty(), Optional.empty());
    }
}

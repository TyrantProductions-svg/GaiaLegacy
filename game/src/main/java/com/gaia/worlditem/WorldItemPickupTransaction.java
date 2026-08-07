package com.gaia.worlditem;

import com.gaia.inventory.BodyInventoryReservationPlanner;
import com.gaia.inventory.InventoryReservationBatch;
import com.overlord.core.transaction.ReservationTerminalState;
import com.overlord.interaction.api.EntityRef;
import com.overlord.inventory.api.BodySlot;
import com.overlord.inventory.api.InventoryEventDispatchException;
import com.overlord.inventory.api.InventoryReservation;
import com.overlord.inventory.api.InventoryReservationAudit;
import com.overlord.inventory.api.InventoryReservationAuditSnapshot;
import com.overlord.inventory.api.InventoryReservationResult;
import com.overlord.inventory.api.InventoryService;
import com.overlord.inventory.api.ItemStack;
import com.overlord.worlditem.api.WorldItemCommitException;
import com.overlord.worlditem.api.WorldItemId;
import com.overlord.worlditem.api.WorldItemPhysicalSnapshot;
import com.overlord.worlditem.api.WorldItemPhysicalState;
import com.overlord.worlditem.api.WorldItemReservation;
import com.overlord.worlditem.api.WorldItemReservationAudit;
import com.overlord.worlditem.api.WorldItemReservationAuditSnapshot;
import com.overlord.worlditem.api.WorldItemReservationResult;
import com.overlord.worlditem.api.WorldItemRuntimeAccess;
import com.overlord.worlditem.api.WorldItemService;
import com.overlord.worlditem.api.WorldItemSnapshot;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/** Synchronous reserve/commit conservation barrier for manual world-item pickup. */
public final class WorldItemPickupTransaction {
    private final InventoryService inventory;
    private final InventoryReservationAudit inventoryAudit;
    private final WorldItemService worldItems;
    private final WorldItemRuntimeAccess runtime;
    private final WorldItemReservationAudit worldAudit;
    private final EntityRef owner;
    private final Consumer<Throwable> fatalDiagnostic;
    private final BodyInventoryReservationPlanner planner;

    public WorldItemPickupTransaction(
            InventoryService inventory,
            WorldItemService worldItems,
            EntityRef owner,
            Consumer<Throwable> fatalDiagnostic) {
        this.inventory = Objects.requireNonNull(inventory, "inventory");
        this.worldItems = Objects.requireNonNull(worldItems, "worldItems");
        this.owner = Objects.requireNonNull(owner, "owner");
        this.fatalDiagnostic = Objects.requireNonNull(fatalDiagnostic, "fatalDiagnostic");
        if (!(inventory instanceof InventoryReservationAudit audit)) {
            throw new IllegalArgumentException(
                    "inventory service must expose read-only reservation audit");
        }
        inventoryAudit = audit;
        if (!(worldItems instanceof WorldItemRuntimeAccess runtimeAccess)
                || !(worldItems instanceof WorldItemReservationAudit reservationAudit)) {
            throw new IllegalArgumentException(
                    "world-item service must expose runtime and reservation audit access");
        }
        runtime = runtimeAccess;
        worldAudit = reservationAudit;
        planner = new BodyInventoryReservationPlanner(inventory);
    }

    public WorldItemPickupResult execute(
            WorldItemId itemId,
            BodySlot preferredSlot,
            long tick) {
        Objects.requireNonNull(itemId, "itemId");
        Objects.requireNonNull(preferredSlot, "preferredSlot");
        if (tick < 0) {
            throw new IllegalArgumentException("tick must be non-negative");
        }
        Optional<WorldItemPhysicalSnapshot> current = runtime.physicalSnapshot(itemId);
        if (current.isEmpty()) {
            return nonApplied(WorldItemPickupResult.Status.UNKNOWN_ITEM, itemId, 0,
                    Optional.empty());
        }
        WorldItemPhysicalSnapshot physical = current.orElseThrow();
        WorldItemSnapshot original = physical.runtime().item();
        int originalCount = original.stack().count();
        if (tick < physical.runtime().pickupAvailableTick()) {
            return nonApplied(WorldItemPickupResult.Status.PICKUP_DELAYED, itemId,
                    originalCount, Optional.empty());
        }
        if (physical.extractionReserved()) {
            return nonApplied(WorldItemPickupResult.Status.WORLD_ITEM_BUSY, itemId,
                    originalCount, Optional.empty());
        }
        if (physical.state() == WorldItemPhysicalState.FROZEN_UNLOADED) {
            return nonApplied(WorldItemPickupResult.Status.WORLD_REJECTED, itemId,
                    originalCount, Optional.empty());
        }

        InventoryReservationBatch batch;
        try {
            batch = planner.reserveInsertion(owner, preferredSlot, original.stack());
        } catch (RuntimeException failure) {
            return nonApplied(WorldItemPickupResult.Status.INVENTORY_REJECTED, itemId,
                    originalCount, Optional.of(failure));
        }
        if (batch.acceptedCount() == 0) {
            Optional<Throwable> rollbackFailure = planner.rollbackReverse(batch);
            return nonApplied(
                    rollbackFailure.isPresent()
                            ? WorldItemPickupResult.Status.INVENTORY_REJECTED
                            : WorldItemPickupResult.Status.INVENTORY_FULL,
                    itemId, originalCount, rollbackFailure);
        }
        if (original.revision() == Long.MAX_VALUE
                && batch.acceptedCount() < originalCount) {
            Optional<Throwable> rollbackFailure = planner.rollbackReverse(batch);
            return nonApplied(WorldItemPickupResult.Status.WORLD_REJECTED,
                    itemId, originalCount, rollbackFailure);
        }

        WorldItemReservation reservation;
        try {
            WorldItemReservationResult reserved = worldItems.reserve(
                    itemId, batch.acceptedCount());
            if ((reserved.status() != WorldItemReservationResult.Status.RESERVED
                            && reserved.status()
                                    != WorldItemReservationResult.Status.PARTIALLY_RESERVED)
                    || reserved.reservation().isEmpty()
                    || reserved.reservation().orElseThrow().reserved().count()
                            != batch.acceptedCount()) {
                Optional<Throwable> rollbackFailure = planner.rollbackReverse(batch);
                WorldItemPickupResult.Status status = switch (reserved.status()) {
                    case UNAVAILABLE -> WorldItemPickupResult.Status.WORLD_ITEM_BUSY;
                    case UNKNOWN_ITEM -> WorldItemPickupResult.Status.UNKNOWN_ITEM;
                    default -> WorldItemPickupResult.Status.WORLD_REJECTED;
                };
                if (status == WorldItemPickupResult.Status.UNKNOWN_ITEM) {
                    return new WorldItemPickupResult(status, itemId, 0, 0, 0,
                            Optional.empty(), rollbackFailure);
                }
                return nonApplied(status, itemId, originalCount, rollbackFailure);
            }
            reservation = reserved.reservation().orElseThrow();
        } catch (RuntimeException failure) {
            planner.rollbackReverse(batch).ifPresent(rollbackFailure ->
                    addSuppressed(failure, rollbackFailure));
            return nonApplied(WorldItemPickupResult.Status.WORLD_REJECTED, itemId,
                    originalCount, Optional.of(failure));
        } catch (Error failure) {
            planner.rollbackReverse(batch).ifPresent(rollbackFailure ->
                    addSuppressed(failure, rollbackFailure));
            throw failure;
        }

        WorldItemPickupReceipt receipt = new WorldItemPickupReceipt(
                itemId,
                new ItemStack(original.stack().itemId(), batch.acceptedCount()),
                original.positionX(), original.positionY(), original.positionZ(), tick);
        return commitBarrier(original, batch, reservation, receipt);
    }

    private WorldItemPickupResult commitBarrier(
            WorldItemSnapshot original,
            InventoryReservationBatch batch,
            WorldItemReservation worldReservation,
            WorldItemPickupReceipt receipt) {
        int inventoryCommitted = 0;
        Throwable diagnostic = null;
        Error fatalError = null;
        WorldItemPickupResult.Status fatalStatus = null;

        for (InventoryReservation reservation : batch.reservations()) {
            try {
                InventoryReservationResult result = inventory.commit(reservation.id());
                if (result.status() != InventoryReservationResult.Status.COMMITTED) {
                    IllegalStateException failure = new IllegalStateException(
                            "fresh inventory reservation did not commit: " + result.status());
                    diagnostic = combine(diagnostic, failure);
                    fatalStatus = WorldItemPickupResult.Status.COMMIT_GUARANTEE_BROKEN;
                } else {
                    inventoryCommitted = Math.addExact(
                            inventoryCommitted, reservation.reserved().count());
                }
            } catch (InventoryEventDispatchException failure) {
                diagnostic = combine(diagnostic, failure);
                if (failure.stateChangeApplied()) {
                    inventoryCommitted = Math.addExact(
                            inventoryCommitted, reservation.reserved().count());
                    if (failure.getCause() instanceof Error error && fatalError == null) {
                        fatalError = error;
                    }
                } else {
                    fatalStatus = WorldItemPickupResult.Status.COMMIT_GUARANTEE_BROKEN;
                }
            } catch (RuntimeException | Error failure) {
                diagnostic = combine(diagnostic, failure);
                try {
                    Optional<InventoryReservationAuditSnapshot> audit =
                            inventoryAudit.reservationAudit(reservation.id());
                    if (audit.isPresent()
                            && audit.orElseThrow().state()
                                    == ReservationTerminalState.COMMITTED) {
                        inventoryCommitted = Math.addExact(
                                inventoryCommitted, reservation.reserved().count());
                    } else if (audit.isPresent()
                            && audit.orElseThrow().state()
                                    == ReservationTerminalState.PENDING) {
                        fatalStatus = WorldItemPickupResult.Status.COMMIT_GUARANTEE_BROKEN;
                    } else {
                        fatalStatus = WorldItemPickupResult.Status.INDETERMINATE;
                    }
                } catch (RuntimeException | Error auditFailure) {
                    diagnostic = combine(diagnostic, auditFailure);
                    fatalStatus = WorldItemPickupResult.Status.INDETERMINATE;
                    if (auditFailure instanceof Error error && fatalError == null) {
                        fatalError = error;
                    }
                }
                if (failure instanceof Error error && fatalError == null) {
                    fatalError = error;
                }
            }
        }

        boolean worldApplied = false;
        try {
            WorldItemReservationResult result = worldItems.commit(worldReservation.id());
            if (result.status() == WorldItemReservationResult.Status.COMMITTED) {
                worldApplied = true;
            } else {
                diagnostic = combine(diagnostic, new IllegalStateException(
                        "fresh world-item reservation did not commit: " + result.status()));
                fatalStatus = WorldItemPickupResult.Status.COMMIT_GUARANTEE_BROKEN;
            }
        } catch (WorldItemCommitException failure) {
            diagnostic = combine(diagnostic, failure);
            if (failure.stateChangeApplied()) {
                worldApplied = true;
                if (failure.getCause() instanceof Error error && fatalError == null) {
                    fatalError = error;
                }
            } else {
                fatalStatus = WorldItemPickupResult.Status.COMMIT_GUARANTEE_BROKEN;
            }
        } catch (RuntimeException | Error failure) {
            diagnostic = combine(diagnostic, failure);
            try {
                Optional<WorldItemReservationAuditSnapshot> audit =
                        worldAudit.reservationAudit(worldReservation.id());
                if (audit.isPresent()
                        && audit.orElseThrow().state() == ReservationTerminalState.COMMITTED) {
                    worldApplied = true;
                } else if (audit.isPresent()
                        && audit.orElseThrow().state() == ReservationTerminalState.PENDING) {
                    fatalStatus = WorldItemPickupResult.Status.COMMIT_GUARANTEE_BROKEN;
                } else {
                    fatalStatus = WorldItemPickupResult.Status.INDETERMINATE;
                }
            } catch (RuntimeException | Error auditFailure) {
                diagnostic = combine(diagnostic, auditFailure);
                fatalStatus = WorldItemPickupResult.Status.INDETERMINATE;
                if (auditFailure instanceof Error error && fatalError == null) {
                    fatalError = error;
                }
            }
            if (failure instanceof Error error && fatalError == null) {
                fatalError = error;
            }
        }

        Optional<WorldItemSnapshot> remaining = worldItems.snapshot(original.id());
        int remainingCount = remaining.map(snapshot -> snapshot.stack().count()).orElse(0);
        boolean identityValid = remaining
                .map(snapshot -> snapshot.stack().itemId().equals(original.stack().itemId()))
                .orElse(true);
        boolean conserved;
        try {
            conserved = identityValid
                    && worldApplied
                    && Math.addExact(inventoryCommitted, remainingCount)
                            == original.stack().count();
        } catch (ArithmeticException failure) {
            diagnostic = combine(diagnostic, failure);
            conserved = false;
        }
        if (!conserved) {
            diagnostic = combine(diagnostic, new IllegalStateException(
                    "world-item pickup conservation guarantee broken"));
            fatalStatus = WorldItemPickupResult.Status.COMMIT_GUARANTEE_BROKEN;
        }

        if (fatalStatus != null) {
            Throwable failure = diagnostic == null
                    ? new IllegalStateException("world-item pickup guarantee broken")
                    : diagnostic;
            if (fatalError != null) {
                addSuppressed(fatalError, failure);
                reportFatalPreserving(failure, fatalError);
                throw fatalError;
            }
            fatalDiagnostic.accept(failure);
            return new WorldItemPickupResult(
                    fatalStatus, original.id(), original.stack().count(),
                    inventoryCommitted, remainingCount, Optional.empty(), Optional.of(failure));
        }
        if (fatalError != null) {
            if (diagnostic != null) {
                addSuppressed(fatalError, diagnostic);
            }
            reportFatalPreserving(fatalError, fatalError);
            throw fatalError;
        }
        WorldItemPickupResult.Status status = diagnostic != null
                ? WorldItemPickupResult.Status.PICKED_WITH_NOTIFICATION_FAILURE
                : remainingCount == 0
                        ? WorldItemPickupResult.Status.PICKED_ALL
                        : WorldItemPickupResult.Status.PICKED_PARTIAL;
        return new WorldItemPickupResult(
                status, original.id(), original.stack().count(), inventoryCommitted,
                remainingCount, Optional.of(receipt), Optional.ofNullable(diagnostic));
    }

    private static WorldItemPickupResult nonApplied(
            WorldItemPickupResult.Status status,
            WorldItemId itemId,
            int originalCount,
            Optional<Throwable> failure) {
        return new WorldItemPickupResult(
                status, itemId, originalCount, 0, originalCount,
                Optional.empty(), failure);
    }

    private static Throwable combine(Throwable current, Throwable additional) {
        if (current == null) {
            return additional;
        }
        addSuppressed(current, additional);
        return current;
    }

    private static void addSuppressed(Throwable primary, Throwable additional) {
        if (primary != additional) {
            primary.addSuppressed(additional);
        }
    }

    private void reportFatalPreserving(Throwable diagnostic, Error original) {
        try {
            fatalDiagnostic.accept(diagnostic);
        } catch (RuntimeException | Error reportingFailure) {
            addSuppressed(original, reportingFailure);
        }
    }
}

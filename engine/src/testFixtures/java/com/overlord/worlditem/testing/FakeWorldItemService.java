package com.overlord.worlditem.testing;

import com.overlord.inventory.api.ItemStack;
import com.overlord.worlditem.api.WorldItemId;
import com.overlord.worlditem.api.WorldItemReservation;
import com.overlord.worlditem.api.WorldItemReservationId;
import com.overlord.worlditem.api.WorldItemReservationResult;
import com.overlord.worlditem.api.WorldItemService;
import com.overlord.worlditem.api.WorldItemSnapshot;
import com.overlord.worlditem.api.WorldItemSpawnRequest;
import com.overlord.worlditem.api.WorldItemSpawnResult;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Stateful world-item fixture without production world or physics semantics. */
public final class FakeWorldItemService implements WorldItemService {
    private final Map<WorldItemId, WorldItemSnapshot> items = new LinkedHashMap<>();
    private final Map<WorldItemReservationId, ReservationState> reservations = new HashMap<>();
    private final Map<WorldItemId, WorldItemReservationId> activeReservations = new HashMap<>();
    private long nextItemId;
    private long nextReservationId;
    private boolean itemIdsExhausted;
    private boolean reservationIdsExhausted;
    private boolean spawnRejectionEnabled;
    private int commitSideEffectCount;
    private int rollbackSideEffectCount;

    @Override
    public WorldItemSpawnResult spawn(WorldItemSpawnRequest request) {
        Objects.requireNonNull(request, "request");
        if (spawnRejectionEnabled) {
            return new WorldItemSpawnResult(
                    request, WorldItemSpawnResult.Status.REJECTED,
                    Optional.empty(), Optional.of(request.stack()));
        }
        WorldItemSnapshot item = new WorldItemSnapshot(
                nextItemId(), request.stack(), request.positionX(), request.positionY(),
                request.positionZ(), request.velocityX(), request.velocityY(),
                request.velocityZ(), 0);
        items.put(item.id(), item);
        return new WorldItemSpawnResult(
                request, WorldItemSpawnResult.Status.SPAWNED,
                Optional.of(item), Optional.empty());
    }

    @Override
    public Optional<WorldItemSnapshot> snapshot(WorldItemId itemId) {
        return Optional.ofNullable(items.get(Objects.requireNonNull(itemId, "itemId")));
    }

    @Override
    public WorldItemReservationResult reserve(WorldItemId itemId, int count) {
        Objects.requireNonNull(itemId, "itemId");
        WorldItemSnapshot item = items.get(itemId);
        if (item == null) {
            return result(WorldItemReservationResult.Status.UNKNOWN_ITEM,
                    Optional.empty(), Optional.empty(), Optional.empty());
        }
        if (count <= 0 || count > item.stack().count()) {
            return result(WorldItemReservationResult.Status.INVALID_COUNT,
                    Optional.empty(), Optional.of(item), Optional.empty());
        }
        if (activeReservations.containsKey(itemId)) {
            return result(WorldItemReservationResult.Status.UNAVAILABLE,
                    Optional.empty(), Optional.of(item), Optional.empty());
        }
        ItemStack reserved = new ItemStack(item.stack().itemId(), count);
        WorldItemReservation reservation = new WorldItemReservation(
                nextReservationId(), itemId, reserved);
        reservations.put(reservation.id(), new ReservationState(reservation));
        activeReservations.put(itemId, reservation.id());
        if (count == item.stack().count()) {
            return result(WorldItemReservationResult.Status.RESERVED,
                    Optional.of(reservation), Optional.of(item), Optional.empty());
        }
        return result(WorldItemReservationResult.Status.PARTIALLY_RESERVED,
                Optional.of(reservation), Optional.of(item),
                Optional.of(new ItemStack(item.stack().itemId(), item.stack().count() - count)));
    }

    @Override
    public WorldItemReservationResult commit(WorldItemReservationId reservationId) {
        return complete(reservationId, TerminalState.COMMITTED);
    }

    @Override
    public WorldItemReservationResult rollback(WorldItemReservationId reservationId) {
        return complete(reservationId, TerminalState.ROLLED_BACK);
    }

    public void setSpawnRejectionEnabled(boolean enabled) {
        spawnRejectionEnabled = enabled;
    }

    public int commitSideEffectCount() {
        return commitSideEffectCount;
    }

    public int rollbackSideEffectCount() {
        return rollbackSideEffectCount;
    }

    private WorldItemReservationResult complete(
            WorldItemReservationId reservationId, TerminalState target) {
        Objects.requireNonNull(reservationId, "reservationId");
        ReservationState state = reservations.get(reservationId);
        if (state == null) {
            return result(WorldItemReservationResult.Status.UNKNOWN_RESERVATION,
                    Optional.empty(), Optional.empty(), Optional.empty());
        }
        if (state.terminalState == target) {
            return terminalResult(state, target == TerminalState.COMMITTED
                    ? WorldItemReservationResult.Status.ALREADY_COMMITTED
                    : WorldItemReservationResult.Status.ALREADY_ROLLED_BACK);
        }
        if (state.terminalState != null) {
            return terminalResult(state, WorldItemReservationResult.Status.TERMINAL_CONFLICT);
        }
        activeReservations.remove(state.reservation.itemId());
        state.terminalState = target;
        if (target == TerminalState.COMMITTED) {
            commitSideEffectCount++;
            applyCommit(state.reservation);
            return terminalResult(state, WorldItemReservationResult.Status.COMMITTED);
        }
        rollbackSideEffectCount++;
        return terminalResult(state, WorldItemReservationResult.Status.ROLLED_BACK);
    }

    private void applyCommit(WorldItemReservation reservation) {
        WorldItemSnapshot current = items.get(reservation.itemId());
        int remainingCount = current.stack().count() - reservation.reserved().count();
        if (remainingCount == 0) {
            items.remove(reservation.itemId());
            return;
        }
        items.put(reservation.itemId(), new WorldItemSnapshot(
                current.id(), new ItemStack(current.stack().itemId(), remainingCount),
                current.positionX(), current.positionY(), current.positionZ(),
                current.velocityX(), current.velocityY(), current.velocityZ(),
                current.revision() + 1));
    }

    private WorldItemReservationResult terminalResult(
            ReservationState state, WorldItemReservationResult.Status status) {
        return result(status, Optional.of(state.reservation),
                Optional.ofNullable(items.get(state.reservation.itemId())), Optional.empty());
    }

    private WorldItemId nextItemId() {
        if (itemIdsExhausted) {
            throw new IllegalStateException("world item ID sequence exhausted");
        }
        WorldItemId id = new WorldItemId(nextItemId);
        if (nextItemId == Long.MAX_VALUE) {
            itemIdsExhausted = true;
        } else {
            nextItemId++;
        }
        return id;
    }

    private WorldItemReservationId nextReservationId() {
        if (reservationIdsExhausted) {
            throw new IllegalStateException("world item reservation ID sequence exhausted");
        }
        WorldItemReservationId id = new WorldItemReservationId(nextReservationId);
        if (nextReservationId == Long.MAX_VALUE) {
            reservationIdsExhausted = true;
        } else {
            nextReservationId++;
        }
        return id;
    }

    private static WorldItemReservationResult result(
            WorldItemReservationResult.Status status,
            Optional<WorldItemReservation> reservation,
            Optional<WorldItemSnapshot> item,
            Optional<ItemStack> remainder) {
        return new WorldItemReservationResult(status, reservation, item, remainder);
    }

    private static final class ReservationState {
        private final WorldItemReservation reservation;
        private TerminalState terminalState;

        private ReservationState(WorldItemReservation reservation) {
            this.reservation = reservation;
        }
    }

    private enum TerminalState {
        COMMITTED,
        ROLLED_BACK
    }
}

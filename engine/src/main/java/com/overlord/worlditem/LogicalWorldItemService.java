package com.overlord.worlditem;

import com.overlord.core.thread.MainThreadGuard;
import com.overlord.inventory.api.ItemStack;
import com.overlord.worlditem.api.WorldItemId;
import com.overlord.worlditem.api.WorldItemReservation;
import com.overlord.worlditem.api.WorldItemReservationId;
import com.overlord.worlditem.api.WorldItemReservationResult;
import com.overlord.worlditem.api.WorldItemRuntimeSnapshot;
import com.overlord.worlditem.api.WorldItemService;
import com.overlord.worlditem.api.WorldItemSnapshot;
import com.overlord.worlditem.api.WorldItemSpawnCommitResult;
import com.overlord.worlditem.api.WorldItemSpawnRequest;
import com.overlord.worlditem.api.WorldItemSpawnReservation;
import com.overlord.worlditem.api.WorldItemSpawnReservationId;
import com.overlord.worlditem.api.WorldItemSpawnReservations;
import com.overlord.worlditem.api.WorldItemSpawnReserveResult;
import com.overlord.worlditem.api.WorldItemSpawnResult;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Main-thread logical backend; Phase 11 may attach PhysicsBody presentation later. */
public final class LogicalWorldItemService
        implements WorldItemService, WorldItemSpawnReservations {
    private final MainThreadGuard mainThreadGuard;
    private final int capacity;
    private final long pickupDelayTicks;
    private final Map<WorldItemId, ItemState> items = new LinkedHashMap<>();
    private final Map<WorldItemReservationId, ExtractionState> extractionReservations =
            new HashMap<>();
    private final Map<WorldItemId, WorldItemReservationId> activeExtractions =
            new HashMap<>();
    private final Map<WorldItemSpawnReservationId, SpawnState> spawnReservations =
            new HashMap<>();
    private long nextItemId;
    private long nextExtractionReservationId;
    private long nextSpawnReservationId;
    private boolean itemIdsExhausted;
    private boolean extractionIdsExhausted;
    private boolean spawnIdsExhausted;

    public LogicalWorldItemService(
            MainThreadGuard mainThreadGuard, int capacity, long pickupDelayTicks) {
        this.mainThreadGuard = Objects.requireNonNull(mainThreadGuard, "mainThreadGuard");
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        if (pickupDelayTicks < 0) {
            throw new IllegalArgumentException("pickupDelayTicks must be non-negative");
        }
        this.capacity = capacity;
        this.pickupDelayTicks = pickupDelayTicks;
    }

    @Override
    public WorldItemSpawnResult spawn(WorldItemSpawnRequest request) {
        assertMainThread("world item spawn");
        Objects.requireNonNull(request, "request");
        WorldItemSpawnReserveResult held = reserveSpawn(request);
        if (held.status() == WorldItemSpawnReserveResult.Status.REJECTED) {
            return new WorldItemSpawnResult(
                    request,
                    WorldItemSpawnResult.Status.REJECTED,
                    Optional.empty(),
                    Optional.of(request.stack()));
        }
        WorldItemSpawnCommitResult committed =
                commitSpawn(held.reservation().orElseThrow().id());
        if (committed.status() != WorldItemSpawnCommitResult.Status.COMMITTED) {
            throw new IllegalStateException("fresh spawn reservation did not commit");
        }
        return new WorldItemSpawnResult(
                request,
                WorldItemSpawnResult.Status.SPAWNED,
                committed.item(),
                Optional.empty());
    }

    @Override
    public Optional<WorldItemSnapshot> snapshot(WorldItemId itemId) {
        assertMainThread("world item snapshot");
        ItemState state = items.get(Objects.requireNonNull(itemId, "itemId"));
        return state == null ? Optional.empty() : Optional.of(state.item);
    }

    public Optional<WorldItemRuntimeSnapshot> runtimeSnapshot(WorldItemId itemId) {
        assertMainThread("world item runtime snapshot");
        ItemState state = items.get(Objects.requireNonNull(itemId, "itemId"));
        return state == null ? Optional.empty() : Optional.of(state.runtimeSnapshot());
    }

    public List<WorldItemSnapshot> snapshots() {
        assertMainThread("world item snapshots");
        List<WorldItemSnapshot> snapshots = new ArrayList<>();
        for (ItemState state : items.values()) {
            snapshots.add(state.item);
        }
        return List.copyOf(snapshots);
    }

    @Override
    public WorldItemSpawnReserveResult reserveSpawn(WorldItemSpawnRequest request) {
        assertMainThread("world item spawn reservation");
        Objects.requireNonNull(request, "request");
        if (occupiedCapacity() >= capacity) {
            return new WorldItemSpawnReserveResult(
                    request,
                    WorldItemSpawnReserveResult.Status.REJECTED,
                    Optional.empty(),
                    Optional.of(request.stack()));
        }
        WorldItemSpawnReservation reservation = new WorldItemSpawnReservation(
                nextSpawnReservationId(), nextItemId(), request);
        spawnReservations.put(reservation.id(), new SpawnState(reservation));
        return new WorldItemSpawnReserveResult(
                request,
                WorldItemSpawnReserveResult.Status.RESERVED,
                Optional.of(reservation),
                Optional.empty());
    }

    @Override
    public WorldItemSpawnCommitResult commitSpawn(
            WorldItemSpawnReservationId reservationId) {
        assertMainThread("world item spawn reservation commit");
        return completeSpawn(reservationId, SpawnTerminal.COMMITTED);
    }

    @Override
    public WorldItemSpawnCommitResult rollbackSpawn(
            WorldItemSpawnReservationId reservationId) {
        assertMainThread("world item spawn reservation rollback");
        return completeSpawn(reservationId, SpawnTerminal.ROLLED_BACK);
    }

    private WorldItemSpawnCommitResult completeSpawn(
            WorldItemSpawnReservationId reservationId, SpawnTerminal requested) {
        Objects.requireNonNull(reservationId, "reservationId");
        SpawnState state = spawnReservations.get(reservationId);
        if (state == null) {
            return spawnResult(
                    WorldItemSpawnCommitResult.Status.UNKNOWN_RESERVATION,
                    Optional.empty(), Optional.empty());
        }
        if (state.terminal == requested) {
            return spawnResult(
                    requested == SpawnTerminal.COMMITTED
                            ? WorldItemSpawnCommitResult.Status.ALREADY_COMMITTED
                            : WorldItemSpawnCommitResult.Status.ALREADY_ROLLED_BACK,
                    Optional.of(state.reservation),
                    itemSnapshot(state.reservation.itemId()));
        }
        if (state.terminal != null) {
            return spawnResult(
                    WorldItemSpawnCommitResult.Status.TERMINAL_CONFLICT,
                    Optional.of(state.reservation),
                    itemSnapshot(state.reservation.itemId()));
        }
        state.terminal = requested;
        if (requested == SpawnTerminal.ROLLED_BACK) {
            return spawnResult(
                    WorldItemSpawnCommitResult.Status.ROLLED_BACK,
                    Optional.of(state.reservation), Optional.empty());
        }
        WorldItemSpawnRequest request = state.reservation.request();
        long pickupAvailableTick = saturatedPickupTick(request.tick());
        WorldItemSnapshot item = new WorldItemSnapshot(
                state.reservation.itemId(),
                request.stack(),
                request.positionX(), request.positionY(), request.positionZ(),
                request.velocityX(), request.velocityY(), request.velocityZ(),
                0);
        items.put(item.id(), new ItemState(
                item, request.source(), request.tick(), pickupAvailableTick));
        return spawnResult(
                WorldItemSpawnCommitResult.Status.COMMITTED,
                Optional.of(state.reservation), Optional.of(item));
    }

    @Override
    public WorldItemReservationResult reserve(WorldItemId itemId, int count) {
        assertMainThread("world item extraction reservation");
        Objects.requireNonNull(itemId, "itemId");
        ItemState state = items.get(itemId);
        if (state == null) {
            return extractionResult(
                    WorldItemReservationResult.Status.UNKNOWN_ITEM,
                    Optional.empty(), Optional.empty(), Optional.empty());
        }
        WorldItemSnapshot item = state.item;
        if (count <= 0 || count > item.stack().count()) {
            return extractionResult(
                    WorldItemReservationResult.Status.INVALID_COUNT,
                    Optional.empty(), Optional.of(item), Optional.empty());
        }
        if (activeExtractions.containsKey(itemId)) {
            return extractionResult(
                    WorldItemReservationResult.Status.UNAVAILABLE,
                    Optional.empty(), Optional.of(item), Optional.empty());
        }
        WorldItemReservation reservation = new WorldItemReservation(
                nextExtractionReservationId(),
                itemId,
                new ItemStack(item.stack().itemId(), count));
        extractionReservations.put(
                reservation.id(), new ExtractionState(reservation));
        activeExtractions.put(itemId, reservation.id());
        if (count == item.stack().count()) {
            return extractionResult(
                    WorldItemReservationResult.Status.RESERVED,
                    Optional.of(reservation), Optional.of(item), Optional.empty());
        }
        return extractionResult(
                WorldItemReservationResult.Status.PARTIALLY_RESERVED,
                Optional.of(reservation), Optional.of(item),
                Optional.of(new ItemStack(
                        item.stack().itemId(), item.stack().count() - count)));
    }

    @Override
    public WorldItemReservationResult commit(WorldItemReservationId reservationId) {
        assertMainThread("world item extraction reservation commit");
        return completeExtraction(reservationId, ExtractionTerminal.COMMITTED);
    }

    @Override
    public WorldItemReservationResult rollback(WorldItemReservationId reservationId) {
        assertMainThread("world item extraction reservation rollback");
        return completeExtraction(reservationId, ExtractionTerminal.ROLLED_BACK);
    }

    private WorldItemReservationResult completeExtraction(
            WorldItemReservationId reservationId, ExtractionTerminal requested) {
        Objects.requireNonNull(reservationId, "reservationId");
        ExtractionState state = extractionReservations.get(reservationId);
        if (state == null) {
            return extractionResult(
                    WorldItemReservationResult.Status.UNKNOWN_RESERVATION,
                    Optional.empty(), Optional.empty(), Optional.empty());
        }
        if (state.terminal == requested) {
            return extractionTerminal(
                    state,
                    requested == ExtractionTerminal.COMMITTED
                            ? WorldItemReservationResult.Status.ALREADY_COMMITTED
                            : WorldItemReservationResult.Status.ALREADY_ROLLED_BACK);
        }
        if (state.terminal != null) {
            return extractionTerminal(
                    state, WorldItemReservationResult.Status.TERMINAL_CONFLICT);
        }
        state.terminal = requested;
        activeExtractions.remove(state.reservation.itemId());
        if (requested == ExtractionTerminal.ROLLED_BACK) {
            return extractionTerminal(
                    state, WorldItemReservationResult.Status.ROLLED_BACK);
        }
        applyExtraction(state.reservation);
        return extractionTerminal(
                state, WorldItemReservationResult.Status.COMMITTED);
    }

    private void applyExtraction(WorldItemReservation reservation) {
        ItemState current = items.get(reservation.itemId());
        if (current == null
                || current.item.stack().count() < reservation.reserved().count()) {
            throw new IllegalStateException(
                    "world item extraction reservation guarantee broken");
        }
        int remainder = current.item.stack().count() - reservation.reserved().count();
        if (remainder == 0) {
            items.remove(reservation.itemId());
            return;
        }
        current.item = new WorldItemSnapshot(
                current.item.id(),
                new ItemStack(current.item.stack().itemId(), remainder),
                current.item.positionX(), current.item.positionY(), current.item.positionZ(),
                current.item.velocityX(), current.item.velocityY(), current.item.velocityZ(),
                current.item.revision() + 1);
    }

    private WorldItemReservationResult extractionTerminal(
            ExtractionState state, WorldItemReservationResult.Status status) {
        return extractionResult(
                status,
                Optional.of(state.reservation),
                itemSnapshot(state.reservation.itemId()),
                Optional.empty());
    }

    private Optional<WorldItemSnapshot> itemSnapshot(WorldItemId itemId) {
        ItemState state = items.get(itemId);
        return state == null ? Optional.empty() : Optional.of(state.item);
    }

    private int occupiedCapacity() {
        int pending = 0;
        for (SpawnState state : spawnReservations.values()) {
            if (state.terminal == null) {
                pending++;
            }
        }
        return Math.addExact(items.size(), pending);
    }

    private long saturatedPickupTick(long spawnTick) {
        return spawnTick > Long.MAX_VALUE - pickupDelayTicks
                ? Long.MAX_VALUE
                : spawnTick + pickupDelayTicks;
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

    private WorldItemReservationId nextExtractionReservationId() {
        if (extractionIdsExhausted) {
            throw new IllegalStateException(
                    "world item extraction reservation ID sequence exhausted");
        }
        WorldItemReservationId id =
                new WorldItemReservationId(nextExtractionReservationId);
        if (nextExtractionReservationId == Long.MAX_VALUE) {
            extractionIdsExhausted = true;
        } else {
            nextExtractionReservationId++;
        }
        return id;
    }

    private WorldItemSpawnReservationId nextSpawnReservationId() {
        if (spawnIdsExhausted) {
            throw new IllegalStateException(
                    "world item spawn reservation ID sequence exhausted");
        }
        WorldItemSpawnReservationId id =
                new WorldItemSpawnReservationId(nextSpawnReservationId);
        if (nextSpawnReservationId == Long.MAX_VALUE) {
            spawnIdsExhausted = true;
        } else {
            nextSpawnReservationId++;
        }
        return id;
    }

    private static WorldItemSpawnCommitResult spawnResult(
            WorldItemSpawnCommitResult.Status status,
            Optional<WorldItemSpawnReservation> reservation,
            Optional<WorldItemSnapshot> item) {
        return new WorldItemSpawnCommitResult(status, reservation, item);
    }

    private static WorldItemReservationResult extractionResult(
            WorldItemReservationResult.Status status,
            Optional<WorldItemReservation> reservation,
            Optional<WorldItemSnapshot> item,
            Optional<ItemStack> remainder) {
        return new WorldItemReservationResult(status, reservation, item, remainder);
    }

    private void assertMainThread(String operation) {
        mainThreadGuard.assertMainThread(operation);
    }

    private static final class ItemState {
        private WorldItemSnapshot item;
        private final Optional<com.overlord.interaction.api.EntityRef> source;
        private final long spawnTick;
        private final long pickupAvailableTick;

        private ItemState(
                WorldItemSnapshot item,
                Optional<com.overlord.interaction.api.EntityRef> source,
                long spawnTick,
                long pickupAvailableTick) {
            this.item = item;
            this.source = source;
            this.spawnTick = spawnTick;
            this.pickupAvailableTick = pickupAvailableTick;
        }

        private WorldItemRuntimeSnapshot runtimeSnapshot() {
            return new WorldItemRuntimeSnapshot(
                    item, source, spawnTick, pickupAvailableTick);
        }
    }

    private static final class SpawnState {
        private final WorldItemSpawnReservation reservation;
        private SpawnTerminal terminal;

        private SpawnState(WorldItemSpawnReservation reservation) {
            this.reservation = reservation;
        }
    }

    private static final class ExtractionState {
        private final WorldItemReservation reservation;
        private ExtractionTerminal terminal;

        private ExtractionState(WorldItemReservation reservation) {
            this.reservation = reservation;
        }
    }

    private enum SpawnTerminal {
        COMMITTED,
        ROLLED_BACK
    }

    private enum ExtractionTerminal {
        COMMITTED,
        ROLLED_BACK
    }
}

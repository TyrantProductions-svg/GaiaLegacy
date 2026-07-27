package com.gaia.interaction;

import com.overlord.assets.ResourceLocation;
import com.overlord.interaction.api.BlockChangeDispatchException;
import com.overlord.interaction.api.BlockChangeRequest;
import com.overlord.interaction.api.BlockChangeResult;
import com.overlord.interaction.api.BlockHitResult;
import com.overlord.interaction.api.EntityRef;
import com.overlord.interaction.api.InteractionAction;
import com.overlord.interaction.api.WorldMutationService;
import com.overlord.inventory.api.BodySlot;
import com.overlord.inventory.api.InventoryEventDispatchException;
import com.overlord.inventory.api.InventoryReserveResult;
import com.overlord.inventory.api.InventoryReservation;
import com.overlord.inventory.api.InventoryReservationOperation;
import com.overlord.inventory.api.InventoryReservationRequest;
import com.overlord.inventory.api.InventoryReservationResult;
import com.overlord.inventory.api.InventoryService;
import com.overlord.inventory.api.ItemStack;
import com.overlord.worlditem.api.WorldItemService;
import com.overlord.worlditem.api.WorldItemSpawnCommitResult;
import com.overlord.worlditem.api.WorldItemSpawnRequest;
import com.overlord.worlditem.api.WorldItemSpawnReservation;
import com.overlord.worlditem.api.WorldItemSpawnReservations;
import com.overlord.worlditem.api.WorldItemSpawnReserveResult;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public final class BlockBreakTransaction {
    private final WorldMutationService mutations;
    private final InventoryService inventory;
    private final EntityRef owner;
    private final WorldItemSpawnReservations worldItemSpawns;
    private final ResourceLocation air;

    public BlockBreakTransaction(
            WorldMutationService mutations,
            InventoryService inventory,
            EntityRef owner,
            WorldItemService worldItems,
            ResourceLocation air) {
        this.mutations = Objects.requireNonNull(mutations, "mutations");
        this.inventory = Objects.requireNonNull(inventory, "inventory");
        this.owner = Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(worldItems, "worldItems");
        if (!(worldItems instanceof WorldItemSpawnReservations spawnReservations)) {
            throw new IllegalArgumentException(
                    "the unique WorldItemService must also reserve future spawn capacity");
        }
        worldItemSpawns = spawnReservations;
        this.air = Objects.requireNonNull(air, "air");
    }

    public BlockBreakResult execute(
            BlockHitResult target,
            Optional<ItemStack> drop,
            BodySlot preferredSlot,
            long tick,
            long timestampNanos) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(drop, "drop");
        Objects.requireNonNull(preferredSlot, "preferredSlot");
        int produced = drop.map(ItemStack::count).orElse(0);
        List<InventoryReservation> inventoryReservations = new ArrayList<>();
        Optional<WorldItemSpawnReservation> worldReservation = Optional.empty();

        Optional<ItemStack> remaining = drop;
        if (remaining.isPresent()) {
            remaining = reserveInventory(
                    remaining.orElseThrow(), preferredSlot, inventoryReservations);
        }
        if (remaining.isPresent()) {
            WorldItemSpawnRequest spawnRequest = new WorldItemSpawnRequest(
                    remaining.orElseThrow(),
                    target.blockX() + 0.5,
                    target.blockY() + 0.5,
                    target.blockZ() + 0.5,
                    0, 0, 0,
                    Optional.of(owner),
                    tick);
            WorldItemSpawnReserveResult reserve =
                    worldItemSpawns.reserveSpawn(spawnRequest);
            if (reserve.status() != WorldItemSpawnReserveResult.Status.RESERVED) {
                rollbackInventory(inventoryReservations);
                return rejected(
                        BlockBreakResult.Status.RESERVATION_REJECTED,
                        produced, Optional.empty(), Optional.empty());
            }
            worldReservation = reserve.reservation();
        }

        BlockChangeRequest request = new BlockChangeRequest(
                new GaiaInteractionContext(
                        owner, preferredSlot, InteractionAction.PRIMARY,
                        tick, timestampNanos),
                target.blockX(), target.blockY(), target.blockZ(),
                target.block(), air);
        Optional<BlockChangeResult> mutation = Optional.empty();
        Optional<Throwable> notificationFailure = Optional.empty();
        try {
            BlockChangeResult result = mutations.changeBlock(request);
            mutation = Optional.of(result);
            if (result.status() != BlockChangeResult.Status.APPLIED) {
                rollbackAll(inventoryReservations, worldReservation);
                return rejected(
                        BlockBreakResult.Status.MUTATION_REJECTED,
                        produced, mutation, Optional.empty());
            }
        } catch (BlockChangeDispatchException failure) {
            if (!failure.mutationApplied()) {
                rollbackAll(inventoryReservations, worldReservation);
                return rejected(
                        BlockBreakResult.Status.MUTATION_REJECTED,
                        produced, Optional.empty(), Optional.of(failure));
            }
            notificationFailure = Optional.of(failure);
        }

        CommitCounts committed = commitAll(inventoryReservations, worldReservation);
        Optional<Throwable> failure = combine(
                notificationFailure, committed.notificationFailure());
        return new BlockBreakResult(
                failure.isPresent()
                        ? BlockBreakResult.Status.APPLIED_WITH_NOTIFICATION_FAILURE
                        : BlockBreakResult.Status.APPLIED,
                mutation,
                produced,
                committed.inventory(),
                committed.world(),
                failure);
    }

    private Optional<ItemStack> reserveInventory(
            ItemStack requested,
            BodySlot preferredSlot,
            List<InventoryReservation> acquired) {
        Optional<ItemStack> remaining = Optional.of(requested);
        Set<BodySlot> order = new LinkedHashSet<>();
        order.add(preferredSlot);
        order.addAll(List.of(BodySlot.values()));
        for (BodySlot slot : order) {
            if (remaining.isEmpty()) {
                break;
            }
            InventoryReserveResult result = inventory.reserve(
                    new InventoryReservationRequest(
                            owner,
                            slot,
                            InventoryReservationOperation.INSERT,
                            remaining.orElseThrow()));
            if (result.reservation().isPresent()) {
                acquired.add(result.reservation().orElseThrow());
                remaining = result.remainder();
            }
        }
        return remaining;
    }

    private CommitCounts commitAll(
            List<InventoryReservation> inventoryReservations,
            Optional<WorldItemSpawnReservation> worldReservation) {
        int inventoryCommitted = 0;
        int worldCommitted = 0;
        Optional<Throwable> notificationFailure = Optional.empty();
        for (InventoryReservation reservation : inventoryReservations) {
            try {
                InventoryReservationResult result = inventory.commit(reservation.id());
                if (result.status() != InventoryReservationResult.Status.COMMITTED) {
                    throw new IllegalStateException(
                            "fresh inventory reservation did not commit: " + result.status());
                }
                inventoryCommitted += reservation.reserved().count();
            } catch (InventoryEventDispatchException failure) {
                if (!failure.stateChangeApplied()) {
                    throw failure;
                }
                inventoryCommitted += reservation.reserved().count();
                notificationFailure = combine(
                        notificationFailure, Optional.of(failure));
            }
        }
        if (worldReservation.isPresent()) {
            WorldItemSpawnReservation reservation = worldReservation.orElseThrow();
            WorldItemSpawnCommitResult result = worldItemSpawns.commitSpawn(reservation.id());
            if (result.status() != WorldItemSpawnCommitResult.Status.COMMITTED) {
                throw new IllegalStateException(
                        "fresh world-item spawn reservation did not commit: "
                                + result.status());
            }
            worldCommitted = reservation.request().stack().count();
        }
        return new CommitCounts(
                inventoryCommitted, worldCommitted, notificationFailure);
    }

    private void rollbackAll(
            List<InventoryReservation> inventoryReservations,
            Optional<WorldItemSpawnReservation> worldReservation) {
        rollbackInventory(inventoryReservations);
        worldReservation.ifPresent(reservation ->
                worldItemSpawns.rollbackSpawn(reservation.id()));
    }

    private void rollbackInventory(List<InventoryReservation> reservations) {
        for (InventoryReservation reservation : reservations) {
            inventory.rollback(reservation.id());
        }
    }

    private static BlockBreakResult rejected(
            BlockBreakResult.Status status,
            int produced,
            Optional<BlockChangeResult> mutation,
            Optional<Throwable> failure) {
        return new BlockBreakResult(
                status, mutation, produced, 0, 0, failure);
    }

    private static Optional<Throwable> combine(
            Optional<Throwable> current, Optional<? extends Throwable> additional) {
        if (additional.isEmpty()) {
            return current;
        }
        Throwable next = additional.orElseThrow();
        if (current.isEmpty()) {
            return Optional.of(next);
        }
        Throwable primary = current.orElseThrow();
        if (primary != next) {
            primary.addSuppressed(next);
        }
        return current;
    }

    private record CommitCounts(
            int inventory,
            int world,
            Optional<Throwable> notificationFailure) {}
}

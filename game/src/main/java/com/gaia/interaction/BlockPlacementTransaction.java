package com.gaia.interaction;

import com.gaia.blocks.BlockDefinition;
import com.gaia.blocks.BlockRegistry;
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
import com.overlord.physics.Aabb;
import com.overlord.physics.PhysicsBody;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import org.joml.Vector3f;

public final class BlockPlacementTransaction {
    private final WorldMutationService mutations;
    private final InventoryService inventory;
    private final EntityRef owner;
    private final Function<ResourceLocation, Optional<BlockDefinition>> blockLookup;
    private final BlockPlacementWorldView world;
    private final PhysicsBody playerBody;
    private final ResourceLocation air;
    private final Vector3f bodyPosition = new Vector3f();

    public BlockPlacementTransaction(
            WorldMutationService mutations,
            InventoryService inventory,
            EntityRef owner,
            BlockRegistry blocks,
            BlockPlacementWorldView world,
            PhysicsBody playerBody,
            ResourceLocation air) {
        this(
                mutations, inventory, owner,
                Objects.requireNonNull(blocks, "blocks")::blockForItem,
                world, playerBody, air);
    }

    BlockPlacementTransaction(
            WorldMutationService mutations,
            InventoryService inventory,
            EntityRef owner,
            Function<ResourceLocation, Optional<BlockDefinition>> blockLookup,
            BlockPlacementWorldView world,
            PhysicsBody playerBody,
            ResourceLocation air) {
        this.mutations = Objects.requireNonNull(mutations, "mutations");
        this.inventory = Objects.requireNonNull(inventory, "inventory");
        this.owner = Objects.requireNonNull(owner, "owner");
        this.blockLookup = Objects.requireNonNull(blockLookup, "blockLookup");
        this.world = Objects.requireNonNull(world, "world");
        this.playerBody = Objects.requireNonNull(playerBody, "playerBody");
        this.air = Objects.requireNonNull(air, "air");
    }

    public BlockPlacementResult execute(
            BlockHitResult target,
            Optional<ItemStack> selected,
            GameMode mode,
            BodySlot activeSlot,
            long tick,
            long timestampNanos) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(selected, "selected");
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(activeSlot, "activeSlot");
        if (selected.isEmpty()) {
            return rejected(BlockPlacementResult.Status.NO_ITEM, Optional.empty());
        }
        ItemStack selectedItem = selected.orElseThrow();
        Optional<BlockDefinition> definition = blockLookup.apply(selectedItem.itemId());
        if (definition.isEmpty() || definition.orElseThrow().id() == 0) {
            return rejected(BlockPlacementResult.Status.UNKNOWN_ITEM, Optional.empty());
        }

        int x = target.adjacentX();
        int y = target.adjacentY();
        int z = target.adjacentZ();
        if (!world.isLoaded(x, y, z)) {
            return rejected(
                    BlockPlacementResult.Status.CHUNK_NOT_LOADED, Optional.empty());
        }
        ResourceLocation observed = Objects.requireNonNull(
                world.blockAt(x, y, z), "world.blockAt");
        if (!observed.equals(air)) {
            return rejected(
                    BlockPlacementResult.Status.NOT_REPLACEABLE, Optional.empty());
        }
        playerBody.position(bodyPosition);
        Aabb playerBounds = playerBody.collider().translated(bodyPosition);
        Aabb blockBounds = new Aabb(x, y, z, x + 1, y + 1, z + 1);
        if (playerBounds.intersects(blockBounds)) {
            return rejected(
                    BlockPlacementResult.Status.PLAYER_INTERSECTION, Optional.empty());
        }

        Optional<InventoryReservation> reservation = Optional.empty();
        if (mode == GameMode.SURVIVAL) {
            InventoryReserveResult reserve = inventory.reserve(
                    new InventoryReservationRequest(
                            owner,
                            activeSlot,
                            InventoryReservationOperation.EXTRACT,
                            new ItemStack(selectedItem.itemId(), 1)));
            if (reserve.reservation().isEmpty()
                    || reserve.reservation().orElseThrow().reserved().count() != 1
                    || reserve.remainder().isPresent()) {
                reserve.reservation().ifPresent(held -> inventory.rollback(held.id()));
                return rejected(
                        BlockPlacementResult.Status.INVENTORY_REJECTED,
                        Optional.empty());
            }
            reservation = reserve.reservation();
        }

        BlockChangeRequest request = new BlockChangeRequest(
                new GaiaInteractionContext(
                        owner, activeSlot, InteractionAction.SECONDARY,
                        tick, timestampNanos),
                x, y, z,
                air,
                definition.orElseThrow().name());
        Optional<BlockChangeResult> mutation = Optional.empty();
        Optional<Throwable> notificationFailure = Optional.empty();
        try {
            BlockChangeResult result = mutations.changeBlock(request);
            mutation = Optional.of(result);
            if (result.status() != BlockChangeResult.Status.APPLIED) {
                rollback(reservation);
                return rejected(
                        BlockPlacementResult.Status.MUTATION_REJECTED, mutation);
            }
        } catch (BlockChangeDispatchException failure) {
            if (!failure.mutationApplied()) {
                rollback(reservation);
                return new BlockPlacementResult(
                        BlockPlacementResult.Status.MUTATION_REJECTED,
                        Optional.empty(), 0, Optional.of(failure));
            }
            notificationFailure = Optional.of(failure);
        }

        int inventoryCommitted = 0;
        if (reservation.isPresent()) {
            InventoryReservation held = reservation.orElseThrow();
            try {
                InventoryReservationResult committed = inventory.commit(held.id());
                if (committed.status() != InventoryReservationResult.Status.COMMITTED) {
                    throw new IllegalStateException(
                            "fresh placement reservation did not commit: "
                                    + committed.status());
                }
                inventoryCommitted = 1;
            } catch (InventoryEventDispatchException failure) {
                if (!failure.stateChangeApplied()) {
                    throw failure;
                }
                inventoryCommitted = 1;
                notificationFailure = combine(
                        notificationFailure, Optional.of(failure));
            }
        }

        return new BlockPlacementResult(
                notificationFailure.isPresent()
                        ? BlockPlacementResult.Status.APPLIED_WITH_NOTIFICATION_FAILURE
                        : BlockPlacementResult.Status.APPLIED,
                mutation,
                inventoryCommitted,
                notificationFailure);
    }

    private void rollback(Optional<InventoryReservation> reservation) {
        reservation.ifPresent(held -> inventory.rollback(held.id()));
    }

    private static BlockPlacementResult rejected(
            BlockPlacementResult.Status status,
            Optional<BlockChangeResult> mutation) {
        return new BlockPlacementResult(
                status, mutation, 0, Optional.empty());
    }

    private static Optional<Throwable> combine(
            Optional<Throwable> current,
            Optional<? extends Throwable> additional) {
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
}

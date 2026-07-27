package com.gaia.inventory;

import com.overlord.interaction.api.EntityRef;
import com.overlord.inventory.api.BodySlot;
import com.overlord.inventory.api.InventoryReservationOperation;
import com.overlord.inventory.api.InventoryReservationRequest;
import com.overlord.inventory.api.InventoryReservationResult;
import com.overlord.inventory.api.InventoryReserveResult;
import com.overlord.inventory.api.InventoryService;
import com.overlord.inventory.api.InventoryView;
import com.overlord.inventory.api.ItemStack;
import com.overlord.inventory.api.ItemStackView;
import com.overlord.worlditem.api.WorldItemService;
import com.overlord.worlditem.api.WorldItemSpawnRequest;
import com.overlord.worlditem.api.WorldItemSpawnResult;
import java.util.Objects;
import java.util.Optional;

/**
 * Coordinates the canonical inventory reservation contract with the single
 * Phase 7 world-item service. It never owns world entities itself.
 */
public final class InventoryDropController {
    private final InventoryService inventory;
    private final WorldItemService worldItems;

    public InventoryDropController(
            InventoryService inventory, WorldItemService worldItems) {
        this.inventory = Objects.requireNonNull(inventory, "inventory");
        this.worldItems = Objects.requireNonNull(worldItems, "worldItems");
    }

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
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(slot, "slot");
        Optional<InventoryView> view = inventory.snapshot(owner);
        if (view.isEmpty()) {
            return result(InventoryDropResult.Status.UNKNOWN_OWNER);
        }
        ItemStackView stackView = view.orElseThrow().stack(slot).orElse(null);
        ItemStack stack = stackView == null
                ? null
                : new ItemStack(stackView.itemId(), stackView.count());
        if (stack == null) {
            return result(InventoryDropResult.Status.EMPTY_SLOT);
        }

        WorldItemSpawnRequest spawnRequest = new WorldItemSpawnRequest(
                stack,
                positionX, positionY, positionZ,
                velocityX, velocityY, velocityZ,
                Optional.of(owner), tick);

        InventoryReserveResult reserve = inventory.reserve(
                new InventoryReservationRequest(
                        owner, slot, InventoryReservationOperation.EXTRACT, stack));
        if (reserve.status() != InventoryReserveResult.Status.RESERVED) {
            reserve.reservation().ifPresent(reservation -> inventory.rollback(reservation.id()));
            return new InventoryDropResult(
                    reserve.status() == InventoryReserveResult.Status.PARTIALLY_RESERVED
                            ? InventoryDropResult.Status.PARTIAL_RESERVATION_REJECTED
                            : InventoryDropResult.Status.INVENTORY_RESERVATION_REJECTED,
                    Optional.empty(),
                    reserve.remainder());
        }

        com.overlord.inventory.api.InventoryReservation reservation =
                reserve.reservation().orElseThrow();
        ItemStack reserved = reservation.reserved();
        WorldItemSpawnRequest reservedSpawnRequest = new WorldItemSpawnRequest(
                reserved,
                spawnRequest.positionX(), spawnRequest.positionY(), spawnRequest.positionZ(),
                spawnRequest.velocityX(), spawnRequest.velocityY(), spawnRequest.velocityZ(),
                spawnRequest.source(), spawnRequest.tick());
        WorldItemSpawnResult spawned;
        try {
            spawned = worldItems.spawn(reservedSpawnRequest);
        } catch (RuntimeException | Error failure) {
            throw new WorldItemSpawnIndeterminateException(
                    "world-item spawn outcome is indeterminate; do not retry blindly",
                    failure,
                    reservation);
        }
        if (spawned.status() != WorldItemSpawnResult.Status.SPAWNED) {
            inventory.rollback(reservation.id());
            return new InventoryDropResult(
                    InventoryDropResult.Status.WORLD_ITEM_REJECTED,
                    Optional.empty(),
                    spawned.remainder());
        }

        InventoryReservationResult committed = inventory.commit(reservation.id());
        if (committed.status() != InventoryReservationResult.Status.COMMITTED
                && committed.status() != InventoryReservationResult.Status.ALREADY_COMMITTED) {
            return new InventoryDropResult(
                    InventoryDropResult.Status.COMMIT_GUARANTEE_BROKEN,
                    spawned.item(),
                    Optional.empty());
        }
        return new InventoryDropResult(
                InventoryDropResult.Status.DROPPED,
                spawned.item(),
                Optional.empty());
    }

    private static InventoryDropResult result(InventoryDropResult.Status status) {
        return new InventoryDropResult(status, Optional.empty(), Optional.empty());
    }
}

package com.gaia.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gaia.blocks.ItemFormDefinition;
import com.overlord.assets.ResourceLocation;
import com.overlord.core.transaction.ReservationTerminalState;
import com.overlord.core.thread.MainThreadGuard;
import com.overlord.interaction.api.EntityRef;
import com.overlord.inventory.api.BodySlot;
import com.overlord.inventory.api.InventoryReservationOperation;
import com.overlord.inventory.api.InventoryReservationRequest;
import com.overlord.inventory.api.ItemStack;
import com.overlord.inventory.api.ItemStackView;
import com.overlord.inventory.api.InventoryChangeRequest;
import com.overlord.inventory.api.InventoryChangeResult;
import com.overlord.inventory.api.InventoryEventDispatchException;
import com.overlord.inventory.api.InventoryReservationId;
import com.overlord.inventory.api.InventoryReservationAudit;
import com.overlord.inventory.api.InventoryReservationAuditSnapshot;
import com.overlord.inventory.api.InventoryReservationResult;
import com.overlord.inventory.api.InventoryReserveResult;
import com.overlord.inventory.api.InventoryService;
import com.overlord.inventory.api.InventoryView;
import com.overlord.worlditem.api.WorldItemId;
import com.overlord.worlditem.api.WorldItemSnapshot;
import com.overlord.worlditem.testing.FakeWorldItemService;
import com.overlord.worlditem.LogicalWorldItemService;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class InventoryDropControllerTest {
    private static final EntityRef OWNER = new EntityRef(5);
    private static final ResourceLocation DIRT = ResourceLocation.parse("gaia:dirt");

    @Test
    void qPressDropsExactlyOneFromMultiCountActiveSlot() {
        BodyInventoryService inventory = inventory();
        inventory.insert(OWNER, new ItemStack(DIRT, 4));
        LogicalWorldItemService worldItems = new LogicalWorldItemService(
                MainThreadGuard.captureCurrentThread(), 8, 20);
        InventoryDropController controller = new InventoryDropController(inventory, worldItems);

        InventoryDropResult result = controller.drop(
                OWNER,
                BodySlot.LEFT_HAND,
                InventoryDropAmount.ONE,
                1.0, 2.0, 3.0,
                4.5, 1.25, 0.0,
                12);

        assertEquals(InventoryDropResult.Status.DROPPED, result.status());
        assertEquals(3, inventory.totalCount(OWNER, DIRT));
        assertEquals(1, worldItems.snapshots().size());
        assertEquals(new ItemStack(DIRT, 1), worldItems.snapshots().get(0).stack());
        assertEquals(0L, worldItems.snapshots().get(0).id().value());
        assertEquals(32L, worldItems.physicalSnapshots().get(0)
                .runtime().pickupAvailableTick());
    }

    @Test
    void dropCommitsOnlyAfterTheWorldItemWasSpawned() {
        BodyInventoryService inventory = inventory();
        inventory.insert(OWNER, new ItemStack(DIRT, 4));
        LogicalWorldItemService worldItems = new LogicalWorldItemService(
                MainThreadGuard.captureCurrentThread(), 8, 20);
        InventoryDropController controller = new InventoryDropController(inventory, worldItems);

        InventoryDropResult result = controller.drop(
                OWNER, BodySlot.LEFT_HAND, 1.0, 2.0, 3.0, 0.0, 0.0, 0.0, 12);

        assertEquals(InventoryDropResult.Status.DROPPED, result.status());
        assertEquals(0, inventory.totalCount(OWNER, DIRT));
        assertEquals(new ItemStack(DIRT, 4),
                worldItems.snapshot(new WorldItemId(0)).orElseThrow().stack());
    }

    @Test
    void rejectedWorldSpawnRollsBackTheInventoryReservationWithoutCreatingAnItem() {
        BodyInventoryService inventory = inventory();
        inventory.insert(OWNER, new ItemStack(DIRT, 4));
        LogicalWorldItemService worldItems = new LogicalWorldItemService(
                MainThreadGuard.captureCurrentThread(), 1, 20);
        worldItems.spawn(new com.overlord.worlditem.api.WorldItemSpawnRequest(
                new ItemStack(DIRT, 1), 0, 0, 0, 0, 0, 0,
                Optional.empty(), 0));
        InventoryDropController controller = new InventoryDropController(inventory, worldItems);

        InventoryDropResult result = controller.drop(
                OWNER, BodySlot.LEFT_HAND, 1.0, 2.0, 3.0, 0.0, 0.0, 0.0, 12);

        assertEquals(InventoryDropResult.Status.WORLD_ITEM_REJECTED, result.status());
        assertEquals(new ItemStack(DIRT, 4), result.remainder().orElseThrow());
        assertEquals(4, inventory.totalCount(OWNER, DIRT));
        assertEquals(1, worldItems.snapshots().size());
        assertFalse(worldItems.snapshot(new WorldItemId(1)).isPresent());
    }

    @Test
    void rejectedInventoryReservationNeverCallsWorldItemSpawn() {
        BodyInventoryService inventory = inventory();
        inventory.insert(OWNER, new ItemStack(DIRT, 4));
        inventory.reserve(new InventoryReservationRequest(
                OWNER, BodySlot.LEFT_HAND, InventoryReservationOperation.EXTRACT,
                new ItemStack(DIRT, 1)));
        LogicalWorldItemService worldItems = new LogicalWorldItemService(
                MainThreadGuard.captureCurrentThread(), 8, 20);
        InventoryDropController controller = new InventoryDropController(inventory, worldItems);

        InventoryDropResult result = controller.drop(
                OWNER, BodySlot.LEFT_HAND, 1.0, 2.0, 3.0, 0.0, 0.0, 0.0, 12);

        assertEquals(InventoryDropResult.Status.INVENTORY_RESERVATION_REJECTED, result.status());
        assertEquals(4, inventory.totalCount(OWNER, DIRT));
        assertTrue(worldItems.snapshot(new WorldItemId(0)).isEmpty());
    }

    @Test
    void dropConvertsAReadOnlyStackProjectionToTheCanonicalCommandValue() {
        InventoryService projectedInventory = new ProjectedInventoryService();
        LogicalWorldItemService worldItems = new LogicalWorldItemService(
                MainThreadGuard.captureCurrentThread(), 8, 20);
        InventoryDropController controller = new InventoryDropController(projectedInventory, worldItems);

        InventoryDropResult result = controller.drop(
                OWNER, BodySlot.LEFT_HAND, 0, 0, 0, 0, 0, 0, 0);

        assertEquals(InventoryDropResult.Status.DROPPED, result.status());
        assertEquals(new ItemStack(DIRT, 2),
                worldItems.snapshot(new WorldItemId(0)).orElseThrow().stack());
    }

    @Test
    void partialInventoryReservationReturnsItsExactCanonicalRemainder() {
        ProjectedInventoryService inventory = new ProjectedInventoryService(1);
        LogicalWorldItemService worldItems = new LogicalWorldItemService(
                MainThreadGuard.captureCurrentThread(), 8, 20);
        InventoryDropController controller = new InventoryDropController(inventory, worldItems);

        InventoryDropResult result = controller.drop(
                OWNER, BodySlot.LEFT_HAND, 0, 0, 0, 0, 0, 0, 0);

        assertEquals(InventoryDropResult.Status.PARTIAL_RESERVATION_REJECTED,
                result.status());
        assertEquals(new ItemStack(DIRT, 1), result.remainder().orElseThrow());
        assertTrue(inventory.rolledBack());
        assertTrue(worldItems.snapshot(new WorldItemId(0)).isEmpty());
    }

    @Test
    void invalidSpawnRequestIsRejectedBeforeInventoryIsReserved() {
        BodyInventoryService inventory = inventory();
        inventory.insert(OWNER, new ItemStack(DIRT, 4));
        InventoryDropController controller = new InventoryDropController(
                inventory, new FakeWorldItemService());

        assertThrows(IllegalArgumentException.class, () -> controller.drop(
                OWNER, BodySlot.LEFT_HAND,
                Double.NaN, 2.0, 3.0,
                0.0, 0.0, 0.0, 12));

        assertEquals(InventoryExtractResult.Status.EXTRACTED,
                inventory.extract(OWNER, BodySlot.LEFT_HAND, 1).status());
        assertEquals(3, inventory.totalCount(OWNER, DIRT));
    }

    @Test
    void commitNotificationFailureLeavesOneAppliedDropAndCannotBeBlindlyRetried() {
        AtomicBoolean failPublication = new AtomicBoolean();
        ItemFormDefinition dirt = new ItemFormDefinition(DIRT, 64, false, false);
        BodyInventoryService inventory = new BodyInventoryService(
                OWNER,
                id -> Optional.ofNullable(Map.of(DIRT, dirt).get(id)),
                event -> {
                    if (failPublication.get()) {
                        throw new IllegalStateException("simulated publication failure");
                    }
                });
        inventory.insert(OWNER, new ItemStack(DIRT, 4));
        LogicalWorldItemService worldItems = new LogicalWorldItemService(
                MainThreadGuard.captureCurrentThread(), 8, 20);
        InventoryDropController controller = new InventoryDropController(inventory, worldItems);
        failPublication.set(true);

        InventoryDropResult result = controller.drop(
                OWNER,
                BodySlot.LEFT_HAND,
                InventoryDropAmount.COMPLETE_STACK,
                1.0, 2.0, 3.0,
                0.0, 0.0, 0.0,
                12);

        assertEquals(
                InventoryDropResult.Status.DROPPED_WITH_NOTIFICATION_FAILURE,
                result.status());
        assertTrue(result.failure().orElseThrow()
                instanceof InventoryEventDispatchException);
        assertTrue(((InventoryEventDispatchException) result.failure().orElseThrow())
                .stateChangeApplied());
        assertEquals(new ItemStack(DIRT, 4), result.worldItem().orElseThrow().stack());
        assertEquals(0, inventory.totalCount(OWNER, DIRT));
        assertEquals(new ItemStack(DIRT, 4),
                worldItems.snapshot(new WorldItemId(0)).orElseThrow().stack());
        assertEquals(InventoryDropResult.Status.EMPTY_SLOT,
                controller.drop(
                        OWNER, BodySlot.LEFT_HAND,
                        1.0, 2.0, 3.0,
                        0.0, 0.0, 0.0, 13).status());
        assertTrue(worldItems.snapshot(new WorldItemId(1)).isEmpty());
    }

    private static BodyInventoryService inventory() {
        ItemFormDefinition dirt = new ItemFormDefinition(DIRT, 64, false, false);
        return new BodyInventoryService(
                OWNER, id -> Optional.ofNullable(Map.of(DIRT, dirt).get(id)), event -> {});
    }

    private static final class ProjectedInventoryService
            implements InventoryService, InventoryReservationAudit {
        private final int reservationLimit;
        private boolean committed;
        private boolean rolledBack;
        private com.overlord.inventory.api.InventoryReservation reservation;

        private ProjectedInventoryService() {
            this(2);
        }

        private ProjectedInventoryService(int reservationLimit) {
            this.reservationLimit = reservationLimit;
        }

        @Override
        public Optional<InventoryView> snapshot(EntityRef owner) {
            return Optional.of(new InventoryView() {
                @Override
                public EntityRef owner() {
                    return OWNER;
                }

                @Override
                public long revision() {
                    return 0;
                }

                @Override
                public Optional<ItemStackView> stack(BodySlot slot) {
                    return committed || slot != BodySlot.LEFT_HAND
                            ? Optional.empty()
                            : Optional.of(new ItemStackView() {
                                @Override
                                public ResourceLocation itemId() {
                                    return DIRT;
                                }

                                @Override
                                public int count() {
                                    return 2;
                                }
                            });
                }
            });
        }

        @Override
        public InventoryChangeResult replaceSlot(InventoryChangeRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public InventoryReserveResult reserve(InventoryReservationRequest request) {
            int protectedCount = Math.min(reservationLimit, request.requested().count());
            reservation =
                    new com.overlord.inventory.api.InventoryReservation(
                            new InventoryReservationId(0), request,
                            new ItemStack(request.requested().itemId(), protectedCount));
            Optional<ItemStack> remainder = protectedCount == request.requested().count()
                    ? Optional.empty()
                    : Optional.of(new ItemStack(
                            request.requested().itemId(),
                            request.requested().count() - protectedCount));
            return new InventoryReserveResult(
                    request,
                    remainder.isEmpty()
                            ? InventoryReserveResult.Status.RESERVED
                            : InventoryReserveResult.Status.PARTIALLY_RESERVED,
                    Optional.of(reservation), remainder, snapshot(OWNER));
        }

        @Override
        public InventoryReservationResult commit(InventoryReservationId reservationId) {
            committed = true;
            return new InventoryReservationResult(
                    reservationId, InventoryReservationResult.Status.COMMITTED, snapshot(OWNER));
        }

        @Override
        public InventoryReservationResult rollback(InventoryReservationId reservationId) {
            rolledBack = true;
            return new InventoryReservationResult(
                    reservationId, InventoryReservationResult.Status.ROLLED_BACK, snapshot(OWNER));
        }

        private boolean rolledBack() {
            return rolledBack;
        }

        @Override
        public Optional<InventoryReservationAuditSnapshot> reservationAudit(
                InventoryReservationId reservationId) {
            if (reservation == null || !reservation.id().equals(reservationId)) {
                return Optional.empty();
            }
            return Optional.of(new InventoryReservationAuditSnapshot(
                    reservation,
                    committed
                            ? ReservationTerminalState.COMMITTED
                            : rolledBack
                                    ? ReservationTerminalState.ROLLED_BACK
                                    : ReservationTerminalState.PENDING));
        }
    }
}

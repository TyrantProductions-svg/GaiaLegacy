package com.gaia.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gaia.blocks.ItemFormDefinition;
import com.overlord.assets.ResourceLocation;
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
import com.overlord.inventory.api.InventoryReservationResult;
import com.overlord.inventory.api.InventoryReserveResult;
import com.overlord.inventory.api.InventoryService;
import com.overlord.inventory.api.InventoryView;
import com.overlord.worlditem.api.WorldItemId;
import com.overlord.worlditem.api.WorldItemReservationId;
import com.overlord.worlditem.api.WorldItemReservationResult;
import com.overlord.worlditem.api.WorldItemService;
import com.overlord.worlditem.api.WorldItemSnapshot;
import com.overlord.worlditem.api.WorldItemSpawnRequest;
import com.overlord.worlditem.api.WorldItemSpawnResult;
import com.overlord.worlditem.testing.FakeWorldItemService;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class InventoryDropControllerTest {
    private static final EntityRef OWNER = new EntityRef(5);
    private static final ResourceLocation DIRT = ResourceLocation.parse("gaia:dirt");

    @Test
    void dropCommitsOnlyAfterTheWorldItemWasSpawned() {
        BodyInventoryService inventory = inventory();
        inventory.insert(OWNER, new ItemStack(DIRT, 4));
        FakeWorldItemService worldItems = new FakeWorldItemService();
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
        FakeWorldItemService worldItems = new FakeWorldItemService();
        worldItems.setSpawnRejectionEnabled(true);
        InventoryDropController controller = new InventoryDropController(inventory, worldItems);

        InventoryDropResult result = controller.drop(
                OWNER, BodySlot.LEFT_HAND, 1.0, 2.0, 3.0, 0.0, 0.0, 0.0, 12);

        assertEquals(InventoryDropResult.Status.WORLD_ITEM_REJECTED, result.status());
        assertEquals(new ItemStack(DIRT, 4), result.remainder().orElseThrow());
        assertEquals(4, inventory.totalCount(OWNER, DIRT));
        assertFalse(worldItems.snapshot(new WorldItemId(0)).isPresent());
    }

    @Test
    void rejectedInventoryReservationNeverCallsWorldItemSpawn() {
        BodyInventoryService inventory = inventory();
        inventory.insert(OWNER, new ItemStack(DIRT, 4));
        inventory.reserve(new InventoryReservationRequest(
                OWNER, BodySlot.LEFT_HAND, InventoryReservationOperation.EXTRACT,
                new ItemStack(DIRT, 1)));
        FakeWorldItemService worldItems = new FakeWorldItemService();
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
        FakeWorldItemService worldItems = new FakeWorldItemService();
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
        FakeWorldItemService worldItems = new FakeWorldItemService();
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
    void throwingSpawnExposesTheLiveReservationForExplicitReconciliation() {
        BodyInventoryService inventory = inventory();
        inventory.insert(OWNER, new ItemStack(DIRT, 4));
        ThrowingWorldItemService worldItems = new ThrowingWorldItemService(true);
        InventoryDropController controller = new InventoryDropController(inventory, worldItems);

        WorldItemSpawnIndeterminateException failure = assertThrows(
                WorldItemSpawnIndeterminateException.class,
                () -> controller.drop(
                        OWNER, BodySlot.LEFT_HAND,
                        1.0, 2.0, 3.0,
                        0.0, 0.0, 0.0, 12));

        assertEquals(new ItemStack(DIRT, 4), failure.reservation().reserved());
        assertTrue(failure.spawnMayHaveApplied());
        assertEquals(4, inventory.totalCount(OWNER, DIRT));
        assertEquals(InventoryExtractResult.Status.RESERVED,
                inventory.extract(OWNER, BodySlot.LEFT_HAND, 1).status());
        assertTrue(worldItems.snapshot(new WorldItemId(0)).isPresent());
        assertEquals(InventoryReservationResult.Status.COMMITTED,
                inventory.commit(failure.reservation().id()).status());
        assertEquals(0, inventory.totalCount(OWNER, DIRT));
    }

    @Test
    void throwingSpawnBeforeSideEffectCanBeExplicitlyRolledBack() {
        BodyInventoryService inventory = inventory();
        inventory.insert(OWNER, new ItemStack(DIRT, 4));
        ThrowingWorldItemService worldItems = new ThrowingWorldItemService(false);
        InventoryDropController controller = new InventoryDropController(inventory, worldItems);

        WorldItemSpawnIndeterminateException failure = assertThrows(
                WorldItemSpawnIndeterminateException.class,
                () -> controller.drop(
                        OWNER, BodySlot.LEFT_HAND,
                        1.0, 2.0, 3.0,
                        0.0, 0.0, 0.0, 12));

        assertEquals(InventoryReservationResult.Status.ROLLED_BACK,
                inventory.rollback(failure.reservation().id()).status());
        assertEquals(4, inventory.totalCount(OWNER, DIRT));
        assertTrue(worldItems.snapshot(new WorldItemId(0)).isEmpty());
        assertEquals(InventoryExtractResult.Status.EXTRACTED,
                inventory.extract(OWNER, BodySlot.LEFT_HAND, 1).status());
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
        FakeWorldItemService worldItems = new FakeWorldItemService();
        InventoryDropController controller = new InventoryDropController(inventory, worldItems);
        failPublication.set(true);

        InventoryEventDispatchException failure = assertThrows(
                InventoryEventDispatchException.class,
                () -> controller.drop(
                        OWNER, BodySlot.LEFT_HAND,
                        1.0, 2.0, 3.0,
                        0.0, 0.0, 0.0, 12));

        assertTrue(failure.stateChangeApplied());
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

    private static final class ProjectedInventoryService implements InventoryService {
        private final int reservationLimit;
        private boolean committed;
        private boolean rolledBack;

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
            com.overlord.inventory.api.InventoryReservation reservation =
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
    }

    private static final class ThrowingWorldItemService implements WorldItemService {
        private final FakeWorldItemService delegate = new FakeWorldItemService();
        private final boolean applyBeforeThrow;

        private ThrowingWorldItemService(boolean applyBeforeThrow) {
            this.applyBeforeThrow = applyBeforeThrow;
        }

        @Override
        public WorldItemSpawnResult spawn(WorldItemSpawnRequest request) {
            if (applyBeforeThrow) {
                delegate.spawn(request);
            }
            throw new IllegalStateException("simulated indeterminate spawn");
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
    }
}

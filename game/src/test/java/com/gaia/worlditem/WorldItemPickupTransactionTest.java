package com.gaia.worlditem;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gaia.blocks.ItemFormDefinition;
import com.gaia.inventory.BodyInventoryService;
import com.overlord.assets.ResourceLocation;
import com.overlord.core.thread.MainThreadGuard;
import com.overlord.interaction.api.EntityRef;
import com.overlord.inventory.api.BodySlot;
import com.overlord.inventory.api.InventoryChangeRequest;
import com.overlord.inventory.api.InventoryChangeResult;
import com.overlord.inventory.api.InventoryReservationAudit;
import com.overlord.inventory.api.InventoryReservationAuditSnapshot;
import com.overlord.inventory.api.InventoryReservationId;
import com.overlord.inventory.api.InventoryReservationRequest;
import com.overlord.inventory.api.InventoryReservationResult;
import com.overlord.inventory.api.InventoryReserveResult;
import com.overlord.inventory.api.InventoryService;
import com.overlord.inventory.api.InventoryView;
import com.overlord.inventory.api.ItemStack;
import com.overlord.worlditem.LogicalWorldItemService;
import com.overlord.worlditem.LogicalWorldItemTestAccess;
import com.overlord.worlditem.api.WorldItemCommitException;
import com.overlord.worlditem.api.WorldItemId;
import com.overlord.worlditem.api.WorldItemMotionUpdate;
import com.overlord.worlditem.api.WorldItemMotionUpdateResult;
import com.overlord.worlditem.api.WorldItemPhysicalSnapshot;
import com.overlord.worlditem.api.WorldItemReservation;
import com.overlord.worlditem.api.WorldItemReservationAudit;
import com.overlord.worlditem.api.WorldItemReservationAuditSnapshot;
import com.overlord.worlditem.api.WorldItemReservationId;
import com.overlord.worlditem.api.WorldItemReservationResult;
import com.overlord.worlditem.api.WorldItemRuntimeAccess;
import com.overlord.worlditem.api.WorldItemService;
import com.overlord.worlditem.api.WorldItemSnapshot;
import com.overlord.worlditem.api.WorldItemSpawnResult;
import com.overlord.worlditem.api.WorldItemSpawnRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

final class WorldItemPickupTransactionTest {
    private static final EntityRef OWNER = new EntityRef(7);
    private static final ResourceLocation DIRT = ResourceLocation.parse("gaia:dirt");

    @Test
    void fullPickupCommitsInventoryAndTerminallyRemovesExactlyOneStableId() {
        Fixture fixture = fixture(0, event -> {});
        WorldItemSnapshot item = fixture.spawn(5, 10);

        WorldItemPickupResult result = fixture.transaction.execute(
                item.id(), BodySlot.RIGHT_HAND, 10);

        assertEquals(WorldItemPickupResult.Status.PICKED_ALL, result.status());
        assertEquals(5, result.originalWorldCount());
        assertEquals(5, result.inventoryCommittedCount());
        assertEquals(0, result.remainingWorldCount());
        assertTrue(fixture.world.snapshot(item.id()).isEmpty());
        assertEquals(5, fixture.inventory.totalCount(OWNER, DIRT));
        assertEquals(item.id(), result.committedReceipt().orElseThrow().itemId());
        assertEquals(new ItemStack(DIRT, 5), result.committedReceipt().orElseThrow().picked());
    }

    @Test
    void partialPickupConservesCountAndRetainsSameStableId() {
        Fixture fixture = fixture(0, event -> {});
        WorldItemSnapshot item = fixture.spawn(200, 10);

        WorldItemPickupResult result = fixture.transaction.execute(
                item.id(), BodySlot.RIGHT_HAND, 10);

        assertEquals(WorldItemPickupResult.Status.PICKED_PARTIAL, result.status());
        assertEquals(200, result.originalWorldCount());
        assertEquals(192, result.inventoryCommittedCount());
        assertEquals(8, result.remainingWorldCount());
        assertEquals(200, result.inventoryCommittedCount() + result.remainingWorldCount());
        assertEquals(item.id(), fixture.world.snapshot(item.id()).orElseThrow().id());
        assertEquals(8, fixture.world.snapshot(item.id()).orElseThrow().stack().count());
        assertEquals(new ItemStack(DIRT, 192), result.committedReceipt().orElseThrow().picked());
    }

    @Test
    void partialPickupAtMaximumRevisionRejectsBeforeCommitAndReleasesReservations() {
        Fixture fixture = fixture(0, event -> {});
        WorldItemSnapshot item = fixture.spawn(200, 10);
        LogicalWorldItemTestAccess.forceRevision(
                fixture.world, item.id(), Long.MAX_VALUE);

        WorldItemPickupResult result = fixture.transaction.execute(
                item.id(), BodySlot.RIGHT_HAND, 10);

        assertEquals(WorldItemPickupResult.Status.WORLD_REJECTED, result.status());
        assertEquals(200, result.originalWorldCount());
        assertEquals(0, result.inventoryCommittedCount());
        assertEquals(200, result.remainingWorldCount());
        assertTrue(result.committedReceipt().isEmpty());
        assertTrue(result.failure().isEmpty());
        assertEquals(0, fixture.inventory.totalCount(OWNER, DIRT));
        WorldItemSnapshot unchanged = fixture.world.snapshot(item.id()).orElseThrow();
        assertEquals(item.id(), unchanged.id());
        assertEquals(new ItemStack(DIRT, 200), unchanged.stack());
        assertEquals(Long.MAX_VALUE, unchanged.revision());
        assertFalse(fixture.world.physicalSnapshot(item.id()).orElseThrow()
                .extractionReserved());
        WorldItemReservation retry = fixture.world.reserve(item.id(), 1)
                .reservation().orElseThrow();
        assertEquals(
                WorldItemReservationResult.Status.ROLLED_BACK,
                fixture.world.rollback(retry.id()).status());
        assertTrue(fixture.fatal.isEmpty());
    }

    @Test
    void fullPickupAtMaximumRevisionUsesTerminalRemovalAndRemainsIdempotent() {
        Fixture fixture = fixture(0, event -> {});
        WorldItemSnapshot item = fixture.spawn(5, 10);
        LogicalWorldItemTestAccess.forceRevision(
                fixture.world, item.id(), Long.MAX_VALUE);

        WorldItemPickupResult first = fixture.transaction.execute(
                item.id(), BodySlot.RIGHT_HAND, 10);
        WorldItemPickupResult repeated = fixture.transaction.execute(
                item.id(), BodySlot.RIGHT_HAND, 10);

        assertEquals(WorldItemPickupResult.Status.PICKED_ALL, first.status());
        assertEquals(5, first.inventoryCommittedCount());
        assertEquals(0, first.remainingWorldCount());
        assertTrue(fixture.world.snapshot(item.id()).isEmpty());
        assertEquals(5, fixture.inventory.totalCount(OWNER, DIRT));
        assertEquals(WorldItemPickupResult.Status.UNKNOWN_ITEM, repeated.status());
        assertEquals(0, repeated.originalWorldCount());
        assertEquals(0, repeated.inventoryCommittedCount());
        assertEquals(0, repeated.remainingWorldCount());
        assertTrue(repeated.committedReceipt().isEmpty());
        assertTrue(fixture.fatal.isEmpty());
    }

    @Test
    void delayedMissingBusyAndFullInventoryPathsDoNotMutateEitherDomain() {
        Fixture delayed = fixture(5, event -> {});
        WorldItemSnapshot delayedItem = delayed.spawn(2, 10);
        assertEquals(WorldItemPickupResult.Status.PICKUP_DELAYED,
                delayed.transaction.execute(delayedItem.id(), BodySlot.LEFT_HAND, 14).status());
        assertEquals(0, delayed.inventory.totalCount(OWNER, DIRT));
        assertEquals(2, delayed.world.snapshot(delayedItem.id()).orElseThrow().stack().count());

        assertEquals(WorldItemPickupResult.Status.UNKNOWN_ITEM,
                delayed.transaction.execute(new WorldItemId(999), BodySlot.LEFT_HAND, 20).status());

        Fixture busy = fixture(0, event -> {});
        WorldItemSnapshot busyItem = busy.spawn(2, 0);
        WorldItemReservation lock = busy.world.reserve(busyItem.id(), 1)
                .reservation().orElseThrow();
        assertEquals(WorldItemPickupResult.Status.WORLD_ITEM_BUSY,
                busy.transaction.execute(busyItem.id(), BodySlot.LEFT_HAND, 0).status());
        assertEquals(0, busy.inventory.totalCount(OWNER, DIRT));
        busy.world.rollback(lock.id());

        Fixture full = fixture(0, event -> {});
        full.inventory.insert(OWNER, new ItemStack(DIRT, 192));
        WorldItemSnapshot fullItem = full.spawn(2, 0);
        assertEquals(WorldItemPickupResult.Status.INVENTORY_FULL,
                full.transaction.execute(fullItem.id(), BodySlot.LEFT_HAND, 0).status());
        assertEquals(2, full.world.snapshot(fullItem.id()).orElseThrow().stack().count());
    }

    @Test
    void appliedInventoryNotificationFailureStillCommitsWorldAndReturnsReceipt() {
        AtomicInteger publications = new AtomicInteger();
        RuntimeException notification = new RuntimeException("event failed");
        Fixture fixture = fixture(0, event -> {
            publications.incrementAndGet();
            throw notification;
        });
        WorldItemSnapshot item = fixture.spawn(3, 0);

        WorldItemPickupResult result = fixture.transaction.execute(
                item.id(), BodySlot.LEFT_HAND, 0);

        assertEquals(WorldItemPickupResult.Status.PICKED_WITH_NOTIFICATION_FAILURE,
                result.status());
        assertEquals(3, result.inventoryCommittedCount());
        assertEquals(0, result.remainingWorldCount());
        assertTrue(fixture.world.snapshot(item.id()).isEmpty());
        assertEquals(3, fixture.inventory.totalCount(OWNER, DIRT));
        assertTrue(result.failure().isPresent());
        assertTrue(result.committedReceipt().isPresent());
        assertEquals(1, publications.get());
    }

    @Test
    void nonAppliedResultsNeverCarryACommittedReceipt() {
        Fixture fixture = fixture(5, event -> {});
        WorldItemSnapshot item = fixture.spawn(1, 0);

        WorldItemPickupResult delayed = fixture.transaction.execute(
                item.id(), BodySlot.LEFT_HAND, 4);

        assertFalse(delayed.committedReceipt().isPresent());
    }

    @Test
    void partialPickupUsesExactReservationAndCommitOrder() {
        List<String> trace = new ArrayList<>();
        BodyInventoryService body = inventory(event -> {});
        assertEquals(InventoryChangeResult.Status.APPLIED,
                body.replaceSlot(new InventoryChangeRequest(
                        OWNER, BodySlot.LEFT_HAND, 0,
                        Optional.of(new ItemStack(DIRT, 63)))).status());
        assertEquals(InventoryChangeResult.Status.APPLIED,
                body.replaceSlot(new InventoryChangeRequest(
                        OWNER, BodySlot.RIGHT_HAND, 1,
                        Optional.of(new ItemStack(DIRT, 63)))).status());
        assertEquals(InventoryChangeResult.Status.APPLIED,
                body.replaceSlot(new InventoryChangeRequest(
                        OWNER, BodySlot.MOUTH, 2,
                        Optional.of(new ItemStack(DIRT, 64)))).status());
        LogicalWorldItemService logical = world(0);
        WorldItemSnapshot item = spawn(logical, 5, 0);
        RecordingInventory inventory = new RecordingInventory(body, trace);
        RecordingWorld wrappedWorld = new RecordingWorld(logical, trace, CommitMode.NORMAL);
        WorldItemPickupTransaction transaction = new WorldItemPickupTransaction(
                inventory, wrappedWorld, OWNER, failure -> {});

        WorldItemPickupResult result = transaction.execute(
                item.id(), BodySlot.RIGHT_HAND, 0);

        assertEquals(WorldItemPickupResult.Status.PICKED_PARTIAL, result.status());
        assertEquals(List.of(
                "inventory.reserve:RIGHT_HAND",
                "inventory.reserve:LEFT_HAND",
                "inventory.reserve:MOUTH",
                "world.reserve:2",
                "inventory.commit:0",
                "inventory.commit:1",
                "world.commit:0"), trace);
        assertEquals(5, result.inventoryCommittedCount() + result.remainingWorldCount());
        assertEquals(item.id(), logical.snapshot(item.id()).orElseThrow().id());
    }

    @Test
    void appliedWorldCommitFailureIsNotRetriedAndStillReturnsConservedReceipt() {
        BodyInventoryService inventory = inventory(event -> {});
        LogicalWorldItemService logical = world(0);
        WorldItemSnapshot item = spawn(logical, 3, 0);
        RecordingWorld wrapped = new RecordingWorld(
                logical, new ArrayList<>(), CommitMode.TYPED_AFTER_APPLY);
        List<Throwable> fatal = new ArrayList<>();

        WorldItemPickupResult result = new WorldItemPickupTransaction(
                inventory, wrapped, OWNER, fatal::add)
                .execute(item.id(), BodySlot.LEFT_HAND, 0);

        assertEquals(WorldItemPickupResult.Status.PICKED_WITH_NOTIFICATION_FAILURE,
                result.status());
        assertEquals(1, wrapped.commitCalls);
        assertTrue(logical.snapshot(item.id()).isEmpty());
        assertEquals(3, inventory.totalCount(OWNER, DIRT));
        assertTrue(fatal.isEmpty());
    }

    @Test
    void freshNonCommittedWorldResultRequestsFatalShutdownWithoutRetry() {
        BodyInventoryService inventory = inventory(event -> {});
        LogicalWorldItemService logical = world(0);
        WorldItemSnapshot item = spawn(logical, 3, 0);
        RecordingWorld wrapped = new RecordingWorld(
                logical, new ArrayList<>(), CommitMode.FRESH_ROLLED_BACK);
        List<Throwable> fatal = new ArrayList<>();

        WorldItemPickupResult result = new WorldItemPickupTransaction(
                inventory, wrapped, OWNER, fatal::add)
                .execute(item.id(), BodySlot.LEFT_HAND, 0);

        assertEquals(WorldItemPickupResult.Status.COMMIT_GUARANTEE_BROKEN,
                result.status());
        assertEquals(1, wrapped.commitCalls);
        assertEquals(1, fatal.size());
        assertEquals(3, logical.snapshot(item.id()).orElseThrow().stack().count());
        assertEquals(3, inventory.totalCount(OWNER, DIRT));
    }

    @Test
    void fatalInventoryNotificationErrorFinishesWorldCommitBeforeRethrow() {
        AssertionError eventFailure = new AssertionError("fatal notification");
        BodyInventoryService inventory = inventory(event -> {
            throw eventFailure;
        });
        LogicalWorldItemService logical = world(0);
        WorldItemSnapshot item = spawn(logical, 2, 0);
        List<Throwable> fatal = new ArrayList<>();

        AssertionError thrown = org.junit.jupiter.api.Assertions.assertThrows(
                AssertionError.class,
                () -> new WorldItemPickupTransaction(
                        inventory, logical, OWNER, fatal::add)
                        .execute(item.id(), BodySlot.LEFT_HAND, 0));

        assertEquals(eventFailure, thrown);
        assertTrue(logical.snapshot(item.id()).isEmpty());
        assertEquals(2, inventory.totalCount(OWNER, DIRT));
        assertEquals(1, fatal.size());
    }

    @Test
    void preBarrierWorldErrorRollsBackInventoryAndPreservesErrorIdentity() {
        BodyInventoryService inventory = inventory(event -> {});
        LogicalWorldItemService logical = world(0);
        WorldItemSnapshot item = spawn(logical, 2, 0);
        AssertionError reserveFailure = new AssertionError("world reserve fatal");
        RecordingWorld wrapped = new RecordingWorld(
                logical, new ArrayList<>(), CommitMode.NORMAL);
        wrapped.reserveFailure = reserveFailure;

        AssertionError thrown = org.junit.jupiter.api.Assertions.assertThrows(
                AssertionError.class,
                () -> new WorldItemPickupTransaction(
                        inventory, wrapped, OWNER, failure -> {})
                        .execute(item.id(), BodySlot.LEFT_HAND, 0));

        assertEquals(reserveFailure, thrown);
        assertEquals(0, inventory.totalCount(OWNER, DIRT));
        assertEquals(2, logical.snapshot(item.id()).orElseThrow().stack().count());
    }

    @Test
    void fatalDiagnosticFailureCannotMaskOriginalCommittedError() {
        AssertionError eventFailure = new AssertionError("fatal notification");
        RuntimeException diagnosticFailure = new RuntimeException("shutdown request failed");
        BodyInventoryService inventory = inventory(event -> {
            throw eventFailure;
        });
        LogicalWorldItemService logical = world(0);
        WorldItemSnapshot item = spawn(logical, 2, 0);

        AssertionError thrown = org.junit.jupiter.api.Assertions.assertThrows(
                AssertionError.class,
                () -> new WorldItemPickupTransaction(
                        inventory,
                        logical,
                        OWNER,
                        failure -> { throw diagnosticFailure; })
                        .execute(item.id(), BodySlot.LEFT_HAND, 0));

        assertEquals(eventFailure, thrown);
        assertTrue(java.util.Arrays.asList(thrown.getSuppressed())
                .contains(diagnosticFailure));
        assertTrue(logical.snapshot(item.id()).isEmpty());
        assertEquals(2, inventory.totalCount(OWNER, DIRT));
    }

    private static Fixture fixture(long delay, java.util.function.Consumer<com.overlord.event.Event> sink) {
        BodyInventoryService inventory = inventory(sink);
        LogicalWorldItemService world = world(delay);
        List<Throwable> fatal = new ArrayList<>();
        return new Fixture(inventory, world,
                new WorldItemPickupTransaction(inventory, world, OWNER, fatal::add), fatal);
    }

    private static BodyInventoryService inventory(Consumer<com.overlord.event.Event> sink) {
        return new BodyInventoryService(
                OWNER,
                id -> Optional.of(new ItemFormDefinition(id, 64, true, false)),
                MainThreadGuard.captureCurrentThread(),
                sink);
    }

    private static LogicalWorldItemService world(long delay) {
        return new LogicalWorldItemService(MainThreadGuard.captureCurrentThread(), 8, delay);
    }

    private static WorldItemSnapshot spawn(
            LogicalWorldItemService world, int count, long tick) {
        return world.spawn(new WorldItemSpawnRequest(new ItemStack(DIRT, count),
                1.0, 2.0, 3.0, 0, 0, 0, Optional.empty(), tick))
                .item().orElseThrow();
    }

    private static final class RecordingInventory
            implements InventoryService, InventoryReservationAudit {
        private final BodyInventoryService delegate;
        private final List<String> trace;

        private RecordingInventory(BodyInventoryService delegate, List<String> trace) {
            this.delegate = delegate;
            this.trace = trace;
        }

        @Override
        public Optional<InventoryView> snapshot(EntityRef owner) {
            return delegate.snapshot(owner);
        }

        @Override
        public InventoryChangeResult replaceSlot(InventoryChangeRequest request) {
            return delegate.replaceSlot(request);
        }

        @Override
        public InventoryReserveResult reserve(InventoryReservationRequest request) {
            trace.add("inventory.reserve:" + request.slot());
            return delegate.reserve(request);
        }

        @Override
        public InventoryReservationResult commit(InventoryReservationId reservationId) {
            trace.add("inventory.commit:" + reservationId.value());
            return delegate.commit(reservationId);
        }

        @Override
        public InventoryReservationResult rollback(InventoryReservationId reservationId) {
            trace.add("inventory.rollback:" + reservationId.value());
            return delegate.rollback(reservationId);
        }

        @Override
        public Optional<InventoryReservationAuditSnapshot> reservationAudit(
                InventoryReservationId reservationId) {
            return delegate.reservationAudit(reservationId);
        }
    }

    private static final class RecordingWorld
            implements WorldItemService, WorldItemRuntimeAccess, WorldItemReservationAudit {
        private final LogicalWorldItemService delegate;
        private final List<String> trace;
        private final CommitMode mode;
        private int commitCalls;
        private Error reserveFailure;

        private RecordingWorld(
                LogicalWorldItemService delegate, List<String> trace, CommitMode mode) {
            this.delegate = delegate;
            this.trace = trace;
            this.mode = mode;
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
            trace.add("world.reserve:" + count);
            if (reserveFailure != null) {
                throw reserveFailure;
            }
            return delegate.reserve(itemId, count);
        }

        @Override
        public WorldItemReservationResult commit(WorldItemReservationId reservationId) {
            commitCalls++;
            trace.add("world.commit:" + reservationId.value());
            return switch (mode) {
                case NORMAL -> delegate.commit(reservationId);
                case TYPED_AFTER_APPLY -> {
                    delegate.commit(reservationId);
                    throw new WorldItemCommitException(
                            "post-apply world failure", new RuntimeException("event"),
                            reservationId, true);
                }
                case FRESH_ROLLED_BACK -> delegate.rollback(reservationId);
            };
        }

        @Override
        public WorldItemReservationResult rollback(WorldItemReservationId reservationId) {
            return delegate.rollback(reservationId);
        }

        @Override
        public List<WorldItemPhysicalSnapshot> physicalSnapshots() {
            return delegate.physicalSnapshots();
        }

        @Override
        public Optional<WorldItemPhysicalSnapshot> physicalSnapshot(WorldItemId itemId) {
            return delegate.physicalSnapshot(itemId);
        }

        @Override
        public WorldItemMotionUpdateResult updateMotion(WorldItemMotionUpdate update) {
            return delegate.updateMotion(update);
        }

        @Override
        public Optional<WorldItemReservationAuditSnapshot> reservationAudit(
                WorldItemReservationId reservationId) {
            return delegate.reservationAudit(reservationId);
        }
    }

    private enum CommitMode {
        NORMAL,
        TYPED_AFTER_APPLY,
        FRESH_ROLLED_BACK
    }

    private record Fixture(
            BodyInventoryService inventory,
            LogicalWorldItemService world,
            WorldItemPickupTransaction transaction,
            List<Throwable> fatal) {
        private WorldItemSnapshot spawn(int count, long tick) {
            return world.spawn(new WorldItemSpawnRequest(new ItemStack(DIRT, count),
                    1.0, 2.0, 3.0, 0, 0, 0, Optional.empty(), tick))
                    .item().orElseThrow();
        }
    }
}

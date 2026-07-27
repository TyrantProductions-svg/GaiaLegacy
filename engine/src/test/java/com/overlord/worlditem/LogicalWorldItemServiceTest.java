package com.overlord.worlditem;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.overlord.assets.ResourceLocation;
import com.overlord.core.thread.MainThreadGuard;
import com.overlord.interaction.api.EntityRef;
import com.overlord.inventory.api.ItemStack;
import com.overlord.worlditem.api.WorldItemSpawnCommitResult;
import com.overlord.worlditem.api.WorldItemSpawnRequest;
import com.overlord.worlditem.api.WorldItemSpawnReserveResult;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class LogicalWorldItemServiceTest {
    private static final ItemStack DIRT =
            new ItemStack(ResourceLocation.parse("gaia:dirt"), 3);

    @Test
    void directSpawnPersistsStableIdentityCanonicalStackAndPickupDelay() {
        LogicalWorldItemService service = service(4, 20);
        WorldItemSpawnRequest request = request(DIRT, 7);

        var spawned = service.spawn(request);
        var item = spawned.item().orElseThrow();
        var runtime = service.runtimeSnapshot(item.id()).orElseThrow();

        assertEquals(DIRT, item.stack());
        assertEquals(item, service.snapshot(item.id()).orElseThrow());
        assertEquals(Optional.of(new EntityRef(5)), runtime.source());
        assertEquals(7, runtime.spawnTick());
        assertEquals(27, runtime.pickupAvailableTick());
        assertEquals(1, service.snapshots().size());
    }

    @Test
    void spawnReservationIsInvisibleUntilGuaranteedIdempotentCommit() {
        LogicalWorldItemService service = service(1, 10);

        WorldItemSpawnReserveResult reserved = service.reserveSpawn(request(DIRT, 2));
        var reservation = reserved.reservation().orElseThrow();

        assertEquals(WorldItemSpawnReserveResult.Status.RESERVED, reserved.status());
        assertTrue(service.snapshot(reservation.itemId()).isEmpty());
        assertTrue(service.snapshots().isEmpty());

        WorldItemSpawnCommitResult committed = service.commitSpawn(reservation.id());
        WorldItemSpawnCommitResult repeated = service.commitSpawn(reservation.id());

        assertEquals(WorldItemSpawnCommitResult.Status.COMMITTED, committed.status());
        assertEquals(WorldItemSpawnCommitResult.Status.ALREADY_COMMITTED, repeated.status());
        assertEquals(reservation.itemId(), committed.item().orElseThrow().id());
        assertEquals(committed.item(), repeated.item());
        assertEquals(1, service.snapshots().size());
    }

    @Test
    void successfulReservationStillCommitsWhenPickupTickWouldOverflow() {
        LogicalWorldItemService service = service(1, 10);
        var reservation = service.reserveSpawn(request(DIRT, Long.MAX_VALUE))
                .reservation().orElseThrow();

        WorldItemSpawnCommitResult committed = service.commitSpawn(reservation.id());

        assertEquals(WorldItemSpawnCommitResult.Status.COMMITTED, committed.status());
        assertEquals(
                Long.MAX_VALUE,
                service.runtimeSnapshot(reservation.itemId())
                        .orElseThrow()
                        .pickupAvailableTick());
    }

    @Test
    void rollbackIsIdempotentAndMakesLaterCommitAConflictWithoutAnItem() {
        LogicalWorldItemService service = service(1, 10);
        var reservation = service.reserveSpawn(request(DIRT, 2))
                .reservation().orElseThrow();

        WorldItemSpawnCommitResult rolledBack = service.rollbackSpawn(reservation.id());
        WorldItemSpawnCommitResult repeated = service.rollbackSpawn(reservation.id());
        WorldItemSpawnCommitResult conflict = service.commitSpawn(reservation.id());

        assertEquals(WorldItemSpawnCommitResult.Status.ROLLED_BACK, rolledBack.status());
        assertEquals(WorldItemSpawnCommitResult.Status.ALREADY_ROLLED_BACK, repeated.status());
        assertEquals(WorldItemSpawnCommitResult.Status.TERMINAL_CONFLICT, conflict.status());
        assertTrue(service.snapshots().isEmpty());
    }

    @Test
    void pendingReservationConsumesCapacityAndRejectionReturnsFullRemainder() {
        LogicalWorldItemService service = service(1, 10);
        service.reserveSpawn(request(DIRT, 1));

        WorldItemSpawnReserveResult rejected = service.reserveSpawn(
                request(new ItemStack(DIRT.itemId(), 1), 2));

        assertEquals(WorldItemSpawnReserveResult.Status.REJECTED, rejected.status());
        assertTrue(rejected.reservation().isEmpty());
        assertEquals(Optional.of(new ItemStack(DIRT.itemId(), 1)), rejected.remainder());
        assertEquals(com.overlord.worlditem.api.WorldItemSpawnResult.Status.REJECTED,
                service.spawn(request(new ItemStack(DIRT.itemId(), 1), 3)).status());
    }

    @Test
    void existingItemReservationCommitAndRollbackRetainPhaseSevenSemantics() {
        LogicalWorldItemService service = service(2, 0);
        var item = service.spawn(request(DIRT, 1)).item().orElseThrow();

        var partial = service.reserve(item.id(), 2);
        var reservation = partial.reservation().orElseThrow();
        assertEquals(com.overlord.worlditem.api.WorldItemReservationResult.Status.PARTIALLY_RESERVED,
                partial.status());
        assertEquals(1, partial.remainder().orElseThrow().count());

        var committed = service.commit(reservation.id());
        var repeated = service.commit(reservation.id());
        assertEquals(com.overlord.worlditem.api.WorldItemReservationResult.Status.COMMITTED,
                committed.status());
        assertEquals(com.overlord.worlditem.api.WorldItemReservationResult.Status.ALREADY_COMMITTED,
                repeated.status());
        assertEquals(1, service.snapshot(item.id()).orElseThrow().stack().count());

        var second = service.reserve(item.id(), 1).reservation().orElseThrow();
        service.rollback(second.id());
        assertEquals(1, service.snapshot(item.id()).orElseThrow().stack().count());
    }

    @Test
    void snapshotsAreImmutableValuesAndNoMutableStoreEscapes() {
        LogicalWorldItemService service = service(2, 0);
        service.spawn(request(DIRT, 1));
        var snapshots = service.snapshots();

        assertFalse(snapshots.isEmpty());
        org.junit.jupiter.api.Assertions.assertThrows(
                UnsupportedOperationException.class, snapshots::clear);
    }

    @Test
    void rolledBackCapacityCanBeReusedButBurnedStableIdIsNeverReissued() {
        LogicalWorldItemService service = service(1, 0);
        var first = service.reserveSpawn(request(DIRT, 1)).reservation().orElseThrow();
        service.rollbackSpawn(first.id());

        var second = service.reserveSpawn(request(DIRT, 2)).reservation().orElseThrow();

        assertTrue(second.itemId().value() > first.itemId().value());
        assertEquals(
                WorldItemSpawnReserveResult.Status.REJECTED,
                service.reserveSpawn(request(DIRT, 3)).status(),
                "a live second reservation must consume the reused capacity");
    }

    @Test
    void liveStoreRejectsWorkerThreadAccess() throws InterruptedException {
        LogicalWorldItemService service = service(1, 0);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try {
                service.snapshots();
            } catch (Throwable thrown) {
                failure.set(thrown);
            }
        }, "world-item-test-worker");

        worker.start();
        worker.join();

        assertTrue(failure.get() instanceof IllegalStateException);
    }

    private static LogicalWorldItemService service(int capacity, long pickupDelay) {
        return new LogicalWorldItemService(
                MainThreadGuard.captureCurrentThread(), capacity, pickupDelay);
    }

    private static WorldItemSpawnRequest request(ItemStack stack, long tick) {
        return new WorldItemSpawnRequest(
                stack,
                1, 2, 3,
                0.1, 0.2, 0.3,
                Optional.of(new EntityRef(5)),
                tick);
    }
}

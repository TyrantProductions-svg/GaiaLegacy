package com.overlord.worlditem;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.overlord.assets.ResourceLocation;
import com.overlord.core.thread.MainThreadGuard;
import com.overlord.inventory.api.ItemStack;
import com.overlord.worlditem.api.WorldItemMotionUpdate;
import com.overlord.worlditem.api.WorldItemMotionUpdateResult;
import com.overlord.worlditem.api.WorldItemPhysicalSnapshot;
import com.overlord.worlditem.api.WorldItemPhysicalState;
import com.overlord.worlditem.api.WorldItemSpawnRequest;
import com.overlord.worlditem.api.WorldItemSnapshot;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class WorldItemRuntimeAccessTest {
    private static final ItemStack DIRT =
            new ItemStack(ResourceLocation.parse("gaia:dirt"), 3);

    @Test
    void physicalSnapshotsAreImmutableAndSortedByStableId() {
        LogicalWorldItemService service = service(3);
        WorldItemSnapshot first = service.spawn(request(DIRT, 1, 2)).item().orElseThrow();
        WorldItemSnapshot second = service.spawn(request(DIRT, 3, 4)).item().orElseThrow();

        List<WorldItemPhysicalSnapshot> snapshots = service.physicalSnapshots();

        assertEquals(List.of(first.id(), second.id()),
                snapshots.stream().map(WorldItemPhysicalSnapshot::id).toList());
        assertEquals(WorldItemPhysicalState.ACTIVE, snapshots.get(0).state());
        assertThrows(UnsupportedOperationException.class, snapshots::clear);
        assertEquals(DIRT, snapshots.get(0).runtime().item().stack());
        assertFalse(snapshots.get(0).extractionReserved());
    }

    @Test
    void motionUpdateUsesTheCanonicalRevisionAndRejectsStaleOrInvalidValues() {
        LogicalWorldItemService service = service(1);
        WorldItemSnapshot spawned = service.spawn(request(DIRT, 1, 2)).item().orElseThrow();

        WorldItemMotionUpdateResult applied = service.updateMotion(new WorldItemMotionUpdate(
                spawned.id(), spawned.revision(),
                4.0, 5.0, 6.0, 1.0, -2.0, 3.0,
                WorldItemPhysicalState.GROUNDED));

        assertEquals(WorldItemMotionUpdateResult.Status.APPLIED, applied.status());
        WorldItemPhysicalSnapshot canonical = applied.snapshot().orElseThrow();
        assertEquals(1L, canonical.runtime().item().revision());
        assertEquals(4.0, canonical.runtime().item().positionX());
        assertEquals(WorldItemPhysicalState.GROUNDED, canonical.state());

        WorldItemMotionUpdateResult stale = service.updateMotion(new WorldItemMotionUpdate(
                spawned.id(), spawned.revision(),
                9.0, 9.0, 9.0, 0.0, 0.0, 0.0,
                WorldItemPhysicalState.ACTIVE));
        assertEquals(WorldItemMotionUpdateResult.Status.STALE_REVISION, stale.status());
        assertEquals(canonical, service.physicalSnapshot(spawned.id()).orElseThrow());

        WorldItemMotionUpdateResult invalid = service.updateMotion(new WorldItemMotionUpdate(
                spawned.id(), canonical.runtime().item().revision(),
                Double.NaN, 0.0, 0.0, 0.0, 0.0, 0.0,
                WorldItemPhysicalState.ACTIVE));
        assertEquals(WorldItemMotionUpdateResult.Status.INVALID_MOTION, invalid.status());
        assertEquals(canonical, service.physicalSnapshot(spawned.id()).orElseThrow());
    }

    @Test
    void motionUpdateRevisionExhaustionIsClosedIdempotentAndStatePreserving() {
        LogicalWorldItemService service = service(1);
        WorldItemSnapshot spawned = service.spawn(request(DIRT, 1, 2)).item().orElseThrow();
        LogicalWorldItemTestAccess.forceRevision(service, spawned.id(), Long.MAX_VALUE);
        WorldItemPhysicalSnapshot before = service.physicalSnapshot(spawned.id()).orElseThrow();
        WorldItemMotionUpdate update = new WorldItemMotionUpdate(
                spawned.id(),
                Long.MAX_VALUE,
                9.0, 8.0, 7.0,
                6.0, 5.0, 4.0,
                WorldItemPhysicalState.GROUNDED);

        WorldItemMotionUpdateResult first = service.updateMotion(update);
        WorldItemMotionUpdateResult repeated = service.updateMotion(update);

        assertEquals(WorldItemMotionUpdateResult.Status.REVISION_EXHAUSTED, first.status());
        assertEquals(first, repeated);
        assertEquals(Optional.of(before), first.snapshot());
        assertEquals(before, service.physicalSnapshot(spawned.id()).orElseThrow());
    }

    @Test
    void motionUpdateResultRejectsEveryInvalidStatusPayloadCombination() {
        LogicalWorldItemService service = service(1);
        WorldItemSnapshot spawned = service.spawn(request(DIRT, 1, 2)).item().orElseThrow();
        WorldItemPhysicalSnapshot snapshot = service.physicalSnapshot(spawned.id()).orElseThrow();

        for (WorldItemMotionUpdateResult.Status status
                : WorldItemMotionUpdateResult.Status.values()) {
            if (status == WorldItemMotionUpdateResult.Status.UNKNOWN_ITEM) {
                assertEquals(status, new WorldItemMotionUpdateResult(
                        status, Optional.empty()).status());
                assertThrows(IllegalArgumentException.class, () ->
                        new WorldItemMotionUpdateResult(status, Optional.of(snapshot)));
            } else {
                assertEquals(status, new WorldItemMotionUpdateResult(
                        status, Optional.of(snapshot)).status());
                assertThrows(IllegalArgumentException.class, () ->
                        new WorldItemMotionUpdateResult(status, Optional.empty()));
            }
        }
    }

    @Test
    void extractionReservationIsVisibleWithoutChangingTheCanonicalStack() {
        LogicalWorldItemService service = service(1);
        WorldItemSnapshot spawned = service.spawn(request(DIRT, 1, 2)).item().orElseThrow();

        var reservation = service.reserve(spawned.id(), 1).reservation().orElseThrow();
        WorldItemPhysicalSnapshot reserved = service.physicalSnapshot(spawned.id()).orElseThrow();
        assertTrue(reserved.extractionReserved());
        assertEquals(3, reserved.runtime().item().stack().count());

        service.rollback(reservation.id());
        assertFalse(service.physicalSnapshot(spawned.id()).orElseThrow().extractionReserved());

        var finalReservation = service.reserve(spawned.id(), 3).reservation().orElseThrow();
        service.commit(finalReservation.id());
        assertTrue(service.physicalSnapshot(spawned.id()).isEmpty());
        assertTrue(service.runtimeSnapshot(spawned.id()).isEmpty());
    }

    @Test
    void runtimeAccessRejectsWorkerThreadCalls() throws InterruptedException {
        LogicalWorldItemService service = service(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try {
                service.physicalSnapshots();
            } catch (Throwable thrown) {
                failure.set(thrown);
            }
        }, "world-item-runtime-worker");

        worker.start();
        worker.join();

        assertInstanceOf(IllegalStateException.class, failure.get());
    }

    @Test
    void everyRuntimeAccessOperationRejectsWorkerThreadCalls() throws InterruptedException {
        LogicalWorldItemService service = service(1);
        WorldItemSnapshot spawned = service.spawn(request(DIRT, 1, 2)).item().orElseThrow();
        WorldItemMotionUpdate update = new WorldItemMotionUpdate(
                spawned.id(), spawned.revision(),
                2.0, 3.0, 4.0, 0.0, 0.0, 0.0,
                WorldItemPhysicalState.ACTIVE);
        List<Runnable> operations = List.of(
                service::physicalSnapshots,
                () -> service.physicalSnapshot(spawned.id()),
                () -> service.updateMotion(update));

        for (Runnable operation : operations) {
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread worker = new Thread(() -> {
                try {
                    operation.run();
                } catch (Throwable thrown) {
                    failure.set(thrown);
                }
            }, "world-item-runtime-worker");
            worker.start();
            worker.join();
            assertInstanceOf(IllegalStateException.class, failure.get());
        }
    }

    @Test
    void projectionFacingSnapshotsExposeOnlyTheCanonicalItemValue() {
        LogicalWorldItemService service = service(1);
        WorldItemSnapshot spawned = service.spawn(request(DIRT, 1, 2)).item().orElseThrow();

        WorldItemPhysicalSnapshot snapshot = service.physicalSnapshot(spawned.id()).orElseThrow();

        assertEquals(spawned.stack(), snapshot.runtime().item().stack());
        assertEquals(spawned.id(), snapshot.id());
    }

    private static LogicalWorldItemService service(int capacity) {
        return new LogicalWorldItemService(
                MainThreadGuard.captureCurrentThread(), capacity, 0);
    }

    private static WorldItemSpawnRequest request(ItemStack stack, double x, double z) {
        return new WorldItemSpawnRequest(
                stack, x, 2.0, z, 0.0, 0.0, 0.0,
                Optional.empty(), 1);
    }
}

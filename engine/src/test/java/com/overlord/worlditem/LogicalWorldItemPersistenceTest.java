package com.overlord.worlditem;

import static com.overlord.worlditem.api.WorldItemRestoreResult.Status.CAPACITY_EXCEEDED;
import static com.overlord.worlditem.api.WorldItemRestoreResult.Status.INVALID_SNAPSHOT;
import static com.overlord.worlditem.api.WorldItemRestoreResult.Status.RESTORED;
import static com.overlord.worlditem.api.WorldItemRestoreResult.Status.TARGET_NOT_FRESH;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.overlord.assets.ResourceLocation;
import com.overlord.core.thread.MainThreadGuard;
import com.overlord.interaction.api.EntityRef;
import com.overlord.inventory.api.ItemStack;
import com.overlord.worlditem.api.LogicalWorldItemSnapshot;
import com.overlord.worlditem.api.WorldItemId;
import com.overlord.worlditem.api.WorldItemMotionUpdate;
import com.overlord.worlditem.api.WorldItemPhysicalState;
import com.overlord.worlditem.api.WorldItemRestoreEntry;
import com.overlord.worlditem.api.WorldItemRestoreResult;
import com.overlord.worlditem.api.WorldItemRuntimeSnapshot;
import com.overlord.worlditem.api.WorldItemSnapshot;
import com.overlord.worlditem.api.WorldItemSpawnRequest;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class LogicalWorldItemPersistenceTest {
    private static final ResourceLocation DIRT = ResourceLocation.parse("gaia:dirt");
    private static final ResourceLocation STONE = ResourceLocation.parse("gaia:stone");
    private static final EntityRef SOURCE = new EntityRef(5);

    @Test
    void canonicalRoundTripSortsAndPreservesRuntimeTimingMotionAndPhysicalState() {
        LogicalWorldItemService source = service(4, 20);
        WorldItemSnapshot first = source.spawn(request(DIRT, 3, 7)).item().orElseThrow();
        WorldItemSnapshot second = source.spawn(request(STONE, 2, 11)).item().orElseThrow();
        source.updateMotion(new WorldItemMotionUpdate(
                second.id(), second.revision(),
                9, 8, 7,
                -0.5, 0.25, 1.5,
                WorldItemPhysicalState.SLEEPING));

        LogicalWorldItemSnapshot snapshot = source.canonicalSnapshot();

        assertEquals(List.of(first.id(), second.id()), snapshot.entries().stream()
                .map(entry -> entry.runtime().item().id()).toList());
        WorldItemRestoreEntry secondEntry = snapshot.entries().get(1);
        assertEquals(source.runtimeSnapshot(second.id()).orElseThrow(), secondEntry.runtime());
        assertEquals(WorldItemPhysicalState.SLEEPING, secondEntry.physicalState());
        assertEquals(2, snapshot.nextItemId());
        assertFalse(snapshot.itemIdsExhausted());

        LogicalWorldItemService restored = service(4, 20);
        WorldItemRestoreResult result = restored.restoreCanonical(snapshot);

        assertEquals(RESTORED, result.status());
        assertEquals(2, result.restoredCount());
        assertEquals(snapshot, restored.canonicalSnapshot());
        assertTrue(restored.physicalSnapshots().stream()
                .noneMatch(physical -> physical.extractionReserved()));
    }

    @Test
    void snapshotOwnsAnImmutableStableIdSortedEntryList() {
        WorldItemRestoreEntry high = entry(9, DIRT, 1, 0);
        WorldItemRestoreEntry low = entry(2, STONE, 1, 0);
        LogicalWorldItemSnapshot snapshot = new LogicalWorldItemSnapshot(
                List.of(high, low), 10, false);

        assertEquals(List.of(new WorldItemId(2), new WorldItemId(9)),
                snapshot.entries().stream().map(e -> e.runtime().item().id()).toList());
        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.entries().clear());
    }

    @Test
    void restoreRejectsDuplicateCapacityAndInvalidAllocatorStateWithoutPartialPublication() {
        LogicalWorldItemService fresh = service(2, 0);
        WorldItemRestoreEntry first = entry(1, DIRT, 1, 0);
        WorldItemRestoreEntry duplicateLast = entry(1, STONE, 1, 0);

        assertEquals(INVALID_SNAPSHOT, fresh.restoreCanonical(
                new LogicalWorldItemSnapshot(
                        List.of(first, duplicateLast), 2, false)).status());
        assertTrue(fresh.canonicalSnapshot().entries().isEmpty(),
                "an invalid last entry must not publish the earlier entry");

        assertEquals(CAPACITY_EXCEEDED, fresh.restoreCanonical(
                new LogicalWorldItemSnapshot(
                        List.of(entry(0, DIRT, 1, 0), entry(1, DIRT, 1, 0),
                                entry(2, DIRT, 1, 0)),
                        3,
                        false)).status());
        assertEquals(INVALID_SNAPSHOT, fresh.restoreCanonical(
                new LogicalWorldItemSnapshot(List.of(first), 1, false)).status());
        assertEquals(INVALID_SNAPSHOT, fresh.restoreCanonical(
                new LogicalWorldItemSnapshot(List.of(), 4, true)).status());
        assertTrue(fresh.canonicalSnapshot().entries().isEmpty());
    }

    @Test
    void restoreRequiresTrulyFreshStateAndAllocatorHistory() {
        LogicalWorldItemSnapshot snapshot = new LogicalWorldItemSnapshot(
                List.of(entry(10, DIRT, 1, 0)), 11, false);

        LogicalWorldItemService nonempty = service(2, 0);
        nonempty.spawn(request(DIRT, 1, 0));
        assertEquals(TARGET_NOT_FRESH, nonempty.restoreCanonical(snapshot).status());
        assertEquals(1, nonempty.snapshots().size());

        LogicalWorldItemService burnedAllocator = service(2, 0);
        var rolledBack = burnedAllocator.reserveSpawn(request(DIRT, 1, 0))
                .reservation().orElseThrow();
        burnedAllocator.rollbackSpawn(rolledBack.id());
        assertEquals(TARGET_NOT_FRESH,
                burnedAllocator.restoreCanonical(snapshot).status());
    }

    @Test
    void restoredHistoricalHighWaterIsUsedEvenWhenNoLiveItemHasThatId() {
        LogicalWorldItemService restored = service(2, 0);
        LogicalWorldItemSnapshot emptyHistoricalState =
                new LogicalWorldItemSnapshot(List.of(), 73, false);

        assertEquals(RESTORED, restored.restoreCanonical(emptyHistoricalState).status());
        WorldItemSnapshot spawned = restored.spawn(request(DIRT, 1, 0))
                .item().orElseThrow();

        assertEquals(new WorldItemId(73), spawned.id());
        assertEquals(74, restored.canonicalSnapshot().nextItemId());
    }

    @Test
    void exhaustedAllocatorRestoresOnlyItsCanonicalLongMaxState() {
        LogicalWorldItemService restored = service(1, 0);
        LogicalWorldItemSnapshot exhausted =
                new LogicalWorldItemSnapshot(List.of(), Long.MAX_VALUE, true);

        assertEquals(RESTORED, restored.restoreCanonical(exhausted).status());
        assertThrows(IllegalStateException.class,
                () -> restored.reserveSpawn(request(DIRT, 1, 0)));
        assertEquals(exhausted, restored.canonicalSnapshot());
    }

    @Test
    void captureRejectsPendingSpawnAndExtractionButOmitsTerminalHistory() {
        LogicalWorldItemService spawnPending = service(2, 0);
        var spawnReservation = spawnPending.reserveSpawn(request(DIRT, 1, 0))
                .reservation().orElseThrow();
        IllegalStateException spawnFailure = assertThrows(
                IllegalStateException.class, spawnPending::canonicalSnapshot);
        assertTrue(spawnFailure.getMessage().contains("pending spawn reservation"));
        spawnPending.rollbackSpawn(spawnReservation.id());
        assertTrue(spawnPending.canonicalSnapshot().entries().isEmpty());

        LogicalWorldItemService extractionPending = service(2, 0);
        var item = extractionPending.spawn(request(DIRT, 2, 0)).item().orElseThrow();
        var extractionReservation = extractionPending.reserve(item.id(), 1)
                .reservation().orElseThrow();
        IllegalStateException extractionFailure = assertThrows(
                IllegalStateException.class, extractionPending::canonicalSnapshot);
        assertTrue(extractionFailure.getMessage().contains("pending extraction reservation"));
        extractionPending.rollback(extractionReservation.id());
        assertEquals(1, extractionPending.canonicalSnapshot().entries().size());
    }

    @Test
    void canonicalCaptureAndRestoreAreMainThreadOnly() throws InterruptedException {
        LogicalWorldItemService service = service(1, 0);
        LogicalWorldItemSnapshot snapshot =
                new LogicalWorldItemSnapshot(List.of(), 0, false);
        AtomicReference<Throwable> captureFailure = new AtomicReference<>();
        AtomicReference<Throwable> restoreFailure = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try {
                service.canonicalSnapshot();
            } catch (Throwable thrown) {
                captureFailure.set(thrown);
            }
            try {
                service.restoreCanonical(snapshot);
            } catch (Throwable thrown) {
                restoreFailure.set(thrown);
            }
        }, "world-item-persistence-worker");

        worker.start();
        worker.join();

        assertTrue(captureFailure.get() instanceof IllegalStateException);
        assertTrue(restoreFailure.get() instanceof IllegalStateException);
    }

    @Test
    void restoreResultRejectsNonzeroCountsForEveryFailureStatus() {
        for (WorldItemRestoreResult.Status status : List.of(
                INVALID_SNAPSHOT, TARGET_NOT_FRESH, CAPACITY_EXCEEDED)) {
            IllegalArgumentException failure = assertThrows(
                    IllegalArgumentException.class,
                    () -> new WorldItemRestoreResult(status, 1));
            assertEquals(
                    "a failed restore must have restoredCount zero",
                    failure.getMessage());
        }

        assertEquals(
                new WorldItemRestoreResult(RESTORED, 0),
                new WorldItemRestoreResult(RESTORED, 0));
    }

    @Test
    void restorePrebuildsDetachedAggregateAndResultBeforeSinglePublication() {
        AtomicReference<LogicalWorldItemService> targetRef = new AtomicReference<>();
        AtomicReference<Object> preparedAggregate = new AtomicReference<>();
        AtomicReference<WorldItemRestoreResult> preparedResult = new AtomicReference<>();
        AtomicBoolean injectFailure = new AtomicBoolean(true);
        IllegalStateException sentinel = new IllegalStateException("world-item publication probe");
        LogicalWorldItemSnapshot snapshot = new LogicalWorldItemSnapshot(
                List.of(entry(10, DIRT, 2, 7)), 11, false);
        LogicalWorldItemService target = new LogicalWorldItemService(
                MainThreadGuard.captureCurrentThread(),
                2,
                0,
                (detached, validated, success) -> {
                    assertNotNull(detached);
                    assertPreparedAggregateMatches(detached, snapshot);
                    assertEquals(
                            new LogicalWorldItemSnapshot(List.of(), 0, false),
                            targetRef.get().canonicalSnapshot());
                    assertEquals(snapshot, validated);
                    assertEquals(RESTORED, success.status());
                    assertEquals(1, success.restoredCount());
                    preparedAggregate.set(detached);
                    preparedResult.set(success);
                    if (injectFailure.getAndSet(false)) {
                        throw sentinel;
                    }
                });
        targetRef.set(target);

        assertSame(sentinel, assertThrows(
                IllegalStateException.class,
                () -> target.restoreCanonical(snapshot)));
        assertEquals(
                new LogicalWorldItemSnapshot(List.of(), 0, false),
                target.canonicalSnapshot());

        WorldItemRestoreResult restored = target.restoreCanonical(snapshot);

        assertSame(preparedResult.get(), restored,
                "restore must return the exact result built before publication");
        assertNotNull(preparedAggregate.get());
        assertEquals(snapshot, target.canonicalSnapshot());
    }

    private static void assertPreparedAggregateMatches(
            Object aggregate, LogicalWorldItemSnapshot snapshot) {
        try {
            Field itemsField = aggregate.getClass().getDeclaredField("items");
            Field nextItemIdField = aggregate.getClass().getDeclaredField("nextItemId");
            Field exhaustedField = aggregate.getClass().getDeclaredField("itemIdsExhausted");
            itemsField.setAccessible(true);
            nextItemIdField.setAccessible(true);
            exhaustedField.setAccessible(true);

            Map<?, ?> items = (Map<?, ?>) itemsField.get(aggregate);
            assertEquals(
                    snapshot.entries().stream()
                            .map(entry -> entry.runtime().item().id())
                            .toList(),
                    List.copyOf(items.keySet()));
            assertEquals(snapshot.nextItemId(), nextItemIdField.getLong(aggregate));
            assertEquals(snapshot.itemIdsExhausted(), exhaustedField.getBoolean(aggregate));
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("cannot inspect prepared world-item aggregate", failure);
        }
    }

    private static LogicalWorldItemService service(int capacity, long pickupDelayTicks) {
        return new LogicalWorldItemService(
                MainThreadGuard.captureCurrentThread(), capacity, pickupDelayTicks);
    }

    private static WorldItemSpawnRequest request(
            ResourceLocation itemId, int count, long tick) {
        return new WorldItemSpawnRequest(
                new ItemStack(itemId, count),
                1, 2, 3,
                0.1, 0.2, 0.3,
                Optional.of(SOURCE),
                tick);
    }

    private static WorldItemRestoreEntry entry(
            long id, ResourceLocation itemId, int count, long revision) {
        WorldItemSnapshot item = new WorldItemSnapshot(
                new WorldItemId(id),
                new ItemStack(itemId, count),
                id + 0.1, id + 0.2, id + 0.3,
                0.4, 0.5, 0.6,
                revision);
        return new WorldItemRestoreEntry(
                new WorldItemRuntimeSnapshot(
                        item, Optional.of(SOURCE), 10, 15),
                WorldItemPhysicalState.GROUNDED);
    }
}

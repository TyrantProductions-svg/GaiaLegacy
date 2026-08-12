package com.gaia.inventory;

import static com.gaia.inventory.BodyInventoryRestoreResult.Status.INVALID_SNAPSHOT;
import static com.gaia.inventory.BodyInventoryRestoreResult.Status.RESTORED;
import static com.gaia.inventory.BodyInventoryRestoreResult.Status.TARGET_NOT_FRESH;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gaia.blocks.ItemFormDefinition;
import com.overlord.assets.ResourceLocation;
import com.overlord.core.thread.MainThreadGuard;
import com.overlord.event.Event;
import com.overlord.interaction.api.EntityRef;
import com.overlord.inventory.api.BodySlot;
import com.overlord.inventory.api.InventoryChangeRequest;
import com.overlord.inventory.api.InventoryReservationOperation;
import com.overlord.inventory.api.InventoryReservationRequest;
import com.overlord.inventory.api.ItemStack;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class BodyInventoryPersistenceTest {
    private static final EntityRef OWNER = new EntityRef(7);
    private static final EntityRef OTHER_OWNER = new EntityRef(8);
    private static final ResourceLocation DIRT = ResourceLocation.parse("gaia:dirt");
    private static final ResourceLocation LEAVES = ResourceLocation.parse("gaia:oak_leaves");
    private static final ResourceLocation HEAVY = ResourceLocation.parse("gaia:heavy_test");
    private static final ResourceLocation UNKNOWN = ResourceLocation.parse("gaia:unknown");

    @Test
    void canonicalRoundTripPreservesDirectSlotsSelectionTwoHandedOccupancyAndRevision() {
        List<Event> sourceEvents = new ArrayList<>();
        BodyInventoryService source = service(sourceEvents);
        source.replaceSlot(new InventoryChangeRequest(
                OWNER, BodySlot.LEFT_HAND, 0, Optional.of(new ItemStack(HEAVY, 2))));
        source.replaceSlot(new InventoryChangeRequest(
                OWNER, BodySlot.MOUTH, 1, Optional.of(new ItemStack(LEAVES, 3))));
        source.selectActiveSlot(OWNER, BodySlot.RIGHT_HAND);

        BodyInventoryCanonicalSnapshot snapshot = source.canonicalSnapshot(OWNER);

        assertEquals(Map.of(
                BodySlot.LEFT_HAND, new ItemStack(HEAVY, 2),
                BodySlot.MOUTH, new ItemStack(LEAVES, 3)), snapshot.stacks(),
                "the mirrored right-hand view must not become a direct saved stack");
        assertEquals(BodySlot.RIGHT_HAND, snapshot.activeSlot());
        assertTrue(snapshot.twoHandedHandsOccupied());
        assertEquals(2, snapshot.revision());

        List<Event> restoredEvents = new ArrayList<>();
        BodyInventoryService restored = service(restoredEvents);
        assertEquals(RESTORED, restored.restoreCanonical(OWNER, snapshot).status());
        assertEquals(snapshot, restored.canonicalSnapshot(OWNER));
        assertTrue(restoredEvents.isEmpty(), "restore must not publish inventory events");
    }

    @Test
    void restoreRejectsUnknownOverstackAndInvalidTwoHandedRepresentationsAtomically() {
        BodyInventoryService fresh = service(new ArrayList<>());

        assertEquals(INVALID_SNAPSHOT, fresh.restoreCanonical(OWNER, snapshot(
                Map.of(BodySlot.LEFT_HAND, new ItemStack(UNKNOWN, 1)), false, 0)).status());
        assertEquals(INVALID_SNAPSHOT, fresh.restoreCanonical(OWNER, snapshot(
                Map.of(BodySlot.LEFT_HAND, new ItemStack(DIRT, 65)), false, 0)).status());

        EnumMap<BodySlot, ItemStack> duplicateHands = new EnumMap<>(BodySlot.class);
        duplicateHands.put(BodySlot.LEFT_HAND, new ItemStack(HEAVY, 1));
        duplicateHands.put(BodySlot.RIGHT_HAND, new ItemStack(HEAVY, 1));
        assertEquals(INVALID_SNAPSHOT, fresh.restoreCanonical(OWNER, snapshot(
                duplicateHands, true, 0)).status());
        assertEquals(INVALID_SNAPSHOT, fresh.restoreCanonical(OWNER, snapshot(
                Map.of(BodySlot.LEFT_HAND, new ItemStack(DIRT, 1)), true, 0)).status());
        assertEquals(INVALID_SNAPSHOT, fresh.restoreCanonical(OWNER, snapshot(
                Map.of(BodySlot.RIGHT_HAND, new ItemStack(HEAVY, 1)), true, 0)).status());
        assertEquals(INVALID_SNAPSHOT, fresh.restoreCanonical(OWNER, snapshot(
                Map.of(BodySlot.LEFT_HAND, new ItemStack(HEAVY, 1)), false, 0)).status());

        BodyInventoryCanonicalSnapshot invalidLastSlot = new BodyInventoryCanonicalSnapshot(
                OWNER,
                Map.of(
                        BodySlot.LEFT_HAND, new ItemStack(DIRT, 1),
                        BodySlot.MOUTH, new ItemStack(DIRT, 1)),
                BodySlot.LEFT_HAND,
                false,
                4);
        assertEquals(INVALID_SNAPSHOT,
                fresh.restoreCanonical(OWNER, invalidLastSlot).status());
        assertTrue(fresh.canonicalSnapshot(OWNER).stacks().isEmpty(),
                "a failure in the last direct slot must not publish earlier slots");
        assertEquals(0, fresh.canonicalSnapshot(OWNER).revision());
    }

    @Test
    void restoreValidatesOwnerRevisionAndFreshTargetBeforeMutation() {
        BodyInventoryService fresh = service(new ArrayList<>());
        BodyInventoryCanonicalSnapshot valid = snapshot(
                Map.of(BodySlot.LEFT_HAND, new ItemStack(DIRT, 1)), false, 6);

        assertEquals(INVALID_SNAPSHOT,
                fresh.restoreCanonical(OTHER_OWNER, valid).status());
        assertEquals(INVALID_SNAPSHOT, fresh.restoreCanonical(OWNER,
                new BodyInventoryCanonicalSnapshot(
                        OTHER_OWNER, valid.stacks(), valid.activeSlot(), false, 6)).status());
        assertEquals(INVALID_SNAPSHOT, fresh.restoreCanonical(OWNER,
                new BodyInventoryCanonicalSnapshot(
                        OWNER, valid.stacks(), valid.activeSlot(), false, -1)).status());
        assertTrue(fresh.canonicalSnapshot(OWNER).stacks().isEmpty());

        BodyInventoryService nonempty = service(new ArrayList<>());
        nonempty.insert(OWNER, new ItemStack(DIRT, 1));
        assertEquals(TARGET_NOT_FRESH,
                nonempty.restoreCanonical(OWNER, valid).status());
        assertEquals(1, nonempty.totalCount(OWNER, DIRT));

        BodyInventoryService allocatorUsed = service(new ArrayList<>());
        var reservation = allocatorUsed.reserve(new InventoryReservationRequest(
                OWNER, BodySlot.LEFT_HAND, InventoryReservationOperation.INSERT,
                new ItemStack(DIRT, 1))).reservation().orElseThrow();
        allocatorUsed.rollback(reservation.id());
        assertEquals(TARGET_NOT_FRESH,
                allocatorUsed.restoreCanonical(OWNER, valid).status());
    }

    @Test
    void captureRejectsPendingReservationsButOmitsTerminalHistory() {
        BodyInventoryService service = service(new ArrayList<>());
        var pending = service.reserve(new InventoryReservationRequest(
                OWNER, BodySlot.LEFT_HAND, InventoryReservationOperation.INSERT,
                new ItemStack(DIRT, 2))).reservation().orElseThrow();

        IllegalStateException failure = assertThrows(
                IllegalStateException.class, () -> service.canonicalSnapshot(OWNER));
        assertTrue(failure.getMessage().contains("pending inventory reservation"));

        service.rollback(pending.id());
        assertEquals(Map.of(), service.canonicalSnapshot(OWNER).stacks());
    }

    @Test
    void canonicalCaptureAndRestoreAreOwnerThreadOnly() throws InterruptedException {
        BodyInventoryService service = service(new ArrayList<>());
        BodyInventoryCanonicalSnapshot snapshot = snapshot(Map.of(), false, 0);
        AtomicReference<Throwable> captureFailure = new AtomicReference<>();
        AtomicReference<Throwable> restoreFailure = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try {
                service.canonicalSnapshot(OWNER);
            } catch (Throwable thrown) {
                captureFailure.set(thrown);
            }
            try {
                service.restoreCanonical(OWNER, snapshot);
            } catch (Throwable thrown) {
                restoreFailure.set(thrown);
            }
        }, "inventory-persistence-worker");

        worker.start();
        worker.join();

        assertTrue(captureFailure.get() instanceof IllegalStateException);
        assertTrue(restoreFailure.get() instanceof IllegalStateException);
    }

    @Test
    void canonicalSnapshotRejectsNullDirectStacksAtItsPublicBoundary() {
        Map<BodySlot, ItemStack> stacks = new HashMap<>();
        stacks.put(BodySlot.LEFT_HAND, null);

        NullPointerException failure = assertThrows(
                NullPointerException.class,
                () -> new BodyInventoryCanonicalSnapshot(
                        OWNER, stacks, BodySlot.LEFT_HAND, false, 0));

        assertEquals("stacks must not contain null values", failure.getMessage());
    }

    @Test
    void reversibleActiveSlotHistoryMakesTargetNonfreshButNoOpSelectionDoesNot() {
        BodyInventoryCanonicalSnapshot empty = snapshot(Map.of(), false, 0);

        BodyInventoryService changed = service(new ArrayList<>());
        changed.selectActiveSlot(OWNER, BodySlot.RIGHT_HAND);
        changed.selectActiveSlot(OWNER, BodySlot.LEFT_HAND);
        assertEquals(TARGET_NOT_FRESH,
                changed.restoreCanonical(OWNER, empty).status());

        BodyInventoryService unchanged = service(new ArrayList<>());
        unchanged.selectActiveSlot(OWNER, BodySlot.LEFT_HAND);
        assertEquals(RESTORED,
                unchanged.restoreCanonical(OWNER, empty).status());
    }

    @Test
    void restorePrebuildsDetachedAggregateAndResultBeforeSinglePublication() {
        List<Event> events = new ArrayList<>();
        AtomicReference<BodyInventoryService> targetRef = new AtomicReference<>();
        AtomicReference<BodyInventory> preparedInventory = new AtomicReference<>();
        AtomicReference<BodyInventoryRestoreResult> preparedResult = new AtomicReference<>();
        AtomicBoolean injectFailure = new AtomicBoolean(true);
        IllegalStateException sentinel = new IllegalStateException("inventory publication probe");
        Map<ResourceLocation, ItemFormDefinition> forms = defaultForms();
        BodyInventoryService target = new BodyInventoryService(
                OWNER,
                id -> Optional.ofNullable(forms.get(id)),
                MainThreadGuard.captureCurrentThread(),
                events::add,
                (detached, success) -> {
                    BodyInventoryCanonicalSnapshot current =
                            targetRef.get().canonicalSnapshot(OWNER);
                    assertTrue(current.stacks().isEmpty());
                    assertEquals(0, current.revision());
                    assertEquals(new ItemStack(DIRT, 4),
                            detached.directStack(BodySlot.LEFT_HAND));
                    assertEquals(9, detached.revision());
                    assertEquals(RESTORED, success.status());
                    preparedInventory.set(detached);
                    preparedResult.set(success);
                    if (injectFailure.getAndSet(false)) {
                        throw sentinel;
                    }
                });
        targetRef.set(target);
        BodyInventoryCanonicalSnapshot snapshot = new BodyInventoryCanonicalSnapshot(
                OWNER,
                Map.of(BodySlot.LEFT_HAND, new ItemStack(DIRT, 4)),
                BodySlot.RIGHT_HAND,
                false,
                9);

        assertSame(sentinel, assertThrows(
                IllegalStateException.class,
                () -> target.restoreCanonical(OWNER, snapshot)));
        assertTrue(target.canonicalSnapshot(OWNER).stacks().isEmpty());
        assertTrue(events.isEmpty());

        BodyInventoryRestoreResult restored = target.restoreCanonical(OWNER, snapshot);

        assertSame(preparedResult.get(), restored,
                "restore must return the exact result built before publication");
        assertEquals(snapshot, target.canonicalSnapshot(OWNER));
        assertTrue(preparedInventory.get() != null);
        assertTrue(events.isEmpty());
    }

    private static BodyInventoryCanonicalSnapshot snapshot(
            Map<BodySlot, ItemStack> stacks,
            boolean twoHanded,
            long revision) {
        return new BodyInventoryCanonicalSnapshot(
                OWNER, stacks, BodySlot.LEFT_HAND, twoHanded, revision);
    }

    private static BodyInventoryService service(List<Event> events) {
        Map<ResourceLocation, ItemFormDefinition> forms = defaultForms();
        return new BodyInventoryService(
                OWNER,
                id -> Optional.ofNullable(forms.get(id)),
                events::add);
    }

    private static Map<ResourceLocation, ItemFormDefinition> defaultForms() {
        return Map.of(
                DIRT, new ItemFormDefinition(DIRT, 64, false, false),
                LEAVES, new ItemFormDefinition(LEAVES, 64, true, false),
                HEAVY, new ItemFormDefinition(HEAVY, 16, false, true));
    }
}

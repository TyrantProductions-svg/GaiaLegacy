package com.gaia.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gaia.blocks.ItemFormDefinition;
import com.overlord.assets.ResourceLocation;
import com.overlord.event.Event;
import com.overlord.interaction.api.EntityRef;
import com.overlord.inventory.api.ActiveBodySlotChanged;
import com.overlord.inventory.api.BodySlot;
import com.overlord.inventory.api.InventoryChangeRequest;
import com.overlord.inventory.api.InventoryChangeResult;
import com.overlord.inventory.api.InventoryChanged;
import com.overlord.inventory.api.InventoryEventDispatchException;
import com.overlord.inventory.api.InventoryReservationId;
import com.overlord.inventory.api.InventoryReservationOperation;
import com.overlord.inventory.api.InventoryReservationRequest;
import com.overlord.inventory.api.InventoryReservationResult;
import com.overlord.inventory.api.InventoryReserveResult;
import com.overlord.inventory.api.ItemStack;
import com.overlord.inventory.api.ItemStackView;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

class BodyInventoryServiceTest {
    private static final EntityRef OWNER = new EntityRef(7);
    private static final ResourceLocation DIRT = ResourceLocation.parse("gaia:dirt");
    private static final ResourceLocation STONE = ResourceLocation.parse("gaia:stone");
    private static final ResourceLocation LEAVES = ResourceLocation.parse("gaia:oak_leaves");
    private static final ResourceLocation HEAVY = ResourceLocation.parse("gaia:heavy_test");

    @Test
    void insertionMergesBeforeUsingTheNextLegalEmptySlot() {
        BodyInventoryService service = service(defaultForms(), new ArrayList<>());

        assertEquals(InventoryInsertResult.Status.INSERTED,
                service.insert(OWNER, new ItemStack(DIRT, 60)).status());
        InventoryInsertResult result = service.insert(OWNER, new ItemStack(DIRT, 10));

        assertEquals(InventoryInsertResult.Status.INSERTED, result.status());
        assertEquals(64, count(service, BodySlot.LEFT_HAND));
        assertEquals(6, count(service, BodySlot.RIGHT_HAND));
        assertEquals(70, service.totalCount(OWNER, DIRT));
        assertTrue(result.remainder().isEmpty());
    }

    @Test
    void mouthUsesItemFormEligibilityAndFailuresPreserveContents() {
        BodyInventoryService service = service(defaultForms(), new ArrayList<>());

        InventoryChangeResult rejected = service.replaceSlot(new InventoryChangeRequest(
                OWNER, BodySlot.MOUTH, 0, Optional.of(new ItemStack(STONE, 1))));
        InventoryChangeResult accepted = service.replaceSlot(new InventoryChangeRequest(
                OWNER, BodySlot.MOUTH, 0, Optional.of(new ItemStack(LEAVES, 1))));

        assertEquals(InventoryChangeResult.Status.INVALID_STACK, rejected.status());
        assertEquals(InventoryChangeResult.Status.APPLIED, accepted.status());
        assertEquals(1, count(service, BodySlot.MOUTH));
        assertEquals(0, service.totalCount(OWNER, STONE));
    }

    @Test
    void twoHandedStacksOccupyBothHandsAtomicallyAndCountOnce() {
        BodyInventoryService service = service(defaultForms(), new ArrayList<>());

        InventoryChangeResult equipped = service.replaceSlot(new InventoryChangeRequest(
                OWNER, BodySlot.LEFT_HAND, 0, Optional.of(new ItemStack(HEAVY, 2))));
        InventoryInsertResult rejected = service.insert(OWNER, new ItemStack(DIRT, 1));

        assertEquals(InventoryChangeResult.Status.APPLIED, equipped.status());
        assertEquals(2, count(service, BodySlot.LEFT_HAND));
        assertEquals(2, count(service, BodySlot.RIGHT_HAND));
        assertEquals(2, service.totalCount(OWNER, HEAVY));
        assertEquals(InventoryInsertResult.Status.REJECTED, rejected.status());
        InventoryChangeResult released = service.replaceSlot(new InventoryChangeRequest(
                OWNER, BodySlot.RIGHT_HAND, 1, Optional.empty()));
        assertEquals(InventoryChangeResult.Status.APPLIED, released.status());
        assertEquals(0, service.totalCount(OWNER, HEAVY));
    }

    @Test
    void reservationsSurviveOrdinaryChangesAndCommitOnlyOnce() {
        BodyInventoryService service = service(defaultForms(), new ArrayList<>());
        service.insert(OWNER, new ItemStack(DIRT, 5));
        InventoryReserveResult reserved = service.reserve(new InventoryReservationRequest(
                OWNER,
                BodySlot.LEFT_HAND,
                InventoryReservationOperation.EXTRACT,
                new ItemStack(DIRT, 3)));

        service.insert(OWNER, new ItemStack(STONE, 1));
        InventoryReservationId id = reserved.reservation().orElseThrow().id();
        InventoryReservationResult committed = service.commit(id);
        InventoryReservationResult repeated = service.commit(id);

        assertEquals(InventoryReserveResult.Status.RESERVED, reserved.status());
        assertEquals(InventoryReservationResult.Status.COMMITTED, committed.status());
        assertEquals(InventoryReservationResult.Status.ALREADY_COMMITTED, repeated.status());
        assertEquals(2, service.totalCount(OWNER, DIRT));
        assertEquals(1, service.totalCount(OWNER, STONE));
    }

    @Test
    void snapshotsAndEventsAreReadOnlyValueProjections() {
        List<Event> events = new ArrayList<>();
        BodyInventoryService service = service(defaultForms(), events);

        service.insert(OWNER, new ItemStack(DIRT, 1));
        service.selectActiveSlot(OWNER, BodySlot.RIGHT_HAND);

        Optional<ItemStackView> stack = service.snapshot(OWNER).orElseThrow().stack(BodySlot.LEFT_HAND);
        assertEquals(new ItemStack(DIRT, 1), stack.orElseThrow());
        assertEquals(2, events.size());
        assertTrue(events.get(0) instanceof InventoryChanged);
        InventoryChanged contents = (InventoryChanged) events.get(0);
        assertEquals(OWNER, contents.owner());
        assertEquals(1, contents.revision());
        assertTrue(events.get(1) instanceof ActiveBodySlotChanged);
        ActiveBodySlotChanged selection = (ActiveBodySlotChanged) events.get(1);
        assertEquals(OWNER, selection.owner());
        assertEquals(BodySlot.LEFT_HAND, selection.previousSlot());
        assertEquals(BodySlot.RIGHT_HAND, selection.activeSlot());
        assertFalse(service.viewModel(OWNER).orElseThrow().inventory().stack(BodySlot.MOUTH).isPresent());
    }

    @Test
    void noOpAndFailureResultsNeverPublishCommittedNotifications() {
        List<Event> events = new ArrayList<>();
        BodyInventoryService service = service(defaultForms(), events);
        EntityRef unknownOwner = new EntityRef(99);
        ResourceLocation unknownItem = ResourceLocation.parse("gaia:unknown_item");

        assertEquals(InventoryInsertResult.Status.UNKNOWN_OWNER,
                service.insert(unknownOwner, new ItemStack(DIRT, 1)).status());
        assertEquals(InventoryInsertResult.Status.REJECTED,
                service.insert(OWNER, new ItemStack(unknownItem, 1)).status());
        assertEquals(InventoryExtractResult.Status.UNKNOWN_OWNER,
                service.extract(unknownOwner, BodySlot.LEFT_HAND, 1).status());
        assertEquals(InventoryExtractResult.Status.INVALID_COUNT,
                service.extract(OWNER, BodySlot.LEFT_HAND, 0).status());
        assertEquals(InventoryExtractResult.Status.EMPTY_SLOT,
                service.extract(OWNER, BodySlot.LEFT_HAND, 1).status());
        assertEquals(InventoryOperationResult.Status.UNKNOWN_OWNER,
                service.swap(unknownOwner, BodySlot.LEFT_HAND, BodySlot.RIGHT_HAND).status());
        assertEquals(InventoryOperationResult.Status.NO_CHANGE,
                service.swap(OWNER, BodySlot.LEFT_HAND, BodySlot.LEFT_HAND).status());
        assertEquals(InventoryOperationResult.Status.NO_CHANGE,
                service.swap(OWNER, BodySlot.LEFT_HAND, BodySlot.RIGHT_HAND).status());
        assertEquals(ActiveSlotChangeResult.Status.UNKNOWN_OWNER,
                service.selectActiveSlot(unknownOwner, BodySlot.RIGHT_HAND).status());
        assertEquals(ActiveSlotChangeResult.Status.UNCHANGED,
                service.selectActiveSlot(OWNER, BodySlot.LEFT_HAND).status());
        assertEquals(ActiveSlotChangeResult.Status.UNCHANGED,
                service.cycleActiveSlot(OWNER, 0).status());
        assertEquals(InventoryChangeResult.Status.UNKNOWN_OWNER,
                service.replaceSlot(new InventoryChangeRequest(
                        unknownOwner, BodySlot.LEFT_HAND, 0,
                        Optional.of(new ItemStack(DIRT, 1)))).status());
        assertEquals(InventoryChangeResult.Status.INVALID_STACK,
                service.replaceSlot(new InventoryChangeRequest(
                        OWNER, BodySlot.MOUTH, 0,
                        Optional.of(new ItemStack(STONE, 1)))).status());
        assertEquals(InventoryReserveResult.Status.UNKNOWN_OWNER,
                service.reserve(new InventoryReservationRequest(
                        unknownOwner, BodySlot.LEFT_HAND,
                        InventoryReservationOperation.EXTRACT,
                        new ItemStack(DIRT, 1))).status());
        assertEquals(InventoryReserveResult.Status.INVALID_STACK,
                service.reserve(new InventoryReservationRequest(
                        OWNER, BodySlot.LEFT_HAND,
                        InventoryReservationOperation.INSERT,
                        new ItemStack(unknownItem, 1))).status());
        assertEquals(InventoryReservationResult.Status.UNKNOWN_RESERVATION,
                service.commit(new InventoryReservationId(999)).status());
        assertEquals(InventoryReservationResult.Status.UNKNOWN_RESERVATION,
                service.rollback(new InventoryReservationId(999)).status());

        assertTrue(events.isEmpty());
        assertEquals(0, service.snapshot(OWNER).orElseThrow().revision());
        assertEquals(0, service.totalCount(OWNER, DIRT));
    }

    @Test
    void reservationLifecyclePublishesOnlyOneOrderedCommitNotification() {
        List<Event> events = new ArrayList<>();
        BodyInventoryService service = service(defaultForms(), events);
        service.insert(OWNER, new ItemStack(DIRT, 5));
        events.clear();

        InventoryReserveResult reserved = service.reserve(new InventoryReservationRequest(
                OWNER, BodySlot.LEFT_HAND, InventoryReservationOperation.EXTRACT,
                new ItemStack(DIRT, 2)));
        InventoryReservationId id = reserved.reservation().orElseThrow().id();
        assertTrue(events.isEmpty());

        assertEquals(InventoryReservationResult.Status.COMMITTED,
                service.commit(id).status());
        assertEquals(InventoryReservationResult.Status.ALREADY_COMMITTED,
                service.commit(id).status());
        assertEquals(InventoryReservationResult.Status.TERMINAL_CONFLICT,
                service.rollback(id).status());

        assertEquals(1, events.size());
        assertTrue(events.get(0) instanceof InventoryChanged);
        InventoryChanged committed = (InventoryChanged) events.get(0);
        assertEquals(OWNER, committed.owner());
        assertEquals(2, committed.revision());
        assertEquals(3, service.totalCount(OWNER, DIRT));
    }

    @Test
    void readViewsMustBeCapturedOnTheInventoryOwnerThread() {
        BodyInventoryService service = service(defaultForms(), new ArrayList<>());
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            ExecutionException snapshotFailure = assertThrows(
                    ExecutionException.class,
                    () -> worker.submit(() -> service.snapshot(OWNER)).get());
            ExecutionException viewFailure = assertThrows(
                    ExecutionException.class,
                    () -> worker.submit(() -> service.viewModel(OWNER)).get());
            ExecutionException totalFailure = assertThrows(
                    ExecutionException.class,
                    () -> worker.submit(() -> service.totalCount(OWNER, DIRT)).get());

            assertTrue(snapshotFailure.getCause() instanceof IllegalStateException);
            assertTrue(viewFailure.getCause() instanceof IllegalStateException);
            assertTrue(totalFailure.getCause() instanceof IllegalStateException);
        } finally {
            worker.shutdownNow();
        }
    }

    @Test
    void postCommitPublicationFailureReportsAppliedStateWithoutRollback() {
        BodyInventoryService service = new BodyInventoryService(
                OWNER,
                id -> Optional.ofNullable(defaultForms().get(id)),
                event -> {
                    throw new IllegalStateException("simulated publication failure");
                });

        InventoryEventDispatchException failure = assertThrows(
                InventoryEventDispatchException.class,
                () -> service.insert(OWNER, new ItemStack(DIRT, 1)));

        assertTrue(failure.stateChangeApplied());
        assertTrue(failure.notification() instanceof InventoryChanged);
        assertEquals(1, ((InventoryChanged) failure.notification()).revision());
        assertEquals(1, service.totalCount(OWNER, DIRT));
        assertEquals(1, service.snapshot(OWNER).orElseThrow().revision());
    }

    @Test
    void extractSelectionAndReservationCommitExposeAppliedPublicationFailures() {
        AtomicBoolean failPublication = new AtomicBoolean();
        BodyInventoryService service = new BodyInventoryService(
                OWNER,
                id -> Optional.ofNullable(defaultForms().get(id)),
                event -> {
                    if (failPublication.get()) {
                        throw new IllegalStateException("simulated publication failure");
                    }
                });
        service.insert(OWNER, new ItemStack(DIRT, 5));

        failPublication.set(true);
        InventoryEventDispatchException extractFailure = assertThrows(
                InventoryEventDispatchException.class,
                () -> service.extract(OWNER, BodySlot.LEFT_HAND, 1));
        InventoryEventDispatchException selectionFailure = assertThrows(
                InventoryEventDispatchException.class,
                () -> service.selectActiveSlot(OWNER, BodySlot.RIGHT_HAND));

        failPublication.set(false);
        InventoryReserveResult reserved = service.reserve(new InventoryReservationRequest(
                OWNER, BodySlot.LEFT_HAND, InventoryReservationOperation.EXTRACT,
                new ItemStack(DIRT, 2)));
        failPublication.set(true);
        InventoryEventDispatchException commitFailure = assertThrows(
                InventoryEventDispatchException.class,
                () -> service.commit(reserved.reservation().orElseThrow().id()));

        assertTrue(extractFailure.notification() instanceof InventoryChanged);
        assertTrue(selectionFailure.notification() instanceof ActiveBodySlotChanged);
        assertTrue(commitFailure.notification() instanceof InventoryChanged);
        assertEquals(BodySlot.RIGHT_HAND,
                service.viewModel(OWNER).orElseThrow().activeSlot());
        assertEquals(2, service.totalCount(OWNER, DIRT));
        assertEquals(InventoryReservationResult.Status.ALREADY_COMMITTED,
                service.commit(reserved.reservation().orElseThrow().id()).status());
    }

    @Test
    void insertReservationsProtectCapacityAndRollbackIsTerminallyIdempotent() {
        BodyInventoryService service = service(defaultForms(), new ArrayList<>());
        InventoryReserveResult reserved = service.reserve(new InventoryReservationRequest(
                OWNER, BodySlot.LEFT_HAND, InventoryReservationOperation.INSERT,
                new ItemStack(DIRT, 4)));

        service.insert(OWNER, new ItemStack(STONE, 1));
        InventoryReservationId id = reserved.reservation().orElseThrow().id();
        assertEquals(InventoryReservationResult.Status.COMMITTED, service.commit(id).status());
        assertEquals(4, service.totalCount(OWNER, DIRT));

        InventoryReserveResult second = service.reserve(new InventoryReservationRequest(
                OWNER, BodySlot.RIGHT_HAND, InventoryReservationOperation.EXTRACT,
                new ItemStack(STONE, 1)));
        InventoryReservationId secondId = second.reservation().orElseThrow().id();
        assertEquals(InventoryReservationResult.Status.ROLLED_BACK, service.rollback(secondId).status());
        assertEquals(InventoryReservationResult.Status.ALREADY_ROLLED_BACK, service.rollback(secondId).status());
        assertEquals(1, service.totalCount(OWNER, STONE));
    }

    @Test
    void twoHandedInsertReservationCommitsTheReservedAtomicOccupancy() {
        BodyInventoryService service = service(defaultForms(), new ArrayList<>());
        InventoryReserveResult reserved = service.reserve(new InventoryReservationRequest(
                OWNER, BodySlot.RIGHT_HAND, InventoryReservationOperation.INSERT,
                new ItemStack(HEAVY, 2)));

        InventoryReservationResult committed = service.commit(
                reserved.reservation().orElseThrow().id());

        assertEquals(InventoryReserveResult.Status.RESERVED, reserved.status());
        assertEquals(InventoryReservationResult.Status.COMMITTED, committed.status());
        assertEquals(new ItemStack(HEAVY, 2), service.snapshot(OWNER).orElseThrow()
                .stack(BodySlot.LEFT_HAND).orElseThrow());
        assertEquals(new ItemStack(HEAVY, 2), service.snapshot(OWNER).orElseThrow()
                .stack(BodySlot.RIGHT_HAND).orElseThrow());
        assertEquals(2, service.totalCount(OWNER, HEAVY));
        assertEquals(InventoryInsertResult.Status.REJECTED,
                service.insert(OWNER, new ItemStack(DIRT, 1)).status());
    }

    @Test
    void swapAndPartialExtractionPreserveTheCombinedItemCount() {
        BodyInventoryService service = service(defaultForms(), new ArrayList<>());
        service.insert(OWNER, new ItemStack(DIRT, 2));
        service.insert(OWNER, new ItemStack(STONE, 2));

        assertEquals(InventoryOperationResult.Status.APPLIED,
                service.swap(OWNER, BodySlot.LEFT_HAND, BodySlot.RIGHT_HAND).status());
        InventoryExtractResult extraction = service.extract(OWNER, BodySlot.LEFT_HAND, 5);

        assertEquals(InventoryExtractResult.Status.PARTIALLY_EXTRACTED, extraction.status());
        assertEquals(2, extraction.extracted().orElseThrow().count());
        assertEquals(2, service.totalCount(OWNER, DIRT));
        assertEquals(0, service.totalCount(OWNER, STONE));
    }

    @Test
    void splitMovesAnExactCountToTheRequestedSlotAtomically() {
        List<Event> events = new ArrayList<>();
        BodyInventoryService service = service(defaultForms(), events);
        service.insert(OWNER, new ItemStack(DIRT, 64));
        events.clear();

        InventorySplitResult result = service.split(
                OWNER, BodySlot.LEFT_HAND, BodySlot.RIGHT_HAND, 32);

        assertEquals(InventorySplitResult.Status.SPLIT, result.status());
        assertEquals(new ItemStack(DIRT, 32), result.moved().orElseThrow());
        assertEquals(32, count(service, BodySlot.LEFT_HAND));
        assertEquals(32, count(service, BodySlot.RIGHT_HAND));
        assertEquals(64, service.totalCount(OWNER, DIRT));
        assertEquals(1, events.size());
        assertTrue(events.get(0) instanceof InventoryChanged);
        assertEquals(2, ((InventoryChanged) events.get(0)).revision());
    }

    @Test
    void splitCanMoveTheCompleteSourceToEmptyOrCompatibleDestinations() {
        List<Event> emptyDestinationEvents = new ArrayList<>();
        BodyInventoryService toEmpty = service(defaultForms(), emptyDestinationEvents);
        toEmpty.insert(OWNER, new ItemStack(DIRT, 10));
        emptyDestinationEvents.clear();

        InventorySplitResult moved = toEmpty.split(
                OWNER, BodySlot.LEFT_HAND, BodySlot.RIGHT_HAND, 10);

        assertEquals(InventorySplitResult.Status.SPLIT, moved.status());
        assertEquals(new ItemStack(DIRT, 10), moved.moved().orElseThrow());
        assertEquals(0, count(toEmpty, BodySlot.LEFT_HAND));
        assertEquals(10, count(toEmpty, BodySlot.RIGHT_HAND));
        assertEquals(10, toEmpty.totalCount(OWNER, DIRT));
        assertEquals(1, emptyDestinationEvents.size());
        assertEquals(2, ((InventoryChanged) emptyDestinationEvents.get(0)).revision());

        List<Event> mergeEvents = new ArrayList<>();
        BodyInventoryService toCompatible = service(defaultForms(), mergeEvents);
        toCompatible.replaceSlot(new InventoryChangeRequest(
                OWNER, BodySlot.LEFT_HAND, 0,
                Optional.of(new ItemStack(DIRT, 10))));
        toCompatible.replaceSlot(new InventoryChangeRequest(
                OWNER, BodySlot.RIGHT_HAND, 1,
                Optional.of(new ItemStack(DIRT, 20))));
        mergeEvents.clear();

        InventorySplitResult merged = toCompatible.split(
                OWNER, BodySlot.LEFT_HAND, BodySlot.RIGHT_HAND, 10);

        assertEquals(InventorySplitResult.Status.SPLIT, merged.status());
        assertEquals(0, count(toCompatible, BodySlot.LEFT_HAND));
        assertEquals(30, count(toCompatible, BodySlot.RIGHT_HAND));
        assertEquals(30, toCompatible.totalCount(OWNER, DIRT));
        assertEquals(1, mergeEvents.size());
        assertEquals(3, ((InventoryChanged) mergeEvents.get(0)).revision());
    }

    @Test
    void splitFailuresAreClosedDoNotPublishAndConserveCounts() {
        List<Event> events = new ArrayList<>();
        BodyInventoryService service = service(defaultForms(), events);

        assertEquals(InventorySplitResult.Status.UNKNOWN_OWNER,
                service.split(new EntityRef(99), BodySlot.LEFT_HAND,
                        BodySlot.RIGHT_HAND, 1).status());
        assertEquals(InventorySplitResult.Status.EMPTY_SOURCE,
                service.split(OWNER, BodySlot.LEFT_HAND,
                        BodySlot.RIGHT_HAND, 1).status());

        service.insert(OWNER, new ItemStack(DIRT, 64));
        events.clear();
        assertEquals(InventorySplitResult.Status.INVALID_COUNT,
                service.split(OWNER, BodySlot.LEFT_HAND,
                        BodySlot.RIGHT_HAND, 0).status());
        assertEquals(InventorySplitResult.Status.SAME_SLOT,
                service.split(OWNER, BodySlot.LEFT_HAND,
                        BodySlot.LEFT_HAND, 1).status());
        assertEquals(InventorySplitResult.Status.SOURCE_TOO_SMALL,
                service.split(OWNER, BodySlot.LEFT_HAND,
                        BodySlot.RIGHT_HAND, 65).status());
        assertEquals(InventorySplitResult.Status.REJECTED,
                service.split(OWNER, BodySlot.LEFT_HAND,
                        BodySlot.MOUTH, 1).status());

        InventoryReserveResult reservation = service.reserve(new InventoryReservationRequest(
                OWNER, BodySlot.LEFT_HAND, InventoryReservationOperation.EXTRACT,
                new ItemStack(DIRT, 1)));
        assertEquals(InventorySplitResult.Status.RESERVED,
                service.split(OWNER, BodySlot.LEFT_HAND,
                        BodySlot.RIGHT_HAND, 1).status());
        service.rollback(reservation.reservation().orElseThrow().id());

        service.insert(OWNER, new ItemStack(DIRT, 64));
        events.clear();
        assertEquals(InventorySplitResult.Status.DESTINATION_FULL,
                service.split(OWNER, BodySlot.LEFT_HAND,
                        BodySlot.RIGHT_HAND, 1).status());
        assertEquals(128, service.totalCount(OWNER, DIRT));
        assertTrue(events.isEmpty());
    }

    @Test
    void fullHandsReturnAnExplicitInsertionRemainder() {
        BodyInventoryService service = service(defaultForms(), new ArrayList<>());

        InventoryInsertResult result = service.insert(OWNER, new ItemStack(DIRT, 200));

        assertEquals(InventoryInsertResult.Status.PARTIALLY_INSERTED, result.status());
        assertEquals(new ItemStack(DIRT, 72), result.remainder().orElseThrow());
        assertEquals(128, service.totalCount(OWNER, DIRT));
    }

    @Test
    void twoHandedReservationsLockAndReleaseBothHandsAsOneUnit() {
        BodyInventoryService service = service(defaultForms(), new ArrayList<>());
        service.replaceSlot(new InventoryChangeRequest(
                OWNER, BodySlot.LEFT_HAND, 0, Optional.of(new ItemStack(HEAVY, 2))));
        InventoryReserveResult reservation = service.reserve(new InventoryReservationRequest(
                OWNER, BodySlot.RIGHT_HAND, InventoryReservationOperation.EXTRACT,
                new ItemStack(HEAVY, 1)));

        assertEquals(InventoryReserveResult.Status.RESERVED, reservation.status());
        assertEquals(InventoryChangeResult.Status.CONFLICT,
                service.replaceSlot(new InventoryChangeRequest(
                        OWNER, BodySlot.LEFT_HAND, 1, Optional.empty())).status());
        assertEquals(InventoryReservationResult.Status.ROLLED_BACK,
                service.rollback(reservation.reservation().orElseThrow().id()).status());
        assertEquals(InventoryChangeResult.Status.APPLIED,
                service.replaceSlot(new InventoryChangeRequest(
                        OWNER, BodySlot.RIGHT_HAND, 1, Optional.empty())).status());
    }

    @Test
    void mouthReservationProtectsOnlyTheMouthWhileTwoHandedItemOccupiesHands() {
        BodyInventoryService service = service(defaultForms(), new ArrayList<>());
        service.replaceSlot(new InventoryChangeRequest(
                OWNER, BodySlot.LEFT_HAND, 0, Optional.of(new ItemStack(HEAVY, 2))));
        service.replaceSlot(new InventoryChangeRequest(
                OWNER, BodySlot.MOUTH, 1, Optional.of(new ItemStack(LEAVES, 1))));

        InventoryReserveResult mouth = service.reserve(new InventoryReservationRequest(
                OWNER, BodySlot.MOUTH, InventoryReservationOperation.EXTRACT,
                new ItemStack(LEAVES, 1)));
        long revision = service.snapshot(OWNER).orElseThrow().revision();
        InventoryChangeResult mouthReplacement = service.replaceSlot(new InventoryChangeRequest(
                OWNER, BodySlot.MOUTH, revision, Optional.empty()));
        InventoryReserveResult hands = service.reserve(new InventoryReservationRequest(
                OWNER, BodySlot.RIGHT_HAND, InventoryReservationOperation.EXTRACT,
                new ItemStack(HEAVY, 1)));

        assertEquals(InventoryReserveResult.Status.RESERVED, mouth.status());
        assertEquals(InventoryChangeResult.Status.CONFLICT, mouthReplacement.status());
        assertEquals(InventoryReserveResult.Status.RESERVED, hands.status());
        assertEquals(InventoryReservationResult.Status.ROLLED_BACK,
                service.rollback(hands.reservation().orElseThrow().id()).status());
        assertEquals(InventoryReservationResult.Status.COMMITTED,
                service.commit(mouth.reservation().orElseThrow().id()).status());
        assertEquals(2, service.totalCount(OWNER, HEAVY));
        assertEquals(0, service.totalCount(OWNER, LEAVES));
    }

    private static BodyInventoryService service(
            Map<ResourceLocation, ItemFormDefinition> forms, List<Event> events) {
        return new BodyInventoryService(
                OWNER,
                id -> Optional.ofNullable(forms.get(id)),
                events::add);
    }

    private static Map<ResourceLocation, ItemFormDefinition> defaultForms() {
        return Map.of(
                DIRT, new ItemFormDefinition(DIRT, 64, false, false),
                STONE, new ItemFormDefinition(STONE, 64, false, false),
                LEAVES, new ItemFormDefinition(LEAVES, 64, true, false),
                HEAVY, new ItemFormDefinition(HEAVY, 16, false, true));
    }

    private static int count(BodyInventoryService service, BodySlot slot) {
        return service.snapshot(OWNER)
                .orElseThrow()
                .stack(slot)
                .map(ItemStackView::count)
                .orElse(0);
    }
}

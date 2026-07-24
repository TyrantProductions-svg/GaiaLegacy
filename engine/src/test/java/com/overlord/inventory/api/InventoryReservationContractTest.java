package com.overlord.inventory.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.overlord.assets.ResourceLocation;
import com.overlord.interaction.api.EntityRef;
import com.overlord.inventory.testing.FakeInventoryReservationService;
import com.overlord.inventory.testing.TestInventoryView;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class InventoryReservationContractTest {
    private static final EntityRef OWNER = new EntityRef(7);
    private static final EntityRef OTHER_OWNER = new EntityRef(8);
    private static final ItemStack STONE =
            new ItemStack(ResourceLocation.parse("gaia:stone"), 5);

    @Test
    void reservationIdsRejectNegativeValues() {
        assertEquals(0, new InventoryReservationId(0).value());
        assertThrows(
                IllegalArgumentException.class,
                () -> new InventoryReservationId(-1));
    }

    @Test
    void reservationRequestsRejectNullFields() {
        assertThrows(
                NullPointerException.class,
                () -> new InventoryReservationRequest(null, BodySlot.LEFT_HAND,
                        InventoryReservationOperation.INSERT, STONE));
        assertThrows(
                NullPointerException.class,
                () -> new InventoryReservationRequest(OWNER, null,
                        InventoryReservationOperation.INSERT, STONE));
        assertThrows(
                NullPointerException.class,
                () -> new InventoryReservationRequest(OWNER, BodySlot.LEFT_HAND,
                        null, STONE));
        assertThrows(
                NullPointerException.class,
                () -> new InventoryReservationRequest(OWNER, BodySlot.LEFT_HAND,
                        InventoryReservationOperation.INSERT, null));
    }

    @Test
    void reservationsRequireMatchingIdentityAndBoundedCount() {
        InventoryReservationRequest request = request(InventoryReservationOperation.INSERT);

        assertThrows(
                NullPointerException.class,
                () -> new InventoryReservation(null, request, STONE));
        assertThrows(
                NullPointerException.class,
                () -> new InventoryReservation(new InventoryReservationId(1), null, STONE));
        assertThrows(
                NullPointerException.class,
                () -> new InventoryReservation(new InventoryReservationId(1), request, null));
        assertThrows(
                IllegalArgumentException.class,
                () -> new InventoryReservation(
                        new InventoryReservationId(1),
                        request,
                        new ItemStack(ResourceLocation.parse("gaia:dirt"), 1)));
        assertThrows(
                IllegalArgumentException.class,
                () -> new InventoryReservation(
                        new InventoryReservationId(1),
                        request,
                        new ItemStack(STONE.itemId(), 6)));
    }

    @Test
    void fullReservationRequiresTheEntireRequestWithoutRemainder() {
        InventoryReservationRequest request = request(InventoryReservationOperation.INSERT);
        InventoryReservation reservation =
                new InventoryReservation(new InventoryReservationId(1), request, STONE);
        TestInventoryView inventory = inventory(3);

        InventoryReserveResult result = new InventoryReserveResult(
                request,
                InventoryReserveResult.Status.RESERVED,
                Optional.of(reservation),
                Optional.empty(),
                Optional.of(inventory));

        assertEquals(Optional.of(reservation), result.reservation());
        assertThrows(
                IllegalArgumentException.class,
                () -> new InventoryReserveResult(
                        request,
                        InventoryReserveResult.Status.RESERVED,
                        Optional.of(new InventoryReservation(
                                new InventoryReservationId(2), request,
                                new ItemStack(STONE.itemId(), 4))),
                        Optional.empty(),
                        Optional.of(inventory)));
    }

    @Test
    void partialReservationRequiresMatchingPartsThatSumToTheRequest() {
        InventoryReservationRequest request = request(InventoryReservationOperation.EXTRACT);
        TestInventoryView inventory = inventory(3);
        InventoryReservation reservation = new InventoryReservation(
                new InventoryReservationId(1), request,
                new ItemStack(STONE.itemId(), 2));

        InventoryReserveResult result = new InventoryReserveResult(
                request,
                InventoryReserveResult.Status.PARTIALLY_RESERVED,
                Optional.of(reservation),
                Optional.of(new ItemStack(STONE.itemId(), 3)),
                Optional.of(inventory));

        assertEquals(Optional.of(new ItemStack(STONE.itemId(), 3)), result.remainder());
        assertThrows(
                IllegalArgumentException.class,
                () -> new InventoryReserveResult(
                        request,
                        InventoryReserveResult.Status.PARTIALLY_RESERVED,
                        Optional.of(reservation),
                        Optional.of(new ItemStack(STONE.itemId(), 2)),
                        Optional.of(inventory)));
        assertThrows(
                IllegalArgumentException.class,
                () -> new InventoryReserveResult(
                        request,
                        InventoryReserveResult.Status.PARTIALLY_RESERVED,
                        Optional.of(reservation),
                        Optional.of(new ItemStack(ResourceLocation.parse("gaia:dirt"), 3)),
                        Optional.of(inventory)));
    }

    @Test
    void rejectedReserveResultsCarryTheFullRequestRemainder() {
        InventoryReservationRequest request = request(InventoryReservationOperation.INSERT);
        TestInventoryView inventory = inventory(3);

        for (InventoryReserveResult.Status status : new InventoryReserveResult.Status[] {
                InventoryReserveResult.Status.REJECTED,
                InventoryReserveResult.Status.INVALID_STACK }) {
            assertEquals(
                    Optional.of(STONE),
                    new InventoryReserveResult(
                            request, status, Optional.empty(), Optional.of(STONE),
                            Optional.of(inventory)).remainder());
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new InventoryReserveResult(
                            request, status, Optional.empty(),
                            Optional.of(new ItemStack(STONE.itemId(), 4)),
                            Optional.of(inventory)));
        }
        assertEquals(
                Optional.empty(),
                new InventoryReserveResult(
                        request,
                        InventoryReserveResult.Status.UNKNOWN_OWNER,
                        Optional.empty(),
                        Optional.of(STONE),
                        Optional.empty()).inventory());
        assertThrows(
                IllegalArgumentException.class,
                () -> new InventoryReserveResult(
                        request,
                        InventoryReserveResult.Status.UNKNOWN_OWNER,
                        Optional.empty(),
                        Optional.of(new ItemStack(STONE.itemId(), 4)),
                        Optional.empty()));
    }

    @Test
    void reserveResultsRejectSnapshotsOwnedByAnotherEntity() {
        InventoryReservationRequest request =
                request(InventoryReservationOperation.INSERT);
        TestInventoryView foreignInventory = inventory(OTHER_OWNER, 3);
        InventoryReservation fullReservation =
                new InventoryReservation(
                        new InventoryReservationId(1), request, STONE);
        InventoryReservation partialReservation =
                new InventoryReservation(
                        new InventoryReservationId(2),
                        request,
                        new ItemStack(STONE.itemId(), 2));

        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new InventoryReserveResult(
                                request,
                                InventoryReserveResult.Status.RESERVED,
                                Optional.of(fullReservation),
                                Optional.empty(),
                                Optional.of(foreignInventory)));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new InventoryReserveResult(
                                request,
                                InventoryReserveResult.Status.PARTIALLY_RESERVED,
                                Optional.of(partialReservation),
                                Optional.of(
                                        new ItemStack(
                                                STONE.itemId(), 3)),
                                Optional.of(foreignInventory)));
        for (InventoryReserveResult.Status status :
                new InventoryReserveResult.Status[] {
                    InventoryReserveResult.Status.REJECTED,
                    InventoryReserveResult.Status.INVALID_STACK
                }) {
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            new InventoryReserveResult(
                                    request,
                                    status,
                                    Optional.empty(),
                                    Optional.of(STONE),
                                    Optional.of(foreignInventory)));
        }
    }

    @Test
    void reservationResultsRequireSnapshotsForKnownReservations() {
        InventoryReservationId id = new InventoryReservationId(3);

        assertThrows(
                IllegalArgumentException.class,
                () -> new InventoryReservationResult(
                        id,
                        InventoryReservationResult.Status.COMMITTED,
                        Optional.empty()));
        assertEquals(
                Optional.empty(),
                new InventoryReservationResult(
                        id,
                        InventoryReservationResult.Status.UNKNOWN_RESERVATION,
                        Optional.empty()).inventory());
        assertThrows(
                IllegalArgumentException.class,
                () -> new InventoryReservationResult(
                        id,
                        InventoryReservationResult.Status.ROLLED_BACK,
                        Optional.of(inventory(-1))));
    }

    @Test
    void fakePreservesInsertAndExtractOperationsAcrossReservations() {
        FakeInventoryReservationService service =
                new FakeInventoryReservationService(Optional.of(inventory(1)));

        InventoryReserveResult insert = service.reserve(request(InventoryReservationOperation.INSERT));
        InventoryReserveResult extract = service.reserve(request(InventoryReservationOperation.EXTRACT));

        assertEquals(InventoryReservationOperation.INSERT,
                insert.reservation().orElseThrow().request().operation());
        assertEquals(InventoryReservationOperation.EXTRACT,
                extract.reservation().orElseThrow().request().operation());
    }

    @Test
    void fakeReturnsFullPartialAndRejectedReservationsFromTheConfiguredLimit() {
        FakeInventoryReservationService service =
                new FakeInventoryReservationService(Optional.of(inventory(1)));

        service.setNextReservationLimit(5);
        assertEquals(InventoryReserveResult.Status.RESERVED,
                service.reserve(request(InventoryReservationOperation.INSERT)).status());
        service.setNextReservationLimit(2);
        InventoryReserveResult partial = service.reserve(request(InventoryReservationOperation.INSERT));
        assertEquals(InventoryReserveResult.Status.PARTIALLY_RESERVED, partial.status());
        assertEquals(2, partial.reservation().orElseThrow().reserved().count());
        assertEquals(3, partial.remainder().orElseThrow().count());
        service.setNextReservationLimit(0);
        assertEquals(InventoryReserveResult.Status.REJECTED,
                service.reserve(request(InventoryReservationOperation.INSERT)).status());
    }

    @Test
    void fakeConsumesTheNextReservationLimitForAnUnknownOwnerAttempt() {
        FakeInventoryReservationService service =
                new FakeInventoryReservationService(Optional.empty());

        service.setNextReservationLimit(2);
        assertEquals(InventoryReserveResult.Status.UNKNOWN_OWNER,
                service.reserve(request(InventoryReservationOperation.INSERT)).status());
        service.simulateOrdinaryStateChange(inventory(1));

        assertEquals(InventoryReserveResult.Status.RESERVED,
                service.reserve(request(InventoryReservationOperation.INSERT)).status());
    }

    @Test
    void fakeSnapshotDoesNotExposeAnotherOwnersInventory() {
        FakeInventoryReservationService service =
                new FakeInventoryReservationService(Optional.of(inventory(1)));

        assertEquals(Optional.empty(), service.snapshot(OTHER_OWNER));
        assertEquals(Optional.of(inventory(1)), service.snapshot(OWNER));
    }

    @Test
    void fakeReplaceSlotReportsUnknownOwnerWithoutAnotherOwnersSnapshot() {
        FakeInventoryReservationService service =
                new FakeInventoryReservationService(Optional.of(inventory(1)));
        InventoryChangeRequest request =
                new InventoryChangeRequest(
                        OTHER_OWNER,
                        BodySlot.LEFT_HAND,
                        1,
                        Optional.empty());

        InventoryChangeResult result = service.replaceSlot(request);

        assertEquals(InventoryChangeResult.Status.UNKNOWN_OWNER, result.status());
        assertEquals(Optional.empty(), result.inventory());
    }

    @Test
    void fakeWrongOwnerReserveReturnsFullRemainderAndConsumesOneShotLimit() {
        FakeInventoryReservationService service =
                new FakeInventoryReservationService(Optional.of(inventory(1)));
        InventoryReservationRequest wrongOwnerRequest =
                request(
                        OTHER_OWNER,
                        InventoryReservationOperation.INSERT);
        service.setNextReservationLimit(2);

        InventoryReserveResult result = service.reserve(wrongOwnerRequest);

        assertEquals(InventoryReserveResult.Status.UNKNOWN_OWNER, result.status());
        assertEquals(Optional.empty(), result.reservation());
        assertEquals(Optional.of(STONE), result.remainder());
        assertEquals(Optional.empty(), result.inventory());
        InventoryReserveResult next =
                service.reserve(
                        request(InventoryReservationOperation.INSERT));
        assertEquals(InventoryReserveResult.Status.RESERVED, next.status());
        assertEquals(5, next.reservation().orElseThrow().reserved().count());
    }

    @Test
    void fakeOrdinaryStateChangeCannotSwitchInventoryOwner() {
        TestInventoryView original = inventory(1);
        FakeInventoryReservationService service =
                new FakeInventoryReservationService(Optional.of(original));

        assertThrows(
                IllegalArgumentException.class,
                () ->
                        service.simulateOrdinaryStateChange(
                                inventory(OTHER_OWNER, 2)));

        assertEquals(Optional.of(original), service.snapshot(OWNER));
        assertEquals(Optional.empty(), service.snapshot(OTHER_OWNER));
    }

    @Test
    void fakeConfiguredReplacementCannotReturnAnotherOwnersView() {
        FakeInventoryReservationService service =
                new FakeInventoryReservationService(Optional.of(inventory(1)));
        service.setReplacementResult(
                new InventoryChangeResult(
                        InventoryChangeResult.Status.CONFLICT,
                        Optional.of(inventory(OTHER_OWNER, 2))));
        InventoryChangeRequest request =
                new InventoryChangeRequest(
                        OWNER,
                        BodySlot.LEFT_HAND,
                        1,
                        Optional.empty());

        assertThrows(
                IllegalStateException.class,
                () -> service.replaceSlot(request));
    }

    @Test
    void fakeCommitIsIdempotent() {
        FakeInventoryReservationService service =
                new FakeInventoryReservationService(Optional.of(inventory(1)));
        InventoryReservationId id = service.reserve(request(InventoryReservationOperation.INSERT))
                .reservation().orElseThrow().id();

        assertEquals(InventoryReservationResult.Status.COMMITTED, service.commit(id).status());
        assertEquals(InventoryReservationResult.Status.ALREADY_COMMITTED, service.commit(id).status());
        assertEquals(1, service.commitSideEffectCount());
    }

    @Test
    void fakeRollbackIsIdempotent() {
        FakeInventoryReservationService service =
                new FakeInventoryReservationService(Optional.of(inventory(1)));
        InventoryReservationId id = service.reserve(request(InventoryReservationOperation.INSERT))
                .reservation().orElseThrow().id();

        assertEquals(InventoryReservationResult.Status.ROLLED_BACK, service.rollback(id).status());
        assertEquals(InventoryReservationResult.Status.ALREADY_ROLLED_BACK, service.rollback(id).status());
        assertEquals(1, service.rollbackSideEffectCount());
    }

    @Test
    void fakeRejectsRollbackAfterCommitAsTerminalConflict() {
        FakeInventoryReservationService service =
                new FakeInventoryReservationService(Optional.of(inventory(1)));
        InventoryReservationId id = service.reserve(request(InventoryReservationOperation.INSERT))
                .reservation().orElseThrow().id();

        service.commit(id);

        assertEquals(InventoryReservationResult.Status.TERMINAL_CONFLICT,
                service.rollback(id).status());
        assertEquals(0, service.rollbackSideEffectCount());
    }

    @Test
    void fakeRejectsCommitAfterRollbackAsTerminalConflict() {
        FakeInventoryReservationService service =
                new FakeInventoryReservationService(Optional.of(inventory(1)));
        InventoryReservationId id = service.reserve(request(InventoryReservationOperation.INSERT))
                .reservation().orElseThrow().id();

        service.rollback(id);

        assertEquals(InventoryReservationResult.Status.TERMINAL_CONFLICT,
                service.commit(id).status());
        assertEquals(0, service.commitSideEffectCount());
    }

    @Test
    void fakeReportsUnknownReservations() {
        FakeInventoryReservationService service =
                new FakeInventoryReservationService(Optional.of(inventory(1)));

        assertEquals(InventoryReservationResult.Status.UNKNOWN_RESERVATION,
                service.commit(new InventoryReservationId(99)).status());
        assertEquals(InventoryReservationResult.Status.UNKNOWN_RESERVATION,
                service.rollback(new InventoryReservationId(99)).status());
    }

    @Test
    void fakeCommitSurvivesAnOrdinaryVisibleStateChange() {
        FakeInventoryReservationService service =
                new FakeInventoryReservationService(Optional.of(inventory(1)));
        InventoryReservationId id = service.reserve(request(InventoryReservationOperation.INSERT))
                .reservation().orElseThrow().id();
        TestInventoryView changed = inventory(2);

        service.simulateOrdinaryStateChange(changed);

        InventoryReservationResult committed = service.commit(id);
        assertEquals(InventoryReservationResult.Status.COMMITTED, committed.status());
        assertEquals(Optional.of(changed), committed.inventory());
        assertFalse(service.snapshot(OWNER).isEmpty());
        assertTrue(service.snapshot(OWNER).isPresent());
    }

    private static InventoryReservationRequest request(
            InventoryReservationOperation operation) {
        return request(OWNER, operation);
    }

    private static InventoryReservationRequest request(
            EntityRef owner,
            InventoryReservationOperation operation) {
        return new InventoryReservationRequest(
                owner, BodySlot.LEFT_HAND, operation, STONE);
    }

    private static TestInventoryView inventory(long revision) {
        return inventory(OWNER, revision);
    }

    private static TestInventoryView inventory(
            EntityRef owner, long revision) {
        return new TestInventoryView(owner, revision, Map.of());
    }
}

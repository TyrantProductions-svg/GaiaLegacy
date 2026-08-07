package com.overlord.worlditem.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.overlord.assets.ResourceLocation;
import com.overlord.interaction.api.EntityRef;
import com.overlord.inventory.api.ItemStack;
import com.overlord.worlditem.testing.FakeWorldItemService;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class WorldItemContractTest {
    private static final ItemStack STONE =
            new ItemStack(ResourceLocation.parse("gaia:stone"), 5);

    @Test
    void idsRejectNegativeValues() {
        assertEquals(0, new WorldItemId(0).value());
        assertEquals(0, new WorldItemReservationId(0).value());
        assertThrows(IllegalArgumentException.class, () -> new WorldItemId(-1));
        assertThrows(IllegalArgumentException.class,
                () -> new WorldItemReservationId(-1));
    }

    @Test
    void spawnRequestsRejectInvalidReferencesVectorsAndTicks() {
        assertThrows(NullPointerException.class,
                () -> new WorldItemSpawnRequest(null, 1, 2, 3, 4, 5, 6,
                        Optional.empty(), 0));
        assertThrows(IllegalArgumentException.class,
                () -> new WorldItemSpawnRequest(STONE, Double.NaN, 2, 3, 4, 5, 6,
                        Optional.empty(), 0));
        assertThrows(IllegalArgumentException.class,
                () -> new WorldItemSpawnRequest(STONE, 1, 2, 3,
                        Double.POSITIVE_INFINITY, 5, 6, Optional.empty(), 0));
        assertThrows(NullPointerException.class,
                () -> new WorldItemSpawnRequest(STONE, 1, 2, 3, 4, 5, 6, null, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new WorldItemSpawnRequest(STONE, 1, 2, 3, 4, 5, 6,
                        Optional.of(new EntityRef(1)), -1));
    }

    @Test
    void snapshotsRejectInvalidReferencesVectorsAndRevisions() {
        assertThrows(NullPointerException.class,
                () -> new WorldItemSnapshot(null, STONE, 1, 2, 3, 4, 5, 6, 0));
        assertThrows(NullPointerException.class,
                () -> new WorldItemSnapshot(new WorldItemId(1), null, 1, 2, 3, 4, 5, 6, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new WorldItemSnapshot(new WorldItemId(1), STONE, 1, 2, 3, 4,
                        Double.NEGATIVE_INFINITY, 6, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new WorldItemSnapshot(new WorldItemId(1), STONE, 1, 2, 3, 4, 5, 6,
                        -1));
    }

    @Test
    void spawnedResultsRequireAnItemAndRejectedResultsRequireTheFullRemainder() {
        WorldItemSpawnRequest request = request(STONE);
        WorldItemSnapshot item = snapshot(new WorldItemId(1), STONE, 0);

        assertEquals(WorldItemSpawnResult.Status.SPAWNED,
                new WorldItemSpawnResult(request, WorldItemSpawnResult.Status.SPAWNED,
                        Optional.of(item), Optional.empty()).status());
        assertThrows(IllegalArgumentException.class,
                () -> new WorldItemSpawnResult(request, WorldItemSpawnResult.Status.SPAWNED,
                        Optional.empty(), Optional.empty()));
        assertThrows(IllegalArgumentException.class,
                () -> new WorldItemSpawnResult(request, WorldItemSpawnResult.Status.SPAWNED,
                        Optional.of(item), Optional.of(STONE)));
        assertThrows(IllegalArgumentException.class,
                () -> new WorldItemSpawnResult(request, WorldItemSpawnResult.Status.SPAWNED,
                        Optional.of(snapshot(new WorldItemId(2),
                                new ItemStack(ResourceLocation.parse("gaia:dirt"), 5), 0)),
                        Optional.empty()));
        assertThrows(IllegalArgumentException.class,
                () -> new WorldItemSpawnResult(request, WorldItemSpawnResult.Status.SPAWNED,
                        Optional.of(new WorldItemSnapshot(
                                new WorldItemId(3), STONE, 1, 20, 3, 4, 5, 6, 0)),
                        Optional.empty()));
        assertThrows(IllegalArgumentException.class,
                () -> new WorldItemSpawnResult(request, WorldItemSpawnResult.Status.SPAWNED,
                        Optional.of(new WorldItemSnapshot(
                                new WorldItemId(4), STONE, 1, 2, 3, 4, 5, 60, 0)),
                        Optional.empty()));
        WorldItemSpawnRequest signedZeroRequest = new WorldItemSpawnRequest(
                STONE, 0.0, 2, 3, 0.0, 5, 6, Optional.empty(), 7);
        assertEquals(WorldItemSpawnResult.Status.SPAWNED,
                new WorldItemSpawnResult(
                        signedZeroRequest,
                        WorldItemSpawnResult.Status.SPAWNED,
                        Optional.of(new WorldItemSnapshot(
                                new WorldItemId(5), STONE, -0.0, 2, 3, -0.0, 5, 6, 0)),
                        Optional.empty()).status());
        assertEquals(Optional.of(STONE),
                new WorldItemSpawnResult(request, WorldItemSpawnResult.Status.REJECTED,
                        Optional.empty(), Optional.of(STONE)).remainder());
        assertThrows(IllegalArgumentException.class,
                () -> new WorldItemSpawnResult(request, WorldItemSpawnResult.Status.REJECTED,
                        Optional.empty(), Optional.of(new ItemStack(STONE.itemId(), 4))));
    }

    @Test
    void reservationResultsRequirePayloadsConsistentWithTheirStatus() {
        WorldItemSnapshot item = snapshot(new WorldItemId(1), STONE, 0);
        WorldItemReservation full = new WorldItemReservation(
                new WorldItemReservationId(1), item.id(), STONE);
        WorldItemReservation partial = new WorldItemReservation(
                new WorldItemReservationId(2), item.id(),
                new ItemStack(STONE.itemId(), 2));

        assertThrows(IllegalArgumentException.class,
                () -> new WorldItemReservationResult(
                        WorldItemReservationResult.Status.RESERVED,
                        Optional.empty(), Optional.of(item), Optional.empty()));
        assertThrows(IllegalArgumentException.class,
                () -> new WorldItemReservationResult(
                        WorldItemReservationResult.Status.RESERVED,
                        Optional.of(full), Optional.of(item), Optional.of(STONE)));
        assertThrows(IllegalArgumentException.class,
                () -> new WorldItemReservationResult(
                        WorldItemReservationResult.Status.PARTIALLY_RESERVED,
                        Optional.of(partial), Optional.of(item), Optional.empty()));
        assertThrows(IllegalArgumentException.class,
                () -> new WorldItemReservationResult(
                        WorldItemReservationResult.Status.PARTIALLY_RESERVED,
                        Optional.of(partial), Optional.of(item),
                        Optional.of(new ItemStack(STONE.itemId(), 2))));
        assertThrows(IllegalArgumentException.class,
                () -> new WorldItemReservationResult(
                        WorldItemReservationResult.Status.UNKNOWN_ITEM,
                        Optional.empty(), Optional.of(item), Optional.empty()));
        assertThrows(IllegalArgumentException.class,
                () -> new WorldItemReservationResult(
                        WorldItemReservationResult.Status.COMMITTED,
                        Optional.empty(), Optional.empty(), Optional.empty()));
    }

    @Test
    void typedSpawnCommitFailureRetainsReservationIdentityAndAppliedState() {
        WorldItemSpawnReservationId reservationId = new WorldItemSpawnReservationId(17L);
        AssertionError cause = new AssertionError("after apply");

        WorldItemSpawnCommitException failure = new WorldItemSpawnCommitException(
                "spawn notification failed", cause, reservationId, true);

        assertSame(cause, failure.getCause());
        assertEquals(reservationId, failure.reservationId());
        assertTrue(failure.stateChangeApplied());
        assertFalse(new WorldItemSpawnCommitException(
                "before apply", null, reservationId, false).stateChangeApplied());
        assertThrows(NullPointerException.class, () -> new WorldItemSpawnCommitException(
                "missing identity", null, null, false));
    }

    @Test
    void spawnAuditSnapshotRejectsMissingIdentityOrState() {
        WorldItemSpawnReservation reservation = new WorldItemSpawnReservation(
                new WorldItemSpawnReservationId(3L),
                new WorldItemId(5L),
                request(new ItemStack(STONE.itemId(), 1)));

        assertThrows(NullPointerException.class, () ->
                new WorldItemSpawnReservationAuditSnapshot(null,
                        com.overlord.core.transaction.ReservationTerminalState.PENDING));
        assertThrows(NullPointerException.class, () ->
                new WorldItemSpawnReservationAuditSnapshot(reservation, null));
    }

    @Test
    void committedSpawnRejectsMismatchedPosition() {
        WorldItemSpawnReservation reservation = spawnReservation();
        WorldItemSnapshot changed = new WorldItemSnapshot(
                reservation.itemId(), reservation.request().stack(),
                99, 2, 3, 4, 5, 6, 0);

        assertThrows(IllegalArgumentException.class, () -> new WorldItemSpawnCommitResult(
                WorldItemSpawnCommitResult.Status.COMMITTED,
                Optional.of(reservation), Optional.of(runtime(reservation, changed))));
    }

    @Test
    void committedSpawnRejectsMismatchedVelocityX() {
        WorldItemSpawnReservation reservation = spawnReservation();
        WorldItemSnapshot changed = new WorldItemSnapshot(
                reservation.itemId(), reservation.request().stack(),
                1, 2, 3, 99, 5, 6, 0);

        assertThrows(IllegalArgumentException.class, () -> new WorldItemSpawnCommitResult(
                WorldItemSpawnCommitResult.Status.COMMITTED,
                Optional.of(reservation), Optional.of(runtime(reservation, changed))));
    }

    @Test
    void committedSpawnRejectsMismatchedVelocityY() {
        WorldItemSpawnReservation reservation = spawnReservation();
        WorldItemSnapshot changed = new WorldItemSnapshot(
                reservation.itemId(), reservation.request().stack(),
                1, 2, 3, 4, 99, 6, 0);

        assertThrows(IllegalArgumentException.class, () -> new WorldItemSpawnCommitResult(
                WorldItemSpawnCommitResult.Status.COMMITTED,
                Optional.of(reservation), Optional.of(runtime(reservation, changed))));
    }

    @Test
    void committedSpawnRejectsMismatchedCount() {
        WorldItemSpawnReservation reservation = spawnReservation();
        ItemStack changedCount = new ItemStack(
                reservation.request().stack().itemId(),
                reservation.request().stack().count() + 1);
        WorldItemSnapshot changed = new WorldItemSnapshot(
                reservation.itemId(), changedCount,
                1, 2, 3, 4, 5, 6, 0);

        assertThrows(IllegalArgumentException.class, () -> new WorldItemSpawnCommitResult(
                WorldItemSpawnCommitResult.Status.COMMITTED,
                Optional.of(reservation), Optional.of(runtime(reservation, changed))));
    }

    @Test
    void committedSpawnRejectsMismatchedRevision() {
        WorldItemSpawnReservation reservation = spawnReservation();
        WorldItemSnapshot changed = snapshot(reservation.itemId(), reservation.request().stack(), 1);

        assertThrows(IllegalArgumentException.class, () -> new WorldItemSpawnCommitResult(
                WorldItemSpawnCommitResult.Status.COMMITTED,
                Optional.of(reservation), Optional.of(runtime(reservation, changed))));
    }

    @Test
    void committedSpawnRejectsMismatchedPickupTiming() {
        WorldItemSpawnReservation reservation = spawnReservation();
        WorldItemSnapshot item = snapshot(
                reservation.itemId(), reservation.request().stack(), 0);
        WorldItemRuntimeSnapshot changed = new WorldItemRuntimeSnapshot(
                item,
                reservation.request().source(),
                reservation.request().tick(),
                reservation.pickupAvailableTick() + 1);

        assertThrows(IllegalArgumentException.class, () -> new WorldItemSpawnCommitResult(
                WorldItemSpawnCommitResult.Status.COMMITTED,
                Optional.of(reservation), Optional.of(changed)));
    }

    @Test
    void committedSpawnRejectsMismatchedSourceIdentity() {
        WorldItemSpawnReservation reservation = spawnReservation();
        WorldItemSnapshot item = snapshot(
                reservation.itemId(), reservation.request().stack(), 0);
        WorldItemRuntimeSnapshot changed = new WorldItemRuntimeSnapshot(
                item,
                Optional.of(new EntityRef(999)),
                reservation.request().tick(),
                reservation.pickupAvailableTick());

        assertThrows(IllegalArgumentException.class, () -> new WorldItemSpawnCommitResult(
                WorldItemSpawnCommitResult.Status.COMMITTED,
                Optional.of(reservation), Optional.of(changed)));
    }

    @Test
    void auditRejectsMismatchedReservationIdentity() {
        WorldItemSpawnReservation expected = spawnReservation();
        WorldItemSpawnRequest unrelatedRequest = new WorldItemSpawnRequest(
                expected.request().stack(), 10, 2, 3, 4, 5, 6,
                expected.request().source(), expected.request().tick());
        WorldItemSpawnReservation unrelated = new WorldItemSpawnReservation(
                new WorldItemSpawnReservationId(99),
                expected.itemId(),
                unrelatedRequest,
                expected.pickupAvailableTick());
        WorldItemRuntimeSnapshot unrelatedRuntime = runtime(
                unrelated,
                new WorldItemSnapshot(
                        unrelated.itemId(), unrelated.request().stack(),
                        10, 2, 3, 4, 5, 6, 0));

        assertThrows(IllegalArgumentException.class, () ->
                new WorldItemSpawnReservationAuditSnapshot(
                        expected,
                        com.overlord.core.transaction.ReservationTerminalState.COMMITTED,
                        Optional.of(unrelatedRuntime)));
    }

    @Test
    void revisionExhaustedRequiresAStrictlyPartialReservation() {
        WorldItemSnapshot item = snapshot(new WorldItemId(1), STONE, Long.MAX_VALUE);
        WorldItemReservation full = new WorldItemReservation(
                new WorldItemReservationId(1), item.id(), STONE);
        WorldItemReservation tooLarge = new WorldItemReservation(
                new WorldItemReservationId(2), item.id(),
                new ItemStack(STONE.itemId(), 6));
        ItemStack exactRemainder = new ItemStack(STONE.itemId(), 3);
        ItemStack inconsistentRemainder = new ItemStack(STONE.itemId(), 1);

        assertThrows(IllegalArgumentException.class,
                () -> new WorldItemReservationResult(
                        WorldItemReservationResult.Status.REVISION_EXHAUSTED,
                        Optional.of(full), Optional.of(item), Optional.empty()));
        assertThrows(IllegalArgumentException.class,
                () -> new WorldItemReservationResult(
                        WorldItemReservationResult.Status.REVISION_EXHAUSTED,
                        Optional.of(tooLarge), Optional.of(item), Optional.empty()));

        WorldItemReservation partial = new WorldItemReservation(
                new WorldItemReservationId(3), item.id(),
                new ItemStack(STONE.itemId(), 2));
        assertThrows(IllegalArgumentException.class,
                () -> new WorldItemReservationResult(
                        WorldItemReservationResult.Status.REVISION_EXHAUSTED,
                        Optional.of(partial), Optional.of(item), Optional.empty()));
        assertEquals(
                WorldItemReservationResult.Status.REVISION_EXHAUSTED,
                new WorldItemReservationResult(
                        WorldItemReservationResult.Status.REVISION_EXHAUSTED,
                        Optional.of(partial), Optional.of(item), Optional.of(exactRemainder)).status());
        assertThrows(IllegalArgumentException.class,
                () -> new WorldItemReservationResult(
                        WorldItemReservationResult.Status.REVISION_EXHAUSTED,
                        Optional.of(partial), Optional.of(item), Optional.of(inconsistentRemainder)));
    }

    @Test
    void revisionExhaustedRejectsIdentityMismatchAndMissingPayloads() {
        WorldItemSnapshot item = snapshot(new WorldItemId(1), STONE, Long.MAX_VALUE);
        WorldItemReservation differentItem = new WorldItemReservation(
                new WorldItemReservationId(1), new WorldItemId(2),
                new ItemStack(STONE.itemId(), 2));
        ItemStack other = new ItemStack(ResourceLocation.parse("gaia:dirt"), 2);
        WorldItemReservation differentIdentity = new WorldItemReservation(
                new WorldItemReservationId(2), item.id(), other);

        assertThrows(IllegalArgumentException.class,
                () -> new WorldItemReservationResult(
                        WorldItemReservationResult.Status.REVISION_EXHAUSTED,
                        Optional.of(differentItem), Optional.of(item), Optional.empty()));
        assertThrows(IllegalArgumentException.class,
                () -> new WorldItemReservationResult(
                        WorldItemReservationResult.Status.REVISION_EXHAUSTED,
                        Optional.of(differentIdentity), Optional.of(item), Optional.empty()));
        assertThrows(IllegalArgumentException.class,
                () -> new WorldItemReservationResult(
                        WorldItemReservationResult.Status.REVISION_EXHAUSTED,
                        Optional.empty(), Optional.of(item), Optional.empty()));
        assertThrows(IllegalArgumentException.class,
                () -> new WorldItemReservationResult(
                        WorldItemReservationResult.Status.REVISION_EXHAUSTED,
                        Optional.of(differentIdentity), Optional.empty(), Optional.empty()));
        assertThrows(IllegalArgumentException.class,
                () -> new WorldItemReservation(
                        new WorldItemReservationId(3), item.id(),
                        new ItemStack(STONE.itemId(), 0)));
    }

    @Test
    void revisionExhaustedRejectsARevisionThatCanStillAdvance() {
        WorldItemSnapshot item = snapshot(new WorldItemId(1), STONE, Long.MAX_VALUE - 1);
        WorldItemReservation partial = new WorldItemReservation(
                new WorldItemReservationId(1), item.id(),
                new ItemStack(STONE.itemId(), 2));

        assertThrows(IllegalArgumentException.class,
                () -> new WorldItemReservationResult(
                        WorldItemReservationResult.Status.REVISION_EXHAUSTED,
                        Optional.of(partial), Optional.of(item), Optional.empty()));
    }

    @Test
    void fakeSpawnsStableIdWithTheCanonicalStack() {
        FakeWorldItemService service = new FakeWorldItemService();

        WorldItemSpawnResult spawned = service.spawn(request(STONE));
        WorldItemSnapshot snapshot = spawned.item().orElseThrow();

        assertEquals(WorldItemSpawnResult.Status.SPAWNED, spawned.status());
        assertEquals(new WorldItemId(0), snapshot.id());
        assertSame(STONE, snapshot.stack());
        assertEquals(Optional.of(snapshot), service.snapshot(snapshot.id()));
    }

    @Test
    void fakeRejectsSpawnsWithTheFullOriginalStackWhenConfigured() {
        FakeWorldItemService service = new FakeWorldItemService();
        service.setSpawnRejectionEnabled(true);

        WorldItemSpawnResult rejected = service.spawn(request(STONE));

        assertEquals(WorldItemSpawnResult.Status.REJECTED, rejected.status());
        assertEquals(Optional.of(STONE), rejected.remainder());
        assertTrue(rejected.item().isEmpty());
    }

    @Test
    void fakePartiallyReservesAndReportsTheStillUnreservedWorldQuantity() {
        FakeWorldItemService service = new FakeWorldItemService();
        WorldItemId itemId = spawn(service, STONE);

        WorldItemReservationResult result = service.reserve(itemId, 2);

        assertEquals(WorldItemReservationResult.Status.PARTIALLY_RESERVED, result.status());
        assertEquals(2, result.reservation().orElseThrow().reserved().count());
        assertEquals(Optional.of(new ItemStack(STONE.itemId(), 3)), result.remainder());
        assertEquals(STONE, result.item().orElseThrow().stack());
    }

    @Test
    void fakeFullyReservesTheCurrentWorldStack() {
        FakeWorldItemService service = new FakeWorldItemService();
        WorldItemId itemId = spawn(service, STONE);

        WorldItemReservationResult result = service.reserve(itemId, 5);

        assertEquals(WorldItemReservationResult.Status.RESERVED, result.status());
        assertEquals(STONE, result.reservation().orElseThrow().reserved());
        assertTrue(result.remainder().isEmpty());
    }

    @Test
    void fakeRejectsInvalidUnavailableAndUnknownReservations() {
        FakeWorldItemService service = new FakeWorldItemService();
        WorldItemId itemId = spawn(service, STONE);

        assertEquals(WorldItemReservationResult.Status.INVALID_COUNT,
                service.reserve(itemId, 6).status());
        assertEquals(WorldItemReservationResult.Status.INVALID_COUNT,
                service.reserve(itemId, 0).status());
        assertEquals(WorldItemReservationResult.Status.INVALID_COUNT,
                service.reserve(itemId, -1).status());
        assertEquals(WorldItemReservationResult.Status.UNKNOWN_ITEM,
                service.reserve(new WorldItemId(99), 1).status());
        service.reserve(itemId, 1);
        assertEquals(WorldItemReservationResult.Status.UNAVAILABLE,
                service.reserve(itemId, 1).status());
    }

    @Test
    void partialCommitPreservesItemIdAndIncrementsRevision() {
        FakeWorldItemService service = new FakeWorldItemService();
        WorldItemId itemId = spawn(service, STONE);
        WorldItemReservationId reservationId =
                service.reserve(itemId, 2).reservation().orElseThrow().id();

        WorldItemReservationResult result = service.commit(reservationId);

        assertEquals(WorldItemReservationResult.Status.COMMITTED, result.status());
        WorldItemSnapshot item = result.item().orElseThrow();
        assertEquals(itemId, item.id());
        assertEquals(new ItemStack(STONE.itemId(), 3), item.stack());
        assertEquals(1, item.revision());
        assertEquals(Optional.of(item), service.snapshot(itemId));
        assertEquals(1, service.commitSideEffectCount());
    }

    @Test
    void fullCommitRemovesTheWorldItem() {
        FakeWorldItemService service = new FakeWorldItemService();
        WorldItemId itemId = spawn(service, STONE);
        WorldItemReservationId reservationId =
                service.reserve(itemId, 5).reservation().orElseThrow().id();

        WorldItemReservationResult result = service.commit(reservationId);

        assertEquals(WorldItemReservationResult.Status.COMMITTED, result.status());
        assertTrue(result.item().isEmpty());
        assertTrue(service.snapshot(itemId).isEmpty());
    }

    @Test
    void rollbackRestoresAvailabilityWithoutChangingTheWorldStack() {
        FakeWorldItemService service = new FakeWorldItemService();
        WorldItemId itemId = spawn(service, STONE);
        WorldItemReservationId reservationId =
                service.reserve(itemId, 2).reservation().orElseThrow().id();

        assertEquals(WorldItemReservationResult.Status.ROLLED_BACK,
                service.rollback(reservationId).status());
        assertEquals(STONE, service.snapshot(itemId).orElseThrow().stack());
        assertEquals(WorldItemReservationResult.Status.RESERVED,
                service.reserve(itemId, 5).status());
        assertEquals(1, service.rollbackSideEffectCount());
    }

    @Test
    void terminalOperationsAreIdempotentAndRejectTheOppositeOperation() {
        FakeWorldItemService service = new FakeWorldItemService();
        WorldItemReservationId committed =
                service.reserve(spawn(service, STONE), 5).reservation().orElseThrow().id();
        assertEquals(WorldItemReservationResult.Status.COMMITTED, service.commit(committed).status());
        assertEquals(WorldItemReservationResult.Status.ALREADY_COMMITTED,
                service.commit(committed).status());
        assertEquals(WorldItemReservationResult.Status.TERMINAL_CONFLICT,
                service.rollback(committed).status());

        WorldItemReservationId rolledBack =
                service.reserve(spawn(service, STONE), 5).reservation().orElseThrow().id();
        assertEquals(WorldItemReservationResult.Status.ROLLED_BACK,
                service.rollback(rolledBack).status());
        assertEquals(WorldItemReservationResult.Status.ALREADY_ROLLED_BACK,
                service.rollback(rolledBack).status());
        assertEquals(WorldItemReservationResult.Status.TERMINAL_CONFLICT,
                service.commit(rolledBack).status());
        assertEquals(1, service.commitSideEffectCount());
        assertEquals(1, service.rollbackSideEffectCount());
    }

    @Test
    void fakeReportsUnknownReservationIds() {
        FakeWorldItemService service = new FakeWorldItemService();

        assertEquals(WorldItemReservationResult.Status.UNKNOWN_RESERVATION,
                service.commit(new WorldItemReservationId(99)).status());
        assertEquals(WorldItemReservationResult.Status.UNKNOWN_RESERVATION,
                service.rollback(new WorldItemReservationId(99)).status());
    }

    @Test
    void fakeThrowsAfterAllocatingTheLastAvailableIds() throws ReflectiveOperationException {
        FakeWorldItemService itemService = new FakeWorldItemService();
        setLongField(itemService, "nextItemId", Long.MAX_VALUE);

        assertEquals(new WorldItemId(Long.MAX_VALUE), spawn(itemService, STONE));
        assertThrows(IllegalStateException.class, () -> spawn(itemService, STONE));

        FakeWorldItemService reservationService = new FakeWorldItemService();
        setLongField(reservationService, "nextReservationId", Long.MAX_VALUE);
        WorldItemId firstItem = spawn(reservationService, STONE);
        WorldItemId secondItem = spawn(reservationService, STONE);

        assertEquals(new WorldItemReservationId(Long.MAX_VALUE),
                reservationService.reserve(firstItem, 1).reservation().orElseThrow().id());
        assertThrows(IllegalStateException.class, () -> reservationService.reserve(secondItem, 1));
    }

    @Test
    void productionWorldItemApiDoesNotDependOnRuntimeSubsystems() {
        Class<?>[] apiTypes = {
                WorldItemId.class,
                WorldItemReservationId.class,
                WorldItemSpawnRequest.class,
                WorldItemSpawnResult.class,
                WorldItemReservation.class,
                WorldItemReservationResult.class,
                WorldItemRuntimeSnapshot.class,
                WorldItemSnapshot.class,
                WorldItemSpawnCommitResult.class,
                WorldItemSpawnIdentity.class,
                WorldItemSpawnReservation.class,
                WorldItemSpawnReservationAuditSnapshot.class,
                WorldItemService.class
        };

        for (Class<?> apiType : apiTypes) {
            assertNoRuntimeSubsystem(apiType);
            for (Constructor<?> constructor : apiType.getDeclaredConstructors()) {
                for (Class<?> parameterType : constructor.getParameterTypes()) {
                    assertNoRuntimeSubsystem(parameterType);
                }
                for (Type parameterType : constructor.getGenericParameterTypes()) {
                    assertNoRuntimeSubsystem(parameterType);
                }
            }
            for (Method method : apiType.getDeclaredMethods()) {
                assertNoRuntimeSubsystem(method.getReturnType());
                for (Class<?> parameterType : method.getParameterTypes()) {
                    assertNoRuntimeSubsystem(parameterType);
                }
                assertNoRuntimeSubsystem(method.getGenericReturnType());
                for (Type parameterType : method.getGenericParameterTypes()) {
                    assertNoRuntimeSubsystem(parameterType);
                }
            }
            if (apiType.isRecord()) {
                for (RecordComponent component : apiType.getRecordComponents()) {
                    assertNoRuntimeSubsystem(component.getType());
                    assertNoRuntimeSubsystem(component.getGenericType());
                }
            }
        }
    }

    private static void assertNoRuntimeSubsystem(Class<?> type) {
        String name = type.getName();
        assertFalse(name.startsWith("com.overlord.ecs."), name);
        assertFalse(name.startsWith("com.overlord.physics."), name);
        assertFalse(name.startsWith("com.overlord.renderer."), name);
        assertFalse(name.startsWith("org.lwjgl."), name);
        assertFalse(name.startsWith("org.opengl."), name);
        assertFalse(name.startsWith("com.gaia."), name);
    }

    private static void assertNoRuntimeSubsystem(Type type) {
        if (type instanceof Class<?> classType) {
            assertNoRuntimeSubsystem(classType);
        } else if (type instanceof ParameterizedType parameterizedType) {
            assertNoRuntimeSubsystem(parameterizedType.getRawType());
            for (Type argument : parameterizedType.getActualTypeArguments()) {
                assertNoRuntimeSubsystem(argument);
            }
        }
    }

    private static void setLongField(
            FakeWorldItemService service, String name, long value)
            throws ReflectiveOperationException {
        java.lang.reflect.Field field = FakeWorldItemService.class.getDeclaredField(name);
        field.setAccessible(true);
        field.setLong(service, value);
    }

    private static WorldItemId spawn(FakeWorldItemService service, ItemStack stack) {
        return service.spawn(request(stack)).item().orElseThrow().id();
    }

    private static WorldItemSpawnRequest request(ItemStack stack) {
        return new WorldItemSpawnRequest(stack, 1, 2, 3, 4, 5, 6,
                Optional.of(new EntityRef(2)), 7);
    }

    private static WorldItemSpawnReservation spawnReservation() {
        return new WorldItemSpawnReservation(
                new WorldItemSpawnReservationId(3L),
                new WorldItemId(5L),
                request(new ItemStack(STONE.itemId(), 1)));
    }

    private static WorldItemRuntimeSnapshot runtime(
            WorldItemSpawnReservation reservation, WorldItemSnapshot item) {
        return new WorldItemRuntimeSnapshot(
                item,
                reservation.request().source(),
                reservation.request().tick(),
                reservation.pickupAvailableTick());
    }

    private static WorldItemSnapshot snapshot(
            WorldItemId id, ItemStack stack, long revision) {
        return new WorldItemSnapshot(id, stack, 1, 2, 3, 4, 5, 6, revision);
    }
}

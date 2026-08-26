package com.overlord.worlditem;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.overlord.assets.ResourceLocation;
import com.overlord.core.thread.MainThreadGuard;
import com.overlord.inventory.api.ItemStack;
import com.overlord.voxel.ChunkKey;
import com.overlord.worlditem.api.LogicalWorldItemSnapshot;
import com.overlord.worlditem.api.WorldItemActivationResult;
import com.overlord.worlditem.api.WorldItemHibernatePayload;
import com.overlord.worlditem.api.WorldItemHibernateResult;
import com.overlord.worlditem.api.WorldItemId;
import com.overlord.worlditem.api.WorldItemMotionUpdate;
import com.overlord.worlditem.api.WorldItemPhysicalState;
import com.overlord.worlditem.api.WorldItemReservation;
import com.overlord.worlditem.api.WorldItemRestoreEntry;
import com.overlord.worlditem.api.WorldItemRuntimeSnapshot;
import com.overlord.worlditem.api.WorldItemSnapshot;
import com.overlord.worlditem.api.WorldItemSpawnRequest;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.Arguments;

class LogicalWorldItemPagingTest {
    private static final ItemStack DIRT =
            new ItemStack(ResourceLocation.parse("gaia:dirt"), 3);

    static List<ChunkKey> signedAndBoundaryKeys() {
        return List.of(
                new ChunkKey(0, 0),
                new ChunkKey(1, 0),
                new ChunkKey(-1, 0),
                new ChunkKey(0, 1),
                new ChunkKey(0, -1));
    }

    @ParameterizedTest
    @MethodSource("signedAndBoundaryKeys")
    void hibernateAndActivatePreserveStableIdAtSignedAndBoundaryChunks(ChunkKey key) {
        LogicalWorldItemService service = service(4);
        WorldItemSnapshot original = spawn(service, key, 3);

        WorldItemHibernateResult prepared = service.prepareHibernate(
                key, Map.of(original.id(), original.revision()));

        assertEquals(WorldItemHibernateResult.Status.PREPARED, prepared.status());
        assertEquals(key, prepared.payload().orElseThrow().chunkKey());
        assertEquals(List.of(original.id()), payloadIds(prepared.payload().orElseThrow()));
        assertEquals(original, service.snapshot(original.id()).orElseThrow(),
                "prepare must not change canonical active state");

        assertEquals(
                WorldItemHibernateResult.Status.COMMITTED,
                service.commitHibernate(prepared.ticket().orElseThrow()).status());
        assertTrue(service.snapshot(original.id()).isEmpty());
        assertEquals(Map.of(original.id(), key),
                service.canonicalSnapshot().dormantChunkKeys());

        WorldItemActivationResult activation = service.prepareActivate(
                key, prepared.payload().orElseThrow());
        assertEquals(WorldItemActivationResult.Status.PREPARED, activation.status());
        assertEquals(
                WorldItemActivationResult.Status.COMMITTED,
                service.commitActivate(activation.ticket().orElseThrow()).status());
        assertEquals(original, service.snapshot(original.id()).orElseThrow());
        assertTrue(service.canonicalSnapshot().dormantChunkKeys().isEmpty());
    }

    @Test
    void partialPickupRemainderKeepsIdAcrossPaging() {
        LogicalWorldItemService service = service(2);
        WorldItemSnapshot original = spawn(service, new ChunkKey(0, 0), 3);
        WorldItemReservation reservation = service.reserve(original.id(), 2)
                .reservation().orElseThrow();
        service.commit(reservation.id());
        WorldItemSnapshot remainder = service.snapshot(original.id()).orElseThrow();
        assertEquals(original.id(), remainder.id());
        assertEquals(1, remainder.stack().count());

        WorldItemHibernateResult hibernate = preparedHibernate(service, remainder);
        service.commitHibernate(hibernate.ticket().orElseThrow());
        WorldItemActivationResult activation = service.prepareActivate(
                hibernate.payload().orElseThrow().chunkKey(),
                hibernate.payload().orElseThrow());
        service.commitActivate(activation.ticket().orElseThrow());

        WorldItemSnapshot restored = service.snapshot(original.id()).orElseThrow();
        assertEquals(original.id(), restored.id());
        assertEquals(1, restored.stack().count());
        assertEquals(remainder.revision(), restored.revision());
    }

    @ParameterizedTest
    @EnumSource(WorldItemPhysicalState.class)
    void pagingPreservesEveryCanonicalPhysicalState(WorldItemPhysicalState state) {
        LogicalWorldItemService service = service(2);
        WorldItemSnapshot spawned = spawn(service, new ChunkKey(0, 0), 1);
        WorldItemSnapshot revised = service.updateMotion(new WorldItemMotionUpdate(
                spawned.id(), spawned.revision(),
                spawned.positionX(), spawned.positionY(), spawned.positionZ(),
                spawned.velocityX(), spawned.velocityY(), spawned.velocityZ(), state))
                .snapshot().orElseThrow().runtime().item();

        WorldItemHibernateResult hibernate = preparedHibernate(service, revised);
        service.commitHibernate(hibernate.ticket().orElseThrow());
        WorldItemRestoreEntry dormant = hibernate.payload().orElseThrow().entries().get(0);
        assertEquals(state, dormant.physicalState());

        WorldItemActivationResult activation = service.prepareActivate(
                hibernate.payload().orElseThrow().chunkKey(),
                hibernate.payload().orElseThrow());
        service.commitActivate(activation.ticket().orElseThrow());
        assertEquals(state, service.physicalSnapshot(spawned.id()).orElseThrow().state());
    }

    @Test
    void activeExtractionAndSpawnReservationsExcludePagingWithoutChangingState() {
        LogicalWorldItemService service = service(4);
        WorldItemSnapshot item = spawn(service, new ChunkKey(0, 0), 3);
        LogicalWorldItemSnapshot beforeExtraction = service.canonicalSnapshot();
        WorldItemReservation extraction = service.reserve(item.id(), 1)
                .reservation().orElseThrow();

        assertEquals(
                WorldItemHibernateResult.Status.RESERVED,
                service.prepareHibernate(
                        new ChunkKey(0, 0), Map.of(item.id(), item.revision())).status());
        assertEquals(beforeExtraction.entries(), service.snapshots().stream()
                .map(snapshot -> new WorldItemRestoreEntry(
                        service.runtimeSnapshot(snapshot.id()).orElseThrow(),
                        service.physicalSnapshot(snapshot.id()).orElseThrow().state()))
                .toList());
        service.rollback(extraction.id());

        var pendingSpawn = service.reserveSpawn(request(new ChunkKey(0, 0), 1));
        assertEquals(
                WorldItemHibernateResult.Status.RESERVED,
                service.prepareHibernate(
                        new ChunkKey(0, 0), Map.of(item.id(), item.revision())).status());
        assertEquals(item, service.snapshot(item.id()).orElseThrow());
        service.rollbackSpawn(pendingSpawn.reservation().orElseThrow().id());
    }

    @Test
    void duplicateIdsWrongBucketAndAllocatorConflictsFailClosed() {
        ChunkKey key = new ChunkKey(-1, 1);
        WorldItemRestoreEntry entry = entry(7, key, 0, WorldItemPhysicalState.ACTIVE);
        WorldItemHibernatePayload duplicate = new WorldItemHibernatePayload(
                key, List.of(entry, entry), 8, false);
        LogicalWorldItemService target = service(4);

        assertEquals(
                WorldItemActivationResult.Status.DUPLICATE_ID,
                target.prepareActivate(key, duplicate).status());
        assertEquals(
                WorldItemActivationResult.Status.WRONG_CHUNK,
                target.prepareActivate(
                        new ChunkKey(0, 1),
                        new WorldItemHibernatePayload(
                                key, List.of(entry), 8, false)).status());
        assertEquals(
                WorldItemActivationResult.Status.INVALID_ALLOCATOR,
                target.prepareActivate(
                        key,
                        new WorldItemHibernatePayload(
                                key, List.of(entry), 7, false)).status());
        assertTrue(target.canonicalSnapshot().entries().isEmpty());
    }

    @Test
    void revisionChangeInvalidatesHibernateTicketAndPrepareCancelIsSideEffectFree() {
        LogicalWorldItemService service = service(2);
        WorldItemSnapshot item = spawn(service, new ChunkKey(0, 0), 1);
        LogicalWorldItemSnapshot beforePrepare = service.canonicalSnapshot();
        WorldItemHibernateResult first = preparedHibernate(service, item);
        assertEquals(beforePrepare, service.canonicalSnapshot());

        WorldItemSnapshot changed = service.updateMotion(new WorldItemMotionUpdate(
                item.id(), item.revision(),
                item.positionX() + 1, item.positionY(), item.positionZ(),
                0, 0, 0, WorldItemPhysicalState.GROUNDED))
                .snapshot().orElseThrow().runtime().item();
        assertEquals(
                WorldItemHibernateResult.Status.STALE_TICKET,
                service.commitHibernate(first.ticket().orElseThrow()).status());
        assertEquals(changed, service.snapshot(item.id()).orElseThrow());

        WorldItemHibernateResult second = preparedHibernate(service, changed);
        assertEquals(
                WorldItemHibernateResult.Status.CANCELED,
                service.cancelHibernate(second.ticket().orElseThrow()).status());
        assertEquals(
                WorldItemHibernateResult.Status.STALE_TICKET,
                service.commitHibernate(second.ticket().orElseThrow()).status());
        assertEquals(changed, service.snapshot(item.id()).orElseThrow());
    }

    @Test
    void staleRepeatedAndForeignTicketsAreRejectedWithoutSwallowingErrors() {
        LogicalWorldItemService first = service(2);
        LogicalWorldItemService second = service(2);
        WorldItemSnapshot item = spawn(first, new ChunkKey(0, 0), 1);
        WorldItemHibernateResult hibernate = preparedHibernate(first, item);

        assertEquals(
                WorldItemHibernateResult.Status.FOREIGN_TICKET,
                second.commitHibernate(hibernate.ticket().orElseThrow()).status());
        assertEquals(
                WorldItemHibernateResult.Status.COMMITTED,
                first.commitHibernate(hibernate.ticket().orElseThrow()).status());
        assertEquals(
                WorldItemHibernateResult.Status.STALE_TICKET,
                first.commitHibernate(hibernate.ticket().orElseThrow()).status());

        WorldItemActivationResult activation = first.prepareActivate(
                hibernate.payload().orElseThrow().chunkKey(),
                hibernate.payload().orElseThrow());
        assertEquals(
                WorldItemActivationResult.Status.FOREIGN_TICKET,
                second.commitActivate(activation.ticket().orElseThrow()).status());
        assertEquals(
                WorldItemActivationResult.Status.CANCELED,
                first.cancelActivate(activation.ticket().orElseThrow()).status());
        assertEquals(
                WorldItemActivationResult.Status.STALE_TICKET,
                first.commitActivate(activation.ticket().orElseThrow()).status());

        LogicalWorldItemService errorService = service(2);
        WorldItemSnapshot errorItem = spawn(errorService, new ChunkKey(0, 0), 1);
        AssertionError sentinel = new AssertionError("expected revision failure");
        Map<WorldItemId, Long> exploding = new java.util.AbstractMap<>() {
            @Override
            public Long get(Object ignored) {
                throw sentinel;
            }

            @Override
            public java.util.Set<Entry<WorldItemId, Long>> entrySet() {
                return java.util.Set.of(Map.entry(
                        errorItem.id(), errorItem.revision()));
            }
        };
        assertEquals(sentinel, assertThrows(
                AssertionError.class,
                () -> errorService.prepareHibernate(new ChunkKey(0, 0), exploding)));
    }

    @Test
    void fullSnapshotContainsActiveAndDormantExactlyOnceAndRestoresBucketsAndIds() {
        LogicalWorldItemService source = service(4);
        WorldItemSnapshot dormant = spawn(source, new ChunkKey(-1, -1), 1);
        WorldItemHibernateResult hibernate = preparedHibernate(source, dormant);
        source.commitHibernate(hibernate.ticket().orElseThrow());
        WorldItemSnapshot active = spawn(source, new ChunkKey(1, 1), 1);

        LogicalWorldItemSnapshot snapshot = source.canonicalSnapshot();

        assertEquals(List.of(dormant.id(), active.id()), snapshot.entries().stream()
                .map(entry -> entry.runtime().item().id()).toList());
        assertEquals(2, snapshot.entries().stream()
                .map(entry -> entry.runtime().item().id()).distinct().count());
        assertEquals(Map.of(dormant.id(), new ChunkKey(-1, -1)),
                snapshot.dormantChunkKeys());

        LogicalWorldItemService restored = service(4);
        assertEquals(
                com.overlord.worlditem.api.WorldItemRestoreResult.Status.RESTORED,
                restored.restoreCanonical(snapshot).status());
        assertEquals(snapshot, restored.canonicalSnapshot());
        assertEquals(active, restored.snapshot(active.id()).orElseThrow());
        assertTrue(restored.snapshot(dormant.id()).isEmpty());

        WorldItemSnapshot next = spawn(restored, new ChunkKey(0, 0), 1);
        assertEquals(snapshot.nextItemId(), next.id().value());
    }

    @Test
    void activationPublishesActiveStateInsideCallbackThenForgetsRollbackAggregate() {
        LogicalWorldItemService service = service(2);
        WorldItemSnapshot item = spawn(service, new ChunkKey(0, 0), 1);
        WorldItemHibernateResult hibernate = preparedHibernate(service, item);
        service.commitHibernate(hibernate.ticket().orElseThrow());
        WorldItemActivationResult activation = service.prepareActivate(
                new ChunkKey(0, 0), hibernate.payload().orElseThrow());
        AtomicBoolean callbackReached = new AtomicBoolean();

        WorldItemActivationResult committed = service.commitActivate(
                activation.ticket().orElseThrow(),
                () -> {
                    callbackReached.set(true);
                    assertEquals(item, service.snapshot(item.id()).orElseThrow(),
                            "projection publication must observe logical active state");
                });

        assertEquals(WorldItemActivationResult.Status.COMMITTED, committed.status());
        assertTrue(callbackReached.get());
        assertEquals(0, service.pagingMetrics().activationTicketCount(),
                "successful publication must not retain the rollback aggregate");
        assertEquals(
                WorldItemActivationResult.Status.STALE_TICKET,
                service.commitActivate(activation.ticket().orElseThrow()).status());
    }

    static Stream<Arguments> sameServiceCallbackMutations() {
        return Stream.of(
                Arguments.of("spawn", (Consumer<CallbackContext>) context ->
                        context.service.spawn(request(new ChunkKey(1, 0), 1))),
                Arguments.of("pickup reserve", (Consumer<CallbackContext>) context ->
                        context.service.reserve(context.item.id(), 1)),
                Arguments.of("motion", (Consumer<CallbackContext>) context -> {
                    WorldItemSnapshot item = context.service.snapshot(
                            context.item.id()).orElseThrow();
                    context.service.updateMotion(new WorldItemMotionUpdate(
                            item.id(), item.revision(),
                            item.positionX() + 0.25, item.positionY(), item.positionZ(),
                            item.velocityX(), item.velocityY(), item.velocityZ(),
                            WorldItemPhysicalState.ACTIVE));
                }),
                Arguments.of("restore", (Consumer<CallbackContext>) context ->
                        context.service.restoreCanonical(context.before)),
                Arguments.of("paging", (Consumer<CallbackContext>) context ->
                        context.service.prepareHibernate(
                                context.key,
                                Map.of(context.item.id(), context.item.revision()))),
                Arguments.of("expiry", (Consumer<CallbackContext>) context ->
                        context.service.deliverWorldTick(
                                context.service.currentWorldTick() + 1L)));
    }

    @ParameterizedTest(name = "same-service {0} is guard-first during projection callback")
    @MethodSource("sameServiceCallbackMutations")
    void projectionCallbackRejectsEverySameServiceMutationBeforeCanonicalChange(
            String ignored,
            Consumer<CallbackContext> mutation) {
        LogicalWorldItemService service = service(4);
        ChunkKey key = new ChunkKey(0, 0);
        WorldItemSnapshot item = spawn(service, key, 1);
        WorldItemHibernateResult hibernate = preparedHibernate(service, item);
        service.commitHibernate(hibernate.ticket().orElseThrow());
        WorldItemActivationResult activation = service.prepareActivate(
                key, hibernate.payload().orElseThrow());
        LogicalWorldItemSnapshot before = service.canonicalSnapshot();
        long tickBefore = service.currentWorldTick();
        CallbackContext context = new CallbackContext(service, key, item, before);

        assertThrows(IllegalStateException.class, () -> service.commitActivate(
                activation.ticket().orElseThrow(),
                () -> {
                    assertEquals(item, service.snapshot(item.id()).orElseThrow(),
                            "reads remain valid while the callback sees active state");
                    mutation.accept(context);
                }));

        assertEquals(before, service.canonicalSnapshot());
        assertEquals(tickBefore, service.currentWorldTick());
        assertTrue(service.snapshot(item.id()).isEmpty());
        assertEquals(0, service.pagingMetrics().activationTicketCount());
        assertEquals(WorldItemActivationResult.Status.STALE_TICKET,
                service.commitActivate(activation.ticket().orElseThrow()).status());
    }

    @Test
    void hibernateProjectionFailureRestoresExactActiveStateAndKeepsTicketRetryable() {
        LogicalWorldItemService service = service(2);
        ChunkKey key = new ChunkKey(-1, 0);
        WorldItemSnapshot item = spawn(service, key, 1);
        WorldItemHibernateResult hibernate = preparedHibernate(service, item);
        LogicalWorldItemSnapshot before = service.canonicalSnapshot();
        AssertionError sentinel = new AssertionError("hibernate projection failure");

        assertSame(sentinel, assertThrows(AssertionError.class, () ->
                service.commitHibernate(
                        hibernate.ticket().orElseThrow(),
                        () -> {
                            assertTrue(service.snapshot(item.id()).isEmpty(),
                                    "projection callback observes detached dormant state");
                            throw sentinel;
                        })));

        assertEquals(before, service.canonicalSnapshot());
        assertEquals(item, service.snapshot(item.id()).orElseThrow());
        assertEquals(WorldItemHibernateResult.Status.COMMITTED,
                service.commitHibernate(
                        hibernate.ticket().orElseThrow(), () -> {}).status(),
                "the exact prepared transaction remains retryable");
    }

    @Test
    void activationProjectionFailureDoesNotStaleUnrelatedPreparedTicket() {
        LogicalWorldItemService service = service(2);
        ChunkKey dormantKey = new ChunkKey(-1, 0);
        WorldItemSnapshot dormantItem = spawn(service, dormantKey, 1);
        WorldItemHibernateResult dormant = preparedHibernate(service, dormantItem);
        service.commitHibernate(dormant.ticket().orElseThrow());

        ChunkKey activeKey = new ChunkKey(1, 0);
        WorldItemSnapshot activeItem = spawn(service, activeKey, 1);
        WorldItemActivationResult activation = service.prepareActivate(
                dormantKey, dormant.payload().orElseThrow());
        WorldItemHibernateResult unrelated = service.prepareHibernate(
                activeKey, Map.of(activeItem.id(), activeItem.revision()));
        AssertionError sentinel = new AssertionError("activation projection failure");

        assertSame(sentinel, assertThrows(AssertionError.class, () ->
                service.commitActivate(
                        activation.ticket().orElseThrow(), () -> {
                            throw sentinel;
                        })));

        assertEquals(WorldItemHibernateResult.Status.COMMITTED,
                service.commitHibernate(
                        unrelated.ticket().orElseThrow(), () -> {}).status(),
                "rollback must restore the exact epoch seen by unrelated tickets");
    }

    @Test
    void freshServiceRejectsCallerPayloadWithoutCurrentLiveMetadataOrDurableProof() {
        ChunkKey key = new ChunkKey(-1, 0);
        WorldItemHibernatePayload persisted = new WorldItemHibernatePayload(
                key,
                List.of(entry(7, key, 4, WorldItemPhysicalState.SLEEPING)),
                8,
                false);
        LogicalWorldItemService service = service(2);

        assertEquals(WorldItemActivationResult.Status.MISSING_METADATA,
                service.prepareActivate(key, persisted).status());
        assertTrue(service.snapshots().isEmpty());
        assertEquals(0, service.canonicalSnapshot().nextItemId());
        assertEquals(0, service.pagingMetrics().activationTicketCount());
    }

    @Test
    void persistedPayloadCannotRetainMutablePhysicsOrRenderingObjects() {
        LogicalWorldItemService service = service(2);
        WorldItemSnapshot item = spawn(service, new ChunkKey(0, 0), 1);
        WorldItemHibernatePayload payload = preparedHibernate(service, item)
                .payload().orElseThrow();

        assertEquals(List.of(WorldItemRestoreEntry.class), payload.entries().stream()
                .map(Object::getClass).distinct().toList());
        assertEquals(4, WorldItemHibernatePayload.class.getRecordComponents().length,
                "the dormant payload is only Chunk identity, immutable logical entries, "
                        + "and allocator authority");
        assertThrows(UnsupportedOperationException.class, () ->
                payload.entries().add(entry(99, new ChunkKey(0, 0), 0,
                        WorldItemPhysicalState.ACTIVE)));
    }

    private static LogicalWorldItemService service(int capacity) {
        return new LogicalWorldItemService(
                MainThreadGuard.captureCurrentThread(), capacity, 0);
    }

    private static WorldItemSnapshot spawn(
            LogicalWorldItemService service, ChunkKey key, int count) {
        return service.spawn(request(key, count)).item().orElseThrow();
    }

    private static WorldItemSpawnRequest request(ChunkKey key, int count) {
        return new WorldItemSpawnRequest(
                new ItemStack(DIRT.itemId(), count),
                key.worldOriginX() + 0.5,
                4.0,
                key.worldOriginZ() + 0.5,
                0.0, 0.0, 0.0,
                Optional.empty(),
                1);
    }

    private static WorldItemHibernateResult preparedHibernate(
            LogicalWorldItemService service, WorldItemSnapshot item) {
        ChunkKey key = ChunkKey.fromWorld(
                (int) Math.floor(item.positionX()),
                (int) Math.floor(item.positionZ()));
        WorldItemHibernateResult result = service.prepareHibernate(
                key, Map.of(item.id(), item.revision()));
        assertEquals(WorldItemHibernateResult.Status.PREPARED, result.status());
        return result;
    }

    private static WorldItemRestoreEntry entry(
            long id, ChunkKey key, long revision, WorldItemPhysicalState state) {
        WorldItemSnapshot item = new WorldItemSnapshot(
                new WorldItemId(id),
                new ItemStack(DIRT.itemId(), 1),
                key.worldOriginX() + 0.5,
                4.0,
                key.worldOriginZ() + 0.5,
                0.0, 0.0, 0.0,
                revision);
        return new WorldItemRestoreEntry(
                new WorldItemRuntimeSnapshot(item, Optional.empty(), 1, 1), state);
    }

    private static List<WorldItemId> payloadIds(WorldItemHibernatePayload payload) {
        return payload.entries().stream()
                .map(entry -> entry.runtime().item().id())
                .toList();
    }

    private record CallbackContext(
            LogicalWorldItemService service,
            ChunkKey key,
            WorldItemSnapshot item,
            LogicalWorldItemSnapshot before) {}

}

package com.overlord.worlditem;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.overlord.assets.ResourceLocation;
import com.overlord.core.thread.MainThreadGuard;
import com.overlord.inventory.api.ItemStack;
import com.overlord.voxel.ChunkKey;
import com.overlord.worlditem.api.SaveIdentity;
import com.overlord.worlditem.api.LogicalWorldItemSnapshot;
import com.overlord.worlditem.api.WorldItemActivationResult;
import com.overlord.worlditem.api.WorldItemActivationTicket;
import com.overlord.worlditem.api.WorldItemDurabilityVerifier;
import com.overlord.worlditem.api.WorldItemDurableProof;
import com.overlord.worlditem.api.WorldItemHibernateResult;
import com.overlord.worlditem.api.WorldItemHibernateTicket;
import com.overlord.worlditem.api.WorldItemId;
import com.overlord.worlditem.api.WorldItemLiveMetadata;
import com.overlord.worlditem.api.WorldItemLiveState;
import com.overlord.worlditem.api.WorldItemPageCachePolicy;
import com.overlord.worlditem.api.WorldItemPageDescriptor;
import com.overlord.worlditem.api.WorldItemDurablePageProof;
import com.overlord.worlditem.api.WorldItemPageMutation;
import com.overlord.worlditem.api.WorldItemPageReadView;
import com.overlord.worlditem.api.WorldItemPageSnapshot;
import com.overlord.worlditem.api.WorldItemPagingCheckpoint;
import com.overlord.worlditem.api.WorldItemPersistencePlan;
import com.overlord.worlditem.api.WorldItemPersistenceTicket;
import com.overlord.worlditem.api.WorldItemMotionUpdate;
import com.overlord.worlditem.api.WorldItemPhysicalState;
import com.overlord.worlditem.api.WorldItemRestoreEntry;
import com.overlord.worlditem.api.WorldItemRuntimeSnapshot;
import com.overlord.worlditem.api.WorldItemSnapshot;
import com.overlord.worlditem.api.WorldItemSpawnRequest;
import com.overlord.worlditem.api.WorldItemSpawnReserveResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class LogicalWorldItemDormantLifecycleTest {
    private static final SaveIdentity SAVE = new SaveIdentity(
            UUID.fromString("123e4567-e89b-12d3-a456-426614174000"));
    private static final ResourceLocation DIRT = ResourceLocation.parse("gaia:dirt");
    private static final long PUBLISHED_SEQUENCE = 42L;

    @Test
    void pagingCapableServiceKeepsCompleteLegacyRestoreV1Representable() {
        LogicalWorldItemService legacy = new LogicalWorldItemService(
                MainThreadGuard.captureCurrentThread(), 2, 0L);
        legacy.deliverWorldTick(0L);
        spawn(legacy, new ChunkKey(0, 0), 1, 0L);
        LogicalWorldItemSnapshot legacySnapshot = legacy.canonicalSnapshot();
        LogicalWorldItemService pagingCapable = service(2);

        assertEquals(com.overlord.worlditem.api.WorldItemRestoreResult.Status.RESTORED,
                pagingCapable.restoreCanonical(legacySnapshot, 0L).status());
        assertEquals(LogicalWorldItemSnapshot.Completeness.LEGACY_COMPLETE,
                pagingCapable.canonicalSnapshot().completeness(),
                "active TTL metadata alone must not imply dormant paged authority");
        assertEquals(1, pagingCapable.liveMetadata().size());
    }

    @Test
    void linkedDurableHibernateCommitsProjectionAndConsumesBothTicketsTogether() {
        LogicalWorldItemService service = service(2);
        service.deliverWorldTick(0L);
        WorldItemSnapshot item = spawn(service, new ChunkKey(0, 0), 1, 0L);
        WorldItemHibernateResult prepared = preparedHibernate(service, item);
        var hibernateTicket = prepared.ticket().orElseThrow();
        var persistenceTicket = prepared.persistenceTicket().orElseThrow();
        var proof = validProof(prepared.persistencePlan().orElseThrow());
        AtomicInteger callbacks = new AtomicInteger();

        assertEquals(WorldItemHibernateResult.Status.COMMITTED,
                commitLinked(service, hibernateTicket, persistenceTicket, proof,
                        callbacks::incrementAndGet).status());

        assertEquals(1, callbacks.get());
        assertTrue(service.snapshot(item.id()).isEmpty());
        assertEquals(WorldItemLiveState.EVICTED_UNEXPIRED,
                service.liveMetadata().get(0).state());
        assertEquals(0, service.pagingMetrics().persistenceTicketCount());
        assertEquals(WorldItemHibernateResult.Status.STALE_TICKET,
                service.commitHibernate(hibernateTicket).status());
        assertEquals(WorldItemHibernateResult.Status.STALE_TICKET,
                service.commitPersistence(persistenceTicket, proof).status());
    }

    @Test
    void linkedCommitRejectsForeignAndMismatchedProofsBeforeProjectionAndCanRetry() {
        LogicalWorldItemService service = service(2);
        service.deliverWorldTick(0L);
        WorldItemSnapshot item = spawn(service, new ChunkKey(0, 0), 1, 0L);
        WorldItemHibernateResult prepared = preparedHibernate(service, item);
        var hibernateTicket = prepared.ticket().orElseThrow();
        var persistenceTicket = prepared.persistenceTicket().orElseThrow();
        WorldItemPersistencePlan plan = prepared.persistencePlan().orElseThrow();
        LogicalWorldItemSnapshot before = service.canonicalSnapshot();
        AtomicInteger callbacks = new AtomicInteger();
        List<WorldItemDurableProof> invalidProofs = List.of(
                new WorldItemDurableProof() {},
                new TestProof(plan.intendedCheckpoint().checkpointRevision(),
                        "00".repeat(32), PUBLISHED_SEQUENCE),
                new TestProof(plan.intendedCheckpoint().checkpointRevision(),
                        plan.transactionDigest(), PUBLISHED_SEQUENCE - 1L));

        for (WorldItemDurableProof invalid : invalidProofs) {
            assertThrows(IllegalArgumentException.class, () -> commitLinked(
                    service, hibernateTicket, persistenceTicket, invalid,
                    callbacks::incrementAndGet));
            assertEquals(before, service.canonicalSnapshot());
        }
        assertEquals(0, callbacks.get());
        assertEquals(1, service.pagingMetrics().persistenceTicketCount());

        assertEquals(WorldItemHibernateResult.Status.COMMITTED,
                commitLinked(service, hibernateTicket, persistenceTicket,
                        validProof(plan), callbacks::incrementAndGet).status());
        assertEquals(1, callbacks.get());
    }

    @Test
    void linkedCommitRejectsForeignHibernateTicketWithoutConsumingExactPair() {
        LogicalWorldItemService service = service(2);
        service.deliverWorldTick(0L);
        WorldItemSnapshot item = spawn(service, new ChunkKey(0, 0), 1, 0L);
        WorldItemHibernateResult prepared = preparedHibernate(service, item);
        WorldItemPersistencePlan plan = prepared.persistencePlan().orElseThrow();
        AtomicInteger callbacks = new AtomicInteger();

        assertEquals(WorldItemHibernateResult.Status.FOREIGN_TICKET,
                commitLinked(service, WorldItemHibernateTicket.issuedBy(new Object()),
                        prepared.persistenceTicket().orElseThrow(), validProof(plan),
                        callbacks::incrementAndGet).status());
        assertEquals(0, callbacks.get());
        assertEquals(WorldItemHibernateResult.Status.COMMITTED,
                commitLinked(service, prepared.ticket().orElseThrow(),
                        prepared.persistenceTicket().orElseThrow(), validProof(plan),
                        callbacks::incrementAndGet).status());
    }

    @Test
    void linkedCommitRejectsForeignPersistenceTicketWithoutConsumingExactPair() {
        LogicalWorldItemService service = service(2);
        service.deliverWorldTick(0L);
        WorldItemSnapshot item = spawn(service, new ChunkKey(0, 0), 1, 0L);
        WorldItemHibernateResult prepared = preparedHibernate(service, item);
        WorldItemPersistencePlan plan = prepared.persistencePlan().orElseThrow();
        AtomicInteger callbacks = new AtomicInteger();

        assertEquals(WorldItemHibernateResult.Status.FOREIGN_TICKET,
                commitLinked(service, prepared.ticket().orElseThrow(),
                        WorldItemPersistenceTicket.issuedBy(new Object()), validProof(plan),
                        callbacks::incrementAndGet).status());
        assertEquals(0, callbacks.get());
        assertEquals(WorldItemHibernateResult.Status.COMMITTED,
                commitLinked(service, prepared.ticket().orElseThrow(),
                        prepared.persistenceTicket().orElseThrow(), validProof(plan),
                        callbacks::incrementAndGet).status());
    }

    @Test
    void linkedCommitRejectsTicketsFromDifferentPreparationsBeforeProjection() {
        LogicalWorldItemService service = service(4);
        service.deliverWorldTick(0L);
        WorldItemSnapshot firstItem = spawn(
                service, new ChunkKey(0, 0), 1, 0L);
        WorldItemSnapshot secondItem = spawn(
                service, new ChunkKey(1, 0), 1, 0L);
        WorldItemHibernateResult first = preparedHibernate(service, firstItem);
        WorldItemHibernateResult second = preparedHibernate(service, secondItem);
        AtomicInteger callbacks = new AtomicInteger();

        assertEquals(WorldItemHibernateResult.Status.STALE_TICKET,
                commitLinked(service, first.ticket().orElseThrow(),
                        second.persistenceTicket().orElseThrow(),
                        validProof(second.persistencePlan().orElseThrow()),
                        callbacks::incrementAndGet).status());
        assertEquals(0, callbacks.get());
        assertEquals(2, service.pagingMetrics().persistenceTicketCount());
        assertEquals(firstItem, service.snapshot(firstItem.id()).orElseThrow());
        assertEquals(secondItem, service.snapshot(secondItem.id()).orElseThrow());
        assertEquals(WorldItemHibernateResult.Status.COMMITTED,
                commitLinked(service, second.ticket().orElseThrow(),
                        second.persistenceTicket().orElseThrow(),
                        validProof(second.persistencePlan().orElseThrow()),
                        callbacks::incrementAndGet).status());
        assertEquals(1, callbacks.get());
    }

    @Test
    void linkedCommitRejectsStaleEpochAndRevisionBeforeProjection() {
        LogicalWorldItemService service = service(3);
        service.deliverWorldTick(0L);
        WorldItemSnapshot item = spawn(service, new ChunkKey(0, 0), 1, 0L);
        WorldItemHibernateResult prepared = preparedHibernate(service, item);
        spawn(service, new ChunkKey(1, 0), 1, 0L);
        AtomicInteger callbacks = new AtomicInteger();

        assertEquals(WorldItemHibernateResult.Status.STALE_TICKET,
                commitLinked(service, prepared.ticket().orElseThrow(),
                        prepared.persistenceTicket().orElseThrow(),
                        validProof(prepared.persistencePlan().orElseThrow()),
                        callbacks::incrementAndGet).status());
        assertEquals(0, callbacks.get());
        assertEquals(item, service.snapshot(item.id()).orElseThrow());
    }

    @Test
    void linkedCommitAllowsUnrelatedWorldTickProgressBeforeItemExpiry() {
        LogicalWorldItemService service = service(2);
        service.deliverWorldTick(100L);
        WorldItemSnapshot item = spawn(service, new ChunkKey(0, 0), 1, 100L);
        WorldItemHibernateResult prepared = preparedHibernate(service, item);
        AtomicInteger callbacks = new AtomicInteger();

        assertTrue(service.deliverWorldTick(1_000L).isEmpty());
        assertEquals(WorldItemHibernateResult.Status.COMMITTED,
                commitLinked(service, prepared.ticket().orElseThrow(),
                        prepared.persistenceTicket().orElseThrow(),
                        validProof(prepared.persistencePlan().orElseThrow()),
                        callbacks::incrementAndGet).status());

        assertEquals(1, callbacks.get());
        assertEquals(1_000L, service.currentWorldTick(),
                "publishing the older durable checkpoint must not rewind live time");
        assertEquals(WorldItemLiveState.EVICTED_UNEXPIRED,
                service.liveMetadata().get(0).state());
    }

    @Test
    void linkedCommitStillRejectsItemThatExpiresWhilePersistenceIsInFlight() {
        LogicalWorldItemService service = service(2);
        service.deliverWorldTick(100L);
        WorldItemSnapshot item = spawn(service, new ChunkKey(0, 0), 1, 100L);
        WorldItemHibernateResult prepared = preparedHibernate(service, item);
        AtomicInteger callbacks = new AtomicInteger();

        assertEquals(List.of(item.id()), service.deliverWorldTick(18_100L));
        assertEquals(WorldItemHibernateResult.Status.STALE_TICKET,
                commitLinked(service, prepared.ticket().orElseThrow(),
                        prepared.persistenceTicket().orElseThrow(),
                        validProof(prepared.persistencePlan().orElseThrow()),
                        callbacks::incrementAndGet).status());

        assertEquals(0, callbacks.get());
        assertTrue(service.snapshot(item.id()).isEmpty());
        assertTrue(service.liveMetadata().isEmpty());
    }

    @Test
    void linkedProjectionFailureRestoresExactLogicalStateAndBothTicketsRetry() {
        LogicalWorldItemService service = service(2);
        service.deliverWorldTick(0L);
        WorldItemSnapshot item = spawn(service, new ChunkKey(0, 0), 1, 0L);
        WorldItemHibernateResult prepared = preparedHibernate(service, item);
        var hibernateTicket = prepared.ticket().orElseThrow();
        var persistenceTicket = prepared.persistenceTicket().orElseThrow();
        var proof = validProof(prepared.persistencePlan().orElseThrow());
        LogicalWorldItemSnapshot canonicalBefore = service.canonicalSnapshot();
        List<WorldItemLiveMetadata> metadataBefore = service.liveMetadata();
        var metricsBefore = service.pagingMetrics();
        AssertionError sentinel = new AssertionError("linked projection failure");

        assertSame(sentinel, assertThrows(AssertionError.class, () -> commitLinked(
                service, hibernateTicket, persistenceTicket, proof,
                () -> { throw sentinel; })));

        assertEquals(canonicalBefore, service.canonicalSnapshot());
        assertEquals(metadataBefore, service.liveMetadata());
        assertEquals(metricsBefore, service.pagingMetrics());
        assertEquals(item, service.snapshot(item.id()).orElseThrow());
        assertEquals(WorldItemHibernateResult.Status.COMMITTED,
                commitLinked(service, hibernateTicket, persistenceTicket, proof,
                        () -> {}).status());
    }

    @Test
    void linkedProjectionCallbackCannotReenterSameLogicalService() {
        LogicalWorldItemService service = service(2);
        service.deliverWorldTick(0L);
        WorldItemHibernateResult prepared = preparedHibernate(service,
                spawn(service, new ChunkKey(0, 0), 1, 0L));
        var hibernateTicket = prepared.ticket().orElseThrow();
        var persistenceTicket = prepared.persistenceTicket().orElseThrow();
        var proof = validProof(prepared.persistencePlan().orElseThrow());

        assertEquals(WorldItemHibernateResult.Status.COMMITTED,
                commitLinked(service, hibernateTicket, persistenceTicket, proof,
                        () -> assertThrows(IllegalStateException.class,
                                () -> commitLinked(service, hibernateTicket,
                                        persistenceTicket, proof, () -> {})))
                        .status());
    }

    @Test
    void activeRevisionObservationIsExactDefensiveAndChunkScoped() {
        LogicalWorldItemService service = service(4);
        service.deliverWorldTick(0L);
        ChunkKey selected = new ChunkKey(-2, 3);
        WorldItemSnapshot first = spawn(service, selected, 1, 0L);
        WorldItemSnapshot second = spawn(service, selected, 1, 0L);
        spawn(service, selected.east(), 1, 0L);

        Map<WorldItemId, Long> observed = service.activeRevisionsInChunk(selected);

        assertEquals(
                Map.of(first.id(), first.revision(), second.id(), second.revision()),
                observed);
        assertThrows(
                UnsupportedOperationException.class,
                () -> observed.put(new WorldItemId(999L), 1L));
        WorldItemHibernateResult hibernate = service.prepareHibernate(selected, observed);
        assertEquals(WorldItemHibernateResult.Status.PREPARED, hibernate.status());
        assertEquals(
                Map.of(first.id(), first.revision(), second.id(), second.revision()),
                observed,
                "later service mutation cannot change the detached observation");
    }

    @Test
    void crossChunkRewritePreservesEveryDurableCoResidentInTheSourcePage() {
        LogicalWorldItemService service = service(4);
        service.deliverWorldTick(0L);
        ChunkKey source = new ChunkKey(0, 0);
        ChunkKey destination = new ChunkKey(1, 0);
        WorldItemSnapshot moving = spawn(service, source, 1, 0L);
        WorldItemSnapshot staying = spawn(service, source, 1, 0L);
        WorldItemHibernateResult initial = service.prepareHibernate(
                source, expectedRevisions(List.of(moving, staying)));
        WorldItemPersistencePlan initialPlan = initial.persistencePlan().orElseThrow();
        service.commitPersistence(
                initial.persistenceTicket().orElseThrow(), validProof(initialPlan));
        WorldItemPageDescriptor sourceDescriptor =
                initialPlan.intendedCheckpoint().pages().get(0);
        WorldItemPageSnapshot sourcePage = ((WorldItemPageMutation.Upsert)
                initialPlan.pageMutations().get(0)).page();
        activate(service, initialPlan.intendedCheckpoint(), sourceDescriptor, sourcePage);

        WorldItemSnapshot current = service.snapshot(moving.id()).orElseThrow();
        service.updateMotion(new WorldItemMotionUpdate(
                current.id(), current.revision(),
                destination.worldOriginX() + 0.5, current.positionY(),
                destination.worldOriginZ() + 0.5,
                current.velocityX(), current.velocityY(), current.velocityZ(),
                WorldItemPhysicalState.ACTIVE));
        WorldItemSnapshot moved = service.snapshot(moving.id()).orElseThrow();
        WorldItemHibernateResult prepared = service.prepareHibernate(
                destination, Map.of(moved.id(), moved.revision()));
        assertEquals(WorldItemHibernateResult.Status.PREPARED, prepared.status());
        WorldItemPersistencePlan plan = prepared.persistencePlan().orElseThrow();

        assertEquals(2, plan.pageMutations().size());
        WorldItemPageMutation.Upsert sourceRewrite = plan.pageMutations().stream()
                .filter(WorldItemPageMutation.Upsert.class::isInstance)
                .map(WorldItemPageMutation.Upsert.class::cast)
                .filter(value -> value.page().chunkKey().equals(source))
                .findFirst().orElseThrow();
        assertEquals(Optional.of(sourceDescriptor), sourceRewrite.expectedPrevious());
        assertEquals(List.of(staying.id()), sourceRewrite.page().entries().stream()
                .map(entry -> entry.runtime().item().id()).toList());
        assertEquals(List.of(staying.id()), plan.intendedCheckpoint().pages().stream()
                .filter(value -> value.chunkKey().equals(source))
                .flatMap(ignored -> sourceRewrite.page().entries().stream())
                .map(entry -> entry.runtime().item().id()).toList());
    }

    @Test
    void mutationAfterPrepareStalesProofCommitWithoutEvictingNewerCanonicalState() {
        LogicalWorldItemService service = service(2);
        service.deliverWorldTick(0L);
        WorldItemSnapshot original = spawn(service, new ChunkKey(0, 0), 2, 0L);
        WorldItemHibernateResult prepared = preparedHibernate(service, original);
        WorldItemPersistencePlan plan = prepared.persistencePlan().orElseThrow();
        service.updateMotion(new WorldItemMotionUpdate(
                original.id(), original.revision(),
                original.positionX() + 0.25, original.positionY(), original.positionZ(),
                0.25, 0.0, 0.0, WorldItemPhysicalState.ACTIVE));
        WorldItemSnapshot newer = service.snapshot(original.id()).orElseThrow();

        assertEquals(WorldItemHibernateResult.Status.STALE_TICKET,
                service.commitPersistence(
                        prepared.persistenceTicket().orElseThrow(), validProof(plan)).status());
        assertEquals(newer, service.snapshot(original.id()).orElseThrow());
        assertEquals(WorldItemLiveState.ACTIVE, service.liveMetadata().get(0).state());
    }

    @Test
    void overlappingPersistencePreparationForOneIdIsRejectedAndCannotRestoreStaleMetadata() {
        LogicalWorldItemService service = service(2);
        service.deliverWorldTick(0L);
        WorldItemSnapshot item = spawn(service, new ChunkKey(0, 0), 1, 0L);
        WorldItemHibernateResult first = preparedHibernate(service, item);
        assertEquals(WorldItemHibernateResult.Status.RESERVED,
                service.prepareHibernate(
                        new ChunkKey(0, 0), Map.of(item.id(), item.revision())).status());
        assertEquals(WorldItemHibernateResult.Status.CANCELED,
                service.cancelPersistence(first.persistenceTicket().orElseThrow()).status());
        assertEquals(WorldItemLiveState.ACTIVE, service.liveMetadata().get(0).state());
        assertEquals(WorldItemHibernateResult.Status.PREPARED,
                preparedHibernate(service, item).status());
    }

    @Test
    void dirtyPageAdmissionFailsClosedBeforeCreatingOutOfCacheDormantHistory() {
        LogicalWorldItemService service = service(33, new WorldItemPageCachePolicy(
                1_024, 32, 16L * 1_024L * 1_024L,
                64, 1_024, 16L * 1_024L * 1_024L,
                64, 64L * 1_024L));
        service.deliverWorldTick(0L);
        for (int index = 0; index < 33; index++) {
            ChunkKey key = new ChunkKey(index, 0);
            WorldItemSnapshot item = spawn(service, key, 1, 0L);
            WorldItemHibernateResult prepared = service.prepareHibernate(
                    key, Map.of(item.id(), item.revision()));
            if (index < 32) {
                assertEquals(WorldItemHibernateResult.Status.PREPARED, prepared.status());
                assertEquals(WorldItemHibernateResult.Status.COMMITTED,
                        service.commitHibernate(prepared.ticket().orElseThrow()).status());
            } else {
                assertEquals(WorldItemHibernateResult.Status.ALL_PINNED, prepared.status());
                assertEquals(item, service.snapshot(item.id()).orElseThrow());
            }
        }
        assertEquals(32, service.pagingMetrics().decodedPageCount());
        assertEquals(32, service.pagingMetrics().pinnedPageCount());
        assertEquals(32, service.pagingMetrics().dirtyEntryCount());
    }

    @Test
    void persistenceFailureRetainsExactCanonicalItemAndUnprovedStateStaysPinned() {
        LogicalWorldItemService service = service(4);
        service.deliverWorldTick(100L);
        WorldItemSnapshot item = spawn(service, new ChunkKey(-1, 2), 3, 100L);
        WorldItemRuntimeSnapshot runtime = service.runtimeSnapshot(item.id()).orElseThrow();
        WorldItemHibernateResult prepared = preparedHibernate(service, item);
        WorldItemPersistencePlan plan = prepared.persistencePlan().orElseThrow();

        assertThrows(IllegalArgumentException.class, () -> service.commitPersistence(
                prepared.persistenceTicket().orElseThrow(),
                new TestProof(
                        plan.intendedCheckpoint().checkpointRevision() + 1L,
                        plan.transactionDigest(),
                        PUBLISHED_SEQUENCE)));

        assertEquals(item, service.snapshot(item.id()).orElseThrow());
        assertEquals(runtime, service.runtimeSnapshot(item.id()).orElseThrow());
        assertEquals(1, service.pagingMetrics().pinnedPageCount());
        assertEquals(1, service.pagingMetrics().dirtyEntryCount());
        assertEquals(WorldItemLiveState.PENDING,
                service.liveMetadata().get(0).state());
    }

    @Test
    void exactProofSingleConsumesTicketThenActivationRestoresSameIdAndState() {
        LogicalWorldItemService service = service(4);
        service.deliverWorldTick(100L);
        WorldItemSnapshot item = spawn(service, new ChunkKey(0, 0), 3, 100L);
        WorldItemHibernateResult prepared = preparedHibernate(service, item);
        WorldItemPersistencePlan plan = prepared.persistencePlan().orElseThrow();
        WorldItemPersistenceTicket ticket = prepared.persistenceTicket().orElseThrow();
        TestProof proof = validProof(plan);

        assertEquals(WorldItemHibernateResult.Status.COMMITTED,
                service.commitPersistence(ticket, proof).status());
        assertTrue(service.snapshot(item.id()).isEmpty());
        assertEquals(WorldItemLiveState.EVICTED_UNEXPIRED,
                service.liveMetadata().get(0).state());
        assertEquals(WorldItemHibernateResult.Status.STALE_TICKET,
                service.commitPersistence(ticket, proof).status());

        WorldItemPageMutation.Upsert upsert = (WorldItemPageMutation.Upsert)
                plan.pageMutations().get(0);
        WorldItemPageDescriptor descriptor = plan.intendedCheckpoint().pages().get(0);
        WorldItemActivationResult activation;
        try (WorldItemPageReadView view = readView(plan, descriptor, upsert.page())) {
            activation = service.prepareActivate(view, descriptor);
        }
        assertEquals(WorldItemActivationResult.Status.PREPARED, activation.status());
        assertEquals(WorldItemActivationResult.Status.FOREIGN_TICKET,
                service.commitActivate(WorldItemActivationTicket.issuedBy(new Object()))
                        .status());
        assertEquals(WorldItemActivationResult.Status.COMMITTED,
                service.commitActivate(activation.ticket().orElseThrow()).status());
        assertEquals(WorldItemActivationResult.Status.STALE_TICKET,
                service.commitActivate(activation.ticket().orElseThrow()).status());
        WorldItemSnapshot restored = service.snapshot(item.id()).orElseThrow();
        assertEquals(item.id(), restored.id());
        assertEquals(item.stack(), restored.stack());
        assertEquals(item.revision(), restored.revision());
        assertEquals(18_100L,
                service.runtimeSnapshot(item.id()).orElseThrow().expiresAtWorldTick());
    }

    @Test
    void pinnedProjectionFailureRestoresExactEvictedProofStateAndCanRetry() {
        LogicalWorldItemService service = service(2);
        service.deliverWorldTick(0L);
        WorldItemSnapshot item = spawn(service, new ChunkKey(0, 0), 1, 0L);
        WorldItemHibernateResult hibernate = preparedHibernate(service, item);
        WorldItemPersistencePlan plan = hibernate.persistencePlan().orElseThrow();
        service.commitPersistence(
                hibernate.persistenceTicket().orElseThrow(), validProof(plan));
        WorldItemPageMutation.Upsert upsert = (WorldItemPageMutation.Upsert)
                plan.pageMutations().get(0);
        WorldItemPageDescriptor descriptor = plan.intendedCheckpoint().pages().get(0);
        LogicalWorldItemSnapshot canonicalBefore = service.canonicalSnapshot();
        List<WorldItemLiveMetadata> metadataBefore = service.liveMetadata();
        int pinsBefore = service.pagingMetrics().pinnedPageCount();
        int pagesBefore = service.pagingMetrics().decodedPageCount();
        WorldItemActivationResult activation;
        try (WorldItemPageReadView view = readView(plan, descriptor, upsert.page())) {
            activation = service.prepareActivate(view, descriptor);
        }
        AssertionError exact = new AssertionError("projection-create");

        assertSame(exact, assertThrows(AssertionError.class, () ->
                service.commitActivate(
                        activation.ticket().orElseThrow(),
                        () -> {
                            assertEquals(item,
                                    service.snapshot(item.id()).orElseThrow());
                            throw exact;
                        })));

        assertEquals(canonicalBefore, service.canonicalSnapshot());
        assertEquals(metadataBefore, service.liveMetadata());
        assertEquals(WorldItemLiveState.EVICTED_UNEXPIRED,
                service.liveMetadata().get(0).state());
        assertEquals(descriptor.pageHash(), service.liveMetadata().get(0)
                .durableProof().orElseThrow().pageHash());
        assertTrue(service.snapshot(item.id()).isEmpty());
        assertEquals(pinsBefore, service.pagingMetrics().pinnedPageCount());
        assertEquals(pagesBefore, service.pagingMetrics().decodedPageCount());
        assertEquals(0, service.pagingMetrics().activationTicketCount());
        assertEquals(WorldItemActivationResult.Status.STALE_TICKET,
                service.commitActivate(activation.ticket().orElseThrow()).status());

        WorldItemActivationResult retry;
        try (WorldItemPageReadView view = readView(plan, descriptor, upsert.page())) {
            retry = service.prepareActivate(view, descriptor);
        }
        assertEquals(WorldItemActivationResult.Status.COMMITTED,
                service.commitActivate(retry.ticket().orElseThrow()).status());
        assertEquals(item, service.snapshot(item.id()).orElseThrow());
    }

    @Test
    void foreignTicketProofFieldMismatchAndReplayFailWithoutEviction() {
        LogicalWorldItemService service = service(2);
        service.deliverWorldTick(0L);
        WorldItemSnapshot item = spawn(service, new ChunkKey(0, 0), 1, 0L);
        WorldItemHibernateResult prepared = preparedHibernate(service, item);
        WorldItemPersistencePlan plan = prepared.persistencePlan().orElseThrow();

        assertEquals(WorldItemHibernateResult.Status.FOREIGN_TICKET,
                service.commitPersistence(
                        WorldItemPersistenceTicket.issuedBy(new Object()),
                        validProof(plan)).status());
        List<TestProof> mismatches = List.of(
                new TestProof(
                        plan.intendedCheckpoint().checkpointRevision() + 1L,
                        plan.transactionDigest(), PUBLISHED_SEQUENCE),
                new TestProof(
                        plan.intendedCheckpoint().checkpointRevision(),
                        "ff".repeat(32), PUBLISHED_SEQUENCE),
                new TestProof(
                        plan.intendedCheckpoint().checkpointRevision(),
                        plan.transactionDigest(), PUBLISHED_SEQUENCE + 1L));
        for (TestProof mismatch : mismatches) {
            assertThrows(IllegalArgumentException.class, () -> service.commitPersistence(
                    prepared.persistenceTicket().orElseThrow(), mismatch));
            assertEquals(item, service.snapshot(item.id()).orElseThrow());
        }
        assertEquals(WorldItemHibernateResult.Status.COMMITTED,
                service.commitPersistence(
                        prepared.persistenceTicket().orElseThrow(),
                        validProof(plan)).status());
    }

    @Test
    void activationRejectsMetadataMismatchDuplicateCollisionAndExpiredPage() {
        LogicalWorldItemService service = service(4);
        service.deliverWorldTick(0L);
        WorldItemSnapshot item = spawn(service, new ChunkKey(1, -1), 1, 0L);
        WorldItemHibernateResult prepared = preparedHibernate(service, item);
        WorldItemPersistencePlan plan = prepared.persistencePlan().orElseThrow();
        service.commitPersistence(
                prepared.persistenceTicket().orElseThrow(), validProof(plan));
        WorldItemPageMutation.Upsert upsert = (WorldItemPageMutation.Upsert)
                plan.pageMutations().get(0);
        WorldItemPageDescriptor descriptor = plan.intendedCheckpoint().pages().get(0);
        WorldItemRestoreEntry entry = upsert.page().entries().get(0);
        WorldItemRuntimeSnapshot runtime = entry.runtime();
        WorldItemRuntimeSnapshot wrongExpiry = new WorldItemRuntimeSnapshot(
                runtime.item(), runtime.source(), runtime.spawnTick(),
                runtime.pickupAvailableTick(), runtime.expiresAtWorldTick() + 1L);
        WorldItemPageSnapshot mismatch = new WorldItemPageSnapshot(
                upsert.page().chunkKey(), upsert.page().pageRevision(),
                List.of(new WorldItemRestoreEntry(wrongExpiry, entry.physicalState())));
        try (WorldItemPageReadView view = readView(plan, descriptor, mismatch)) {
            assertEquals(WorldItemActivationResult.Status.METADATA_MISMATCH,
                    service.prepareActivate(view, descriptor).status());
        }

        WorldItemPageSnapshot duplicate = new WorldItemPageSnapshot(
                upsert.page().chunkKey(), upsert.page().pageRevision(),
                List.of(entry, entry));
        WorldItemPageDescriptor duplicateDescriptor = new WorldItemPageDescriptor(
                descriptor.chunkKey(), descriptor.pageRevision(),
                descriptor.pageHash(), 2, 2);
        WorldItemPagingCheckpoint duplicateCheckpoint = new WorldItemPagingCheckpoint(
                plan.intendedCheckpoint().saveIdentity(),
                plan.intendedCheckpoint().checkpointRevision(),
                plan.intendedCheckpoint().worldTick(),
                plan.intendedCheckpoint().nextItemId(),
                plan.intendedCheckpoint().itemIdsExhausted(),
                2, List.of(duplicateDescriptor));
        LogicalWorldItemService duplicateTarget = service(2);
        duplicateTarget.deliverWorldTick(0L);
        try (WorldItemPageReadView view = new TestReadView(
                PUBLISHED_SEQUENCE, plan.transactionDigest(), duplicateCheckpoint,
                Map.of(duplicateDescriptor, duplicate))) {
            assertEquals(WorldItemActivationResult.Status.INVALID_VIEW,
                    duplicateTarget.prepareActivate(view, duplicateDescriptor).status());
        }

        WorldItemPageDescriptor staleRevision = new WorldItemPageDescriptor(
                descriptor.chunkKey(), descriptor.pageRevision() + 1L,
                descriptor.pageHash(), descriptor.encodedEntryCount(),
                descriptor.expectedLiveCountAtCheckpointTick());
        try (WorldItemPageReadView view = readView(plan, descriptor, upsert.page())) {
            assertEquals(WorldItemActivationResult.Status.METADATA_MISMATCH,
                    service.prepareActivate(view, staleRevision).status());
        }
        WorldItemPageDescriptor staleHash = new WorldItemPageDescriptor(
                descriptor.chunkKey(), descriptor.pageRevision(),
                "ab".repeat(32), descriptor.encodedEntryCount(),
                descriptor.expectedLiveCountAtCheckpointTick());
        try (WorldItemPageReadView view = readView(plan, descriptor, upsert.page())) {
            assertEquals(WorldItemActivationResult.Status.METADATA_MISMATCH,
                    service.prepareActivate(view, staleHash).status());
        }
        WorldItemPageDescriptor wrongKey = new WorldItemPageDescriptor(
                new ChunkKey(2, -1), descriptor.pageRevision(),
                descriptor.pageHash(), descriptor.encodedEntryCount(),
                descriptor.expectedLiveCountAtCheckpointTick());
        try (WorldItemPageReadView view = readView(plan, descriptor, upsert.page())) {
            assertEquals(WorldItemActivationResult.Status.METADATA_MISMATCH,
                    service.prepareActivate(view, wrongKey).status());
        }
        assertEquals(0, service.pagingMetrics().activationTicketCount());

        WorldItemPagingCheckpoint wrongSaveCheckpoint = new WorldItemPagingCheckpoint(
                new SaveIdentity(UUID.fromString(
                        "223e4567-e89b-12d3-a456-426614174000")),
                plan.intendedCheckpoint().checkpointRevision(),
                plan.intendedCheckpoint().worldTick(),
                plan.intendedCheckpoint().nextItemId(),
                plan.intendedCheckpoint().itemIdsExhausted(),
                plan.intendedCheckpoint().totalLiveItemCount(),
                plan.intendedCheckpoint().pages());
        try (WorldItemPageReadView view = new TestReadView(
                42L, "42".repeat(32), wrongSaveCheckpoint,
                Map.of(descriptor, upsert.page()))) {
            assertEquals(WorldItemActivationResult.Status.INVALID_VIEW,
                    service.prepareActivate(view, descriptor).status());
        }
        assertEquals(0, service.pagingMetrics().activationTicketCount());

        LogicalWorldItemService missingMetadata = service(4);
        missingMetadata.deliverWorldTick(0L);
        try (WorldItemPageReadView view = readView(plan, descriptor, upsert.page())) {
            assertEquals(WorldItemActivationResult.Status.INVALID_VIEW,
                    missingMetadata.prepareActivate(view, descriptor).status());
        }
        WorldItemLiveMetadata currentMetadata = service.liveMetadata().get(0);
        LogicalWorldItemService proofMismatch = service(4);
        WorldItemLiveMetadata wrongProof = new WorldItemLiveMetadata(
                currentMetadata.id(),
                currentMetadata.intendedChunkKey(),
                currentMetadata.intendedPageRevision(),
                currentMetadata.expiresAtWorldTick(),
                currentMetadata.state(),
                Optional.of(new WorldItemDurablePageProof(
                        currentMetadata.intendedChunkKey(),
                        currentMetadata.intendedPageRevision(),
                        "cd".repeat(32))));
        assertFalse(proofMismatch.restorePagingState(
                plan.intendedCheckpoint(), List.of(wrongProof), List.of(upsert.page())));
        try (WorldItemPageReadView view = readView(plan, descriptor, upsert.page())) {
            assertEquals(WorldItemActivationResult.Status.INVALID_VIEW,
                    proofMismatch.prepareActivate(view, descriptor).status());
        }
        assertEquals(0, proofMismatch.pagingMetrics().activationTicketCount());

        WorldItemActivationResult valid;
        try (WorldItemPageReadView view = readView(plan, descriptor, upsert.page())) {
            valid = service.prepareActivate(view, descriptor);
        }
        service.commitActivate(valid.ticket().orElseThrow());
        try (WorldItemPageReadView view = readView(plan, descriptor, upsert.page())) {
            assertEquals(WorldItemActivationResult.Status.COLLISION,
                    service.prepareActivate(view, descriptor).status());
        }
        assertEquals(0, service.pagingMetrics().activationTicketCount());
        service.deliverWorldTick(18_000L);
        try (WorldItemPageReadView view = readView(plan, descriptor, upsert.page())) {
            assertEquals(WorldItemActivationResult.Status.EXPIRED,
                    service.prepareActivate(view, descriptor).status());
        }
        assertEquals(0, service.pagingMetrics().activationTicketCount());
        assertTrue(service.snapshot(item.id()).isEmpty());
        assertTrue(service.liveMetadata().isEmpty());
    }

    @Test
    void aggregateCapacityCountsActiveDecodedEvictedAndUniquePendingExactlyOnce() {
        LogicalWorldItemService service = service(4);
        service.deliverWorldTick(0L);
        WorldItemSnapshot decoded = spawn(service, new ChunkKey(0, 0), 1, 0L);
        WorldItemSnapshot active = spawn(service, new ChunkKey(1, 0), 1, 0L);
        WorldItemSnapshot evicted = spawn(service, new ChunkKey(2, 0), 1, 0L);
        WorldItemHibernateResult prepared = preparedHibernate(service, decoded);
        service.commitHibernate(prepared.ticket().orElseThrow());
        WorldItemHibernateResult evict = preparedHibernate(service, evicted);
        WorldItemPersistencePlan evictPlan = evict.persistencePlan().orElseThrow();
        service.commitPersistence(
                evict.persistenceTicket().orElseThrow(), validProof(evictPlan));
        var pending = service.reserveSpawn(request(new ChunkKey(3, 0), 1, 0L));

        assertEquals(4, service.pagingMetrics().liveMetadataCount());
        assertEquals(1, service.pagingMetrics().activeDtoCount());
        assertEquals(1, service.pagingMetrics().decodedDormantDtoCount());
        assertEquals(1, service.pagingMetrics().evictedUnexpiredCount());
        assertEquals(1, service.pagingMetrics().pendingCount());
        assertEquals(4, service.pagingMetrics().expiryIndexCount());
        assertEquals(com.overlord.worlditem.api.WorldItemSpawnResult.Status.REJECTED,
                service.spawn(request(new ChunkKey(4, 0), 1, 0L)).status());

        service.rollbackSpawn(pending.reservation().orElseThrow().id());
        assertEquals(3, service.pagingMetrics().liveMetadataCount());
        assertEquals(List.of(decoded.id(), active.id(), evicted.id()),
                service.liveMetadata().stream()
                .map(com.overlord.worlditem.api.WorldItemLiveMetadata::id)
                .sorted(java.util.Comparator.comparingLong(WorldItemId::value))
                .toList());
    }

    @Test
    void exactHardBoundSpansAllFourStatesAndExpiryStalesReservationsAndTickets() {
        LogicalWorldItemService service = service(1_024);
        service.deliverWorldTick(0L);
        var pendingSpawn = service.reserveSpawn(request(new ChunkKey(-1, 0), 1, 0L));
        ChunkKey activeKey = new ChunkKey(0, 0);
        ChunkKey decodedKey = new ChunkKey(0, 1);
        ChunkKey evictedKey = new ChunkKey(0, 2);
        List<WorldItemSnapshot> activeItems = new ArrayList<>();
        List<WorldItemSnapshot> decodedItems = new ArrayList<>();
        List<WorldItemSnapshot> evictedItems = new ArrayList<>();
        for (int index = 0; index < 341; index++) {
            activeItems.add(spawn(service, activeKey, 1, 0L));
        }
        for (int index = 0; index < 341; index++) {
            decodedItems.add(spawn(service, decodedKey, 1, 0L));
        }
        for (int index = 0; index < 341; index++) {
            evictedItems.add(spawn(service, evictedKey, 1, 0L));
        }
        WorldItemHibernateResult decoded = service.prepareHibernate(
                decodedKey, expectedRevisions(decodedItems));
        assertEquals(WorldItemHibernateResult.Status.PREPARED, decoded.status());
        assertEquals(WorldItemHibernateResult.Status.COMMITTED,
                service.commitHibernate(decoded.ticket().orElseThrow()).status());
        WorldItemHibernateResult evicted = service.prepareHibernate(
                evictedKey, expectedRevisions(evictedItems));
        assertEquals(WorldItemHibernateResult.Status.PREPARED, evicted.status());
        WorldItemPersistencePlan evictedPlan = evicted.persistencePlan().orElseThrow();
        assertEquals(WorldItemHibernateResult.Status.COMMITTED,
                service.commitPersistence(
                        evicted.persistenceTicket().orElseThrow(),
                        validProof(evictedPlan)).status());

        assertEquals(1_024, service.pagingMetrics().liveMetadataCount());
        assertEquals(1_024, service.pagingMetrics().expiryIndexCount());
        assertEquals(341, service.pagingMetrics().activeDtoCount());
        assertEquals(341, service.pagingMetrics().decodedDormantDtoCount());
        assertEquals(341, service.pagingMetrics().evictedUnexpiredCount());
        assertEquals(1, service.pagingMetrics().pendingCount());
        assertTrue(service.pagingMetrics().decodedPageCount() <= 3);
        assertEquals(1, service.pagingMetrics().pinnedPageCount());
        assertEquals(1_024L, service.liveMetadata().stream()
                .map(com.overlord.worlditem.api.WorldItemLiveMetadata::id)
                .distinct().count());
        WorldItemHibernateResult outstanding = service.prepareHibernate(
                activeKey, expectedRevisions(activeItems));
        assertEquals(WorldItemHibernateResult.Status.PREPARED, outstanding.status());
        WorldItemPersistenceTicket outstandingTicket =
                outstanding.persistenceTicket().orElseThrow();
        assertEquals(1_024, service.deliverWorldTick(18_000L).size());
        assertEquals(
                com.overlord.worlditem.api.WorldItemSpawnCommitResult.Status
                        .UNKNOWN_RESERVATION,
                service.commitSpawn(pendingSpawn.reservation().orElseThrow().id()).status());
        assertEquals(WorldItemHibernateResult.Status.STALE_TICKET,
                service.commitPersistence(
                        outstandingTicket,
                        validProof(outstanding.persistencePlan().orElseThrow())).status());
        assertEquals(0, service.pagingMetrics().liveMetadataCount());
        assertEquals(0, service.pagingMetrics().expiryIndexCount());
    }

    @Test
    void cancellationAndCrossChunkMoveRetainStateUntilOneTwoPagePlanIsDurable() {
        LogicalWorldItemService service = service(2);
        service.deliverWorldTick(10L);
        ChunkKey oldKey = new ChunkKey(0, 0);
        ChunkKey newKey = new ChunkKey(1, 0);
        WorldItemSnapshot original = spawn(service, oldKey, 2, 10L);
        WorldItemHibernateResult initial = preparedHibernate(service, original);
        WorldItemPersistencePlan initialPlan = initial.persistencePlan().orElseThrow();
        service.commitPersistence(
                initial.persistenceTicket().orElseThrow(), validProof(initialPlan));
        WorldItemPageDescriptor initialDescriptor =
                initialPlan.intendedCheckpoint().pages().get(0);
        WorldItemPageSnapshot initialPage = ((WorldItemPageMutation.Upsert)
                initialPlan.pageMutations().get(0)).page();
        WorldItemActivationResult activation;
        try (WorldItemPageReadView view = readView(
                initialPlan, initialDescriptor, initialPage)) {
            activation = service.prepareActivate(view, initialDescriptor);
        }
        service.commitActivate(activation.ticket().orElseThrow());
        WorldItemSnapshot active = service.snapshot(original.id()).orElseThrow();
        assertEquals(com.overlord.worlditem.api.WorldItemMotionUpdateResult.Status.APPLIED,
                service.updateMotion(new WorldItemMotionUpdate(
                        active.id(), active.revision(),
                        newKey.worldOriginX() + 0.5, active.positionY(),
                        newKey.worldOriginZ() + 0.5,
                        active.velocityX(), active.velocityY(), active.velocityZ(),
                        WorldItemPhysicalState.ACTIVE)).status());
        WorldItemSnapshot moved = service.snapshot(original.id()).orElseThrow();
        WorldItemRuntimeSnapshot movedRuntime =
                service.runtimeSnapshot(original.id()).orElseThrow();
        WorldItemHibernateResult prepared = preparedHibernate(service, moved);
        WorldItemPersistenceTicket ticket = prepared.persistenceTicket().orElseThrow();

        assertEquals(WorldItemHibernateResult.Status.CANCELED,
                service.cancelPersistence(ticket).status());
        assertEquals(moved, service.snapshot(original.id()).orElseThrow());
        assertEquals(1, service.pagingMetrics().dirtyEntryCount());
        assertEquals(1, service.pagingMetrics().pinnedPageCount());
        WorldItemLiveMetadata retained = service.liveMetadata().get(0);
        assertEquals(newKey, retained.intendedChunkKey());
        assertEquals(initialDescriptor.pageRevision(), retained.durableProof()
                .orElseThrow().pageRevision());
        assertEquals(initialDescriptor.pageHash(), retained.durableProof()
                .orElseThrow().pageHash());
        assertEquals(oldKey, retained.durableProof().orElseThrow().chunkKey());

        WorldItemHibernateResult retry = preparedHibernate(service, moved);
        WorldItemPersistencePlan retryPlan = retry.persistencePlan().orElseThrow();
        assertEquals(2, retryPlan.pageMutations().size());
        WorldItemPageMutation.Remove remove = retryPlan.pageMutations().stream()
                .filter(WorldItemPageMutation.Remove.class::isInstance)
                .map(WorldItemPageMutation.Remove.class::cast)
                .findFirst().orElseThrow();
        WorldItemPageMutation.Upsert upsert = retryPlan.pageMutations().stream()
                .filter(WorldItemPageMutation.Upsert.class::isInstance)
                .map(WorldItemPageMutation.Upsert.class::cast)
                .findFirst().orElseThrow();
        assertEquals(initialDescriptor, remove.expected());
        assertEquals(newKey, upsert.page().chunkKey());
        assertTrue(upsert.expectedPrevious().isEmpty());
        assertEquals(List.of(new WorldItemRestoreEntry(
                        movedRuntime, WorldItemPhysicalState.ACTIVE)),
                upsert.page().entries());
        assertEquals(List.of(newKey), retryPlan.intendedCheckpoint().pages().stream()
                .map(WorldItemPageDescriptor::chunkKey).toList());
        assertEquals(List.of(testDescriptor(upsert.page())),
                retryPlan.intendedCheckpoint().pages());

        assertEquals(WorldItemHibernateResult.Status.COMMITTED,
                service.commitPersistence(
                        retry.persistenceTicket().orElseThrow(),
                        validProof(retryPlan)).status());
        assertTrue(service.snapshot(original.id()).isEmpty());
        WorldItemPageDescriptor newDescriptor =
                retryPlan.intendedCheckpoint().pages().get(0);
        WorldItemLiveMetadata evicted = service.liveMetadata().get(0);
        assertEquals(WorldItemLiveState.EVICTED_UNEXPIRED, evicted.state());
        assertEquals(newKey, evicted.durableProof().orElseThrow().chunkKey());
        assertEquals(newDescriptor.pageRevision(),
                evicted.durableProof().orElseThrow().pageRevision());
        assertEquals(newDescriptor.pageHash(),
                evicted.durableProof().orElseThrow().pageHash());
    }

    @Test
    void persistenceTicketLedgerIsBoundedAtPolicyLimit() {
        WorldItemPageCachePolicy oneTicket = new WorldItemPageCachePolicy(
                2, 2, 1_024L, 1, 2, 1_024L, 64, 64L * 1_024L);
        LogicalWorldItemService service = service(2, oneTicket);
        service.deliverWorldTick(0L);
        for (int index = 0; index < 2; index++) {
            spawn(service, new ChunkKey(index, 0), 1, 0L);
        }
        WorldItemSnapshot first = itemInChunk(service, new ChunkKey(0, 0));
        assertEquals(WorldItemHibernateResult.Status.PREPARED,
                preparedHibernate(service, first).status());
        WorldItemSnapshot rejected = itemInChunk(service, new ChunkKey(1, 0));
        assertEquals(WorldItemHibernateResult.Status.TICKET_LIMIT,
                service.prepareHibernate(
                        new ChunkKey(1, 0), Map.of(rejected.id(), rejected.revision()))
                        .status());
        assertEquals(1, service.pagingMetrics().persistenceTicketCount());
    }

    @Test
    void activationDefersWhenEveryResidentCandidateIsDirtyOrUnprovedAndPinned() {
        WorldItemPageCachePolicy onePage = new WorldItemPageCachePolicy(
                2, 1, 512L, 64, 2, 512L, 64, 64L * 1_024L);
        LogicalWorldItemService service = service(2, onePage);
        WorldItemPageSnapshot firstPage = page(new ChunkKey(0, 0), 1L, 0L);
        WorldItemPageSnapshot secondPage = page(new ChunkKey(1, 0), 1L, 1L);
        WorldItemPageDescriptor firstDescriptor = descriptor(firstPage);
        WorldItemPageDescriptor secondDescriptor = descriptor(secondPage);
        WorldItemPagingCheckpoint checkpoint = new WorldItemPagingCheckpoint(
                SAVE, 1L, 0L, 2L, false, 2,
                List.of(firstDescriptor, secondDescriptor));
        assertTrue(service.restorePagingState(
                checkpoint,
                List.of(metadata(firstDescriptor, 0L), metadata(secondDescriptor, 1L)),
                List.of(firstPage, secondPage)));
        activate(service, checkpoint, secondDescriptor, secondPage);
        WorldItemSnapshot pinned = service.snapshot(new WorldItemId(1L)).orElseThrow();
        WorldItemHibernateResult failed = preparedHibernate(service, pinned);
        WorldItemPersistencePlan failedPlan = failed.persistencePlan().orElseThrow();
        assertThrows(IllegalArgumentException.class, () -> service.commitPersistence(
                failed.persistenceTicket().orElseThrow(),
                new TestProof(
                        failedPlan.intendedCheckpoint().checkpointRevision() + 1L,
                        failedPlan.transactionDigest(),
                        PUBLISHED_SEQUENCE)));
        assertEquals(1, service.pagingMetrics().pinnedPageCount());

        try (WorldItemPageReadView view = new TestReadView(
                PUBLISHED_SEQUENCE, "42".repeat(32), checkpoint,
                Map.of(firstDescriptor, firstPage))) {
            assertEquals(WorldItemActivationResult.Status.ALL_PINNED,
                    service.prepareActivate(view, firstDescriptor).status());
        }
        assertTrue(service.snapshot(new WorldItemId(0L)).isEmpty());
        assertEquals(1, service.pagingMetrics().pinnedPageCount());
    }

    @Test
    void forwardAndReversePageActivationProduceIdenticalCanonicalState() {
        ChunkKey firstKey = new ChunkKey(-1, 0);
        ChunkKey secondKey = new ChunkKey(1, 0);
        WorldItemPageSnapshot firstPage = page(firstKey, 1L, 0L);
        WorldItemPageSnapshot secondPage = page(secondKey, 1L, 1L);
        WorldItemPageDescriptor firstDescriptor = descriptor(firstPage);
        WorldItemPageDescriptor secondDescriptor = descriptor(secondPage);
        WorldItemPagingCheckpoint checkpoint = new WorldItemPagingCheckpoint(
                SAVE, 1L, 0L, 2L, false, 2,
                List.of(firstDescriptor, secondDescriptor));
        List<WorldItemLiveMetadata> metadata = List.of(
                metadata(firstDescriptor, 0L),
                metadata(secondDescriptor, 1L));
        List<WorldItemPageSnapshot> pages = List.of(firstPage, secondPage);
        LogicalWorldItemService forward = service(2);
        LogicalWorldItemService reverse = service(2);
        assertTrue(forward.restorePagingState(checkpoint, metadata, pages));
        assertTrue(reverse.restorePagingState(checkpoint, metadata, pages));

        activate(forward, checkpoint, firstDescriptor, firstPage);
        activate(forward, checkpoint, secondDescriptor, secondPage);
        activate(reverse, checkpoint, secondDescriptor, secondPage);
        activate(reverse, checkpoint, firstDescriptor, firstPage);

        assertEquals(forward.canonicalSnapshot(), reverse.canonicalSnapshot());
        assertEquals(List.of(new WorldItemId(0L), new WorldItemId(1L)),
                forward.snapshots().stream().map(WorldItemSnapshot::id).toList());
        assertEquals(2L, forward.canonicalSnapshot().nextItemId());
    }

    @Test
    void partialPickupKeepsStableIdExpiryAndAllocatorHighWaterAcrossEviction() {
        LogicalWorldItemService service = service(2);
        service.deliverWorldTick(50L);
        WorldItemSnapshot original = spawn(service, new ChunkKey(0, 0), 3, 50L);
        long expiry = service.runtimeSnapshot(original.id()).orElseThrow()
                .expiresAtWorldTick();
        var reservation = service.reserve(original.id(), 2).reservation().orElseThrow();
        service.commit(reservation.id());
        WorldItemSnapshot remainder = service.snapshot(original.id()).orElseThrow();
        WorldItemHibernateResult prepared = preparedHibernate(service, remainder);
        WorldItemPersistencePlan plan = prepared.persistencePlan().orElseThrow();
        service.commitPersistence(
                prepared.persistenceTicket().orElseThrow(), validProof(plan));

        assertEquals(original.id(), service.liveMetadata().get(0).id());
        assertEquals(expiry, service.liveMetadata().get(0).expiresAtWorldTick());
        assertEquals(original.id().value() + 1L,
                service.canonicalSnapshot().nextItemId());
        WorldItemPageSnapshot page = ((WorldItemPageMutation.Upsert)
                plan.pageMutations().get(0)).page();
        assertEquals(1, page.entries().get(0).runtime().item().stack().count());
        assertEquals(original.id(), page.entries().get(0).runtime().item().id());
    }

    private static LogicalWorldItemService service(int capacity) {
        return service(capacity, new WorldItemPageCachePolicy(
                1_024, 32, 16L * 1_024L * 1_024L,
                64, 1_024, 16L * 1_024L * 1_024L,
                64, 64L * 1_024L));
    }

    private static WorldItemHibernateResult commitLinked(
            LogicalWorldItemService service,
            WorldItemHibernateTicket hibernateTicket,
            WorldItemPersistenceTicket persistenceTicket,
            WorldItemDurableProof proof,
            Runnable projectionPublication) {
        try {
            var method = LogicalWorldItemService.class.getMethod(
                    "commitLinkedHibernate",
                    WorldItemHibernateTicket.class,
                    WorldItemPersistenceTicket.class,
                    WorldItemDurableProof.class,
                    Runnable.class);
            return (WorldItemHibernateResult) method.invoke(
                    service, hibernateTicket, persistenceTicket, proof,
                    projectionPublication);
        } catch (NoSuchMethodException missing) {
            throw new AssertionError(
                    "missing atomic linked hibernate+persistence commit API", missing);
        } catch (InvocationTargetException invoked) {
            Throwable cause = invoked.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new AssertionError(cause);
        } catch (ReflectiveOperationException reflectionFailure) {
            throw new AssertionError(reflectionFailure);
        }
    }

    private static LogicalWorldItemService service(
            int capacity, WorldItemPageCachePolicy policy) {
        return new LogicalWorldItemService(
                MainThreadGuard.captureCurrentThread(),
                capacity,
                0L,
                SAVE,
                policy,
                new TestVerifier(),
                LogicalWorldItemDormantLifecycleTest::testDescriptor);
    }

    private static WorldItemHibernateResult preparedHibernate(
            LogicalWorldItemService service, WorldItemSnapshot item) {
        ChunkKey key = ChunkKey.fromWorld(
                (int) Math.floor(item.positionX()),
                (int) Math.floor(item.positionZ()));
        WorldItemHibernateResult prepared = service.prepareHibernate(
                key, Map.of(item.id(), item.revision()));
        assertEquals(WorldItemHibernateResult.Status.PREPARED, prepared.status());
        assertTrue(prepared.persistenceTicket().isPresent());
        assertTrue(prepared.persistencePlan().isPresent());
        return prepared;
    }

    private static WorldItemSnapshot spawn(
            LogicalWorldItemService service,
            ChunkKey key,
            int count,
            long tick) {
        return service.spawn(request(key, count, tick)).item().orElseThrow();
    }

    private static WorldItemSpawnRequest request(
            ChunkKey key, int count, long tick) {
        return new WorldItemSpawnRequest(
                new ItemStack(DIRT, count),
                key.worldOriginX() + 0.5,
                4.0,
                key.worldOriginZ() + 0.5,
                0.0, 0.0, 0.0,
                Optional.empty(), tick);
    }

    private static TestProof validProof(WorldItemPersistencePlan plan) {
        return new TestProof(
                plan.intendedCheckpoint().checkpointRevision(),
                plan.transactionDigest(),
                PUBLISHED_SEQUENCE);
    }

    private static WorldItemSnapshot itemInChunk(
            LogicalWorldItemService service, ChunkKey key) {
        return service.snapshots().stream()
                .filter(candidate -> ChunkKey.fromWorld(
                        (int) Math.floor(candidate.positionX()),
                        (int) Math.floor(candidate.positionZ())).equals(key))
                .findFirst()
                .orElseThrow();
    }

    private static Map<WorldItemId, Long> expectedRevisions(
            List<WorldItemSnapshot> items) {
        Map<WorldItemId, Long> expected = new java.util.LinkedHashMap<>();
        for (WorldItemSnapshot item : items) {
            expected.put(item.id(), item.revision());
        }
        return expected;
    }

    private static WorldItemPageSnapshot page(
            ChunkKey key, long revision, long id) {
        WorldItemSnapshot item = new WorldItemSnapshot(
                new WorldItemId(id), new ItemStack(DIRT, 1),
                key.worldOriginX() + 0.5, 4.0, key.worldOriginZ() + 0.5,
                0.0, 0.0, 0.0, revision);
        return new WorldItemPageSnapshot(
                key, revision, List.of(new WorldItemRestoreEntry(
                        new WorldItemRuntimeSnapshot(
                                item, Optional.empty(), 0L, 0L, 18_000L),
                        WorldItemPhysicalState.FROZEN_UNLOADED)));
    }

    private static WorldItemPageDescriptor descriptor(WorldItemPageSnapshot page) {
        return new WorldItemPageDescriptor(
                page.chunkKey(), page.pageRevision(),
                String.format("%064x", page.entries().get(0).runtime().item().id().value() + 1L),
                page.entries().size(), page.entries().size());
    }

    private static WorldItemPageDescriptor testDescriptor(
            WorldItemPageSnapshot page) {
        long token = Integer.toUnsignedLong(java.util.Objects.hash(
                page.chunkKey(),
                page.pageRevision(),
                page.entries().stream()
                        .map(entry -> entry.runtime().item().id())
                        .toList(),
                page.entries().stream()
                        .map(entry -> entry.runtime().item().revision())
                        .toList()));
        return new WorldItemPageDescriptor(
                page.chunkKey(),
                page.pageRevision(),
                String.format("%064x", token),
                page.entries().size(),
                page.entries().size());
    }

    private static WorldItemLiveMetadata metadata(
            WorldItemPageDescriptor descriptor, long id) {
        return new WorldItemLiveMetadata(
                new WorldItemId(id), descriptor.chunkKey(),
                descriptor.pageRevision(), 18_000L,
                WorldItemLiveState.EVICTED_UNEXPIRED,
                Optional.of(new WorldItemDurablePageProof(
                        descriptor.chunkKey(), descriptor.pageRevision(),
                        descriptor.pageHash())));
    }

    private static void activate(
            LogicalWorldItemService service,
            WorldItemPagingCheckpoint checkpoint,
            WorldItemPageDescriptor descriptor,
            WorldItemPageSnapshot page) {
        WorldItemActivationResult prepared;
        try (WorldItemPageReadView view = new TestReadView(
                PUBLISHED_SEQUENCE, "42".repeat(32), checkpoint,
                Map.of(descriptor, page))) {
            prepared = service.prepareActivate(view, descriptor);
        }
        assertEquals(WorldItemActivationResult.Status.PREPARED, prepared.status());
        assertEquals(WorldItemActivationResult.Status.COMMITTED,
                service.commitActivate(prepared.ticket().orElseThrow()).status());
    }

    private static WorldItemPageReadView readView(
            WorldItemPersistencePlan plan,
            WorldItemPageDescriptor descriptor,
            WorldItemPageSnapshot page) {
        return new TestReadView(
                PUBLISHED_SEQUENCE,
                plan.transactionDigest(),
                plan.intendedCheckpoint(),
                Map.of(descriptor, page));
    }

    private record TestProof(
            long checkpointRevision,
            String transactionDigest,
            long publishedIndexSequence) implements WorldItemDurableProof {}

    private static final class TestVerifier implements WorldItemDurabilityVerifier {
        @Override
        public void verify(
                WorldItemPersistenceTicket ticket,
                WorldItemPersistencePlan plan,
                WorldItemDurableProof proof) {
            if (!(proof instanceof TestProof checked)
                    || checked.checkpointRevision()
                            != plan.intendedCheckpoint().checkpointRevision()
                    || !checked.transactionDigest().equals(plan.transactionDigest())
                    || checked.publishedIndexSequence() != PUBLISHED_SEQUENCE) {
                throw new IllegalArgumentException("proof mismatch");
            }
        }
    }

    private static final class TestReadView implements WorldItemPageReadView {
        private final long sequence;
        private final String digest;
        private final WorldItemPagingCheckpoint checkpoint;
        private final Map<WorldItemPageDescriptor, WorldItemPageSnapshot> pages;
        private boolean closed;

        private TestReadView(
                long sequence,
                String digest,
                WorldItemPagingCheckpoint checkpoint,
                Map<WorldItemPageDescriptor, WorldItemPageSnapshot> pages) {
            this.sequence = sequence;
            this.digest = digest;
            this.checkpoint = checkpoint;
            this.pages = Map.copyOf(pages);
        }

        @Override
        public long indexSequence() {
            return sequence;
        }

        @Override
        public String checkpointDigest() {
            return digest;
        }

        @Override
        public WorldItemPagingCheckpoint checkpoint() {
            return checkpoint;
        }

        @Override
        public WorldItemPageSnapshot read(WorldItemPageDescriptor descriptor) {
            if (closed || !pages.containsKey(descriptor)) {
                throw new IllegalArgumentException(
                        "descriptor is outside the pinned read view");
            }
            return pages.get(descriptor);
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    @Test
    void unrepresentableSpawnRejectsBeforeBurningIdOrRetainingReservation() {
        LogicalWorldItemService service = service(1);
        service.deliverWorldTick(0L);
        LogicalWorldItemSnapshot before = service.canonicalSnapshot();
        WorldItemSpawnRequest outside = new WorldItemSpawnRequest(
                new ItemStack(DIRT, 1),
                Double.MAX_VALUE, 4.0, 0.5,
                0.0, 0.0, 0.0, Optional.empty(), 0L);

        for (int attempt = 0; attempt < 3; attempt++) {
            assertEquals(WorldItemSpawnReserveResult.Status.REJECTED,
                    service.reserveSpawn(outside).status());
        }
        assertEquals(before, service.canonicalSnapshot());

        WorldItemSnapshot valid = spawn(service, new ChunkKey(0, 0), 1, 0L);
        assertEquals(new WorldItemId(0L), valid.id());
        assertEquals(WorldItemHibernateResult.Status.PREPARED,
                preparedHibernate(service, valid).status());
    }

    @Test
    void expiryInsideMultiItemPreparationRestoresEveryStillLiveMetadataRow() {
        LogicalWorldItemService service = service(2);
        ChunkKey key = new ChunkKey(0, 0);
        service.deliverWorldTick(0L);
        WorldItemSnapshot first = spawn(service, key, 1, 0L);
        service.deliverWorldTick(1L);
        WorldItemSnapshot second = spawn(service, key, 1, 1L);
        WorldItemHibernateResult prepared = service.prepareHibernate(
                key, Map.of(first.id(), first.revision(),
                        second.id(), second.revision()));
        assertEquals(WorldItemHibernateResult.Status.PREPARED, prepared.status());

        assertEquals(List.of(first.id()), service.deliverWorldTick(18_000L));
        assertTrue(service.snapshot(first.id()).isEmpty());
        assertEquals(second, service.snapshot(second.id()).orElseThrow());
        WorldItemLiveMetadata surviving = service.liveMetadata().stream()
                .filter(row -> row.id().equals(second.id())).findFirst().orElseThrow();
        assertEquals(WorldItemLiveState.ACTIVE, surviving.state());
        assertEquals(0, service.pagingMetrics().persistenceTicketCount());
    }

    @Test
    void aggregatePagingTicketBudgetRejectsPersistenceAfterActivationSaturation() {
        LogicalWorldItemService service = service(2);
        service.deliverWorldTick(0L);
        ChunkKey firstKey = new ChunkKey(0, 0);
        WorldItemSnapshot dormant = spawn(service, firstKey, 1, 0L);
        WorldItemHibernateResult persisted = preparedHibernate(service, dormant);
        WorldItemPersistencePlan plan = persisted.persistencePlan().orElseThrow();
        service.commitPersistence(
                persisted.persistenceTicket().orElseThrow(), validProof(plan));
        WorldItemPageMutation.Upsert firstUpsert = (WorldItemPageMutation.Upsert)
                plan.pageMutations().get(0);
        WorldItemPageDescriptor firstDescriptor = plan.intendedCheckpoint()
                .pages().get(0);
        for (int index = 0; index < 64; index++) {
            try (WorldItemPageReadView view = readView(
                    plan, firstDescriptor, firstUpsert.page())) {
                assertEquals(WorldItemActivationResult.Status.PREPARED,
                        service.prepareActivate(view, firstDescriptor).status());
            }
        }
        assertEquals(64, service.pagingMetrics().activationTicketCount());

        ChunkKey secondKey = new ChunkKey(1, 0);
        WorldItemSnapshot active = spawn(service, secondKey, 1, 0L);
        assertEquals(WorldItemHibernateResult.Status.TICKET_LIMIT,
                service.prepareHibernate(
                        secondKey, Map.of(active.id(), active.revision())).status());
        assertEquals(active, service.snapshot(active.id()).orElseThrow());
    }

    @Test
    void payloadActivationUsesTheSameAggregateTicketBudget() {
        LogicalWorldItemService service = new LogicalWorldItemService(
                MainThreadGuard.captureCurrentThread(), 1, 0L);
        ChunkKey key = new ChunkKey(0, 0);
        WorldItemSnapshot item = service.spawn(request(key, 1, 0L))
                .item().orElseThrow();
        WorldItemHibernateResult hibernate = service.prepareHibernate(
                key, Map.of(item.id(), item.revision()));
        service.commitHibernate(hibernate.ticket().orElseThrow());
        for (int index = 0; index < 64; index++) {
            assertEquals(WorldItemActivationResult.Status.PREPARED,
                    service.prepareActivate(
                            key, hibernate.payload().orElseThrow()).status());
        }
        assertEquals(WorldItemActivationResult.Status.CAPACITY_EXCEEDED,
                service.prepareActivate(
                        key, hibernate.payload().orElseThrow()).status());
        assertEquals(64, service.pagingMetrics().activationTicketCount());
    }
}

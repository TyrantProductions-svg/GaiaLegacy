package com.overlord.worlditem;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.overlord.assets.ResourceLocation;
import com.overlord.core.thread.MainThreadGuard;
import com.overlord.inventory.api.ItemStack;
import com.overlord.voxel.ChunkKey;
import com.overlord.worlditem.api.SaveIdentity;
import com.overlord.worlditem.api.WorldItemActivationResult;
import com.overlord.worlditem.api.WorldItemDurabilityVerifier;
import com.overlord.worlditem.api.WorldItemDurablePageProof;
import com.overlord.worlditem.api.WorldItemId;
import com.overlord.worlditem.api.WorldItemHibernateResult;
import com.overlord.worlditem.api.WorldItemLiveMetadata;
import com.overlord.worlditem.api.WorldItemLiveState;
import com.overlord.worlditem.api.WorldItemPageCachePolicy;
import com.overlord.worlditem.api.WorldItemPageDescriptor;
import com.overlord.worlditem.api.WorldItemPageMutation;
import com.overlord.worlditem.api.WorldItemPageReadView;
import com.overlord.worlditem.api.WorldItemPageSnapshot;
import com.overlord.worlditem.api.WorldItemPagingCheckpoint;
import com.overlord.worlditem.api.WorldItemPagingMetrics;
import com.overlord.worlditem.api.WorldItemPersistencePlan;
import com.overlord.worlditem.api.WorldItemPhysicalState;
import com.overlord.worlditem.api.WorldItemRestoreEntry;
import com.overlord.worlditem.api.WorldItemRuntimeSnapshot;
import com.overlord.worlditem.api.WorldItemSnapshot;
import com.overlord.worlditem.api.WorldItemSpawnRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LogicalWorldItemExpiryTest {
    private static final SaveIdentity SAVE = new SaveIdentity(
            UUID.fromString("123e4567-e89b-12d3-a456-426614174000"));
    private static final ResourceLocation DIRT = ResourceLocation.parse("gaia:dirt");

    @Test
    void expiryProjectionFailureRestoresExactTickCanonicalAndExpiryOrdering() {
        LogicalWorldItemService service = service(2);
        ChunkKey key = new ChunkKey(0, 0);
        service.deliverWorldTick(0L);
        WorldItemSnapshot item = spawn(service, key, 0L);
        var before = service.canonicalSnapshot();
        List<WorldItemLiveMetadata> metadataBefore = service.liveMetadata();
        AssertionError sentinel = new AssertionError("expiry projection failure");

        assertSame(sentinel, assertThrows(AssertionError.class, () ->
                service.deliverWorldTick(18_000L, () -> {
                    assertTrue(service.snapshot(item.id()).isEmpty(),
                            "projection callback observes semantic expiry");
                    throw sentinel;
                })));

        assertEquals(0L, service.currentWorldTick());
        assertEquals(before, service.canonicalSnapshot());
        assertEquals(metadataBefore, service.liveMetadata());
        assertEquals(item, service.snapshot(item.id()).orElseThrow());
        assertEquals(List.of(item.id()),
                service.deliverWorldTick(18_000L, () -> {}),
                "retry at the same authoritative tick expires the same stable ID");
    }

    @Test
    void expiryCallbackSeesReservationRemovedAndFailureRestoresItExactly() {
        LogicalWorldItemService service = service(2);
        service.deliverWorldTick(0L);
        WorldItemSnapshot item = spawn(service, new ChunkKey(0, 0), 0L);
        var reservation = service.reserve(item.id(), 1).reservation().orElseThrow();
        var auditBefore = service.reservationAudit(reservation.id()).orElseThrow();
        AssertionError sentinel = new AssertionError("expiry projection failure");

        assertSame(sentinel, assertThrows(AssertionError.class, () ->
                service.deliverWorldTick(18_000L, () -> {
                    assertTrue(service.snapshot(item.id()).isEmpty());
                    assertTrue(service.reservationAudit(reservation.id()).isEmpty(),
                            "semantic expiry removes the pending reservation before callback");
                    throw sentinel;
                })));

        assertEquals(auditBefore,
                service.reservationAudit(reservation.id()).orElseThrow());
        assertEquals(item, service.snapshot(item.id()).orElseThrow());
        assertEquals(
                com.overlord.worlditem.api.WorldItemReservationResult.Status.COMMITTED,
                service.commit(reservation.id()).status(),
                "rollback restores the exact usable reservation");
    }

    @Test
    void activationFailureAndCancelRestoreDisplacedCacheStateExactly() {
        CacheActivationFixture failed = cacheActivationFixture();
        WorldItemPagingMetrics failedBefore = failed.service.pagingMetrics();
        AssertionError sentinel = new AssertionError("activation projection failure");
        WorldItemActivationResult activation;
        try (WorldItemPageReadView view = new TestReadView(
                failed.checkpoint, failed.descriptor, failed.page)) {
            activation = failed.service.prepareActivate(view, failed.descriptor);
        }
        assertEquals(WorldItemActivationResult.Status.PREPARED, activation.status());

        assertSame(sentinel, assertThrows(AssertionError.class, () ->
                failed.service.commitActivate(
                        activation.ticket().orElseThrow(), () -> {
                            throw sentinel;
                        })));

        assertEquals(failedBefore, failed.service.pagingMetrics(),
                "callback rollback restores evicted pages, bytes, pins and cleanup deltas");

        CacheActivationFixture canceled = cacheActivationFixture();
        WorldItemPagingMetrics canceledBefore = canceled.service.pagingMetrics();
        WorldItemActivationResult canceledActivation;
        try (WorldItemPageReadView view = new TestReadView(
                canceled.checkpoint, canceled.descriptor, canceled.page)) {
            canceledActivation = canceled.service.prepareActivate(
                    view, canceled.descriptor);
        }
        assertEquals(WorldItemActivationResult.Status.CANCELED,
                canceled.service.cancelActivate(
                        canceledActivation.ticket().orElseThrow()).status());
        assertEquals(canceledBefore, canceled.service.pagingMetrics(),
                "cancel restores the exact pre-prepare cache and cleanup state");
    }

    @Test
    void mixedExpiryPageActivatesLiveSurvivorAndCleanupRewritesExactFilteredPage() {
        LogicalWorldItemService service = service(2);
        ChunkKey key = new ChunkKey(0, 0);
        WorldItemPageSnapshot raw = new WorldItemPageSnapshot(
                key, 1L, List.of(
                        restoreEntry(0L, key, 10L),
                        restoreEntry(1L, key, 20L)));
        WorldItemPageDescriptor descriptor = new WorldItemPageDescriptor(
                key, 1L, "12".repeat(32), 2, 2);
        WorldItemPagingCheckpoint checkpoint = new WorldItemPagingCheckpoint(
                SAVE, 1L, 0L, 2L, false, 2, List.of(descriptor));
        List<WorldItemLiveMetadata> metadata = List.of(
                metadata(0L, descriptor, 10L), metadata(1L, descriptor, 20L));
        assertTrue(service.restorePagingState(checkpoint, metadata, List.of(raw)));
        assertEquals(List.of(new WorldItemId(0L)), service.deliverWorldTick(10L));

        WorldItemActivationResult activation;
        try (WorldItemPageReadView view = new TestReadView(checkpoint, descriptor, raw)) {
            activation = service.prepareActivate(view, descriptor);
        }
        assertEquals(WorldItemActivationResult.Status.PREPARED, activation.status());
        service.commitActivate(activation.ticket().orElseThrow());
        assertEquals(List.of(new WorldItemId(1L)), service.snapshots().stream()
                .map(WorldItemSnapshot::id).toList());

        WorldItemHibernateResult cleanup = service.prepareCleanupPersistence().orElseThrow();
        assertEquals(WorldItemHibernateResult.Status.PERSISTENCE_PREPARED,
                cleanup.status());
        assertTrue(cleanup.ticket().isEmpty());
        assertTrue(cleanup.payload().isEmpty());
        WorldItemPersistencePlan plan = cleanup.persistencePlan().orElseThrow();
        WorldItemPageMutation.Upsert rewrite =
                (WorldItemPageMutation.Upsert) plan.pageMutations().get(0);
        assertEquals(Optional.of(descriptor), rewrite.expectedPrevious());
        assertEquals(List.of(new WorldItemId(1L)), rewrite.page().entries().stream()
                .map(entry -> entry.runtime().item().id()).toList());
    }

    @Test
    void expiryOfUnpublishedPreparedPagesReleasesPinsForFutureChunks() {
        LogicalWorldItemService service = service(1);
        for (int cycle = 0; cycle < 40; cycle++) {
            long tick = cycle * 18_001L;
            service.deliverWorldTick(tick);
            WorldItemSnapshot item = spawn(service, new ChunkKey(cycle, 0), tick);
            assertEquals(WorldItemHibernateResult.Status.PREPARED,
                    service.prepareHibernate(
                            new ChunkKey(cycle, 0),
                            java.util.Map.of(item.id(), item.revision())).status());
            service.deliverWorldTick(tick + 18_000L);
            assertEquals(0, service.pagingMetrics().pinnedPageCount());
            assertEquals(0, service.pagingMetrics().dirtyEntryCount());
        }
    }

    @Test
    void fullPickupOfDurableActiveItemQueuesBoundedPhysicalCleanup() {
        LogicalWorldItemService service = service(1);
        service.deliverWorldTick(0L);
        WorldItemSnapshot item = spawn(service, new ChunkKey(0, 0), 0L);
        WorldItemHibernateResult hibernate = service.prepareHibernate(
                new ChunkKey(0, 0), java.util.Map.of(item.id(), item.revision()));
        WorldItemPersistencePlan plan = hibernate.persistencePlan().orElseThrow();
        service.commitPersistence(
                hibernate.persistenceTicket().orElseThrow(),
                new ValidProof(plan.intendedCheckpoint().checkpointRevision(),
                        plan.transactionDigest()));
        WorldItemPageDescriptor descriptor = plan.intendedCheckpoint().pages().get(0);
        WorldItemPageSnapshot page = ((WorldItemPageMutation.Upsert)
                plan.pageMutations().get(0)).page();
        try (WorldItemPageReadView view = new TestReadView(
                plan.intendedCheckpoint(), descriptor, page)) {
            var activation = service.prepareActivate(view, descriptor);
            service.commitActivate(activation.ticket().orElseThrow());
        }
        service.commit(service.reserve(item.id(), 1).reservation().orElseThrow().id());

        assertEquals(0, service.pagingMetrics().liveMetadataCount());
        assertEquals(1, service.pagingMetrics().cleanupIntentCount());
        assertEquals(List.of(new WorldItemPageMutation.Remove(descriptor)),
                service.prepareCleanupPersistence().orElseThrow()
                        .persistencePlan().orElseThrow().pageMutations());
    }

    private static WorldItemRestoreEntry restoreEntry(
            long id, ChunkKey key, long expiry) {
        WorldItemSnapshot item = new WorldItemSnapshot(
                new WorldItemId(id), new ItemStack(DIRT, 1),
                key.worldOriginX() + 0.5, 4.0, key.worldOriginZ() + 0.5,
                0.0, 0.0, 0.0, 0L);
        return new WorldItemRestoreEntry(
                new WorldItemRuntimeSnapshot(item, Optional.empty(), 0L, 0L, expiry),
                WorldItemPhysicalState.FROZEN_UNLOADED);
    }

    private static WorldItemLiveMetadata metadata(
            long id, WorldItemPageDescriptor descriptor, long expiry) {
        return new WorldItemLiveMetadata(
                new WorldItemId(id), descriptor.chunkKey(), descriptor.pageRevision(),
                expiry, WorldItemLiveState.EVICTED_UNEXPIRED,
                Optional.of(new WorldItemDurablePageProof(
                        descriptor.chunkKey(), descriptor.pageRevision(),
                        descriptor.pageHash())));
    }

    private static CacheActivationFixture cacheActivationFixture() {
        LogicalWorldItemService service = service(4);
        ChunkKey keyA = new ChunkKey(0, 0);
        ChunkKey keyB = new ChunkKey(1, 0);
        ChunkKey keyC = new ChunkKey(2, 0);
        WorldItemPageSnapshot pageA = new WorldItemPageSnapshot(
                keyA, 1L, List.of(
                        restoreEntry(0L, keyA, 18_000L),
                        restoreEntry(3L, keyA, 18_000L)));
        WorldItemPageSnapshot pageB = new WorldItemPageSnapshot(
                keyB, 1L, List.of(restoreEntry(1L, keyB, 18_000L)));
        WorldItemPageSnapshot pageC = new WorldItemPageSnapshot(
                keyC, 1L, List.of(restoreEntry(2L, keyC, 18_000L)));
        WorldItemPageDescriptor descriptorA = testDescriptor(pageA);
        WorldItemPageDescriptor descriptorB = testDescriptor(pageB);
        WorldItemPageDescriptor descriptorC = testDescriptor(pageC);
        WorldItemPagingCheckpoint checkpoint = new WorldItemPagingCheckpoint(
                SAVE, 1L, 0L, 4L, false, 4,
                List.of(descriptorA, descriptorB, descriptorC));
        List<WorldItemLiveMetadata> metadata = List.of(
                metadata(0L, descriptorA, 18_000L),
                metadata(1L, descriptorB, 18_000L),
                metadata(2L, descriptorC, 18_000L),
                metadata(3L, descriptorA, 18_000L));
        assertTrue(service.restorePagingState(
                checkpoint, metadata, List.of(pageC, pageA, pageB)));
        assertEquals(2, service.pagingMetrics().decodedPageCount());
        assertEquals(512L, service.pagingMetrics().decodedPageBytes());
        return new CacheActivationFixture(service, checkpoint, descriptorC, pageC);
    }

    private record CacheActivationFixture(
            LogicalWorldItemService service,
            WorldItemPagingCheckpoint checkpoint,
            WorldItemPageDescriptor descriptor,
            WorldItemPageSnapshot page) {}

    @Test
    void exactTickExpiresEveryDueIdInStableIdOrderAndPauseDoesNotAdvanceTime() {
        LogicalWorldItemService service = service(4);
        service.deliverWorldTick(0L);
        WorldItemSnapshot first = spawn(service, new ChunkKey(0, 0), 0L);
        WorldItemSnapshot second = spawn(service, new ChunkKey(1, 0), 0L);

        assertEquals(18_000L,
                service.runtimeSnapshot(first.id()).orElseThrow().expiresAtWorldTick());
        assertEquals(List.of(), service.deliverWorldTick(17_999L));
        assertEquals(17_999L, service.currentWorldTick());
        assertEquals(2, service.pagingMetrics().liveMetadataCount());

        // A paused session delivers no later tick; querying state cannot advance TTL.
        assertEquals(17_999L, service.currentWorldTick());
        assertEquals(List.of(first.id(), second.id()),
                service.deliverWorldTick(18_000L));
        assertEquals(0, service.pagingMetrics().liveMetadataCount());
        assertEquals(0, service.pagingMetrics().expiryIndexCount());
        assertThrows(IllegalArgumentException.class,
                () -> service.deliverWorldTick(17_999L));
    }

    @Test
    void deliveredClockBootstrapControlsAllocatorAndRejectsRequestDrivenTime() {
        LogicalWorldItemService service = service(2);
        WorldItemPagingCheckpoint empty = new WorldItemPagingCheckpoint(
                SAVE, 1L, 100L, 101L, false, 0, List.of());
        assertTrue(service.restorePagingState(empty, List.of(), List.of()));

        WorldItemSnapshot item = spawn(service, new ChunkKey(0, 0), 100L);
        assertEquals(new WorldItemId(101L), item.id());
        assertEquals(18_100L,
                service.runtimeSnapshot(item.id()).orElseThrow().expiresAtWorldTick());
        assertEquals(102L, service.canonicalSnapshot().nextItemId());

        assertEquals(com.overlord.worlditem.api.WorldItemSpawnResult.Status.REJECTED,
                service.spawn(new WorldItemSpawnRequest(
                        new ItemStack(DIRT, 1), 0.5, 4.0, 0.5,
                        0.0, 0.0, 0.0, Optional.empty(), 99L)).status());
        assertEquals(com.overlord.worlditem.api.WorldItemSpawnResult.Status.REJECTED,
                service.spawn(new WorldItemSpawnRequest(
                        new ItemStack(DIRT, 1), 0.5, 4.0, 0.5,
                        0.0, 0.0, 0.0, Optional.empty(), 101L)).status());
        assertEquals(100L, service.currentWorldTick());
        assertEquals(102L, service.canonicalSnapshot().nextItemId());
    }

    @Test
    void dueDrainOrdersByExpiryThenStableIdAcrossInterleavedSpawnTicks() {
        LogicalWorldItemService service = service(4);
        service.deliverWorldTick(0L);
        WorldItemSnapshot earliest = spawn(service, new ChunkKey(0, 0), 0L);
        service.deliverWorldTick(1L);
        WorldItemSnapshot sameExpiryFirst = spawn(service, new ChunkKey(1, 0), 1L);
        WorldItemSnapshot sameExpirySecond = spawn(service, new ChunkKey(2, 0), 1L);

        assertEquals(List.of(earliest.id(), sameExpiryFirst.id(), sameExpirySecond.id()),
                service.deliverWorldTick(18_001L));
    }

    @Test
    void unloadedItemsExpireOnTheGlobalTickAndAllocatorNeverReusesTheirIds() {
        LogicalWorldItemService service = service(1);
        service.deliverWorldTick(1L);
        WorldItemSnapshot original = spawn(service, new ChunkKey(-1, 0), 1L);
        var hibernate = service.prepareHibernate(
                new ChunkKey(-1, 0), java.util.Map.of(
                        original.id(), original.revision()));
        service.commitHibernate(hibernate.ticket().orElseThrow());

        assertTrue(service.snapshot(original.id()).isEmpty());
        assertEquals(1, service.pagingMetrics().decodedDormantDtoCount());
        assertEquals(List.of(), service.deliverWorldTick(18_000L));
        assertEquals(List.of(original.id()), service.deliverWorldTick(18_001L));
        assertEquals(0, service.pagingMetrics().decodedDormantDtoCount());

        WorldItemSnapshot replacement = spawn(service, new ChunkKey(0, 0), 18_001L);
        assertEquals(original.id().value() + 1L, replacement.id().value());
    }

    @Test
    void expirySaturatesAtLongMaxAndStillTerminatesAtTheExactFinalTick() {
        LogicalWorldItemService service = service(1);
        long spawnTick = Long.MAX_VALUE - 1L;
        service.deliverWorldTick(spawnTick);
        WorldItemSnapshot item = spawn(service, new ChunkKey(0, 0), spawnTick);

        assertEquals(Long.MAX_VALUE,
                service.runtimeSnapshot(item.id()).orElseThrow().expiresAtWorldTick());
        assertEquals(List.of(item.id()), service.deliverWorldTick(Long.MAX_VALUE));
        assertTrue(service.snapshot(item.id()).isEmpty());
        assertEquals(1L, service.canonicalSnapshot().nextItemId());
    }

    @Test
    void liveMetadataAndExpiryIndexShareTheExactHardBoundOf1024() {
        LogicalWorldItemService service = service(1_024);
        service.deliverWorldTick(0L);
        for (int index = 0; index < 1_024; index++) {
            spawn(service, new ChunkKey(index, 0), 0L);
        }
        assertEquals(1_024, service.pagingMetrics().liveMetadataCount());
        assertEquals(1_024, service.pagingMetrics().expiryIndexCount());
        assertEquals(com.overlord.worlditem.api.WorldItemSpawnResult.Status.REJECTED,
                service.spawn(new WorldItemSpawnRequest(
                        new ItemStack(DIRT, 1),
                        0.5, 4.0, 0.5,
                        0.0, 0.0, 0.0,
                        Optional.empty(), 0L)).status());
        assertEquals(1_024, service.deliverWorldTick(18_000L).size());
        assertTrue(service.liveMetadata().isEmpty());
        assertEquals(1_024L, service.canonicalSnapshot().nextItemId());
    }

    @Test
    void cleanupQueueSaturationAndFailureCannotResurrectSemanticallyExpiredItems() {
        LogicalWorldItemService service = service(65);
        List<WorldItemPageDescriptor> descriptors = new ArrayList<>();
        List<WorldItemLiveMetadata> metadata = new ArrayList<>();
        List<WorldItemPageSnapshot> pages = new ArrayList<>();
        for (int index = 0; index < 65; index++) {
            ChunkKey key = new ChunkKey(index, 0);
            String hash = String.format("%064x", index + 1L);
            descriptors.add(new WorldItemPageDescriptor(key, 1L, hash, 1, 1));
            metadata.add(new WorldItemLiveMetadata(
                    new WorldItemId(index),
                    key,
                    1L,
                    18_000L,
                    WorldItemLiveState.EVICTED_UNEXPIRED,
                    Optional.of(new WorldItemDurablePageProof(key, 1L, hash))));
            WorldItemSnapshot item = new WorldItemSnapshot(
                    new WorldItemId(index), new ItemStack(DIRT, 1),
                    key.worldOriginX() + 0.5, 4.0, key.worldOriginZ() + 0.5,
                    0.0, 0.0, 0.0, 0L);
            pages.add(new WorldItemPageSnapshot(
                    key, 1L, List.of(new WorldItemRestoreEntry(
                            new WorldItemRuntimeSnapshot(
                                    item, Optional.empty(), 0L, 0L, 18_000L),
                            WorldItemPhysicalState.FROZEN_UNLOADED))));
        }
        WorldItemPagingCheckpoint checkpoint = new WorldItemPagingCheckpoint(
                SAVE, 1L, 0L, 65L, false, 65, descriptors);
        assertTrue(service.restorePagingState(checkpoint, metadata, pages));

        assertEquals(65, service.deliverWorldTick(18_000L).size());
        WorldItemPagingMetrics expired = service.pagingMetrics();
        assertEquals(0, expired.liveMetadataCount());
        assertEquals(64, expired.cleanupIntentCount());
        assertTrue(expired.cleanupIntentBytes() <= 64L * 1_024L);
        assertEquals(1L, expired.droppedCleanupIntentCount());

        var cleanup = service.prepareCleanupPersistence().orElseThrow();
        var cleanupPlan = cleanup.persistencePlan().orElseThrow();
        assertEquals(64, cleanupPlan.pageMutations().size());
        assertTrue(cleanupPlan.pageMutations().stream()
                .allMatch(com.overlord.worlditem.api.WorldItemPageMutation.Remove.class
                        ::isInstance));
        assertEquals(
                descriptors.subList(0, 64),
                cleanupPlan.pageMutations().stream()
                        .map(com.overlord.worlditem.api.WorldItemPageMutation.Remove.class
                                ::cast)
                        .map(com.overlord.worlditem.api.WorldItemPageMutation.Remove
                                ::expected)
                        .toList());
        assertEquals(18_000L, cleanupPlan.intendedCheckpoint().worldTick());
        assertEquals(0, cleanupPlan.intendedCheckpoint().totalLiveItemCount());
        assertEquals(1, cleanupPlan.intendedCheckpoint().pages().size());
        assertEquals(0, cleanupPlan.intendedCheckpoint().pages().get(0)
                .expectedLiveCountAtCheckpointTick());
        assertThrows(IllegalArgumentException.class, () -> service.commitPersistence(
                cleanup.persistenceTicket().orElseThrow(), new InvalidProof()));
        assertEquals(64, service.pagingMetrics().cleanupIntentCount());
        assertTrue(service.pagingMetrics().pinnedPageCount() > 0);
        assertEquals(0, service.pagingMetrics().liveMetadataCount());
        assertTrue(service.liveMetadata().isEmpty());

        service.commitPersistence(
                cleanup.persistenceTicket().orElseThrow(),
                new ValidProof(
                        cleanupPlan.intendedCheckpoint().checkpointRevision(),
                        cleanupPlan.transactionDigest()));
        assertEquals(0, service.pagingMetrics().cleanupIntentCount());

        WorldItemPagingCheckpoint afterCleanup = cleanupPlan.intendedCheckpoint();
        WorldItemPageDescriptor dropped = afterCleanup.pages().get(0);
        try (WorldItemPageReadView reread = new TestReadView(
                afterCleanup, dropped, pages.get(64))) {
            assertEquals(
                    com.overlord.worlditem.api.WorldItemActivationResult.Status.EXPIRED,
                    service.prepareActivate(reread, dropped).status());
        }
        assertEquals(1, service.pagingMetrics().cleanupIntentCount());
        var finalCleanup = service.prepareCleanupPersistence().orElseThrow();
        var finalPlan = finalCleanup.persistencePlan().orElseThrow();
        assertEquals(List.of(new com.overlord.worlditem.api.WorldItemPageMutation.Remove(
                        dropped)),
                finalPlan.pageMutations());
        assertTrue(finalPlan.intendedCheckpoint().pages().isEmpty());
        service.commitPersistence(
                finalCleanup.persistenceTicket().orElseThrow(),
                new ValidProof(
                        finalPlan.intendedCheckpoint().checkpointRevision(),
                        finalPlan.transactionDigest()));
        assertEquals(0, service.pagingMetrics().cleanupIntentCount());
        assertEquals(0, service.pagingMetrics().physicalDescriptorCount());
    }

    @Test
    void unrelatedPersistenceRetainsDroppedZeroLivePhysicalDescriptor() {
        LogicalWorldItemService service = service(1);
        ChunkKey staleKey = new ChunkKey(64, 0);
        WorldItemPageSnapshot stalePage = new WorldItemPageSnapshot(
                staleKey, 1L, List.of(restoreEntry(64L, staleKey, 18_000L)));
        WorldItemPageDescriptor staleDescriptor = new WorldItemPageDescriptor(
                staleKey, 1L, "64".repeat(32), 1, 0);
        WorldItemPagingCheckpoint checkpoint = new WorldItemPagingCheckpoint(
                SAVE, 1L, 18_000L, 65L, false, 0,
                List.of(staleDescriptor));
        assertTrue(service.restorePagingState(
                checkpoint, List.of(), List.of(stalePage)));

        ChunkKey liveKey = new ChunkKey(100, 0);
        WorldItemSnapshot live = spawn(service, liveKey, 18_000L);
        WorldItemHibernateResult prepared = service.prepareHibernate(
                liveKey, java.util.Map.of(live.id(), live.revision()));
        assertEquals(WorldItemHibernateResult.Status.PREPARED, prepared.status());
        WorldItemPageDescriptor retained = prepared.persistencePlan().orElseThrow()
                .intendedCheckpoint().pages().stream()
                .filter(descriptor -> descriptor.chunkKey().equals(staleKey))
                .findFirst().orElseThrow();
        assertEquals(staleDescriptor, retained);
        assertEquals(0, retained.expectedLiveCountAtCheckpointTick());
    }

    @Test
    void historicalChurnRetainsOnlyCurrentLiveStateNotExpiredDtoHistory() {
        LogicalWorldItemService service = service(1);
        for (int cycle = 0; cycle < 100; cycle++) {
            long spawnTick = cycle * 18_001L;
            service.deliverWorldTick(spawnTick);
            WorldItemSnapshot item = spawn(
                    service, new ChunkKey(cycle, -cycle), spawnTick);
            var hibernate = service.prepareHibernate(
                    new ChunkKey(cycle, -cycle),
                    java.util.Map.of(item.id(), item.revision()));
            service.commitHibernate(hibernate.ticket().orElseThrow());
            service.deliverWorldTick(spawnTick + 18_000L);
            WorldItemPagingMetrics metrics = service.pagingMetrics();
            assertEquals(0, metrics.liveMetadataCount());
            assertEquals(0, metrics.decodedDormantDtoCount());
            assertTrue(metrics.decodedPageCount() <= 2);
        }
        assertEquals(100L, service.canonicalSnapshot().nextItemId());
    }

    @Test
    void strictLegacyRestorePublishesTickMetadataCapacityAndPrunesDueBeforeProjection() {
        LogicalWorldItemService legacy = new LogicalWorldItemService(
                MainThreadGuard.captureCurrentThread(), 1_024, 0L);
        for (int index = 0; index < 1_024; index++) {
            legacy.spawn(new WorldItemSpawnRequest(
                    new ItemStack(DIRT, 1),
                    0.5,
                    4.0,
                    0.5,
                    0.0,
                    0.0,
                    0.0,
                    Optional.empty(),
                    0L));
        }
        var legacySnapshot = legacy.canonicalSnapshot();
        LogicalWorldItemService restored = service(1_024);

        assertEquals(
                com.overlord.worlditem.api.WorldItemRestoreResult.Status.RESTORED,
                restored.restoreCanonical(legacySnapshot, 17_000L).status());
        assertEquals(17_000L, restored.currentWorldTick());
        assertEquals(1_024, restored.liveMetadata().size());
        assertEquals(1_024, restored.pagingMetrics().expiryIndexCount());
        assertEquals(
                com.overlord.worlditem.api.WorldItemSpawnResult.Status.REJECTED,
                restored.spawn(new WorldItemSpawnRequest(
                        new ItemStack(DIRT, 1),
                        0.5,
                        4.0,
                        0.5,
                        0.0,
                        0.0,
                        0.0,
                        Optional.empty(),
                        17_000L)).status());
        assertEquals(1_024, restored.deliverWorldTick(18_000L).size());
        assertTrue(restored.snapshots().isEmpty());

        LogicalWorldItemService alreadyDue = service(1_024);
        assertEquals(
                com.overlord.worlditem.api.WorldItemRestoreResult.Status.RESTORED,
                alreadyDue.restoreCanonical(legacySnapshot, 18_000L).status());
        assertEquals(18_000L, alreadyDue.currentWorldTick());
        assertTrue(alreadyDue.snapshots().isEmpty());
        assertTrue(alreadyDue.liveMetadata().isEmpty());
        assertEquals(0, alreadyDue.pagingMetrics().expiryIndexCount());
    }

    private static LogicalWorldItemService service(int capacity) {
        WorldItemPageCachePolicy policy = new WorldItemPageCachePolicy(
                1_024, 2, 512L, 64, 1_024, 16L * 1_024L * 1_024L,
                64, 64L * 1_024L);
        WorldItemDurabilityVerifier verifier = (ticket, plan, proof) -> {
            if (!(proof instanceof ValidProof valid)
                    || valid.checkpointRevision != plan.intendedCheckpoint()
                            .checkpointRevision()
                    || !valid.transactionDigest.equals(plan.transactionDigest())) {
                throw new IllegalArgumentException("proof mismatch");
            }
        };
        return new LogicalWorldItemService(
                MainThreadGuard.captureCurrentThread(), capacity, 0L,
                SAVE, policy, verifier, LogicalWorldItemExpiryTest::testDescriptor);
    }

    private static WorldItemSnapshot spawn(
            LogicalWorldItemService service, ChunkKey key, long tick) {
        return service.spawn(new WorldItemSpawnRequest(
                new ItemStack(DIRT, 1),
                key.worldOriginX() + 0.5,
                4.0,
                key.worldOriginZ() + 0.5,
                0.0, 0.0, 0.0,
                Optional.empty(), tick)).item().orElseThrow();
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
                page.chunkKey(), page.pageRevision(),
                String.format("%064x", token),
                page.entries().size(), page.entries().size());
    }

    private record ValidProof(
            long checkpointRevision,
            String transactionDigest) implements com.overlord.worlditem.api.WorldItemDurableProof {}

    private static final class InvalidProof
            implements com.overlord.worlditem.api.WorldItemDurableProof {}

    private static final class TestReadView implements WorldItemPageReadView {
        private final WorldItemPagingCheckpoint checkpoint;
        private final WorldItemPageDescriptor descriptor;
        private final WorldItemPageSnapshot page;
        private boolean closed;

        private TestReadView(
                WorldItemPagingCheckpoint checkpoint,
                WorldItemPageDescriptor descriptor,
                WorldItemPageSnapshot page) {
            this.checkpoint = checkpoint;
            this.descriptor = descriptor;
            this.page = page;
        }

        @Override
        public long indexSequence() {
            return 7L;
        }

        @Override
        public String checkpointDigest() {
            return "77".repeat(32);
        }

        @Override
        public WorldItemPagingCheckpoint checkpoint() {
            return checkpoint;
        }

        @Override
        public WorldItemPageSnapshot read(WorldItemPageDescriptor requested) {
            if (closed || !descriptor.equals(requested)) {
                throw new IllegalStateException("invalid test read view");
            }
            return page;
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    @Test
    void exhaustedAllocatorRestoreAcceptsItsFinalAllocatedLongMaxId() {
        LogicalWorldItemService service = service(1);
        ChunkKey key = new ChunkKey(0, 0);
        WorldItemRestoreEntry entry = restoreEntry(Long.MAX_VALUE, key, 18_001L);
        WorldItemPageSnapshot page = new WorldItemPageSnapshot(
                key, 1L, List.of(entry));
        WorldItemPageDescriptor descriptor = new WorldItemPageDescriptor(
                key, 1L, "7f".repeat(32), 1, 1);
        WorldItemPagingCheckpoint checkpoint = new WorldItemPagingCheckpoint(
                SAVE, 1L, 0L, Long.MAX_VALUE, true, 1, List.of(descriptor));
        WorldItemLiveMetadata metadata = new WorldItemLiveMetadata(
                new WorldItemId(Long.MAX_VALUE), key, 1L, 18_001L,
                WorldItemLiveState.EVICTED_UNEXPIRED,
                Optional.of(new WorldItemDurablePageProof(
                        key, 1L, descriptor.pageHash())));

        assertTrue(service.restorePagingState(
                checkpoint, List.of(metadata), List.of(page)));
        assertEquals(Long.MAX_VALUE, service.liveMetadata().get(0).id().value());
        assertTrue(service.canonicalSnapshot().itemIdsExhausted());
    }

    @Test
    void emptyMetadataStillRejectsDescriptorReplayOutsideCurrentCheckpoint() {
        LogicalWorldItemService service = service(1);
        ChunkKey currentKey = new ChunkKey(0, 0);
        WorldItemPageSnapshot currentPage = new WorldItemPageSnapshot(
                currentKey, 1L, List.of(restoreEntry(0L, currentKey, 18_000L)));
        WorldItemPageDescriptor currentDescriptor = new WorldItemPageDescriptor(
                currentKey, 1L, "01".repeat(32), 1, 0);
        WorldItemPagingCheckpoint current = new WorldItemPagingCheckpoint(
                SAVE, 1L, 18_000L, 1L, false, 0,
                List.of(currentDescriptor));
        assertTrue(service.restorePagingState(
                current, List.of(), List.of(currentPage)));

        ChunkKey foreignKey = new ChunkKey(99, 0);
        WorldItemPageSnapshot replayPage = new WorldItemPageSnapshot(
                foreignKey, 1L, List.of(restoreEntry(0L, foreignKey, 18_000L)));
        WorldItemPageDescriptor replayDescriptor = new WorldItemPageDescriptor(
                foreignKey, 1L, "99".repeat(32), 1, 0);
        WorldItemPagingCheckpoint replay = new WorldItemPagingCheckpoint(
                SAVE, 1L, 18_000L, 1L, false, 0,
                List.of(replayDescriptor));
        try (WorldItemPageReadView view = new TestReadView(
                replay, replayDescriptor, replayPage)) {
            assertEquals(WorldItemActivationResult.Status.METADATA_MISMATCH,
                    service.prepareActivate(view, replayDescriptor).status());
        }
        assertEquals(0, service.pagingMetrics().cleanupIntentCount());
    }

    @Test
    void mixedExpiryCleanupRebuildsLatestPartialPickupRemainder() {
        LogicalWorldItemService service = service(2);
        ChunkKey key = new ChunkKey(0, 0);
        service.deliverWorldTick(0L);
        WorldItemSnapshot expiring = spawn(service, key, 0L);
        service.deliverWorldTick(1L);
        WorldItemSnapshot survivor = service.spawn(new WorldItemSpawnRequest(
                new ItemStack(DIRT, 2), 0.5, 4.0, 0.5,
                0.0, 0.0, 0.0, Optional.empty(), 1L)).item().orElseThrow();
        WorldItemHibernateResult hibernate = service.prepareHibernate(
                key, java.util.Map.of(
                        expiring.id(), expiring.revision(),
                        survivor.id(), survivor.revision()));
        WorldItemPersistencePlan persisted = hibernate.persistencePlan().orElseThrow();
        service.commitPersistence(
                hibernate.persistenceTicket().orElseThrow(),
                new ValidProof(
                        persisted.intendedCheckpoint().checkpointRevision(),
                        persisted.transactionDigest()));
        service.deliverWorldTick(18_000L);

        WorldItemPageMutation.Upsert original = (WorldItemPageMutation.Upsert)
                persisted.pageMutations().get(0);
        WorldItemPageDescriptor descriptor = persisted.intendedCheckpoint().pages().get(0);
        WorldItemActivationResult activation;
        try (WorldItemPageReadView view = new TestReadView(
                persisted.intendedCheckpoint(), descriptor, original.page())) {
            activation = service.prepareActivate(view, descriptor);
        }
        assertEquals(WorldItemActivationResult.Status.PREPARED, activation.status());
        service.commitActivate(activation.ticket().orElseThrow());
        service.commit(service.reserve(survivor.id(), 1)
                .reservation().orElseThrow().id());
        WorldItemSnapshot remainder = service.snapshot(survivor.id()).orElseThrow();

        WorldItemPersistencePlan cleanup = service.prepareCleanupPersistence()
                .orElseThrow().persistencePlan().orElseThrow();
        WorldItemPageMutation.Upsert rewrite = (WorldItemPageMutation.Upsert)
                cleanup.pageMutations().get(0);
        assertEquals(1, rewrite.page().entries().size());
        assertEquals(remainder,
                rewrite.page().entries().get(0).runtime().item());
    }

    @Test
    void completelyFreshServiceRejectsArbitraryExpiredViewWithoutCleanupSideEffects() {
        LogicalWorldItemService service = service(1);
        ChunkKey key = new ChunkKey(77, 0);
        WorldItemPageSnapshot page = new WorldItemPageSnapshot(
                key, 1L, List.of(restoreEntry(0L, key, 18_000L)));
        WorldItemPageDescriptor descriptor = new WorldItemPageDescriptor(
                key, 1L, "77".repeat(32), 1, 0);
        WorldItemPagingCheckpoint checkpoint = new WorldItemPagingCheckpoint(
                SAVE, 1L, 18_000L, 1L, false, 0, List.of(descriptor));
        try (WorldItemPageReadView view = new TestReadView(
                checkpoint, descriptor, page)) {
            assertEquals(WorldItemActivationResult.Status.INVALID_VIEW,
                    service.prepareActivate(view, descriptor).status());
        }
        assertEquals(0, service.pagingMetrics().cleanupIntentCount());
        assertTrue(service.restorePagingState(
                checkpoint, List.of(), List.of(page)));
    }
}

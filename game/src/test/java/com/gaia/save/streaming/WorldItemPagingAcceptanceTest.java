package com.gaia.save.streaming;

import static com.gaia.save.streaming.WorldItemPagingAcceptanceFixture.SAVE;
import static com.gaia.save.streaming.WorldItemPagingAcceptanceFixture.activateAll;
import static com.gaia.save.streaming.WorldItemPagingAcceptanceFixture.backend;
import static com.gaia.save.streaming.WorldItemPagingAcceptanceFixture.checkpoint;
import static com.gaia.save.streaming.WorldItemPagingAcceptanceFixture.entry;
import static com.gaia.save.streaming.WorldItemPagingAcceptanceFixture.page;
import static com.gaia.save.streaming.WorldItemPagingAcceptanceFixture.pagedSessionSnapshot;
import static com.gaia.save.streaming.WorldItemPagingAcceptanceFixture.publish;
import static com.gaia.save.streaming.WorldItemPagingAcceptanceFixture.publishBaseChunk;
import static com.gaia.save.streaming.WorldItemPagingAcceptanceFixture.service;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertAll;

import com.gaia.session.GameSessionPersistenceTestFixture;
import com.gaia.session.GameSessionState;
import com.gaia.worlditem.PhysicalWorldItemSystem;
import com.gaia.worlditem.WorldItemPhysicsConfig;
import com.overlord.core.thread.MainThreadGuard;
import com.overlord.physics.BlockCollisionShapeResolver;
import com.overlord.physics.CollisionWorld;
import com.overlord.physics.PhysicsWorld;
import com.overlord.voxel.ChunkCoordinatePolicy;
import com.overlord.voxel.ChunkKey;
import com.overlord.voxel.World;
import com.overlord.worlditem.api.WorldItemId;
import com.overlord.worlditem.api.WorldItemRestoreResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.Map;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorldItemPagingAcceptanceTest {
    private static final long WORLD_TICK = 10_000L;
    private static final List<ChunkKey> KEYS = List.of(
            new ChunkKey(-17, -3),
            new ChunkKey(-2, 5),
            new ChunkKey(-1, -12),
            new ChunkKey(0, 0),
            new ChunkKey(2, 9),
            new ChunkKey(11, -7));
    private static final long[] IDS = {90L, 10L, 70L, 20L, 50L, 30L};

    @TempDir Path tempDirectory;

    @Test
    void sixPageProcessRestartIsIndependentOfTraversalOrder() throws Exception {
        Path root = Files.createDirectory(tempDirectory.resolve("six-page-order"));
        var pages = sixPages();
        publish(root, checkpoint(7L, WORLD_TICK, 91L, pages), pages);
        StreamedWorldItemPageBackend persisted = backend(root);

        var forward = service(persisted);
        var reverse = service(persisted);
        var shuffled = service(persisted);
        assertEquals(WorldItemRestoreResult.Status.RESTORED,
                persisted.restoreFresh(
                        forward, SAVE, WORLD_TICK,
                        ChunkCoordinatePolicy.canonicalComparator(), ignored -> {}).status());
        assertEquals(WorldItemRestoreResult.Status.RESTORED,
                persisted.restoreFresh(
                        reverse, SAVE, WORLD_TICK,
                        ChunkCoordinatePolicy.canonicalComparator().reversed(), ignored -> {})
                        .status());
        Comparator<ChunkKey> shuffleOrder = shuffledOrder();
        assertEquals(WorldItemRestoreResult.Status.RESTORED,
                persisted.restoreFresh(
                        shuffled, SAVE, WORLD_TICK, shuffleOrder, ignored -> {}).status());

        assertEquals(forward.liveMetadata(), reverse.liveMetadata());
        assertEquals(forward.liveMetadata(), shuffled.liveMetadata());
        activateAll(forward, persisted, ChunkCoordinatePolicy.canonicalComparator());
        activateAll(reverse, persisted,
                ChunkCoordinatePolicy.canonicalComparator().reversed());
        assertEquals(forward.canonicalSnapshot(), reverse.canonicalSnapshot());
        activateAll(shuffled, persisted, shuffleOrder);
        assertEquals(forward.canonicalSnapshot(), shuffled.canonicalSnapshot());
        assertEquals(91L, forward.canonicalSnapshot().nextItemId());
    }

    @Test
    void restartRetainsStableIdPartialPickupAndExactRemainingTtl() throws Exception {
        Path root = Files.createDirectory(tempDirectory.resolve("ttl-pickup"));
        ChunkKey key = KEYS.get(0);
        var persistedPage = page(
                key,
                1L,
                List.of(entry(key, 500L, 3, 100_000L, 118_000L)));
        publish(root,
                checkpoint(1L, 110_800L, 501L, List.of(persistedPage)),
                List.of(persistedPage));

        StreamedWorldItemPageBackend relaunched = backend(root);
        var restored = service(relaunched);
        assertEquals(WorldItemRestoreResult.Status.RESTORED,
                relaunched.restoreFresh(restored, SAVE, 110_800L).status());
        activateAll(restored, relaunched, ChunkCoordinatePolicy.canonicalComparator());

        WorldItemId id = new WorldItemId(500L);
        restored.commit(restored.reserve(id, 1).reservation().orElseThrow().id());
        assertEquals(id, restored.snapshot(id).orElseThrow().id());
        assertEquals(2, restored.snapshot(id).orElseThrow().stack().count());
        assertTrue(restored.deliverWorldTick(117_999L).isEmpty());
        assertEquals(List.of(id), restored.deliverWorldTick(118_000L));
        assertFalse(restored.snapshot(id).isPresent());
        assertEquals(501L, restored.spawn(new com.overlord.worlditem.api.WorldItemSpawnRequest(
                new com.overlord.inventory.api.ItemStack(
                        com.overlord.assets.ResourceLocation.of("gaia", "test/after-expiry"), 1),
                0.5, 4.0, 0.5, 0.0, 0.0, 0.0,
                java.util.Optional.empty(), 118_000L)).item().orElseThrow().id().value());
    }

    @Test
    void realAuthorityGraphPersistsDisposesReopensActivatesAndClosesCleanly()
            throws Exception {
        Path root = Files.createDirectory(tempDirectory.resolve("real-process-graph"));
        long saveTick = 110_800L;
        ChunkKey key = new ChunkKey(-1, 0);
        publish(root, checkpoint(1L, saveTick, 0L, List.of()), List.of());
        publishBaseChunk(root, key);

        StreamedWorldItemPageBackend firstBackend = backend(root);
        var firstService = service(firstBackend);
        assertEquals(WorldItemRestoreResult.Status.RESTORED,
                firstBackend.restoreFresh(firstService, SAVE, saveTick).status());
        var item = firstService.spawn(new com.overlord.worlditem.api.WorldItemSpawnRequest(
                new com.overlord.inventory.api.ItemStack(
                        com.overlord.assets.ResourceLocation.of(
                                "gaia", "test/process-graph"), 2),
                key.worldOriginX() + 0.5, 4.0, key.worldOriginZ() + 0.5,
                0.0, 0.0, 0.0, java.util.Optional.empty(), saveTick))
                .item().orElseThrow();
        firstService.commit(firstService.reserve(item.id(), 1)
                .reservation().orElseThrow().id());

        MainThreadGuard guard = MainThreadGuard.captureCurrentThread();
        PhysicsWorld firstPhysics = new PhysicsWorld(
                new CollisionWorld(
                        new World(), BlockCollisionShapeResolver.fullCubesForNonAir()),
                new Vector3f());
        PhysicalWorldItemSystem firstPhysical = new PhysicalWorldItemSystem(
                firstService,
                firstPhysics,
                guard,
                new WorldItemPhysicsConfig(0.50f, 1_024));
        firstPhysical.reconcileRestoredCanonicalState(1L);
        assertEquals(1, firstPhysics.bodies().size());

        var hibernate = firstService.prepareHibernate(
                key,
                Map.of(item.id(), firstService.snapshot(item.id()).orElseThrow().revision()));
        var proof = firstBackend.persist(hibernate.persistencePlan().orElseThrow());
        firstService.commitPersistence(
                hibernate.persistenceTicket().orElseThrow(), proof);
        firstPhysical.reconcileRestoredCanonicalState(2L);
        assertTrue(firstPhysics.bodies().isEmpty());
        firstPhysical.close();
        assertTrue(firstPhysics.bodies().isEmpty());
        long retiredAllocatorHighWater = firstService.canonicalSnapshot().nextItemId();
        firstService.close();
        assertAll(
                () -> assertEquals(0, firstService.pagingMetrics().persistenceTicketCount()),
                () -> assertEquals(0, firstService.pagingMetrics().activationTicketCount()),
                () -> assertEquals(0, firstService.pagingMetrics().pinnedPageCount()),
                () -> assertEquals(0, firstService.pagingMetrics().decodedPageCount()),
                () -> assertEquals(0, firstService.pagingMetrics().liveMetadataCount()),
                () -> assertEquals(
                        retiredAllocatorHighWater,
                        firstService.canonicalSnapshot().nextItemId()));

        StreamedWorldItemPageBackend relaunchedBackend = backend(root);
        var relaunchedService = service(relaunchedBackend);
        assertEquals(WorldItemRestoreResult.Status.RESTORED,
                relaunchedBackend.restoreFresh(
                        relaunchedService, SAVE, saveTick).status());
        PhysicsWorld relaunchedPhysics = new PhysicsWorld(
                new CollisionWorld(
                        new World(), BlockCollisionShapeResolver.fullCubesForNonAir()),
                new Vector3f());
        PhysicalWorldItemSystem relaunchedPhysical = new PhysicalWorldItemSystem(
                relaunchedService,
                relaunchedPhysics,
                guard,
                new WorldItemPhysicsConfig(0.50f, 1_024));
        try (var view = relaunchedBackend.openReadView()) {
            var activation = relaunchedService.prepareActivate(
                    view, view.checkpoint().pages().get(0));
            relaunchedPhysical.commitActivate(
                    relaunchedService,
                    activation.ticket().orElseThrow(),
                    1L);
        }
        assertAll(
                () -> assertEquals(item.id(),
                        relaunchedService.snapshot(item.id()).orElseThrow().id()),
                () -> assertEquals(1,
                        relaunchedService.snapshot(item.id()).orElseThrow().stack().count()),
                () -> assertEquals(1, relaunchedPhysics.bodies().size()));
        var pending = relaunchedService.prepareHibernate(
                key,
                Map.of(item.id(),
                        relaunchedService.snapshot(item.id()).orElseThrow().revision()));
        assertTrue(pending.persistenceTicket().isPresent());
        assertTrue(relaunchedService.pagingMetrics().pinnedPageCount() > 0);
        relaunchedPhysical.close();
        relaunchedService.close();
        assertTrue(relaunchedPhysics.bodies().isEmpty());
        assertEquals(0, relaunchedService.pagingMetrics().persistenceTicketCount());
        assertEquals(0, relaunchedService.pagingMetrics().activationTicketCount());
        assertEquals(0, relaunchedService.pagingMetrics().pinnedPageCount());
        assertEquals(0, relaunchedService.pagingMetrics().decodedPageCount());
        assertEquals(0, relaunchedService.pagingMetrics().liveMetadataCount());

        for (int process = 0; process < 3; process++) {
            var production = GameSessionPersistenceTestFixture
                    .restoreActualProductionSession(
                            pagedSessionSnapshot(saveTick, item.id().value() + 1L),
                            backend(root));
            production.driveToReady();
            var captured = production.captureSave();
            assertAll(
                    () -> assertEquals(GameSessionState.READY, production.state()),
                    () -> assertEquals(
                            com.gaia.session.SessionSaveCaptureResult.Status.CAPTURED,
                            captured.status()),
                    () -> assertEquals(1, production.worldItemLiveMetadataCount()),
                    () -> assertEquals(0, production.worldItemPendingReservations()),
                    () -> assertEquals(1, production.authorizationEntryCount()));
            production.close();
            assertAll(
                    () -> assertEquals(0, production.physicsBodyCount()),
                    () -> assertEquals(0, production.inventoryPendingReservations()),
                    () -> assertEquals(0, production.worldItemPendingReservations()),
                    () -> assertEquals(0, production.liveWorkerCount()),
                    () -> assertEquals(0,
                            production.worldItemPagingMetrics().persistenceTicketCount()),
                    () -> assertEquals(0,
                            production.worldItemPagingMetrics().activationTicketCount()),
                    () -> assertEquals(0,
                            production.worldItemPagingMetrics().pinnedPageCount()),
                    () -> assertEquals(0,
                            production.worldItemPagingMetrics().decodedPageCount()),
                    () -> assertEquals(0,
                            production.worldItemPagingMetrics().liveMetadataCount()));
        }
    }

    @Test
    void realPersistenceFailureBeforeEvictionRetainsActiveCanonicalAuthority()
            throws Exception {
        Path root = Files.createDirectory(tempDirectory.resolve("persist-failure"));
        publish(root, checkpoint(1L, 0L, 0L, List.of()), List.of());
        ChunkKey key = new ChunkKey(-3, 4);
        publishBaseChunk(root, key);
        ProtocolFileOperations files = new ProtocolFileOperations(
                new com.gaia.save.store.JdkSaveFileOperations());
        StreamedWorldItemPageBackend persisted = new StreamedWorldItemPageBackend(
                new StreamedChunkStore(
                        root,
                        WorldItemPagingAcceptanceFixture.SAVE_ID,
                        new StreamedChunkCodec(),
                        new StreamedChunkIndexCodec(),
                        files));
        var logical = service(persisted);
        assertEquals(WorldItemRestoreResult.Status.RESTORED,
                persisted.restoreFresh(logical, SAVE, 0L).status());
        var item = logical.spawn(new com.overlord.worlditem.api.WorldItemSpawnRequest(
                new com.overlord.inventory.api.ItemStack(
                        com.overlord.assets.ResourceLocation.of("gaia", "test/failure"), 1),
                key.worldOriginX() + 0.5, 4.0, key.worldOriginZ() + 0.5,
                0.0, 0.0, 0.0, java.util.Optional.empty(), 0L))
                .item().orElseThrow();
        var hibernate = logical.prepareHibernate(
                key, Map.of(item.id(), item.revision()));
        var dirtyPage = ((com.overlord.worlditem.api.WorldItemPageMutation.Upsert)
                hibernate.persistencePlan().orElseThrow().pageMutations().get(0)).page();
        long exactDirtyBytes = new WorldItemPageCodec().encode(SAVE, dirtyPage).length;
        assertTrue(exactDirtyBytes > 0L);
        assertEquals(exactDirtyBytes, logical.pagingMetrics().dirtyCandidateBytes());
        files.before(ProtocolStage.WRITE_PAYLOAD_A, ignored -> {
            throw new java.io.IOException("acceptance persistence failure");
        });

        assertThrows(IllegalStateException.class, () -> persisted.persist(
                hibernate.persistencePlan().orElseThrow()));
        assertEquals(item, logical.snapshot(item.id()).orElseThrow());
        assertEquals(1, logical.pagingMetrics().pendingCount());
        assertEquals(1, logical.pagingMetrics().dirtyEntryCount());
        assertTrue(logical.pagingMetrics().pinnedPageCount() > 0);
        assertEquals(1, logical.pagingMetrics().persistenceTicketCount());
        logical.cancelPersistence(hibernate.persistenceTicket().orElseThrow());
        assertEquals(item, logical.snapshot(item.id()).orElseThrow());
        assertEquals(1, logical.pagingMetrics().activeDtoCount());
        assertEquals(0, logical.pagingMetrics().pendingCount());
        assertEquals(0, logical.pagingMetrics().persistenceTicketCount());
    }

    @Test
    void repeatedReadViewCloseLeavesSaveRootMovableOnWindows() throws Exception {
        Path root = Files.createDirectory(tempDirectory.resolve("repeated-close"));
        var pages = sixPages();
        publish(root, checkpoint(1L, WORLD_TICK, 91L, pages), pages);
        for (int iteration = 0; iteration < 3; iteration++) {
            StreamedWorldItemPageBackend reopened = backend(root);
            try (var view = reopened.openReadView()) {
                assertEquals(6, view.checkpoint().pages().size());
                assertEquals(6, view.checkpoint().totalLiveItemCount());
            }
        }
        Path authority = root.resolve(WorldItemPagingAcceptanceFixture.SAVE_ID.value());
        Path moved = root.resolve("moved-authority");
        Files.move(authority, moved);
        Files.move(moved, authority);
        assertTrue(Files.isDirectory(authority));
    }

    private static List<WorldItemPagingAcceptanceFixture.PageData> sixPages() {
        List<WorldItemPagingAcceptanceFixture.PageData> pages = new ArrayList<>();
        for (int index = 0; index < KEYS.size(); index++) {
            ChunkKey key = KEYS.get(index);
            pages.add(page(
                    key,
                    index + 1L,
                    List.of(entry(
                            key,
                            IDS[index],
                            index + 1,
                            1_000L + index,
                            18_500L + index * 100L))));
        }
        return pages;
    }

    private static Comparator<ChunkKey> shuffledOrder() {
        List<ChunkKey> shuffled = new ArrayList<>(KEYS);
        java.util.Collections.shuffle(shuffled, new Random(0x6EL));
        return Comparator.comparingInt(shuffled::indexOf);
    }
}

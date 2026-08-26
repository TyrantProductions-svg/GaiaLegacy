package com.gaia.world.streaming;

import static com.gaia.save.streaming.WorldItemPagingAcceptanceFixture.assertStructuralBounds;
import static com.gaia.save.streaming.WorldItemPagingAcceptanceFixture.policy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gaia.save.streaming.StreamedChunkStore;
import com.gaia.save.streaming.StreamedWorldItemPageBackend;
import com.gaia.save.streaming.WorldItemPagingAcceptanceFixture;
import com.overlord.assets.ResourceLocation;
import com.overlord.inventory.api.ItemStack;
import com.overlord.voxel.ChunkKey;
import com.overlord.worlditem.LogicalWorldItemService;
import com.overlord.worlditem.api.WorldItemPagingMetrics;
import com.overlord.worlditem.api.WorldItemDurablePageProof;
import com.overlord.worlditem.api.WorldItemId;
import com.overlord.worlditem.api.WorldItemLiveMetadata;
import com.overlord.worlditem.api.WorldItemLiveState;
import com.overlord.worlditem.api.WorldItemRuntimeSnapshot;
import com.overlord.worlditem.api.WorldItemSpawnRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorldItemPagingMetricsTest {
    @TempDir Path tempDirectory;

    @Test
    void historicalExpiryChurnRetainsOnlyBoundedCurrentlyLiveMetadata()
            throws Exception {
        Path root = Files.createDirectory(tempDirectory.resolve("strict-history"));
        WorldItemPagingAcceptanceFixture.publish(
                root,
                WorldItemPagingAcceptanceFixture.checkpoint(1L, 0L, 0L, List.of()),
                List.of());
        var backend = WorldItemPagingAcceptanceFixture.backend(root);
        LogicalWorldItemService service = WorldItemPagingAcceptanceFixture.service(backend);
        assertEquals(
                com.overlord.worlditem.api.WorldItemRestoreResult.Status.RESTORED,
                backend.restoreFresh(
                        service, WorldItemPagingAcceptanceFixture.SAVE, 0L).status());

        for (int epoch = 0; epoch < 8; epoch++) {
            long tick = epoch * WorldItemRuntimeSnapshot.WORLD_ITEM_TTL_TICKS;
            service.deliverWorldTick(tick);
            for (int item = 0; item < 125; item++) {
                service.spawn(new WorldItemSpawnRequest(
                        new ItemStack(
                                ResourceLocation.of("gaia", "test/metrics"), 1),
                        item + 0.5, 4.0, epoch + 0.5,
                        0.0, 0.0, 0.0, Optional.empty(), tick));
                assertStructuralBounds(service);
            }
            service.deliverWorldTick(
                    Math.addExact(tick, WorldItemRuntimeSnapshot.WORLD_ITEM_TTL_TICKS));
            WorldItemPagingMetrics metrics = service.pagingMetrics();
            assertEquals(0, metrics.liveMetadataCount());
            assertEquals(0, metrics.expiryIndexCount());
            assertEquals(0, metrics.activeDtoCount());
            assertTrue(metrics.decodedPageCount() <= policy().maxDecodedPages());
            assertStructuralBounds(service);
        }
        assertEquals(1_000L, service.canonicalSnapshot().nextItemId());
    }

    @Test
    void cleanupSaturationAndProofFailureCannotResurrectExpiredMetadata()
            throws Exception {
        Path root = Files.createDirectory(tempDirectory.resolve("cleanup-saturation"));
        var backend = WorldItemPagingAcceptanceFixture.backend(root);
        LogicalWorldItemService service = WorldItemPagingAcceptanceFixture.service(backend);
        List<WorldItemPagingAcceptanceFixture.PageData> pageData = new ArrayList<>();
        List<WorldItemLiveMetadata> metadata = new ArrayList<>();
        for (int index = 0; index < 65; index++) {
            ChunkKey key = new ChunkKey(index, 0);
            var page = WorldItemPagingAcceptanceFixture.page(
                    key,
                    1L,
                    List.of(WorldItemPagingAcceptanceFixture.entry(
                            key, index, 1, 0L, 18_000L)));
            pageData.add(page);
            metadata.add(new WorldItemLiveMetadata(
                    new WorldItemId(index),
                    key,
                    page.descriptor().pageRevision(),
                    18_000L,
                    WorldItemLiveState.EVICTED_UNEXPIRED,
                    Optional.of(new WorldItemDurablePageProof(
                            key,
                            page.descriptor().pageRevision(),
                            page.descriptor().pageHash()))));
        }
        assertTrue(service.restorePagingState(
                WorldItemPagingAcceptanceFixture.checkpoint(
                        1L, 0L, 65L, pageData),
                metadata,
                pageData.stream().map(WorldItemPagingAcceptanceFixture.PageData::page)
                        .toList()));

        assertEquals(65, service.deliverWorldTick(18_000L).size());
        WorldItemPagingMetrics expired = service.pagingMetrics();
        assertEquals(0, expired.liveMetadataCount());
        assertEquals(0, expired.expiryIndexCount());
        assertEquals(64, expired.cleanupIntentCount());
        assertTrue(expired.cleanupIntentBytes() <= policy().maxCleanupIntentBytes());
        assertEquals(1L, expired.droppedCleanupIntentCount());
        assertEquals(64L, expired.droppedCleanupIntentBytes());
        assertEquals(64, expired.tombstoneCount());
        assertStructuralBounds(service);

        var cleanup = service.prepareCleanupPersistence().orElseThrow();
        assertThrows(IllegalStateException.class, () -> service.commitPersistence(
                cleanup.persistenceTicket().orElseThrow(), new ForeignProof()));
        assertEquals(0, service.pagingMetrics().liveMetadataCount());
        assertEquals(64, service.pagingMetrics().cleanupIntentCount());
        assertTrue(service.snapshots().isEmpty());
        assertTrue(service.deliverWorldTick(18_001L).isEmpty());
        service.cancelPersistence(cleanup.persistenceTicket().orElseThrow());
        assertStructuralBounds(service);
    }

    @Test
    void realCleanupStorageFailureRevisitRetryAndRestartNeverResurrect()
            throws Exception {
        Path root = Files.createDirectory(tempDirectory.resolve("cleanup-retry"));
        ChunkKey key = new ChunkKey(-7, 4);
        var page = WorldItemPagingAcceptanceFixture.page(
                key,
                1L,
                List.of(WorldItemPagingAcceptanceFixture.entry(
                        key, 7L, 1, 0L, 18_000L)));
        WorldItemPagingAcceptanceFixture.publish(
                root,
                WorldItemPagingAcceptanceFixture.checkpoint(
                        1L, 0L, 8L, List.of(page)),
                List.of(page));
        StreamedWorldItemPageBackend backend =
                WorldItemPagingAcceptanceFixture
                        .backendFailingBeforePublicationOnce(root);
        LogicalWorldItemService service = WorldItemPagingAcceptanceFixture.service(backend);
        assertEquals(
                com.overlord.worlditem.api.WorldItemRestoreResult.Status.RESTORED,
                backend.restoreFresh(
                        service, WorldItemPagingAcceptanceFixture.SAVE, 0L).status());
        assertEquals(List.of(new WorldItemId(7L)),
                service.deliverWorldTick(18_000L));

        var cleanup = service.prepareCleanupPersistence().orElseThrow();
        assertThrows(IllegalStateException.class, () ->
                WorldItemPagingAcceptanceFixture.persist(
                        backend, cleanup.persistencePlan().orElseThrow()));
        assertTrue(service.liveMetadata().isEmpty());
        assertTrue(service.snapshots().isEmpty());
        service.cancelPersistence(cleanup.persistenceTicket().orElseThrow());

        try (var view = backend.openReadView()) {
            var due = service.prepareActivate(view, view.checkpoint().pages().get(0));
            assertEquals(
                    com.overlord.worlditem.api.WorldItemActivationResult.Status.EXPIRED,
                    due.status());
        }
        assertTrue(service.snapshots().isEmpty());

        var retry = service.prepareCleanupPersistence().orElseThrow();
        service.commitPersistence(
                retry.persistenceTicket().orElseThrow(),
                WorldItemPagingAcceptanceFixture.persist(
                        backend, retry.persistencePlan().orElseThrow()));
        assertEquals(0, service.pagingMetrics().cleanupIntentCount());
        assertEquals(0, service.pagingMetrics().physicalDescriptorCount());
        assertTrue(service.pagingMetrics().cleanupWrittenBytes() >= 64L);
        assertStructuralBounds(service);

        var reopenedBackend = WorldItemPagingAcceptanceFixture.backend(root);
        var restarted = WorldItemPagingAcceptanceFixture.service(reopenedBackend);
        assertEquals(
                com.overlord.worlditem.api.WorldItemRestoreResult.Status.RESTORED,
                reopenedBackend.restoreFresh(
                        restarted,
                        WorldItemPagingAcceptanceFixture.SAVE,
                        18_000L).status());
        assertTrue(restarted.liveMetadata().isEmpty());
        assertTrue(restarted.snapshots().isEmpty());
    }

    private static final class ForeignProof
            implements com.overlord.worlditem.api.WorldItemDurableProof {}
}

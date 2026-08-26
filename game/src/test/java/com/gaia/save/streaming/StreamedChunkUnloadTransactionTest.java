package com.gaia.save.streaming;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gaia.save.format.SaveSectionId;
import com.gaia.interaction.GameMode;
import com.gaia.save.snapshot.InventorySaveSnapshot;
import com.gaia.save.snapshot.PlayerSaveSnapshot;
import com.gaia.save.store.JdkSaveFileOperations;
import com.overlord.assets.ResourceLocation;
import com.overlord.interaction.api.EntityRef;
import com.overlord.inventory.api.BodySlot;
import com.overlord.inventory.api.ItemStack;
import com.overlord.voxel.ChunkKey;
import com.overlord.worlditem.api.WorldItemHibernateResult;
import com.overlord.worlditem.api.WorldItemSpawnRequest;
import java.time.Instant;
import java.util.HexFormat;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StreamedChunkUnloadTransactionTest {
    private static final String GENERATOR_VERSION = "task8-v1";
    private static final String BASE_HASH = "41".repeat(32);

    @TempDir
    Path temporaryDirectory;

    @Test
    void chunkPageCheckpointAndRequiredGlobalPublishThroughOneRoot() throws Exception {
        Path root = Files.createDirectory(temporaryDirectory.resolve("combined-success"));
        StreamedChunkStore store = store(root, new JdkSaveFileOperations());
        StreamedWorldItemPageBackend backend = new StreamedWorldItemPageBackend(store);
        var logical = WorldItemPagingAcceptanceFixture.service(backend);
        logical.deliverWorldTick(100L);
        ChunkKey key = new ChunkKey(-4, 6);
        var item = logical.spawn(new WorldItemSpawnRequest(
                new ItemStack(ResourceLocation.of("gaia", "test/task8"), 2),
                key.worldOriginX() + 0.5, 4.0, key.worldOriginZ() + 0.5,
                0.0, 0.0, 0.0, Optional.empty(), 100L))
                .item().orElseThrow();
        WorldItemHibernateResult hibernate = logical.prepareHibernate(
                key, Map.of(item.id(), item.revision()));
        byte[] blocks = filled((byte) 7);
        StreamedGlobalExtension session = sessionExtension(100L);
        StreamedChunkUnloadPlan plan = new StreamedChunkUnloadPlan(
                capture(key, 1L, 0L, blocks, () -> true),
                hibernate.persistencePlan(),
                List.of(new StreamedGlobalExtensionMutation.Upsert(session)));

        StreamedChunkUnloadResult persisted = backend.persistUnload(plan);

        assertEquals(StreamedChunkUnloadResult.Status.SUCCESS, persisted.status());
        assertTrue(persisted.durableProof().isPresent());
        assertEquals(
                WorldItemHibernateResult.Status.COMMITTED,
                logical.commitPersistence(
                        hibernate.persistenceTicket().orElseThrow(),
                        persisted.durableProof().orElseThrow()).status());
        StreamedChunkStore.CurrentAuthorityReadResult current = store(root,
                new JdkSaveFileOperations()).readCurrentAuthority(
                        WorldItemPagingAcceptanceFixture.SAVE_ID);
        assertEquals(StreamedChunkStore.CurrentAuthorityReadResult.Status.FOUND,
                current.status());
        assertEquals(1, current.payloads().size());
        StreamedChunkPayload payload = current.payloads().get(0);
        assertArrayEquals(blocks, payload.copyCanonicalVoxels());
        assertTrue(payload.extensions().stream().anyMatch(extension ->
                extension.sectionId().equals(SaveSectionId.WORLD_ITEM_PAGE)));
        assertTrue(current.index().orElseThrow().globalExtension(
                SaveSectionId.WORLD_ITEM_CHECKPOINT).isPresent());
        StreamedGlobalExtension publishedSession = current.index().orElseThrow()
                .globalExtension(SaveSectionId.STREAMED_SESSION_CHECKPOINT)
                .orElseThrow();
        StreamedSessionCheckpoint bound = new StreamedSessionCheckpointCodec()
                .decode(publishedSession.copyPayloadBytes());
        var intended = hibernate.persistencePlan().orElseThrow()
                .intendedCheckpoint();
        String intendedDigest = HexFormat.of().formatHex(StreamedChunkCodec.sha256(
                new WorldItemPagingCheckpointCodec().encode(intended)));
        assertEquals(intended.checkpointRevision(),
                bound.worldItemCheckpointRevision());
        assertEquals(intendedDigest, bound.worldItemCheckpointDigest());
        assertEquals(1L, bound.worldItemSourceIndexSequence());
        assertEquals(100L, bound.fixedTick());
    }

    @Test
    void finalRootFailureLeavesCompleteOldAuthorityWithoutMixedState() throws Exception {
        Path root = Files.createDirectory(temporaryDirectory.resolve("combined-failure"));
        ProtocolFileOperations files = new ProtocolFileOperations(
                new JdkSaveFileOperations());
        StreamedChunkStore store = store(root, files);
        ChunkKey key = new ChunkKey(3, -2);
        byte[] oldBlocks = filled((byte) 2);
        assertEquals(StreamedChunkStore.CommitResult.Status.SUCCESS,
                store.commitTransaction(new StreamedPersistenceTransaction(
                        List.of(new StreamedChunkMutation.Upsert(
                                capture(key, 1L, 0L, oldBlocks, () -> true))),
                        List.of(),
                        () -> true)).status());
        StreamedWorldItemPageBackend backend = new StreamedWorldItemPageBackend(store);
        var logical = WorldItemPagingAcceptanceFixture.service(backend);
        logical.deliverWorldTick(0L);
        var item = logical.spawn(new WorldItemSpawnRequest(
                new ItemStack(ResourceLocation.of("gaia", "test/task8-failure"), 1),
                key.worldOriginX() + 0.5, 4.0, key.worldOriginZ() + 0.5,
                0.0, 0.0, 0.0, Optional.empty(), 0L))
                .item().orElseThrow();
        WorldItemHibernateResult hibernate = logical.prepareHibernate(
                key, Map.of(item.id(), item.revision()));
        files.before(ProtocolStage.WRITE_RECOVERY, ignored -> {
            throw new java.io.IOException("crash before combined root publication");
        });

        StreamedChunkUnloadResult failed = backend.persistUnload(
                new StreamedChunkUnloadPlan(
                        capture(key, 2L, 1L, filled((byte) 9), () -> true),
                        hibernate.persistencePlan(),
                        List.of(new StreamedGlobalExtensionMutation.Upsert(
                                sessionExtension(0L)))));

        assertEquals(StreamedChunkUnloadResult.Status.FAILED, failed.status());
        assertTrue(failed.durableProof().isEmpty());
        StreamedChunkStore.CurrentAuthorityReadResult current = store(root,
                new JdkSaveFileOperations()).readCurrentAuthority(
                        WorldItemPagingAcceptanceFixture.SAVE_ID);
        assertEquals(StreamedChunkStore.CurrentAuthorityReadResult.Status.FOUND,
                current.status());
        assertEquals(1, current.payloads().size());
        assertArrayEquals(oldBlocks, current.payloads().get(0).copyCanonicalVoxels());
        assertFalse(current.payloads().get(0).extensions().stream().anyMatch(extension ->
                extension.sectionId().equals(SaveSectionId.WORLD_ITEM_PAGE)));
        assertTrue(current.index().orElseThrow().globalExtensions().isEmpty());
        assertEquals(item, logical.snapshot(item.id()).orElseThrow());
    }

    @Test
    void staleDetachedCaptureCannotPublishAnyPartOfCandidate() throws Exception {
        Path root = Files.createDirectory(temporaryDirectory.resolve("combined-stale"));
        StreamedChunkStore store = store(root, new JdkSaveFileOperations());
        StreamedWorldItemPageBackend backend = new StreamedWorldItemPageBackend(store);
        ChunkKey key = new ChunkKey(7, 8);

        StreamedChunkUnloadResult result = backend.persistUnload(
                new StreamedChunkUnloadPlan(
                        capture(key, 1L, 0L, filled((byte) 4), () -> false),
                        Optional.empty(),
                        List.of()));

        assertEquals(StreamedChunkUnloadResult.Status.STALE, result.status());
        assertTrue(result.durableProof().isEmpty());
        assertTrue(store(root, new JdkSaveFileOperations())
                .readCurrentAuthority(WorldItemPagingAcceptanceFixture.SAVE_ID)
                .index().orElseThrow().entries().isEmpty());
    }

    @Test
    void cleanUnloadIsNoOpWhenAbsentAndRemovesObsoleteModifiedAuthority()
            throws Exception {
        Path root = Files.createDirectory(
                temporaryDirectory.resolve("clean-remove"));
        StreamedChunkStore store = store(root, new JdkSaveFileOperations());
        StreamedWorldItemPageBackend backend =
                new StreamedWorldItemPageBackend(store);
        ChunkKey key = new ChunkKey(-6, 4);
        StreamedChunkUnloadPlan initiallyClean = new StreamedChunkUnloadPlan(
                capture(key, 1L, 0L, filled((byte) 1), true, () -> true),
                Optional.empty(),
                List.of(),
                false);

        assertEquals(StreamedChunkUnloadResult.Status.SUCCESS,
                backend.persistUnload(initiallyClean).status());
        assertTrue(store(root, new JdkSaveFileOperations())
                .readCurrentAuthority(WorldItemPagingAcceptanceFixture.SAVE_ID)
                .index().orElseThrow().entry(key).isEmpty());

        assertEquals(StreamedChunkStore.CommitResult.Status.SUCCESS,
                store.commitTransaction(new StreamedPersistenceTransaction(
                        List.of(new StreamedChunkMutation.Upsert(
                                capture(key, 1L, 0L, filled((byte) 9),
                                        true, () -> true))),
                        List.of(),
                        () -> true)).status());
        StreamedChunkUnloadPlan restoredToBase = new StreamedChunkUnloadPlan(
                capture(key, 2L, 1L, filled((byte) 1), true, () -> true),
                Optional.empty(),
                List.of(),
                false);

        assertEquals(StreamedChunkUnloadResult.Status.SUCCESS,
                backend.persistUnload(restoredToBase).status());
        assertTrue(store(root, new JdkSaveFileOperations())
                .readCurrentAuthority(WorldItemPagingAcceptanceFixture.SAVE_ID)
                .index().orElseThrow().entry(key).isEmpty(),
                "returning to deterministic base must remove the old payload");
    }

    @Test
    void cleanUnloadIsNoOpWhenExistingDurableBytesAlreadyEqualTheBaseCapture()
            throws Exception {
        Path root = Files.createDirectory(
                temporaryDirectory.resolve("clean-equivalent"));
        StreamedChunkStore store = store(root, new JdkSaveFileOperations());
        StreamedWorldItemPageBackend backend =
                new StreamedWorldItemPageBackend(store);
        ChunkKey key = new ChunkKey(-8, -5);
        byte[] baseBytes = filled((byte) 3);
        assertEquals(StreamedChunkStore.CommitResult.Status.SUCCESS,
                store.commitTransaction(new StreamedPersistenceTransaction(
                        List.of(new StreamedChunkMutation.Upsert(
                                capture(key, 1L, 0L, baseBytes,
                                        true, () -> true))),
                        List.of(), () -> true)).status());

        StreamedChunkUnloadResult result = backend.persistUnload(
                new StreamedChunkUnloadPlan(
                        capture(key, 2L, 1L, baseBytes, true, () -> true),
                        Optional.empty(), List.of(), false));

        assertEquals(StreamedChunkUnloadResult.Status.SUCCESS, result.status());
        assertEquals(1L, store(root, new JdkSaveFileOperations())
                .readCurrentAuthority(WorldItemPagingAcceptanceFixture.SAVE_ID)
                .index().orElseThrow().entry(key).orElseThrow().revision(),
                "an exact equivalent legacy payload needs no synchronous root rewrite");
    }

    @Test
    void restoredModifiedUnloadIsNoOpWhenDurableChunkBytesAreAlreadyExact()
            throws Exception {
        Path root = Files.createDirectory(
                temporaryDirectory.resolve("modified-equivalent"));
        StreamedChunkStore store = store(root, new JdkSaveFileOperations());
        StreamedWorldItemPageBackend backend =
                new StreamedWorldItemPageBackend(store);
        ChunkKey key = new ChunkKey(80, -10);
        byte[] modifiedBytes = filled((byte) 7);
        assertEquals(StreamedChunkStore.CommitResult.Status.SUCCESS,
                store.commitTransaction(new StreamedPersistenceTransaction(
                        List.of(new StreamedChunkMutation.Upsert(
                                capture(key, 1L, 0L, modifiedBytes,
                                        true, () -> true))),
                        List.of(), () -> true)).status());

        StreamedChunkUnloadResult result = backend.persistUnload(
                new StreamedChunkUnloadPlan(
                        capture(key, 1L, 1L, modifiedBytes, true, () -> true),
                        Optional.empty(), List.of(), true));

        assertEquals(StreamedChunkUnloadResult.Status.SUCCESS, result.status());
        assertEquals(1L, result.persistedChunkRevision().orElseThrow());
        assertEquals(1L, store(root, new JdkSaveFileOperations())
                .readCurrentAuthority(WorldItemPagingAcceptanceFixture.SAVE_ID)
                .index().orElseThrow().entry(key).orElseThrow().revision(),
                "an exact restored modified payload needs no root rewrite");
    }

    @Test
    void restoredModifiedChunkCanPublishWorldItemPageWithoutFalseChunkRevisionAck()
            throws Exception {
        Path root = Files.createDirectory(
                temporaryDirectory.resolve("modified-page-update"));
        StreamedChunkStore store = store(root, new JdkSaveFileOperations());
        StreamedWorldItemPageBackend backend =
                new StreamedWorldItemPageBackend(store);
        ChunkKey key = new ChunkKey(80, -10);
        byte[] modifiedBytes = filled((byte) 8);
        assertEquals(StreamedChunkStore.CommitResult.Status.SUCCESS,
                store.commitTransaction(new StreamedPersistenceTransaction(
                        List.of(new StreamedChunkMutation.Upsert(
                                capture(key, 1L, 0L, modifiedBytes,
                                        true, () -> true))),
                        List.of(), () -> true)).status());
        var logical = WorldItemPagingAcceptanceFixture.service(backend);
        logical.deliverWorldTick(100L);
        var item = logical.spawn(new WorldItemSpawnRequest(
                new ItemStack(ResourceLocation.of("gaia", "test/rehibernate"), 1),
                key.worldOriginX() + 0.5, 4.0, key.worldOriginZ() + 0.5,
                0.0, 0.0, 0.0, Optional.empty(), 100L))
                .item().orElseThrow();
        WorldItemHibernateResult hibernate = logical.prepareHibernate(
                key, Map.of(item.id(), item.revision()));

        StreamedChunkUnloadResult result = backend.persistUnload(
                new StreamedChunkUnloadPlan(
                        capture(key, 1L, 1L, modifiedBytes, true, () -> true),
                        hibernate.persistencePlan(),
                        List.of(new StreamedGlobalExtensionMutation.Upsert(
                                sessionExtension(100L))),
                        true));

        assertEquals(StreamedChunkUnloadResult.Status.SUCCESS, result.status());
        assertTrue(result.durableProof().isPresent());
        assertTrue(result.persistedChunkRevision().isEmpty(),
                "a page-only physical rewrite must not claim a newer canonical Chunk revision");
        assertEquals(2L, store(root, new JdkSaveFileOperations())
                .readCurrentAuthority(WorldItemPagingAcceptanceFixture.SAVE_ID)
                .index().orElseThrow().entry(key).orElseThrow().revision());
        assertEquals(WorldItemHibernateResult.Status.COMMITTED,
                logical.commitPersistence(
                        hibernate.persistenceTicket().orElseThrow(),
                        result.durableProof().orElseThrow()).status());
    }

    @Test
    void combinedPageRewritePreservesIndependentOpaqueChunkExtensions()
            throws Exception {
        Path root = Files.createDirectory(
                temporaryDirectory.resolve("preserve-independent-extension"));
        StreamedChunkStore store = store(root, new JdkSaveFileOperations());
        StreamedWorldItemPageBackend backend = new StreamedWorldItemPageBackend(store);
        ChunkKey key = new ChunkKey(81, -11);
        byte[] blocks = filled((byte) 9);
        byte[] detail = new byte[] {4, 5, 6};
        StreamedChunkPayload current = new StreamedChunkPayload(
                WorldItemPagingAcceptanceFixture.SAVE_ID,
                key, GENERATOR_VERSION, BASE_HASH, 1L, 0L,
                true, true, 1, blocks,
                List.of(new StreamedChunkPayload.ExtensionDescriptor(
                        SaveSectionId.DETAIL_BLOCKS, 1, false, detail)));
        assertEquals(StreamedChunkStore.CommitResult.Status.SUCCESS,
                store.commitTransaction(new StreamedPersistenceTransaction(
                        List.of(new StreamedChunkMutation.Upsert(
                                new StreamedChunkStore.ExactChunkCapture(
                                        current, () -> true))),
                        List.of(), () -> true)).status());
        var logical = WorldItemPagingAcceptanceFixture.service(backend);
        logical.deliverWorldTick(100L);
        var item = logical.spawn(new WorldItemSpawnRequest(
                new ItemStack(ResourceLocation.of("gaia", "test/preserve-extension"), 1),
                key.worldOriginX() + 0.5, 4.0, key.worldOriginZ() + 0.5,
                0.0, 0.0, 0.0, Optional.empty(), 100L)).item().orElseThrow();
        WorldItemHibernateResult hibernate = logical.prepareHibernate(
                key, Map.of(item.id(), item.revision()));

        StreamedChunkUnloadResult result = backend.persistUnload(
                new StreamedChunkUnloadPlan(
                        capture(key, 1L, 1L, blocks, true, () -> true),
                        hibernate.persistencePlan(),
                        List.of(new StreamedGlobalExtensionMutation.Upsert(
                                sessionExtension(100L))), true));

        assertEquals(StreamedChunkUnloadResult.Status.SUCCESS, result.status());
        StreamedChunkPayload published = store(root, new JdkSaveFileOperations())
                .readCurrentAuthority(WorldItemPagingAcceptanceFixture.SAVE_ID)
                .payloads().get(0);
        assertArrayEquals(detail, published.extensions().stream()
                .filter(extension -> extension.sectionId().equals(
                        SaveSectionId.DETAIL_BLOCKS))
                .findFirst().orElseThrow().copyBytes());
        assertTrue(published.extensions().stream().anyMatch(extension ->
                extension.sectionId().equals(SaveSectionId.WORLD_ITEM_PAGE)));
    }

    @Test
    void worldItemCancellationIsWorkerVisibleAndPreventsCombinedPublication()
            throws Exception {
        Path root = Files.createDirectory(temporaryDirectory.resolve("world-item-stale"));
        StreamedChunkStore store = store(root, new JdkSaveFileOperations());
        StreamedWorldItemPageBackend backend = new StreamedWorldItemPageBackend(store);
        var logical = WorldItemPagingAcceptanceFixture.service(backend);
        logical.deliverWorldTick(400L);
        ChunkKey key = new ChunkKey(-9, 2);
        var item = logical.spawn(new WorldItemSpawnRequest(
                new ItemStack(ResourceLocation.of("gaia", "test/task8-stale"), 1),
                key.worldOriginX() + 0.5, 4.0, key.worldOriginZ() + 0.5,
                0.0, 0.0, 0.0, Optional.empty(), 400L))
                .item().orElseThrow();
        WorldItemHibernateResult hibernate = logical.prepareHibernate(
                key, Map.of(item.id(), item.revision()));
        StreamedChunkUnloadPlan plan = new StreamedChunkUnloadPlan(
                capture(key, 1L, 0L, filled((byte) 6), () -> true),
                hibernate.persistencePlan(), List.of(
                        new StreamedGlobalExtensionMutation.Upsert(
                                sessionExtension(400L))));
        CountDownLatch releaseWorker = new CountDownLatch(1);
        AtomicReference<StreamedChunkUnloadResult> workerResult =
                new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try {
                assertTrue(releaseWorker.await(5, TimeUnit.SECONDS));
                workerResult.set(backend.persistUnload(plan));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError(interrupted);
            }
        }, "task8-world-item-stale-worker");
        worker.start();

        assertTrue(hibernate.persistencePlan().orElseThrow()
                .stillCurrent().getAsBoolean());
        assertEquals(WorldItemHibernateResult.Status.CANCELED,
                logical.cancelPersistence(
                        hibernate.persistenceTicket().orElseThrow()).status());
        releaseWorker.countDown();
        worker.join(5_000L);

        assertFalse(worker.isAlive());
        assertEquals(StreamedChunkUnloadResult.Status.STALE,
                workerResult.get().status());
        assertTrue(store(root, new JdkSaveFileOperations())
                .readCurrentAuthority(WorldItemPagingAcceptanceFixture.SAVE_ID)
                .index().orElseThrow().entries().isEmpty());
        assertEquals(item, logical.snapshot(item.id()).orElseThrow());
    }

    @Test
    void combinedUnloadRejectsUnboundedOrNonSessionGlobalMutations() {
        StreamedChunkStore.ExactChunkCapture capture = capture(
                new ChunkKey(1, 1), 1L, 0L, filled((byte) 1), () -> true);
        StreamedGlobalExtension session = new StreamedGlobalExtension(
                SaveSectionId.STREAMED_SESSION_CHECKPOINT,
                1, true, Optional.empty(), new byte[] {1});
        StreamedGlobalExtension nonRequiredSession = new StreamedGlobalExtension(
                SaveSectionId.STREAMED_SESSION_CHECKPOINT,
                1, false, Optional.empty(), new byte[] {1});
        StreamedGlobalExtension arbitrary = new StreamedGlobalExtension(
                SaveSectionId.DISCOVERY_LORE,
                1, true, Optional.empty(), new byte[] {1});

        assertThrows(IllegalArgumentException.class, () ->
                new StreamedChunkUnloadPlan(capture, Optional.empty(), List.of(
                        new StreamedGlobalExtensionMutation.Upsert(session),
                        new StreamedGlobalExtensionMutation.Upsert(session))));
        assertThrows(IllegalArgumentException.class, () ->
                new StreamedChunkUnloadPlan(capture, Optional.empty(), List.of(
                        new StreamedGlobalExtensionMutation.Upsert(session))));
        assertThrows(IllegalArgumentException.class, () ->
                new StreamedChunkUnloadPlan(capture, Optional.empty(), List.of(
                        new StreamedGlobalExtensionMutation.Remove(
                                SaveSectionId.STREAMED_SESSION_CHECKPOINT))));
        assertThrows(IllegalArgumentException.class, () ->
                new StreamedChunkUnloadPlan(capture, Optional.empty(), List.of(
                        new StreamedGlobalExtensionMutation.Upsert(
                                nonRequiredSession))));
        assertThrows(IllegalArgumentException.class, () ->
                new StreamedChunkUnloadPlan(capture, Optional.empty(), List.of(
                        new StreamedGlobalExtensionMutation.Upsert(arbitrary))));
        assertThrows(IllegalArgumentException.class, () ->
                new StreamedChunkUnloadPlan(capture, Optional.empty(), List.of(
                        new StreamedGlobalExtensionMutation.Upsert(
                                new StreamedGlobalExtension(
                                        SaveSectionId.STREAMED_SESSION_CHECKPOINT,
                                        StreamedSessionCheckpointCodec.CODEC_VERSION,
                                        true, Optional.empty(), new byte[] {9, 8, 7})))));
    }

    private static StreamedChunkStore store(
            Path root, com.gaia.save.store.SaveFileOperations files) {
        return new StreamedChunkStore(
                root,
                WorldItemPagingAcceptanceFixture.SAVE_ID,
                new StreamedChunkCodec(),
                new StreamedChunkIndexCodec(),
                files);
    }

    private static StreamedChunkStore.ExactChunkCapture capture(
            ChunkKey key,
            long revision,
            long persistedRevision,
            byte[] blocks,
            java.util.function.BooleanSupplier stillCurrent) {
        return new StreamedChunkStore.ExactChunkCapture(
                new StreamedChunkPayload(
                        WorldItemPagingAcceptanceFixture.SAVE_ID,
                        key,
                        GENERATOR_VERSION,
                        BASE_HASH,
                        revision,
                        persistedRevision,
                        true,
                        true,
                        1,
                        blocks,
                        List.of()),
                stillCurrent);
    }

    private static StreamedChunkStore.ExactChunkCapture capture(
            ChunkKey key,
            long revision,
            long persistedRevision,
            byte[] blocks,
            boolean voxelModified,
            java.util.function.BooleanSupplier stillCurrent) {
        return new StreamedChunkStore.ExactChunkCapture(
                new StreamedChunkPayload(
                        WorldItemPagingAcceptanceFixture.SAVE_ID,
                        key,
                        GENERATOR_VERSION,
                        BASE_HASH,
                        revision,
                        persistedRevision,
                        voxelModified,
                        voxelModified,
                        1,
                        blocks,
                        List.of()),
                stillCurrent);
    }

    private static byte[] filled(byte value) {
        byte[] blocks = new byte[16 * 16];
        java.util.Arrays.fill(blocks, value);
        return blocks;
    }

    private static StreamedGlobalExtension sessionExtension(long fixedTick) {
        EntityRef owner = new EntityRef(0);
        StreamedSessionCheckpoint placeholder = new StreamedSessionCheckpoint(
                WorldItemPagingAcceptanceFixture.SAVE_ID,
                fixedTick,
                1L,
                "00".repeat(32),
                0L,
                Instant.parse("2026-08-23T00:00:00Z"),
                new PlayerSaveSnapshot(
                        owner, 0.5, 8.0, 0.5,
                        0.0, 0.0, 0.0,
                        -90.0, 0.0, GameMode.SURVIVAL, false),
                new InventorySaveSnapshot(
                        owner, Map.of(), BodySlot.LEFT_HAND, false, 0L));
        return new StreamedGlobalExtension(
                SaveSectionId.STREAMED_SESSION_CHECKPOINT,
                StreamedSessionCheckpointCodec.CODEC_VERSION,
                true,
                Optional.empty(),
                new StreamedSessionCheckpointCodec().encode(placeholder));
    }
}

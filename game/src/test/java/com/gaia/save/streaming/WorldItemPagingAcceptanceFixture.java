package com.gaia.save.streaming;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gaia.save.format.SaveGameId;
import com.gaia.save.format.SaveFormatVersion;
import com.gaia.save.format.SaveSectionId;
import com.gaia.save.snapshot.InventorySaveSnapshot;
import com.gaia.save.snapshot.PlayerSaveSnapshot;
import com.gaia.save.snapshot.SaveGameSnapshot;
import com.gaia.save.snapshot.WorldItemsSaveSnapshot;
import com.gaia.save.store.JdkSaveFileOperations;
import com.gaia.interaction.GameMode;
import com.overlord.inventory.api.BodySlot;
import com.overlord.assets.ResourceLocation;
import com.overlord.core.thread.MainThreadGuard;
import com.overlord.interaction.api.EntityRef;
import com.overlord.inventory.api.ItemStack;
import com.overlord.voxel.ChunkRepositorySnapshot;
import com.overlord.voxel.ChunkSnapshot;
import com.overlord.voxel.ChunkKey;
import com.overlord.worlditem.LogicalWorldItemService;
import com.overlord.worlditem.api.SaveIdentity;
import com.overlord.worlditem.api.WorldItemId;
import com.overlord.worlditem.api.WorldItemPageCachePolicy;
import com.overlord.worlditem.api.WorldItemPageDescriptor;
import com.overlord.worlditem.api.WorldItemPageSnapshot;
import com.overlord.worlditem.api.WorldItemPagingCheckpoint;
import com.overlord.worlditem.api.WorldItemPagingMetrics;
import com.overlord.worlditem.api.WorldItemPersistencePlan;
import com.overlord.worlditem.api.WorldItemDurableProof;
import com.overlord.worlditem.api.WorldItemPhysicalState;
import com.overlord.worlditem.api.WorldItemRestoreEntry;
import com.overlord.worlditem.api.WorldItemRuntimeSnapshot;
import com.overlord.worlditem.api.WorldItemSnapshot;
import com.overlord.worlditem.api.LogicalWorldItemSnapshot;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/** Shared real-backend fixture for the final Phase 15 WorldItem acceptance matrix. */
public final class WorldItemPagingAcceptanceFixture {
    public static final SaveGameId SAVE_ID =
            SaveGameId.parse("123e4567-e89b-12d3-a456-426614174000");
    public static final SaveIdentity SAVE =
            new SaveIdentity(UUID.fromString(SAVE_ID.value()));
    private static final String BASE_HASH = "11".repeat(32);

    private WorldItemPagingAcceptanceFixture() {}

    public static LogicalWorldItemService service(StreamedWorldItemPageBackend backend) {
        WorldItemPageCodec codec = new WorldItemPageCodec();
        return new LogicalWorldItemService(
                MainThreadGuard.captureCurrentThread(),
                1_024,
                0L,
                SAVE,
                policy(),
                backend.durabilityVerifier(),
                page -> descriptor(codec, page),
                page -> codec.encode(SAVE, page).length);
    }

    public static WorldItemPageCachePolicy policy() {
        return new WorldItemPageCachePolicy(
                1_024, 32, 16L * 1_024L * 1_024L,
                64, 1_024, 16L * 1_024L * 1_024L,
                64, 64L * 1_024L);
    }

    /** Checks every bounded resident dimension exposed by the accepted paging API. */
    public static void assertStructuralBounds(LogicalWorldItemService service) {
        WorldItemPagingMetrics metrics = service.pagingMetrics();
        assertTrue(metrics.liveMetadataCount() <= policy().maxLiveMetadata());
        assertEquals(metrics.liveMetadataCount(), metrics.expiryIndexCount());
        assertEquals(
                metrics.liveMetadataCount(),
                metrics.activeDtoCount()
                        + metrics.decodedDormantDtoCount()
                        + metrics.evictedUnexpiredCount()
                        + metrics.pendingCount());
        assertTrue(metrics.decodedPageCount() <= policy().maxDecodedPages());
        assertTrue(metrics.decodedPageBytes() <= policy().maxDecodedPageBytes());
        assertTrue(metrics.pinnedPageCount() <= metrics.decodedPageCount());
        assertTrue(metrics.dirtyEntryCount() <= policy().maxDirtyEntries());
        assertTrue(metrics.dirtyCandidateBytes() >= 0L);
        assertTrue(metrics.dirtyCandidateBytes() <= policy().maxDirtyCandidateBytes());
        assertTrue(metrics.zeroLiveDescriptorCount()
                <= metrics.physicalDescriptorCount());
        assertTrue(metrics.unprovedPinnedPageCount()
                <= metrics.pinnedPageCount());
        assertTrue(metrics.cleanupIntentCount() <= policy().maxCleanupIntents());
        assertTrue(metrics.cleanupIntentBytes() <= policy().maxCleanupIntentBytes());
        assertTrue(metrics.tombstoneCount() <= metrics.cleanupIntentCount());
        assertTrue(metrics.cleanupWrittenBytes() >= 0L);
        assertEquals(
                Math.multiplyExact(metrics.droppedCleanupIntentCount(), 64L),
                metrics.droppedCleanupIntentBytes());
        assertTrue(metrics.persistenceTicketCount()
                + metrics.activationTicketCount() <= policy().maxPagingTickets());
        assertTrue(metrics.physicalDescriptorCount() <= policy().maxLiveMetadata());
        assertTrue(metrics.projectionCallbackDepth() >= 0);
        assertTrue(metrics.projectionCallbackDepth() <= 1);
    }

    public static StreamedWorldItemPageBackend backend(Path root) {
        return new StreamedWorldItemPageBackend(new StreamedChunkStore(
                root,
                SAVE_ID,
                new StreamedChunkCodec(),
                new StreamedChunkIndexCodec(),
                new JdkSaveFileOperations()));
    }

    /** Real backend whose next candidate/root mutation fails before publication, then retries. */
    public static StreamedWorldItemPageBackend backendFailingBeforePublicationOnce(
            Path root) {
        ProtocolFileOperations files = new ProtocolFileOperations(
                new JdkSaveFileOperations());
        AtomicBoolean failOnce = new AtomicBoolean(true);
        ProtocolAction fail = ignored -> {
            if (failOnce.getAndSet(false)) {
                throw new java.io.IOException("cleanup persistence failure");
            }
        };
        files.before(ProtocolStage.CREATE_PAYLOAD_A, fail);
        files.before(ProtocolStage.CREATE_PAYLOAD_B, fail);
        files.before(ProtocolStage.WRITE_PAYLOAD_A, fail);
        files.before(ProtocolStage.WRITE_PAYLOAD_B, fail);
        files.before(ProtocolStage.CREATE_MAIN, fail);
        files.before(ProtocolStage.CREATE_RECOVERY, fail);
        files.before(ProtocolStage.WRITE_MAIN, fail);
        files.before(ProtocolStage.WRITE_RECOVERY, fail);
        return new StreamedWorldItemPageBackend(new StreamedChunkStore(
                root,
                SAVE_ID,
                new StreamedChunkCodec(),
                new StreamedChunkIndexCodec(),
                files));
    }

    /** Minimal real production v2 snapshot bound to the shared paging save identity. */
    public static SaveGameSnapshot pagedSessionSnapshot(long worldTick, long nextItemId) {
        int radius = 2;
        int height = 16;
        List<ChunkSnapshot> chunks = new ArrayList<>();
        for (int x = -radius; x <= radius; x++) {
            for (int z = -radius; z <= radius; z++) {
                chunks.add(ChunkSnapshot.empty(new ChunkKey(x, z), 1L, height));
            }
        }
        EntityRef owner = new EntityRef(0);
        return new SaveGameSnapshot(
                new SaveGameSnapshot.StaticMetadata(
                        SaveFormatVersion.CURRENT,
                        "0.2.0-test",
                        SAVE_ID,
                        "Task 6E process restart",
                        Instant.parse("2026-08-12T00:00:00Z"),
                        12345L,
                        "gaia-v2",
                        "22".repeat(32),
                        radius,
                        height,
                        Optional.empty()),
                worldTick,
                new ChunkRepositorySnapshot(height, 1L, chunks),
                new PlayerSaveSnapshot(
                        owner, 0.5, 8.0, 0.5,
                        0.0, 0.0, 0.0,
                        -90.0, 0.0, GameMode.SURVIVAL, false),
                new InventorySaveSnapshot(
                        owner, Map.of(), BodySlot.LEFT_HAND, false, 0L),
                new WorldItemsSaveSnapshot(
                        worldTick,
                        List.of(),
                        nextItemId,
                        false,
                        LogicalWorldItemSnapshot.Completeness.PAGED_PARTIAL));
    }

    public static WorldItemDurableProof persist(
            StreamedWorldItemPageBackend backend, WorldItemPersistencePlan plan) {
        return backend.persist(plan);
    }

    /** Bounded test adapter that exercises the strict plan/proof/read-view seam without disk I/O. */
    public static BoundedSoakBackend boundedSoakBackend() {
        return new BoundedSoakBackend();
    }

    public static final class BoundedSoakBackend {
        private final Object proofScope = new Object();
        private final WorldItemPageCodec codec = new WorldItemPageCodec();
        private Map<ChunkKey, WorldItemPageSnapshot> pages = Map.of();
        private WorldItemPagingCheckpoint checkpoint;
        private String checkpointDigest = "00".repeat(32);

        public LogicalWorldItemService service() {
            return new LogicalWorldItemService(
                    MainThreadGuard.captureCurrentThread(), 1_024, 0L, SAVE, policy(),
                    this::verify,
                    page -> descriptor(codec, page),
                    page -> codec.encode(SAVE, page).length);
        }

        public WorldItemDurableProof persist(WorldItemPersistencePlan plan) {
            WorldItemPersistencePlan checked = Objects.requireNonNull(plan, "plan");
            long currentRevision = checkpoint == null ? 0L : checkpoint.checkpointRevision();
            if (checked.expectedCheckpointRevision() != currentRevision
                    || !checked.stillCurrent().getAsBoolean()) {
                throw new IllegalStateException("stale soak persistence plan");
            }
            Map<ChunkKey, WorldItemPageSnapshot> candidate = new LinkedHashMap<>(pages);
            Map<ChunkKey, WorldItemPageDescriptor> currentDescriptors = new LinkedHashMap<>();
            if (checkpoint != null) {
                checkpoint.pages().forEach(value ->
                        currentDescriptors.put(value.chunkKey(), value));
            }
            for (var mutation : checked.pageMutations()) {
                if (mutation instanceof com.overlord.worlditem.api.WorldItemPageMutation.Upsert upsert) {
                    if (!upsert.expectedPrevious().equals(Optional.ofNullable(
                            currentDescriptors.get(upsert.page().chunkKey())))) {
                        throw new IllegalStateException("soak replacement proof mismatch");
                    }
                    candidate.put(upsert.page().chunkKey(), upsert.page());
                } else {
                    var remove = (com.overlord.worlditem.api.WorldItemPageMutation.Remove) mutation;
                    if (!remove.expected().equals(currentDescriptors.get(
                            remove.expected().chunkKey()))) {
                        throw new IllegalStateException("soak removal proof mismatch");
                    }
                    candidate.remove(remove.expected().chunkKey());
                }
            }
            Map<ChunkKey, WorldItemPageDescriptor> intended = new LinkedHashMap<>();
            checked.intendedCheckpoint().pages().forEach(value ->
                    intended.put(value.chunkKey(), value));
            if (!candidate.keySet().equals(intended.keySet())) {
                throw new IllegalStateException("soak candidate/checkpoint key mismatch");
            }
            for (Map.Entry<ChunkKey, WorldItemPageSnapshot> entry : candidate.entrySet()) {
                if (!descriptor(codec, entry.getValue()).equals(intended.get(entry.getKey()))) {
                    throw new IllegalStateException("soak candidate descriptor mismatch");
                }
            }
            pages = Map.copyOf(candidate);
            checkpoint = checked.intendedCheckpoint();
            checkpointDigest = checked.transactionDigest();
            return new BoundedProof(
                    proofScope, checkpoint.checkpointRevision(), checkpointDigest);
        }

        public com.overlord.worlditem.api.WorldItemPageReadView openReadView() {
            if (checkpoint == null) {
                throw new IllegalStateException("soak backend has no checkpoint");
            }
            WorldItemPagingCheckpoint capturedCheckpoint = checkpoint;
            Map<ChunkKey, WorldItemPageSnapshot> capturedPages = pages;
            String capturedDigest = checkpointDigest;
            return new com.overlord.worlditem.api.WorldItemPageReadView() {
                private boolean closed;

                @Override
                public long indexSequence() {
                    requireOpen();
                    return capturedCheckpoint.checkpointRevision();
                }

                @Override
                public String checkpointDigest() {
                    requireOpen();
                    return capturedDigest;
                }

                @Override
                public WorldItemPagingCheckpoint checkpoint() {
                    requireOpen();
                    return capturedCheckpoint;
                }

                @Override
                public WorldItemPageSnapshot read(WorldItemPageDescriptor requested) {
                    requireOpen();
                    if (!capturedCheckpoint.pages().contains(requested)) {
                        throw new IllegalArgumentException("descriptor is not in soak view");
                    }
                    WorldItemPageSnapshot page = capturedPages.get(requested.chunkKey());
                    if (page == null || !descriptor(codec, page).equals(requested)) {
                        throw new IllegalStateException("soak page proof mismatch");
                    }
                    return page;
                }

                @Override
                public void close() {
                    closed = true;
                }

                private void requireOpen() {
                    if (closed) {
                        throw new IllegalStateException("soak view is closed");
                    }
                }
            };
        }

        private void verify(
                com.overlord.worlditem.api.WorldItemPersistenceTicket ticket,
                WorldItemPersistencePlan plan,
                WorldItemDurableProof proof) {
            Objects.requireNonNull(ticket, "ticket");
            if (!(proof instanceof BoundedProof checked)
                    || checked.scope() != proofScope
                    || checkpoint == null
                    || checked.revision() != checkpoint.checkpointRevision()
                    || !checked.digest().equals(checkpointDigest)
                    || !plan.intendedCheckpoint().equals(checkpoint)) {
                throw new IllegalArgumentException("foreign or stale soak durability proof");
            }
        }
    }

    private record BoundedProof(Object scope, long revision, String digest)
            implements WorldItemDurableProof {}

    public static void publishBaseChunk(Path root, ChunkKey key) {
        StreamedChunkStore store = new StreamedChunkStore(
                root,
                SAVE_ID,
                new StreamedChunkCodec(),
                new StreamedChunkIndexCodec(),
                new JdkSaveFileOperations());
        try (StreamedChunkStore.PinnedReadView current = store.openPinnedReadView()) {
            long revision = current.index().entry(key)
                    .map(StreamedChunkIndex.Entry::revision)
                    .orElse(0L);
            if (revision != 0L) {
                return;
            }
        }
        StreamedChunkPayload base = new StreamedChunkPayload(
                SAVE_ID,
                key,
                "v15",
                BASE_HASH,
                1L,
                0L,
                true,
                true,
                1,
                new byte[16 * 16],
                List.of());
        StreamedChunkStore.CommitResult result = store.commitTransaction(
                new StreamedPersistenceTransaction(
                        List.of(new StreamedChunkMutation.Upsert(
                                new StreamedChunkStore.ExactChunkCapture(
                                        base, () -> true))),
                        List.of(),
                        () -> true));
        assertEquals(StreamedChunkStore.CommitResult.Status.SUCCESS, result.status());
    }

    public static StreamedWorldItemPageBackend backendWithInitialCapture(Path root) {
        StreamedChunkStore store = new StreamedChunkStore(
                root,
                SAVE_ID,
                new StreamedChunkCodec(),
                new StreamedChunkIndexCodec(),
                new JdkSaveFileOperations());
        return new StreamedWorldItemPageBackend(
                store,
                (save, page, pageBytes) -> new StreamedChunkStore.ExactChunkCapture(
                        payload(page.chunkKey(), page.pageRevision(), pageBytes),
                        () -> true));
    }

    public static PageData page(
            ChunkKey key, long revision, List<WorldItemRestoreEntry> entries) {
        return page(key, revision, entries, entries.size());
    }

    public static PageData page(
            ChunkKey key,
            long revision,
            List<WorldItemRestoreEntry> entries,
            int survivors) {
        WorldItemPageSnapshot page = new WorldItemPageSnapshot(key, revision, entries);
        WorldItemPageCodec codec = new WorldItemPageCodec();
        byte[] pageBytes = codec.encode(SAVE, page);
        WorldItemPageDescriptor descriptor = new WorldItemPageDescriptor(
                key,
                revision,
                HexFormat.of().formatHex(StreamedChunkCodec.sha256(pageBytes)),
                entries.size(),
                survivors);
        return new PageData(page, descriptor, payload(key, revision, pageBytes));
    }

    public static WorldItemRestoreEntry entry(
            ChunkKey key,
            long id,
            int count,
            long createdTick,
            long expiresAtWorldTick) {
        return new WorldItemRestoreEntry(
                new WorldItemRuntimeSnapshot(
                        new WorldItemSnapshot(
                                new WorldItemId(id),
                                new ItemStack(
                                        ResourceLocation.of("gaia", "test/acceptance"),
                                        count),
                                key.worldOriginX() + 0.5,
                                4.0,
                                key.worldOriginZ() + 0.5,
                                0.0,
                                0.0,
                                0.0,
                                1L),
                        Optional.empty(),
                        createdTick,
                        createdTick,
                        expiresAtWorldTick),
                WorldItemPhysicalState.FROZEN_UNLOADED);
    }

    public static WorldItemPagingCheckpoint checkpoint(
            long revision,
            long worldTick,
            long nextItemId,
            List<PageData> pages) {
        return new WorldItemPagingCheckpoint(
                SAVE,
                revision,
                worldTick,
                nextItemId,
                false,
                pages.stream()
                        .mapToInt(page -> page.descriptor()
                                .expectedLiveCountAtCheckpointTick())
                        .sum(),
                pages.stream().map(PageData::descriptor).toList());
    }

    public static void publish(
            Path root,
            WorldItemPagingCheckpoint checkpoint,
            List<PageData> pages) {
        StreamedChunkStore.CommitResult result = publish(
                root, checkpoint, pages, pages.size());
        assertEquals(
                StreamedChunkStore.CommitResult.Status.SUCCESS,
                result.status(),
                () -> result.diagnostics().toString());
    }

    public static StreamedChunkStore.CommitResult publish(
            Path root,
            WorldItemPagingCheckpoint checkpoint,
            List<PageData> pages,
            int dependencyCount) {
        StreamedGlobalExtension global = new StreamedGlobalExtension(
                SaveSectionId.WORLD_ITEM_CHECKPOINT,
                WorldItemPagingCheckpointCodec.CODEC_VERSION,
                true,
                Optional.of(new RequiredChunkExtensionDependency(
                        SaveSectionId.WORLD_ITEM_PAGE, dependencyCount)),
                new WorldItemPagingCheckpointCodec().encode(checkpoint));
        StreamedChunkStore store = new StreamedChunkStore(
                root,
                SAVE_ID,
                new StreamedChunkCodec(),
                new StreamedChunkIndexCodec(),
                new JdkSaveFileOperations());
        List<StreamedChunkMutation> mutations = new ArrayList<>();
        try (StreamedChunkStore.PinnedReadView current = store.openPinnedReadView()) {
            for (PageData page : pages) {
                StreamedChunkPayload source = page.payload();
                long persistedRevision = current.index().entry(source.key())
                        .map(StreamedChunkIndex.Entry::revision)
                        .orElse(0L);
                StreamedChunkPayload normalized = new StreamedChunkPayload(
                        source.saveGameId(),
                        source.key(),
                        source.generatorVersion(),
                        source.baseHash(),
                        Math.addExact(persistedRevision, 1L),
                        persistedRevision,
                        source.persistenceRequired(),
                        source.voxelModified(),
                        source.worldHeight(),
                        source.copyCanonicalVoxels(),
                        source.extensions());
                mutations.add(new StreamedChunkMutation.Upsert(
                        new StreamedChunkStore.ExactChunkCapture(
                                normalized, () -> true)));
            }
        }
        return store.commitTransaction(new StreamedPersistenceTransaction(
                mutations,
                List.of(new StreamedGlobalExtensionMutation.Upsert(global)),
                () -> true));
    }

    public static List<WorldItemId> activateAll(
            LogicalWorldItemService service,
            StreamedWorldItemPageBackend backend,
            Comparator<ChunkKey> order) {
        List<WorldItemPageDescriptor> descriptors;
        try (var view = backend.openReadView()) {
            descriptors = view.checkpoint().pages().stream().sorted(
                    Comparator.comparing(
                            WorldItemPageDescriptor::chunkKey, order)).toList();
            for (WorldItemPageDescriptor descriptor : descriptors) {
                var prepared = service.prepareActivate(view, descriptor);
                assertEquals(
                        com.overlord.worlditem.api.WorldItemActivationResult.Status.PREPARED,
                        prepared.status());
                assertEquals(
                        com.overlord.worlditem.api.WorldItemActivationResult.Status.COMMITTED,
                        service.commitActivate(prepared.ticket().orElseThrow()).status());
            }
        }
        return service.snapshots().stream().map(WorldItemSnapshot::id).toList();
    }

    private static StreamedChunkPayload payload(
            ChunkKey key, long pageRevision, byte[] pageBytes) {
        return new StreamedChunkPayload(
                SAVE_ID,
                key,
                "v15",
                BASE_HASH,
                pageRevision,
                pageRevision - 1L,
                true,
                false,
                1,
                new byte[16 * 16],
                List.of(new StreamedChunkPayload.ExtensionDescriptor(
                        SaveSectionId.WORLD_ITEM_PAGE,
                        WorldItemPageCodec.CODEC_VERSION,
                        true,
                        pageBytes)));
    }

    private static WorldItemPageDescriptor descriptor(
            WorldItemPageCodec codec, WorldItemPageSnapshot page) {
        byte[] bytes = codec.encode(SAVE, page);
        return new WorldItemPageDescriptor(
                page.chunkKey(),
                page.pageRevision(),
                HexFormat.of().formatHex(StreamedChunkCodec.sha256(bytes)),
                page.entries().size(),
                page.entries().size());
    }

    public record PageData(
            WorldItemPageSnapshot page,
            WorldItemPageDescriptor descriptor,
            StreamedChunkPayload payload) {}
}

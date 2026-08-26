package com.gaia.save.streaming;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gaia.save.format.SaveGameId;
import com.gaia.save.format.SaveSectionId;
import com.gaia.save.store.JdkSaveFileOperations;
import com.gaia.save.codec.SaveCodecException;
import com.gaia.save.codec.WorldItemsSectionCodec;
import com.gaia.save.snapshot.WorldItemsSaveSnapshot;
import com.gaia.save.snapshot.SaveGameSnapshot;
import com.gaia.save.snapshot.PlayerSaveSnapshot;
import com.gaia.save.snapshot.InventorySaveSnapshot;
import com.gaia.save.format.SaveFormatVersion;
import com.gaia.interaction.GameMode;
import com.gaia.session.GameSessionPersistenceTestFixture;
import com.overlord.interaction.api.EntityRef;
import com.overlord.inventory.api.BodySlot;
import com.overlord.voxel.ChunkRepositorySnapshot;
import com.overlord.voxel.ChunkSnapshot;
import java.time.Instant;
import com.overlord.assets.ResourceLocation;
import com.overlord.core.thread.MainThreadGuard;
import com.overlord.inventory.api.ItemStack;
import com.overlord.voxel.ChunkCoordinatePolicy;
import com.overlord.voxel.ChunkKey;
import com.overlord.worlditem.LogicalWorldItemService;
import com.overlord.worlditem.api.LogicalWorldItemSnapshot;
import com.overlord.worlditem.api.SaveIdentity;
import com.overlord.worlditem.api.WorldItemId;
import com.overlord.worlditem.api.WorldItemPageCachePolicy;
import com.overlord.worlditem.api.WorldItemPageDescriptor;
import com.overlord.worlditem.api.WorldItemPageSnapshot;
import com.overlord.worlditem.api.WorldItemPagingCheckpoint;
import com.overlord.worlditem.api.WorldItemPhysicalState;
import com.overlord.worlditem.api.WorldItemRestoreEntry;
import com.overlord.worlditem.api.WorldItemRestoreResult;
import com.overlord.worlditem.api.WorldItemRuntimeSnapshot;
import com.overlord.worlditem.api.WorldItemSnapshot;
import com.overlord.worlditem.api.WorldItemDurableProof;
import com.overlord.worlditem.api.WorldItemPageMutation;
import com.overlord.worlditem.api.WorldItemPageReadView;
import com.overlord.worlditem.api.WorldItemPersistencePlan;
import com.overlord.worlditem.api.WorldItemSpawnRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorldItemPagingRestartTest {
    private static final SaveGameId SAVE_ID =
            SaveGameId.parse("123e4567-e89b-12d3-a456-426614174000");
    private static final SaveIdentity SAVE =
            new SaveIdentity(UUID.fromString(SAVE_ID.value()));
    private static final String BASE_HASH = "11".repeat(32);
    private static final long SAVE_TICK = 110_800L;
    private static final long EXPIRES_AT = 118_000L;
    private static final ChunkKey NEGATIVE = new ChunkKey(-17, -3);
    private static final ChunkKey POSITIVE = new ChunkKey(2, 9);
    private static final ChunkKey THIRD = new ChunkKey(-1, 12);

    @TempDir Path tempDirectory;

    @Test
    void legalOneThousandTwentyFourOwnerCheckpointPublishesThroughBoundedStaging()
            throws Exception {
        Path root = Files.createDirectory(tempDirectory.resolve("1024-owner-staging"));
        List<PageData> pages = new ArrayList<>(1_024);
        List<WorldItemPageMutation> mutations = new ArrayList<>(1_024);
        for (int index = 0; index < 1_024; index++) {
            ChunkKey key = new ChunkKey(-11, index - 512);
            PageData page = page(
                    key,
                    1L,
                    entryForChunk(key, index + 1L));
            pages.add(page);
            mutations.add(new WorldItemPageMutation.Upsert(
                    page.page(), Optional.empty()));
        }
        WorldItemPagingCheckpoint intended = checkpoint(
                1L, 100L, 1_025L, pages);
        WorldItemPersistencePlan plan = new WorldItemPersistencePlan(
                0L,
                intended,
                mutations,
                "ab".repeat(32),
                () -> true);

        StreamedWorldItemPageBackend backend = backendWithInitialCapture(root);
        StreamedWorldItemPageBackend.AtomicPersistenceResult persisted =
                backend.persistAtomically(
                        plan,
                        binding -> List.of(
                                new StreamedGlobalExtensionMutation.Upsert(
                                        new StreamedGlobalExtension(
                                                SaveSectionId
                                                        .STREAMED_SESSION_CHECKPOINT,
                                                1,
                                                true,
                                                Optional.empty(),
                                                ("session-at-"
                                                                + binding
                                                                        .intendedIndexSequence())
                                                        .getBytes(java.nio.charset
                                                                .StandardCharsets.UTF_8)))));

        assertTrue(persisted.proof() != null);
        assertEquals(1_024, persisted.stagingMetrics().stagedMutations());
        assertTrue(persisted.stagingMetrics().maximumBatchMutations() <= 32);
        assertTrue(persisted.stagingMetrics().maximumBatchPhysicalBlobs() <= 64);
        assertTrue(persisted.stagingMetrics().maximumBatchBytes()
                <= StreamedPersistenceTransaction.MAX_CANDIDATE_BYTES);
        try (WorldItemPageReadView reopened = backend(root).openReadView()) {
            assertEquals(1_024, reopened.checkpoint().pages().size());
            assertEquals(1_024, reopened.checkpoint().totalLiveItemCount());
            assertEquals(1_025L, reopened.checkpoint().nextItemId());
            assertEquals(
                    persisted.binding().intendedIndexSequence(),
                    reopened.indexSequence());
        }
        StreamedChunkStore reopenedStore = new StreamedChunkStore(
                root,
                SAVE_ID,
                new StreamedChunkCodec(),
                new StreamedChunkIndexCodec(),
                new JdkSaveFileOperations());
        try (StreamedChunkStore.PinnedReadView rootView =
                reopenedStore.openPinnedReadView()) {
            assertTrue(rootView.index().globalExtension(
                    SaveSectionId.STREAMED_SESSION_CHECKPOINT).isPresent());
        }
    }

    @Test
    void processRestartRestoresTickAllocatorStableIdRemainingTtlAndPublishesOnce()
            throws Exception {
        Path root = Files.createDirectory(tempDirectory.resolve("process-restart"));
        PageData page = page(NEGATIVE, 1L, entry(500L, 2, 100_000L, EXPIRES_AT));
        publish(root, checkpoint(1L, SAVE_TICK, 501L, List.of(page)), List.of(page));
        List<StreamedWorldItemPageBackend.RestartStage> stages = new ArrayList<>();
        AtomicInteger publications = new AtomicInteger();

        StreamedWorldItemPageBackend relaunched = backend(root);
        LogicalWorldItemService fresh = service(relaunched);
        WorldItemRestoreResult restored = relaunched.restoreFresh(
                fresh,
                SAVE,
                SAVE_TICK,
                ChunkCoordinatePolicy.canonicalComparator(),
                stage -> {
                    stages.add(stage);
                    if (stage == StreamedWorldItemPageBackend.RestartStage.PUBLISHED) {
                        publications.incrementAndGet();
                    }
                });

        assertEquals(WorldItemRestoreResult.Status.RESTORED, restored.status());
        assertEquals(SAVE_TICK, fresh.currentWorldTick());
        assertEquals(
                LogicalWorldItemSnapshot.Completeness.PAGED_PARTIAL,
                fresh.canonicalSnapshot().completeness());
        assertEquals(List.of(new WorldItemId(500L)), fresh.liveMetadata().stream()
                .map(row -> row.id()).toList());
        assertEquals(List.of(
                        StreamedWorldItemPageBackend.RestartStage.IDENTITY_VALIDATED,
                        StreamedWorldItemPageBackend.RestartStage.WORLD_TICK_VALIDATED,
                        StreamedWorldItemPageBackend.RestartStage.ALLOCATOR_VALIDATED,
                        StreamedWorldItemPageBackend.RestartStage.PAGES_VALIDATED,
                        StreamedWorldItemPageBackend.RestartStage.PUBLISHED),
                stages);
        assertEquals(1, publications.get());

        var spawned = fresh.spawn(new WorldItemSpawnRequest(
                new ItemStack(ResourceLocation.of("gaia", "test/new"), 1),
                0.5, 4.0, 0.5, 0.0, 0.0, 0.0,
                Optional.empty(), SAVE_TICK)).item().orElseThrow();
        assertEquals(501L, spawned.id().value(),
                "restored allocator high-water must precede all new allocation");
        assertTrue(fresh.deliverWorldTick(EXPIRES_AT - 1L).isEmpty());
        assertEquals(List.of(new WorldItemId(500L)),
                fresh.deliverWorldTick(EXPIRES_AT));
        assertFalse(fresh.liveMetadata().stream()
                .anyMatch(row -> row.id().equals(new WorldItemId(500L))));
        assertEquals(
                WorldItemRestoreResult.Status.TARGET_NOT_FRESH,
                relaunched.restoreFresh(fresh, SAVE, EXPIRES_AT).status());
    }

    @Test
    void atomicSessionRejectsTickOrAllocatorMismatchBeforePublication() {
        SaveGameSnapshot snapshot = pagedSessionSnapshot(501L);
        WorldItemPagingCheckpoint matching = checkpoint(
                1L, SAVE_TICK, 501L, List.of());
        StreamedSessionSaveTarget.requireAtomicWorldItemBinding(snapshot, matching);

        assertThrows(IllegalStateException.class, () ->
                StreamedSessionSaveTarget.requireAtomicWorldItemBinding(
                        snapshot,
                        checkpoint(1L, SAVE_TICK - 1L, 501L, List.of())));
        assertThrows(IllegalStateException.class, () ->
                StreamedSessionSaveTarget.requireAtomicWorldItemBinding(
                        snapshot,
                        checkpoint(1L, SAVE_TICK, 500L, List.of())));
        assertThrows(IllegalStateException.class, () ->
                StreamedSessionSaveTarget.requireAtomicWorldItemBinding(
                        snapshot,
                        new WorldItemPagingCheckpoint(
                                SAVE,
                                1L,
                                SAVE_TICK,
                                Long.MAX_VALUE,
                                true,
                                0,
                                List.of())));
    }

    @Test
    void realProductionFactoryUsesPagedRestoreAndKeepsMetadataAuthority() throws Exception {
        Path root = Files.createDirectory(tempDirectory.resolve("real-production-factory"));
        PageData page = page(NEGATIVE, 1L, entry(500L, 2, 100_000L, EXPIRES_AT));
        publish(root, checkpoint(1L, SAVE_TICK, 501L, List.of(page)), List.of(page));
        StreamedWorldItemPageBackend backend = backend(root);

        var production = GameSessionPersistenceTestFixture
                .restoreActualProductionSession(
                        pagedSessionSnapshot(501L), backend);
        production.driveToReady();
        SaveGameSnapshot captured = production.captureSave()
                .snapshot().orElseThrow();

        assertEquals(LogicalWorldItemSnapshot.Completeness.PAGED_PARTIAL,
                captured.worldItems().completeness());
        assertEquals(501L, captured.worldItems().nextItemId());
        assertEquals(1, production.worldItemLiveMetadataCount());
        assertEquals(SAVE_TICK, captured.fixedTick());
        production.close();
    }

    @Test
    void worldTickAdvanceWithoutItemMutationStillRequiresCheckpointPersistence()
            throws Exception {
        Path root = Files.createDirectory(tempDirectory.resolve("tick-only-checkpoint"));
        PageData page = page(NEGATIVE, 1L, entry(500L, 2, 100_000L, EXPIRES_AT));
        publish(root, checkpoint(1L, SAVE_TICK, 501L, List.of(page)), List.of(page));
        StreamedWorldItemPageBackend backend = backend(root);
        LogicalWorldItemService restored = service(backend);
        assertEquals(WorldItemRestoreResult.Status.RESTORED,
                backend.restoreFresh(restored, SAVE, SAVE_TICK).status());
        assertTrue(restored.savePersistenceReady());

        restored.deliverWorldTick(SAVE_TICK + 1L);

        assertFalse(restored.savePersistenceReady(),
                "the durable checkpoint must advance with the authoritative world tick");
        var prepared = restored.prepareSavePersistence();
        var plan = prepared.persistencePlan().orElseThrow();
        assertTrue(plan.pageMutations().isEmpty(),
                "a tick-only save must not rewrite unchanged WorldItem pages");
        assertEquals(1L, plan.expectedCheckpointRevision());
        assertEquals(2L, plan.intendedCheckpoint().checkpointRevision());
        assertEquals(SAVE_TICK + 1L, plan.intendedCheckpoint().worldTick());
        restored.deliverWorldTick(SAVE_TICK + 2L);
        assertFalse(plan.stillCurrent().getAsBoolean(),
                "a prepared checkpoint cannot publish an older authoritative tick");
        restored.cancelPersistence(prepared.persistenceTicket().orElseThrow());
    }

    @Test
    void crashBetweenIndexSlotsExposesOnlyOldOrNewCombinedPageAndSessionRoot()
            throws Exception {
        Path root = Files.createDirectory(tempDirectory.resolve("atomic-session-root"));
        PageData oldPage = page(NEGATIVE, 1L, entry(40L, 1, 100L, 18_100L));
        publish(root, checkpoint(1L, 1_000L, 41L, List.of(oldPage)), List.of(oldPage));

        PageData newPage = page(NEGATIVE, 2L, entry(40L, 2, 100L, 18_100L));
        WorldItemPagingCheckpoint intended = checkpoint(
                2L, 1_001L, 41L, List.of(newPage));
        WorldItemPersistencePlan plan = new WorldItemPersistencePlan(
                1L,
                intended,
                List.of(new WorldItemPageMutation.Upsert(
                        newPage.page(), Optional.of(oldPage.descriptor()))),
                "33".repeat(32),
                () -> true);
        ProtocolFileOperations crashing = new ProtocolFileOperations(
                new JdkSaveFileOperations());
        crashing.before(ProtocolStage.WRITE_MAIN, ignored -> {
            throw new java.io.IOException("simulated process kill before main index");
        });
        StreamedWorldItemPageBackend backend = new StreamedWorldItemPageBackend(
                new StreamedChunkStore(
                        root,
                        SAVE_ID,
                        new StreamedChunkCodec(),
                        new StreamedChunkIndexCodec(),
                        crashing));

        assertThrows(IllegalStateException.class, () -> backend.persistAtomically(
                plan,
                binding -> List.of(new StreamedGlobalExtensionMutation.Upsert(
                        new StreamedGlobalExtension(
                                SaveSectionId.STREAMED_SESSION_CHECKPOINT,
                                1,
                                true,
                                Optional.empty(),
                                ("session-for-" + binding.checkpointRevision()).getBytes(
                                        java.nio.charset.StandardCharsets.UTF_8))))));

        StreamedChunkStore relaunchedStore = new StreamedChunkStore(
                root,
                SAVE_ID,
                new StreamedChunkCodec(),
                new StreamedChunkIndexCodec(),
                new JdkSaveFileOperations());
        StreamedWorldItemPageBackend relaunched =
                new StreamedWorldItemPageBackend(relaunchedStore);
        try (WorldItemPageReadView view = relaunched.openReadView();
                StreamedChunkStore.PinnedReadView index =
                        relaunchedStore.openPinnedReadView()) {
            boolean newRoot = view.checkpoint().checkpointRevision() == 2L;
            assertEquals(newRoot, index.index().globalExtension(
                    SaveSectionId.STREAMED_SESSION_CHECKPOINT).isPresent());
            assertTrue(view.checkpoint().checkpointRevision() == 1L || newRoot);
        }
    }

    @Test
    void dirtyFreshnessInvalidatedAfterPageStagingPreventsCombinedRootPublication()
            throws Exception {
        Path root = Files.createDirectory(
                tempDirectory.resolve("dirty-freshness-combined-root"));
        PageData oldPage = page(NEGATIVE, 1L, entry(40L, 1, 100L, 18_100L));
        publish(root, checkpoint(1L, 1_000L, 41L, List.of(oldPage)), List.of(oldPage));

        PageData newPage = page(NEGATIVE, 2L, entry(40L, 2, 100L, 18_100L));
        WorldItemPagingCheckpoint intended = checkpoint(
                2L, 1_001L, 41L, List.of(newPage));
        WorldItemPersistencePlan plan = new WorldItemPersistencePlan(
                1L,
                intended,
                List.of(new WorldItemPageMutation.Upsert(
                        newPage.page(), Optional.of(oldPage.descriptor()))),
                "34".repeat(32),
                () -> true);
        AtomicInteger freshnessChecks = new AtomicInteger();

        assertThrows(IllegalStateException.class, () -> backend(root).persistAtomically(
                plan,
                binding -> List.of(new StreamedGlobalExtensionMutation.Upsert(
                        new StreamedGlobalExtension(
                                SaveSectionId.STREAMED_SESSION_CHECKPOINT,
                                1,
                                true,
                                Optional.empty(),
                                ("session-for-" + binding.checkpointRevision()).getBytes(
                                        java.nio.charset.StandardCharsets.UTF_8)))),
                List.of(),
                () -> freshnessChecks.incrementAndGet() == 1));

        assertTrue(freshnessChecks.get() >= 2,
                "freshness must turn stale after staging and before publication");
        try (WorldItemPageReadView reopened = backend(root).openReadView()) {
            assertEquals(1L, reopened.checkpoint().checkpointRevision());
            assertEquals(oldPage.descriptor(), reopened.checkpoint().pages().get(0));
        }
    }

    @Test
    void arbitraryPageTraversalOrderProducesIdenticalCanonicalStateAndAllocator()
            throws Exception {
        Path root = Files.createDirectory(tempDirectory.resolve("order"));
        PageData negative = page(NEGATIVE, 4L, entry(40L, 3, 100L, 18_100L));
        PageData positive = page(POSITIVE, 7L, entry(70L, 5, 200L, 18_200L));
        PageData third = page(THIRD, 9L, entry(90L, 7, 300L, 18_300L));
        publish(root, checkpoint(
                        8L, 1_000L, 91L, List.of(negative, positive, third)),
                List.of(negative, positive, third));
        StreamedWorldItemPageBackend backend = backend(root);
        LogicalWorldItemService forward = service(backend);
        LogicalWorldItemService reverse = service(backend);
        LogicalWorldItemService shuffled = service(backend);

        assertEquals(WorldItemRestoreResult.Status.RESTORED,
                backend.restoreFresh(
                        forward, SAVE, 1_000L,
                        ChunkCoordinatePolicy.canonicalComparator(), ignored -> {}).status());
        assertEquals(WorldItemRestoreResult.Status.RESTORED,
                backend.restoreFresh(
                        reverse, SAVE, 1_000L,
                        ChunkCoordinatePolicy.canonicalComparator().reversed(), ignored -> {})
                        .status());
        Comparator<ChunkKey> shuffledOrder = Comparator.comparingInt(key -> {
            if (key.equals(POSITIVE)) {
                return 0;
            }
            return key.equals(NEGATIVE) ? 1 : 2;
        });
        assertEquals(WorldItemRestoreResult.Status.RESTORED,
                backend.restoreFresh(
                        shuffled, SAVE, 1_000L, shuffledOrder, ignored -> {}).status());

        assertEquals(forward.liveMetadata(), reverse.liveMetadata());
        assertEquals(forward.liveMetadata(), shuffled.liveMetadata());
        assertEquals(91L, forward.canonicalSnapshot().nextItemId());
        assertEquals(forward.canonicalSnapshot().nextItemId(),
                reverse.canonicalSnapshot().nextItemId());
        assertEquals(Set.of(NEGATIVE, POSITIVE, THIRD), forward.liveMetadata().stream()
                .map(row -> row.intendedChunkKey()).collect(java.util.stream.Collectors.toSet()));
    }

    @Test
    void restoreUsesOnePinnedGenerationEvenWhenANewerIndexPublishesMidValidation()
            throws Exception {
        Path root = Files.createDirectory(tempDirectory.resolve("pinned-generation"));
        PageData oldPage = page(NEGATIVE, 1L, entry(7L, 1, 0L, 18_000L));
        publish(root, checkpoint(1L, 100L, 8L, List.of(oldPage)), List.of(oldPage));
        StreamedWorldItemPageBackend oldBackend = backend(root);
        LogicalWorldItemService oldTarget = service(oldBackend);
        AtomicInteger publications = new AtomicInteger();

        WorldItemRestoreResult restored = oldBackend.restoreFresh(
                oldTarget,
                SAVE,
                100L,
                ChunkCoordinatePolicy.canonicalComparator(),
                stage -> {
                    if (stage
                            == StreamedWorldItemPageBackend.RestartStage.ALLOCATOR_VALIDATED
                            && publications.getAndIncrement() == 0) {
                        PageData newer = page(
                                NEGATIVE, 2L, entry(8L, 4, 0L, 18_000L));
                        publish(root, checkpoint(
                                        2L, 100L, 9L, List.of(newer)),
                                List.of(newer));
                    }
                });

        assertEquals(WorldItemRestoreResult.Status.RESTORED, restored.status());
        assertEquals(List.of(new WorldItemId(7L)), oldTarget.liveMetadata().stream()
                .map(row -> row.id()).toList(),
                "one restore may not mix the newer index into its pinned view");
        assertEquals(8L, oldTarget.canonicalSnapshot().nextItemId());

        StreamedWorldItemPageBackend newBackend = backend(root);
        LogicalWorldItemService newTarget = service(newBackend);
        assertEquals(WorldItemRestoreResult.Status.RESTORED,
                newBackend.restoreFresh(newTarget, SAVE, 100L).status());
        assertEquals(List.of(new WorldItemId(8L)), newTarget.liveMetadata().stream()
                .map(row -> row.id()).toList());
        assertEquals(9L, newTarget.canonicalSnapshot().nextItemId());
    }

    @Test
    void duplicateStableIdsAcrossPagesFailClosedInBothOrdersBeforePublication()
            throws Exception {
        Path root = Files.createDirectory(tempDirectory.resolve("duplicate"));
        PageData first = page(NEGATIVE, 1L, entry(7L, 1, 0L, 18_000L));
        PageData second = page(POSITIVE, 1L, entry(7L, 2, 0L, 18_000L));
        publish(root, checkpoint(1L, 100L, 8L, List.of(first, second)),
                List.of(first, second));
        StreamedWorldItemPageBackend backend = backend(root);
        for (Comparator<ChunkKey> order : List.of(
                ChunkCoordinatePolicy.canonicalComparator(),
                ChunkCoordinatePolicy.canonicalComparator().reversed())) {
            LogicalWorldItemService target = service(backend);
            assertThrows(IllegalStateException.class,
                    () -> backend.restoreFresh(target, SAVE, 100L, order, ignored -> {}));
            assertTrue(target.liveMetadata().isEmpty());
            assertTrue(target.canonicalSnapshot().entries().isEmpty());
        }
    }

    @Test
    void descriptorCountHashRevisionDependencyAndManifestTickMismatchFailBeforePublish()
            throws Exception {
        PageData valid = page(
                NEGATIVE, 3L, entry(9L, 1, 100_000L, 118_000L));
        List<Attack> attacks = List.of(
                new Attack("raw-count", descriptor(valid, 2, 1), 1, SAVE_TICK),
                new Attack("survivor-count", descriptor(valid, 1, 0), 1, SAVE_TICK),
                new Attack("hash", new WorldItemPageDescriptor(
                        NEGATIVE, 3L, "ff".repeat(32), 1, 1), 1, SAVE_TICK),
                new Attack("revision", new WorldItemPageDescriptor(
                        NEGATIVE, 4L, valid.descriptor().pageHash(), 1, 1), 1, SAVE_TICK),
                new Attack("dependency", valid.descriptor(), 0, SAVE_TICK),
                new Attack("world-tick", valid.descriptor(), 1, SAVE_TICK + 1L));

        for (Attack attack : attacks) {
            Path root = Files.createDirectory(tempDirectory.resolve(attack.name()));
            WorldItemPagingCheckpoint checkpoint = new WorldItemPagingCheckpoint(
                    SAVE, 1L, SAVE_TICK, 10L, false,
                    attack.descriptor().expectedLiveCountAtCheckpointTick(),
                    List.of(attack.descriptor()));
            StreamedChunkStore.CommitResult malformed = publish(
                    root, checkpoint, List.of(valid), attack.dependencyCount());
            StreamedWorldItemPageBackend backend = backend(root);
            LogicalWorldItemService target = service(backend);
            if (malformed.status() == StreamedChunkStore.CommitResult.Status.SUCCESS) {
                assertThrows(IllegalStateException.class,
                        () -> backend.restoreFresh(
                                target, SAVE, attack.manifestTick()));
            }
            assertTrue(target.liveMetadata().isEmpty(), attack.name());
        }
    }

    @Test
    void wrongExpectedSaveIdentityFailsBeforePublicationAndDoesNotPoisonRetry()
            throws Exception {
        Path root = Files.createDirectory(tempDirectory.resolve("wrong-save-identity"));
        PageData valid = page(
                NEGATIVE, 3L, entry(9L, 1, 100_000L, 118_000L));
        publish(root, checkpoint(1L, SAVE_TICK, 10L, List.of(valid)), List.of(valid));
        StreamedWorldItemPageBackend backend = backend(root);
        LogicalWorldItemService target = service(backend);
        SaveIdentity wrong = new SaveIdentity(UUID.fromString(
                "223e4567-e89b-12d3-a456-426614174000"));

        assertThrows(IllegalStateException.class,
                () -> backend.restoreFresh(target, wrong, SAVE_TICK));
        assertTrue(target.liveMetadata().isEmpty());
        assertTrue(target.canonicalSnapshot().entries().isEmpty());

        assertEquals(WorldItemRestoreResult.Status.RESTORED,
                backend.restoreFresh(target, SAVE, SAVE_TICK).status());
        assertEquals(List.of(new WorldItemId(9L)), target.liveMetadata().stream()
                .map(row -> row.id()).toList());
    }

    @Test
    void dueEntryIsPrunedOnRestartButItsAllocatorRangeIsNeverReused()
            throws Exception {
        Path root = Files.createDirectory(tempDirectory.resolve("due"));
        PageData due = page(NEGATIVE, 1L, entry(900L, 1, 0L, 18_000L), 0);
        publish(root, checkpoint(1L, 18_000L, 901L, List.of(due)), List.of(due));
        StreamedWorldItemPageBackend backend = backend(root);
        LogicalWorldItemService target = service(backend);

        assertEquals(WorldItemRestoreResult.Status.RESTORED,
                backend.restoreFresh(target, SAVE, 18_000L).status());
        assertTrue(target.liveMetadata().isEmpty());
        assertEquals(0, target.pagingMetrics().decodedPageCount(),
                "restore must not cache raw expired DTO history");
        var next = target.spawn(new WorldItemSpawnRequest(
                new ItemStack(ResourceLocation.of("gaia", "test/drop"), 1),
                0.5, 4.0, 0.5, 0.0, 0.0, 0.0,
                Optional.empty(), 18_000L)).item().orElseThrow();
        assertEquals(901L, next.id().value());
    }

    @Test
    void serviceOwnedCheckpointEvictionAndPreparedTicketCanNeverMasqueradeAsLegacyV1()
            throws Exception {
        Path root = Files.createDirectory(tempDirectory.resolve("service-completeness"));
        PageData page = page(NEGATIVE, 1L, entry(10L, 1, 0L, 18_000L));
        publish(root, checkpoint(1L, 100L, 11L, List.of(page)), List.of(page));
        StreamedWorldItemPageBackend backend = backend(root);
        LogicalWorldItemService restored = service(backend);
        assertEquals(WorldItemRestoreResult.Status.RESTORED,
                backend.restoreFresh(restored, SAVE, 100L).status());
        LogicalWorldItemSnapshot evicted = restored.canonicalSnapshot();
        assertEquals(LogicalWorldItemSnapshot.Completeness.PAGED_PARTIAL,
                evicted.completeness());
        SaveCodecException evictedFailure = assertThrows(
                SaveCodecException.class,
                () -> new WorldItemsSectionCodec().encode(
                        new WorldItemsSaveSnapshot(100L, evicted)));
        assertEquals("world-items-v1.paged-state-unsupported", evictedFailure.code());

        Path pendingRoot = Files.createDirectory(tempDirectory.resolve("prepared-ticket"));
        StreamedWorldItemPageBackend pendingBackend = backend(pendingRoot);
        LogicalWorldItemService pending = service(pendingBackend);
        pending.deliverWorldTick(100L);
        WorldItemSnapshot spawned = pending.spawn(new WorldItemSpawnRequest(
                new ItemStack(ResourceLocation.of("gaia", "test/pending"), 1),
                NEGATIVE.worldOriginX() + 0.5, 4.0,
                NEGATIVE.worldOriginZ() + 0.5,
                0.0, 0.0, 0.0, Optional.empty(), 100L))
                .item().orElseThrow();
        var prepared = pending.prepareHibernate(
                NEGATIVE, Map.of(spawned.id(), spawned.revision()));
        assertTrue(prepared.persistenceTicket().isPresent());
        LogicalWorldItemSnapshot pendingSnapshot = pending.canonicalSnapshot();
        assertEquals(LogicalWorldItemSnapshot.Completeness.PAGED_PARTIAL,
                pendingSnapshot.completeness());
        assertThrows(SaveCodecException.class, () ->
                new WorldItemsSectionCodec().encode(
                        new WorldItemsSaveSnapshot(100L, pendingSnapshot)));
        assertEquals(spawned, pending.snapshot(spawned.id()).orElseThrow(),
                "pre-proof state must remain resident and canonical");
    }

    @Test
    void saveAndQuitPersistsOneDirtyPlanAndRequiresBackendProofBeforeSafeClose()
            throws Exception {
        Path root = Files.createDirectory(tempDirectory.resolve("save-and-quit"));
        StreamedWorldItemPageBackend backend = backendWithInitialCapture(root);
        LogicalWorldItemService service = service(backend);
        service.deliverWorldTick(SAVE_TICK);
        WorldItemSnapshot first = service.spawn(new WorldItemSpawnRequest(
                new ItemStack(ResourceLocation.of("gaia", "test/first"), 2),
                NEGATIVE.worldOriginX() + 0.5, 4.0,
                NEGATIVE.worldOriginZ() + 0.5,
                0.0, 0.0, 0.0, Optional.empty(), SAVE_TICK))
                .item().orElseThrow();
        WorldItemSnapshot second = service.spawn(new WorldItemSpawnRequest(
                new ItemStack(ResourceLocation.of("gaia", "test/second"), 3),
                POSITIVE.worldOriginX() + 0.5, 4.0,
                POSITIVE.worldOriginZ() + 0.5,
                0.0, 0.0, 0.0, Optional.empty(), SAVE_TICK))
                .item().orElseThrow();

        var prepared = service.prepareSavePersistence();
        var plan = prepared.persistencePlan().orElseThrow();
        var ticket = prepared.persistenceTicket().orElseThrow();
        assertEquals(2, plan.pageMutations().size());
        assertEquals(2, plan.intendedCheckpoint().totalLiveItemCount());
        assertEquals(SAVE_TICK, plan.intendedCheckpoint().worldTick());
        assertThrows(IllegalStateException.class,
                () -> service.commitPersistence(ticket, new CallerProof()));
        assertEquals(first, service.snapshot(first.id()).orElseThrow());
        assertEquals(second, service.snapshot(second.id()).orElseThrow());
        assertFalse(service.savePersistenceReady(),
                "save/close is blocked until backend durability is proven");

        var proof = backend.persist(plan);
        service.commitPersistence(ticket, proof);
        assertTrue(service.savePersistenceReady());
        assertEquals(first, service.snapshot(first.id()).orElseThrow(),
                "a normal save must not evict active gameplay state");
        assertEquals(second, service.snapshot(second.id()).orElseThrow());

        StreamedWorldItemPageBackend relaunched = backend(root);
        LogicalWorldItemService fresh = service(relaunched);
        assertEquals(WorldItemRestoreResult.Status.RESTORED,
                relaunched.restoreFresh(fresh, SAVE, SAVE_TICK).status());
        assertEquals(Set.of(first.id(), second.id()), fresh.liveMetadata().stream()
                .map(row -> row.id()).collect(java.util.stream.Collectors.toSet()));
    }

    @Test
    void oneSavePlanCombinesDirtyActiveStateWithExpiryCleanupFromDurablePage()
            throws Exception {
        Path root = Files.createDirectory(tempDirectory.resolve("save-dirty-and-expiry"));
        PageData expiring = page(
                NEGATIVE, 1L, entry(20L, 1, 0L, 1_001L));
        publish(root, checkpoint(1L, 1_000L, 21L, List.of(expiring)),
                List.of(expiring));
        StreamedWorldItemPageBackend backend = backendWithInitialCapture(root);
        LogicalWorldItemService service = service(backend);
        assertEquals(WorldItemRestoreResult.Status.RESTORED,
                backend.restoreFresh(service, SAVE, 1_000L).status());

        assertEquals(List.of(new WorldItemId(20L)), service.deliverWorldTick(1_001L));
        WorldItemSnapshot dirty = service.spawn(new WorldItemSpawnRequest(
                new ItemStack(ResourceLocation.of("gaia", "test/dirty"), 4),
                POSITIVE.worldOriginX() + 0.5, 4.0,
                POSITIVE.worldOriginZ() + 0.5,
                0.0, 0.0, 0.0, Optional.empty(), 1_001L))
                .item().orElseThrow();

        var prepared = service.prepareSavePersistence();
        var plan = prepared.persistencePlan().orElseThrow();
        assertEquals(2, plan.pageMutations().size());
        assertEquals(Set.of(NEGATIVE, POSITIVE), plan.pageMutations().stream()
                .map(mutation -> mutation instanceof
                                com.overlord.worlditem.api.WorldItemPageMutation.Upsert upsert
                        ? upsert.page().chunkKey()
                        : ((com.overlord.worlditem.api.WorldItemPageMutation.Remove) mutation)
                                .expected().chunkKey())
                .collect(java.util.stream.Collectors.toSet()));
        assertEquals(1_001L, plan.intendedCheckpoint().worldTick());
        assertEquals(1, plan.intendedCheckpoint().totalLiveItemCount());
        assertEquals(List.of(dirty.id()), service.liveMetadata().stream()
                .map(row -> row.id()).toList());

        service.commitPersistence(
                prepared.persistenceTicket().orElseThrow(), backend.persist(plan));
        try (var view = backend.openReadView()) {
            assertEquals(1, view.checkpoint().totalLiveItemCount());
            assertEquals(List.of(dirty.id()), view.checkpoint().pages().stream()
                    .map(view::read)
                    .flatMap(page -> page.entries().stream())
                    .filter(entry -> entry.runtime().expiresAtWorldTick()
                            > view.checkpoint().worldTick())
                    .map(entry -> entry.runtime().item().id())
                    .toList());
        }
    }

    @Test
    void proofMayCrossBackendInstancesOnlyWithinTheSameAnchoredSaveRoot()
            throws Exception {
        Path anchoredRoot = Files.createDirectory(tempDirectory.resolve("proof-root-a"));
        Path foreignRoot = Files.createDirectory(tempDirectory.resolve("proof-root-b"));
        StreamedWorldItemPageBackend verifierBackend =
                backendWithInitialCapture(anchoredRoot);
        LogicalWorldItemService service = service(verifierBackend);
        service.deliverWorldTick(100L);
        WorldItemSnapshot item = service.spawn(new WorldItemSpawnRequest(
                new ItemStack(ResourceLocation.of("gaia", "test/root-proof"), 1),
                NEGATIVE.worldOriginX() + 0.5, 4.0,
                NEGATIVE.worldOriginZ() + 0.5,
                0.0, 0.0, 0.0, Optional.empty(), 100L))
                .item().orElseThrow();
        var prepared = service.prepareSavePersistence();
        var plan = prepared.persistencePlan().orElseThrow();
        var ticket = prepared.persistenceTicket().orElseThrow();

        StreamedWorldItemPageBackend foreignBackend =
                backendWithInitialCapture(foreignRoot);
        WorldItemDurableProof foreignProof = foreignBackend.persist(plan);
        assertThrows(IllegalStateException.class,
                () -> service.commitPersistence(ticket, foreignProof));
        assertEquals(item, service.snapshot(item.id()).orElseThrow());
        assertFalse(service.savePersistenceReady());

        StreamedWorldItemPageBackend sameRootBackend =
                backendWithInitialCapture(anchoredRoot);
        WorldItemDurableProof sameRootProof = sameRootBackend.persist(plan);
        service.commitPersistence(ticket, sameRootProof);
        assertTrue(service.savePersistenceReady());
        assertEquals(item, service.snapshot(item.id()).orElseThrow());
        try (var view = sameRootBackend.openReadView()) {
            assertEquals(plan.intendedCheckpoint(), view.checkpoint());
        }
    }

    @Test
    void partialPickupRemainderSurvivesRestartWithSameIdAndCount() throws Exception {
        Path root = Files.createDirectory(tempDirectory.resolve("partial-pickup"));
        PageData page = page(NEGATIVE, 2L, entry(77L, 2, 100L, 18_100L));
        publish(root, checkpoint(2L, 1_000L, 78L, List.of(page)), List.of(page));
        StreamedWorldItemPageBackend backend = backend(root);
        LogicalWorldItemService target = service(backend);
        assertEquals(WorldItemRestoreResult.Status.RESTORED,
                backend.restoreFresh(target, SAVE, 1_000L).status());
        try (var view = backend.openReadView()) {
            var activation = target.prepareActivate(view, view.checkpoint().pages().get(0));
            assertEquals(
                    com.overlord.worlditem.api.WorldItemActivationResult.Status.PREPARED,
                    activation.status());
            target.commitActivate(activation.ticket().orElseThrow());
        }

        target.commit(target.reserve(new WorldItemId(77L), 1)
                .reservation().orElseThrow().id());
        assertEquals(77L, target.snapshot(new WorldItemId(77L)).orElseThrow().id().value());
        assertEquals(1, target.snapshot(new WorldItemId(77L)).orElseThrow().stack().count());

        WorldItemSnapshot remainder = target.snapshot(new WorldItemId(77L)).orElseThrow();
        var hibernate = target.prepareHibernate(
                NEGATIVE, Map.of(remainder.id(), remainder.revision()));
        var plan = hibernate.persistencePlan().orElseThrow();
        target.commitPersistence(
                hibernate.persistenceTicket().orElseThrow(), backend.persist(plan));

        StreamedWorldItemPageBackend relaunched = backend(root);
        LogicalWorldItemService restarted = service(relaunched);
        assertEquals(WorldItemRestoreResult.Status.RESTORED,
                relaunched.restoreFresh(restarted, SAVE, 1_000L).status());
        try (var view = relaunched.openReadView()) {
            var activation = restarted.prepareActivate(
                    view, view.checkpoint().pages().get(0));
            restarted.commitActivate(activation.ticket().orElseThrow());
        }
        assertEquals(77L,
                restarted.snapshot(new WorldItemId(77L)).orElseThrow().id().value());
        assertEquals(1,
                restarted.snapshot(new WorldItemId(77L)).orElseThrow().stack().count());
    }

    private static LogicalWorldItemService service(StreamedWorldItemPageBackend backend) {
        WorldItemPageCodec codec = new WorldItemPageCodec();
        return new LogicalWorldItemService(
                MainThreadGuard.captureCurrentThread(),
                1_024,
                0L,
                SAVE,
                new WorldItemPageCachePolicy(
                        1_024, 32, 16L * 1_024L * 1_024L,
                        64, 1_024, 16L * 1_024L * 1_024L,
                        64, 64L * 1_024L),
                backend.durabilityVerifier(),
                page -> descriptor(codec, page));
    }

    private static SaveGameSnapshot pagedSessionSnapshot(long nextItemId) {
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
                        "Paged production restore",
                        Instant.parse("2026-08-12T00:00:00Z"),
                        12345L,
                        "gaia-v2",
                        "22".repeat(32),
                        radius,
                        height,
                        Optional.empty()),
                SAVE_TICK,
                new ChunkRepositorySnapshot(height, 1L, chunks),
                new PlayerSaveSnapshot(
                        owner, 0.5, 8.0, 0.5,
                        0.0, 0.0, 0.0,
                        -90.0, 0.0, GameMode.SURVIVAL, false),
                new InventorySaveSnapshot(
                        owner, Map.of(), BodySlot.LEFT_HAND, false, 0L),
                new WorldItemsSaveSnapshot(
                        SAVE_TICK,
                        List.of(),
                        nextItemId,
                        false,
                        LogicalWorldItemSnapshot.Completeness.PAGED_PARTIAL));
    }

    private static StreamedWorldItemPageBackend backend(Path root) {
        return new StreamedWorldItemPageBackend(new StreamedChunkStore(
                root, SAVE_ID, new StreamedChunkCodec(),
                new StreamedChunkIndexCodec(), new JdkSaveFileOperations()));
    }

    private static StreamedWorldItemPageBackend backendWithInitialCapture(Path root) {
        StreamedChunkStore store = new StreamedChunkStore(
                root, SAVE_ID, new StreamedChunkCodec(),
                new StreamedChunkIndexCodec(), new JdkSaveFileOperations());
        return new StreamedWorldItemPageBackend(
                store,
                (save, page, pageBytes) -> new StreamedChunkStore.ExactChunkCapture(
                        new StreamedChunkPayload(
                                SAVE_ID,
                                page.chunkKey(),
                                "v15",
                                BASE_HASH,
                                page.pageRevision(),
                                0L,
                                true,
                                false,
                                1,
                                new byte[16 * 16],
                                List.of(new StreamedChunkPayload.ExtensionDescriptor(
                                        SaveSectionId.WORLD_ITEM_PAGE,
                                        WorldItemPageCodec.CODEC_VERSION,
                                        true,
                                        pageBytes))),
                        () -> true));
    }

    private static void publish(
            Path root,
            WorldItemPagingCheckpoint checkpoint,
            List<PageData> pages) {
        StreamedChunkStore.CommitResult committed =
                publish(root, checkpoint, pages, pages.size());
        assertEquals(
                StreamedChunkStore.CommitResult.Status.SUCCESS,
                committed.status(),
                () -> committed.diagnostics().stream()
                        .map(diagnostic -> diagnostic.code() + ":" + diagnostic.message())
                        .toList().toString());
    }

    private static StreamedChunkStore.CommitResult publish(
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
                root, SAVE_ID, new StreamedChunkCodec(),
                new StreamedChunkIndexCodec(), new JdkSaveFileOperations());
        List<StreamedChunkMutation> mutations = new ArrayList<>();
        try (StreamedChunkStore.PinnedReadView current = store.openPinnedReadView()) {
            for (PageData page : pages) {
                StreamedChunkPayload source = page.payload();
                long persistedRevision = current.index().entry(source.key())
                        .map(StreamedChunkIndex.Entry::revision).orElse(0L);
                StreamedChunkPayload normalized = new StreamedChunkPayload(
                        source.saveGameId(), source.key(), source.generatorVersion(),
                        source.baseHash(), Math.addExact(persistedRevision, 1L),
                        persistedRevision, source.persistenceRequired(), source.voxelModified(),
                        source.worldHeight(), source.copyCanonicalVoxels(),
                        source.extensions());
                mutations.add(new StreamedChunkMutation.Upsert(
                        new StreamedChunkStore.ExactChunkCapture(
                                normalized, () -> true)));
            }
        }
        StreamedPersistenceTransaction transaction = new StreamedPersistenceTransaction(
                mutations,
                List.of(new StreamedGlobalExtensionMutation.Upsert(global)),
                () -> true);
        return store.commitTransaction(transaction);
    }

    private static WorldItemPagingCheckpoint checkpoint(
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
                pages.stream().mapToInt(page ->
                        page.descriptor().expectedLiveCountAtCheckpointTick()).sum(),
                pages.stream().map(PageData::descriptor).toList());
    }

    private static PageData page(
            ChunkKey key,
            long revision,
            WorldItemRestoreEntry entry) {
        return page(key, revision, entry, 1);
    }

    private static PageData page(
            ChunkKey key,
            long revision,
            WorldItemRestoreEntry entry,
            int survivors) {
        WorldItemPageSnapshot page = new WorldItemPageSnapshot(
                key, revision, List.of(entry));
        WorldItemPageCodec codec = new WorldItemPageCodec();
        byte[] pageBytes = codec.encode(SAVE, page);
        WorldItemPageDescriptor descriptor = new WorldItemPageDescriptor(
                key,
                revision,
                HexFormat.of().formatHex(StreamedChunkCodec.sha256(pageBytes)),
                1,
                survivors);
        StreamedChunkPayload payload = new StreamedChunkPayload(
                SAVE_ID,
                key,
                "v15",
                BASE_HASH,
                revision,
                revision - 1L,
                true,
                false,
                1,
                new byte[16 * 16],
                List.of(new StreamedChunkPayload.ExtensionDescriptor(
                        SaveSectionId.WORLD_ITEM_PAGE,
                        WorldItemPageCodec.CODEC_VERSION,
                        true,
                        pageBytes)));
        return new PageData(page, descriptor, payload);
    }

    private static WorldItemRestoreEntry entry(
            long id,
            int count,
            long spawnTick,
            long expiresAtWorldTick) {
        return new WorldItemRestoreEntry(
                new WorldItemRuntimeSnapshot(
                        new WorldItemSnapshot(
                                new WorldItemId(id),
                                new ItemStack(ResourceLocation.of("gaia", "test/drop"), count),
                                -271.5, 4.0, -47.5,
                                0.0, 0.0, 0.0,
                                2L),
                        Optional.empty(),
                        spawnTick,
                        spawnTick,
                        expiresAtWorldTick),
                WorldItemPhysicalState.FROZEN_UNLOADED);
    }

    private static WorldItemRestoreEntry entryForChunk(ChunkKey key, long id) {
        long spawnTick = 100L;
        return new WorldItemRestoreEntry(
                new WorldItemRuntimeSnapshot(
                        new WorldItemSnapshot(
                                new WorldItemId(id),
                                new ItemStack(
                                        ResourceLocation.of("gaia", "test/drop"), 1),
                                key.x() * 16.0 + 0.5,
                                4.0,
                                key.z() * 16.0 + 0.5,
                                0.0,
                                0.0,
                                0.0,
                                1L),
                        Optional.empty(),
                        spawnTick,
                        spawnTick,
                        18_100L),
                WorldItemPhysicalState.FROZEN_UNLOADED);
    }

    private static WorldItemPageDescriptor descriptor(
            WorldItemPageCodec codec,
            WorldItemPageSnapshot page) {
        byte[] bytes = codec.encode(SAVE, page);
        return new WorldItemPageDescriptor(
                page.chunkKey(), page.pageRevision(),
                HexFormat.of().formatHex(StreamedChunkCodec.sha256(bytes)),
                page.entries().size(), page.entries().size());
    }

    private static WorldItemPageDescriptor descriptor(
            PageData page, int rawCount, int survivorCount) {
        return new WorldItemPageDescriptor(
                page.descriptor().chunkKey(),
                page.descriptor().pageRevision(),
                page.descriptor().pageHash(),
                rawCount,
                survivorCount);
    }

    private record PageData(
            WorldItemPageSnapshot page,
            WorldItemPageDescriptor descriptor,
            StreamedChunkPayload payload) {}

    private record Attack(
            String name,
            WorldItemPageDescriptor descriptor,
            int dependencyCount,
            long manifestTick) {}

    private static final class CallerProof implements WorldItemDurableProof {}
}

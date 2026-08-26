package com.gaia.save.streaming;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gaia.save.format.SaveGameId;
import com.gaia.save.format.SaveSectionId;
import com.gaia.save.store.JdkSaveFileOperations;
import com.overlord.assets.ResourceLocation;
import com.overlord.inventory.api.ItemStack;
import com.overlord.voxel.ChunkKey;
import com.overlord.worlditem.api.SaveIdentity;
import com.overlord.worlditem.api.WorldItemId;
import com.overlord.worlditem.api.WorldItemPageDescriptor;
import com.overlord.worlditem.api.WorldItemPageMutation;
import com.overlord.worlditem.api.WorldItemPageReadView;
import com.overlord.worlditem.api.WorldItemPageSnapshot;
import com.overlord.worlditem.api.WorldItemPagingCheckpoint;
import com.overlord.worlditem.api.WorldItemDurableProof;
import com.overlord.worlditem.api.WorldItemPersistencePlan;
import com.overlord.worlditem.api.WorldItemPersistenceTicket;
import com.overlord.worlditem.api.WorldItemPhysicalState;
import com.overlord.worlditem.api.WorldItemRestoreEntry;
import com.overlord.worlditem.api.WorldItemRuntimeSnapshot;
import com.overlord.worlditem.api.WorldItemSnapshot;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class StreamedChunkTtlStoreTest {
    private static final SaveGameId SAVE_ID =
            SaveGameId.parse("123e4567-e89b-12d3-a456-426614174000");
    private static final SaveIdentity SAVE =
            new SaveIdentity(UUID.fromString(SAVE_ID.value()));
    private static final ChunkKey KEY = new ChunkKey(-2, 7);
    private static final ChunkKey SECOND_KEY = new ChunkKey(3, -5);
    private static final String BASE_HASH = "11".repeat(32);

    @TempDir Path tempDirectory;

    @Test
    void pinnedReadViewKeepsOneCheckpointAndPageGenerationWhileNewerPublishes()
            throws Exception {
        Path root = Files.createDirectory(tempDirectory.resolve("pinned-view"));
        StreamedChunkStore store = reopen(root, new JdkSaveFileOperations());
        PublishedPage first = publishedPage(1L, 100L);
        assertEquals(StreamedChunkStore.CommitResult.Status.SUCCESS,
                store.commitTransaction(first.transaction()).status());

        try (WorldItemPageReadView pinned = backend(store).openReadView()) {
            long sequence = pinned.indexSequence();
            String digest = pinned.checkpointDigest();
            assertEquals(100L, pinned.checkpoint().worldTick());
            assertEquals(first.page(), pinned.read(first.descriptor()));

            PublishedPage second = publishedPage(2L, 200L);
            assertEquals(StreamedChunkStore.CommitResult.Status.SUCCESS,
                    store.commitTransaction(second.transaction()).status());

            assertEquals(sequence, pinned.indexSequence());
            assertEquals(digest, pinned.checkpointDigest());
            assertEquals(100L, pinned.checkpoint().worldTick());
            assertEquals(first.page(), pinned.read(first.descriptor()));
        }

        try (WorldItemPageReadView current = backend(store).openReadView()) {
            assertEquals(200L, current.checkpoint().worldTick());
        }
    }

    @Test
    void modifiedChunkMetricIsRememberedByTheValidatedAuthorityAndReopen()
            throws Exception {
        Path root = Files.createDirectory(tempDirectory.resolve("modified-metric"));
        StreamedChunkStore store = reopen(root, new JdkSaveFileOperations());
        assertEquals(0, store.modifiedChunkCount());
        PublishedPage page = publishedPage(1L, 100L);
        StreamedChunkPayload source = ((StreamedChunkMutation.Upsert)
                page.transaction().chunks().get(0)).capture().payload();
        StreamedChunkPayload modified = new StreamedChunkPayload(
                source.saveGameId(), source.key(), source.generatorVersion(),
                source.baseHash(), source.revision(), source.persistedRevision(),
                true, true, source.worldHeight(), source.copyCanonicalVoxels(),
                source.extensions());
        StreamedPersistenceTransaction transaction =
                new StreamedPersistenceTransaction(
                        List.of(new StreamedChunkMutation.Upsert(
                                new StreamedChunkStore.ExactChunkCapture(
                                        modified, () -> true))),
                        page.transaction().globalExtensionMutations(),
                        () -> true);

        assertEquals(StreamedChunkStore.CommitResult.Status.SUCCESS,
                store.commitTransaction(transaction).status());

        assertEquals(1, store.modifiedChunkCount());
        assertEquals(1, reopen(root, new JdkSaveFileOperations())
                .modifiedChunkCount());
    }

    @Test
    void staleAndErrorFreshnessStopBeforeFirstMutationAndRunExactlyOnce()
            throws Exception {
        Path root = Files.createDirectory(tempDirectory.resolve("freshness"));
        ProtocolFileOperations files = new ProtocolFileOperations(
                new JdkSaveFileOperations());
        StreamedChunkStore store = reopen(root, files);
        files.clear();
        AtomicInteger staleCalls = new AtomicInteger();
        StreamedPersistenceTransaction stale = transaction(
                publishedPage(1L, 100L),
                () -> staleCalls.incrementAndGet() < 0);

        assertEquals(StreamedChunkStore.CommitResult.Status.STALE,
                store.commitTransaction(stale).status());
        assertEquals(1, staleCalls.get());
        assertTrue(files.mutations().isEmpty());

        AssertionError exact = new AssertionError("freshness-error");
        AtomicInteger errorCalls = new AtomicInteger();
        StreamedPersistenceTransaction error = transaction(
                publishedPage(1L, 100L),
                () -> {
                    errorCalls.incrementAndGet();
                    throw exact;
                });
        assertSame(exact, assertThrows(AssertionError.class,
                () -> store.commitTransaction(error)));
        assertEquals(1, errorCalls.get());
        assertTrue(files.mutations().isEmpty());
    }

    @Test
    void checkpointCannotBeRemovedWhileRequiredPageDependencyRemains()
            throws Exception {
        Path root = Files.createDirectory(tempDirectory.resolve("dependency"));
        StreamedChunkStore store = reopen(root, new JdkSaveFileOperations());
        assertEquals(StreamedChunkStore.CommitResult.Status.SUCCESS,
                store.commitTransaction(publishedPage(1L, 100L).transaction()).status());

        StreamedPersistenceTransaction removeCheckpoint =
                new StreamedPersistenceTransaction(
                        List.of(),
                        List.of(new StreamedGlobalExtensionMutation.Remove(
                                SaveSectionId.WORLD_ITEM_CHECKPOINT)),
                        () -> true);
        StreamedChunkStore.CommitResult rejected =
                store.commitTransaction(removeCheckpoint);

        assertTrue(rejected.status() != StreamedChunkStore.CommitResult.Status.SUCCESS);
        try (WorldItemPageReadView view = backend(store).openReadView()) {
            assertEquals(100L, view.checkpoint().worldTick());
            assertEquals(1, view.checkpoint().pages().size());
        }
    }

    @Test
    void unknownRequiredGlobalExtensionBlocksWorldItemReadView() throws Exception {
        Path root = Files.createDirectory(tempDirectory.resolve("unknown-required"));
        StreamedChunkStore store = reopen(root, new JdkSaveFileOperations());
        assertEquals(StreamedChunkStore.CommitResult.Status.SUCCESS,
                store.commitTransaction(publishedPage(1L, 100L).transaction()).status());
        StreamedGlobalExtension unknown = new StreamedGlobalExtension(
                new SaveSectionId("future-required"),
                1,
                true,
                Optional.empty(),
                new byte[] {1});
        assertEquals(StreamedChunkStore.CommitResult.Status.SUCCESS,
                store.commitTransaction(new StreamedPersistenceTransaction(
                        List.of(),
                        List.of(new StreamedGlobalExtensionMutation.Upsert(unknown)),
                        () -> true)).status());

        assertThrows(IllegalStateException.class, () -> backend(store).openReadView());
    }

    @Test
    void initialPersistenceRejectsUnknownRequiredGlobalBeforeMutation()
            throws Exception {
        Path root = Files.createDirectory(tempDirectory.resolve(
                "initial-unknown-required"));
        ProtocolFileOperations files = new ProtocolFileOperations(
                new JdkSaveFileOperations());
        StreamedChunkStore store = reopen(root, files);
        StreamedGlobalExtension unknown = new StreamedGlobalExtension(
                new SaveSectionId("future-required"),
                1,
                true,
                Optional.empty(),
                new byte[] {1});
        assertEquals(StreamedChunkStore.CommitResult.Status.SUCCESS,
                store.commitTransaction(new StreamedPersistenceTransaction(
                        List.of(),
                        List.of(new StreamedGlobalExtensionMutation.Upsert(unknown)),
                        () -> true)).status());
        long sequence;
        try (StreamedChunkStore.PinnedReadView pinned = store.openPinnedReadView()) {
            sequence = pinned.sequence();
        }
        files.clear();
        WorldItemPagingCheckpoint intended = new WorldItemPagingCheckpoint(
                SAVE, 1L, 0L, 0L, false, 0, List.of());

        assertThrows(IllegalStateException.class, () -> backend(store).persist(
                new WorldItemPersistencePlan(
                        0L, intended, List.of(), "ab".repeat(32), () -> true)));

        try (StreamedChunkStore.PinnedReadView pinned = store.openPinnedReadView()) {
            assertEquals(sequence, pinned.sequence());
            assertTrue(pinned.index().globalExtension(
                    SaveSectionId.WORLD_ITEM_CHECKPOINT).isEmpty());
            assertTrue(pinned.index().globalExtension(
                    new SaveSectionId("future-required")).isPresent());
        }
        assertTrue(files.mutations().isEmpty());
    }

    @Test
    void initialPersistenceRejectsUntrackedPhysicalPageBeforeMutation()
            throws Exception {
        Path root = Files.createDirectory(tempDirectory.resolve(
                "initial-untracked-page"));
        ProtocolFileOperations files = new ProtocolFileOperations(
                new JdkSaveFileOperations());
        StreamedChunkStore store = reopen(root, files);
        PublishedPage page = publishedPage(1L, 100L);
        assertEquals(StreamedChunkStore.CommitResult.Status.SUCCESS,
                store.commitTransaction(new StreamedPersistenceTransaction(
                        page.transaction().chunks(), List.of(), () -> true)).status());
        long sequence;
        try (StreamedChunkStore.PinnedReadView pinned = store.openPinnedReadView()) {
            sequence = pinned.sequence();
        }
        files.clear();
        WorldItemPagingCheckpoint intended = new WorldItemPagingCheckpoint(
                SAVE, 1L, 100L, 8L, false, 0, List.of());

        assertThrows(IllegalStateException.class, () -> backend(store).persist(
                new WorldItemPersistencePlan(
                        0L, intended, List.of(), "bc".repeat(32), () -> true)));

        try (StreamedChunkStore.PinnedReadView pinned = store.openPinnedReadView()) {
            assertEquals(sequence, pinned.sequence());
            assertTrue(pinned.index().globalExtension(
                    SaveSectionId.WORLD_ITEM_CHECKPOINT).isEmpty());
            assertTrue(pinned.index().entry(KEY).isPresent());
        }
        assertTrue(files.mutations().isEmpty());
    }

    @Test
    void dependencyIntroductionCountsPreexistingRequiredPagesExactly()
            throws Exception {
        Path root = Files.createDirectory(tempDirectory.resolve(
                "initial-dependency-baseline"));
        StreamedChunkStore store = reopen(root, new JdkSaveFileOperations());
        PublishedPage page = publishedPage(1L, 100L);
        assertEquals(StreamedChunkStore.CommitResult.Status.SUCCESS,
                store.commitTransaction(new StreamedPersistenceTransaction(
                        page.transaction().chunks(), List.of(), () -> true)).status());
        StreamedGlobalExtension wrongCount = new StreamedGlobalExtension(
                SaveSectionId.WORLD_ITEM_CHECKPOINT,
                WorldItemPagingCheckpointCodec.CODEC_VERSION,
                true,
                Optional.of(new RequiredChunkExtensionDependency(
                        SaveSectionId.WORLD_ITEM_PAGE, 0)),
                new byte[] {1});

        assertTrue(store.commitTransaction(new StreamedPersistenceTransaction(
                List.of(),
                List.of(new StreamedGlobalExtensionMutation.Upsert(wrongCount)),
                () -> true)).status() != StreamedChunkStore.CommitResult.Status.SUCCESS);
        try (StreamedChunkStore.PinnedReadView pinned = store.openPinnedReadView()) {
            assertTrue(pinned.index().globalExtension(
                    SaveSectionId.WORLD_ITEM_CHECKPOINT).isEmpty());
            assertTrue(pinned.index().entry(KEY).isPresent());
        }
    }

    @Test
    void readViewRejectsCrossPageDuplicateSurvivorCountAndAllocatorAttacks()
            throws Exception {
        assertSemanticReadAttackRejected(
                "duplicate-live-id",
                100L,
                8L,
                List.of(
                        pageData(KEY, 1L, 1L, 7L, 18_021L, 1),
                        pageData(SECOND_KEY, 1L, 1L, 7L, 18_021L, 1)));
        assertSemanticReadAttackRejected(
                "expired-counted-live",
                100L,
                8L,
                List.of(pageData(KEY, 1L, 1L, 7L, 100L, 1)));
        assertSemanticReadAttackRejected(
                "allocator-collision",
                100L,
                7L,
                List.of(pageData(KEY, 1L, 1L, 7L, 18_021L, 1)));
    }

    @Test
    void pageRevisionIsIndependentFromContainingChunkRevision() throws Exception {
        Path root = Files.createDirectory(tempDirectory.resolve("container-revision"));
        StreamedChunkStore store = reopen(root, new JdkSaveFileOperations());
        PublishedPage first = publishedPage(1L, 100L);
        assertEquals(StreamedChunkStore.CommitResult.Status.SUCCESS,
                store.commitTransaction(first.transaction()).status());

        PageData samePage = pageData(KEY, 1L, 2L, 7L, 18_021L, 1);
        assertEquals(StreamedChunkStore.CommitResult.Status.SUCCESS,
                store.commitTransaction(new StreamedPersistenceTransaction(
                        List.of(new StreamedChunkMutation.Upsert(
                                new StreamedChunkStore.ExactChunkCapture(
                                        samePage.payload(), () -> true))),
                        List.of(),
                        () -> true)).status());

        try (WorldItemPageReadView view = backend(store).openReadView()) {
            assertEquals(1L, view.checkpoint().pages().get(0).pageRevision());
            assertEquals(first.page(), view.read(first.descriptor()));
        }
    }

    @Test
    void semanticPlanPersistsExactPageAndCheckpointThenItemOnlyRemove()
            throws Exception {
        Path root = Files.createDirectory(tempDirectory.resolve("semantic-plan"));
        StreamedChunkStore store = reopen(root, new JdkSaveFileOperations());
        PublishedPage first = publishedPage(1L, 100L);
        assertEquals(StreamedChunkStore.CommitResult.Status.SUCCESS,
                store.commitTransaction(first.transaction()).status());
        PublishedPage second = publishedPage(2L, 200L);
        WorldItemPagingCheckpoint intended = new WorldItemPagingCheckpoint(
                SAVE, 2L, 200L, 8L, false, 1, List.of(second.descriptor()));
        WorldItemPersistencePlan replace = new WorldItemPersistencePlan(
                1L,
                intended,
                List.of(new WorldItemPageMutation.Upsert(
                        second.page(), Optional.of(first.descriptor()))),
                "22".repeat(32),
                () -> true);
        StreamedWorldItemPageBackend backend = backend(store);

        WorldItemDurableProof proof = backend.persist(replace);
        Object serviceIssuer = new Object();
        backend.durabilityVerifier().verify(
                WorldItemPersistenceTicket.issuedBy(serviceIssuer), replace, proof);
        try (WorldItemPageReadView view = backend.openReadView()) {
            assertEquals(intended, view.checkpoint());
            assertEquals(second.page(), view.read(second.descriptor()));
        }

        WorldItemPagingCheckpoint empty = new WorldItemPagingCheckpoint(
                SAVE, 3L, 200L, 8L, false, 0, List.of());
        WorldItemPersistencePlan remove = new WorldItemPersistencePlan(
                2L,
                empty,
                List.of(new WorldItemPageMutation.Remove(second.descriptor())),
                "33".repeat(32),
                () -> true);
        backend.persist(remove);

        assertTrue(store.readCurrentAuthority(SAVE_ID)
                .index().orElseThrow().entries().isEmpty());
        try (WorldItemPageReadView view = backend.openReadView()) {
            assertEquals(empty, view.checkpoint());
        }
    }

    @Test
    void initialSemanticPlanUsesInjectedExactChunkCaptureAndPublishesOneProof()
            throws Exception {
        Path root = Files.createDirectory(tempDirectory.resolve("initial-semantic-plan"));
        StreamedChunkStore store = reopen(root, new JdkSaveFileOperations());
        PageData initialPage = pageData(KEY, 1L, 1L, 7L, 18_021L, 1);
        WorldItemPagingCheckpoint intended = new WorldItemPagingCheckpoint(
                SAVE, 1L, 100L, 8L, false, 1,
                List.of(initialPage.descriptor()));
        WorldItemPersistencePlan plan = new WorldItemPersistencePlan(
                0L,
                intended,
                List.of(new WorldItemPageMutation.Upsert(
                        initialPage.page(), Optional.empty())),
                "44".repeat(32),
                () -> true);
        StreamedWorldItemPageBackend backend = new StreamedWorldItemPageBackend(
                store,
                (save, page, pageBytes) -> new StreamedChunkStore.ExactChunkCapture(
                        initialPage.payload(), () -> true));

        WorldItemDurableProof proof = backend.persist(plan);

        backend.durabilityVerifier().verify(
                WorldItemPersistenceTicket.issuedBy(new Object()), plan, proof);
        try (WorldItemPageReadView view = backend.openReadView()) {
            assertEquals(intended, view.checkpoint());
            assertEquals(initialPage.page(), view.read(initialPage.descriptor()));
        }
    }

    @Test
    void removingLastPagePreservesVoxelModifiedChunkAndRejectsStaleDescriptor()
            throws Exception {
        Path root = Files.createDirectory(tempDirectory.resolve("extension-only-remove"));
        StreamedChunkStore store = reopen(root, new JdkSaveFileOperations());
        PageData page = voxelModified(pageData(KEY, 1L, 1L, 7L, 18_021L, 1));
        assertEquals(StreamedChunkStore.CommitResult.Status.SUCCESS,
                store.commitTransaction(transactionForPages(
                        1L, 100L, 8L, List.of(page))).status());
        StreamedWorldItemPageBackend backend = backend(store);
        WorldItemPagingCheckpoint empty = new WorldItemPagingCheckpoint(
                SAVE, 2L, 100L, 8L, false, 0, List.of());
        WorldItemPageDescriptor stale = new WorldItemPageDescriptor(
                KEY, 1L, "ff".repeat(32), 1, 1);
        assertThrows(IllegalStateException.class, () -> backend.persist(
                new WorldItemPersistencePlan(
                        1L,
                        empty,
                        List.of(new WorldItemPageMutation.Remove(stale)),
                        "55".repeat(32),
                        () -> true)));

        backend.persist(new WorldItemPersistencePlan(
                1L,
                empty,
                List.of(new WorldItemPageMutation.Remove(page.descriptor())),
                "66".repeat(32),
                () -> true));

        StreamedChunkStore.CurrentAuthorityReadResult current =
                store.readCurrentAuthority(SAVE_ID);
        assertEquals(1, current.index().orElseThrow().entries().size());
        StreamedChunkPayload retained = current.payloads().stream()
                .filter(payload -> payload.key().equals(KEY))
                .findFirst()
                .orElseThrow();
        assertTrue(retained.extensions().stream()
                .noneMatch(extension -> extension.sectionId().equals(
                        SaveSectionId.WORLD_ITEM_PAGE)));
    }

    @Test
    void invalidSemanticPlansFailBeforeDurableMutation() throws Exception {
        Path root = Files.createDirectory(tempDirectory.resolve("precommit-semantic"));
        StreamedChunkStore store = reopen(root, new JdkSaveFileOperations());
        PublishedPage first = publishedPage(1L, 100L);
        assertEquals(StreamedChunkStore.CommitResult.Status.SUCCESS,
                store.commitTransaction(first.transaction()).status());
        long sequence;
        try (StreamedChunkStore.PinnedReadView pinned = store.openPinnedReadView()) {
            sequence = pinned.sequence();
        }

        PageData duplicate = pageData(SECOND_KEY, 1L, 1L, 7L, 18_021L, 1);
        WorldItemPagingCheckpoint duplicateCheckpoint = new WorldItemPagingCheckpoint(
                SAVE, 2L, 100L, 8L, false, 2,
                List.of(first.descriptor(), duplicate.descriptor()));
        StreamedWorldItemPageBackend duplicateBackend =
                new StreamedWorldItemPageBackend(
                        store,
                        (save, page, bytes) -> new StreamedChunkStore.ExactChunkCapture(
                                duplicate.payload(), () -> true));
        assertThrows(IllegalStateException.class, () -> duplicateBackend.persist(
                new WorldItemPersistencePlan(
                        1L,
                        duplicateCheckpoint,
                        List.of(new WorldItemPageMutation.Upsert(
                                duplicate.page(), Optional.empty())),
                        "77".repeat(32),
                        () -> true)));

        WorldItemPagingCheckpoint allocatorCollision = new WorldItemPagingCheckpoint(
                SAVE, 2L, 100L, 7L, false, 1, List.of(first.descriptor()));
        assertThrows(IllegalStateException.class, () -> backend(store).persist(
                new WorldItemPersistencePlan(
                        1L,
                        allocatorCollision,
                        List.of(),
                        "88".repeat(32),
                        () -> true)));

        WorldItemPageDescriptor falseSurvivor = new WorldItemPageDescriptor(
                KEY, 1L, first.descriptor().pageHash(), 1, 0);
        WorldItemPagingCheckpoint falseCount = new WorldItemPagingCheckpoint(
                SAVE, 2L, 100L, 8L, false, 0, List.of(falseSurvivor));
        assertThrows(IllegalStateException.class, () -> backend(store).persist(
                new WorldItemPersistencePlan(
                        1L,
                        falseCount,
                        List.of(),
                        "99".repeat(32),
                        () -> true)));

        try (StreamedChunkStore.PinnedReadView pinned = store.openPinnedReadView()) {
            assertEquals(sequence, pinned.sequence());
            assertEquals(1L, new WorldItemPagingCheckpointCodec().decode(
                    SAVE,
                    pinned.index().globalExtension(SaveSectionId.WORLD_ITEM_CHECKPOINT)
                            .orElseThrow().copyPayloadBytes()).checkpointRevision());
        }
    }

    @Test
    void allocatorHighWaterCannotMoveBackwardWhileRemovingExpiredPage()
            throws Exception {
        Path root = Files.createDirectory(tempDirectory.resolve("allocator-regression"));
        StreamedChunkStore store = reopen(root, new JdkSaveFileOperations());
        PublishedPage first = publishedPage(1L, 100L);
        assertEquals(StreamedChunkStore.CommitResult.Status.SUCCESS,
                store.commitTransaction(first.transaction()).status());

        WorldItemPagingCheckpoint regressed = new WorldItemPagingCheckpoint(
                SAVE, 2L, 18_022L, 7L, false, 0, List.of());
        assertThrows(IllegalStateException.class, () -> backend(store).persist(
                new WorldItemPersistencePlan(
                        1L,
                        regressed,
                        List.of(new WorldItemPageMutation.Remove(
                                first.descriptor())),
                        "a1".repeat(32),
                        () -> true)));

        try (WorldItemPageReadView current = backend(store).openReadView()) {
            assertEquals(8L, current.checkpoint().nextItemId());
            assertEquals(1L, current.checkpoint().checkpointRevision());
        }
    }

    @Test
    void foreignInitialCheckpointFailsBeforePublication() throws Exception {
        Path root = Files.createDirectory(tempDirectory.resolve("foreign-initial"));
        StreamedChunkStore store = reopen(root, new JdkSaveFileOperations());
        long sequence;
        try (StreamedChunkStore.PinnedReadView pinned = store.openPinnedReadView()) {
            sequence = pinned.sequence();
        }
        SaveIdentity foreign = new SaveIdentity(
                UUID.fromString("00000000-0000-0000-0000-000000000001"));
        WorldItemPagingCheckpoint checkpoint = new WorldItemPagingCheckpoint(
                foreign, 1L, 0L, 0L, false, 0, List.of());

        assertThrows(IllegalStateException.class, () -> backend(store).persist(
                new WorldItemPersistencePlan(
                        0L,
                        checkpoint,
                        List.of(),
                        "aa".repeat(32),
                        () -> true)));
        try (StreamedChunkStore.PinnedReadView pinned = store.openPinnedReadView()) {
            assertEquals(sequence, pinned.sequence());
            assertTrue(pinned.index().globalExtensions().isEmpty());
        }
    }

    @Test
    void unchangedPageCanAdvanceCheckpointTickWithCountOnlyDescriptorChange()
            throws Exception {
        Path root = Files.createDirectory(tempDirectory.resolve("count-only"));
        StreamedChunkStore store = reopen(root, new JdkSaveFileOperations());
        PublishedPage first = publishedPage(1L, 100L);
        assertEquals(StreamedChunkStore.CommitResult.Status.SUCCESS,
                store.commitTransaction(first.transaction()).status());
        WorldItemPageDescriptor expired = new WorldItemPageDescriptor(
                KEY, 1L, first.descriptor().pageHash(), 1, 0);
        WorldItemPagingCheckpoint intended = new WorldItemPagingCheckpoint(
                SAVE, 2L, 20_000L, 8L, false, 0, List.of(expired));

        backend(store).persist(new WorldItemPersistencePlan(
                1L,
                intended,
                List.of(),
                "bb".repeat(32),
                () -> true));

        try (WorldItemPageReadView view = backend(store).openReadView()) {
            assertEquals(intended, view.checkpoint());
            assertEquals(first.page(), view.read(expired));
        }
    }

    @Test
    void customRequiredExtensionRegistryIsUsedByTransactionAndStore()
            throws Exception {
        Path root = Files.createDirectory(tempDirectory.resolve("custom-registry"));
        SaveSectionId future = new SaveSectionId("future-runtime");
        StreamedChunkCodec codec = new StreamedChunkCodec(
                StreamedExtensionSupportRegistry.builder()
                        .supportRequired(future, 1)
                        .build());
        StreamedChunkStore store = new StreamedChunkStore(
                root,
                SAVE_ID,
                codec,
                new StreamedChunkIndexCodec(),
                new JdkSaveFileOperations());
        StreamedChunkPayload payload = new StreamedChunkPayload(
                SAVE_ID,
                KEY,
                "v15",
                BASE_HASH,
                1L,
                0L,
                true,
                false,
                1,
                new byte[16 * 16],
                List.of(new StreamedChunkPayload.ExtensionDescriptor(
                        future, 1, true, new byte[] {7})));
        StreamedPersistenceTransaction transaction = new StreamedPersistenceTransaction(
                List.of(new StreamedChunkMutation.Upsert(
                        new StreamedChunkStore.ExactChunkCapture(payload, () -> true))),
                List.of(),
                () -> true);

        assertEquals(StreamedChunkStore.CommitResult.Status.SUCCESS,
                store.commitTransaction(transaction).status());
        assertEquals(future, store.readCurrentAuthority(SAVE_ID).payloads().get(0)
                .extensions().get(0).sectionId());
    }

    @Test
    void exactChunkRemoveAndCheckpointReplacementPublishTogetherOrNotAtAll()
            throws Exception {
        Path root = Files.createDirectory(tempDirectory.resolve("paired-remove"));
        StreamedChunkStore store = reopen(root, new JdkSaveFileOperations());
        assertEquals(StreamedChunkStore.CommitResult.Status.SUCCESS,
                store.commitTransaction(publishedPage(1L, 100L).transaction()).status());
        StreamedChunkIndex.Entry current = store.readCurrentAuthority(SAVE_ID)
                .index().orElseThrow().entry(KEY).orElseThrow();
        WorldItemPagingCheckpoint empty = new WorldItemPagingCheckpoint(
                SAVE, 2L, 100L, 8L, false, 0, List.of());
        byte[] checkpointBytes = new WorldItemPagingCheckpointCodec().encode(empty);
        StreamedGlobalExtension emptyCheckpoint = new StreamedGlobalExtension(
                SaveSectionId.WORLD_ITEM_CHECKPOINT,
                1,
                true,
                Optional.of(new RequiredChunkExtensionDependency(
                        SaveSectionId.WORLD_ITEM_PAGE, 0)),
                checkpointBytes);

        StreamedPersistenceTransaction stale = new StreamedPersistenceTransaction(
                List.of(new StreamedChunkMutation.Remove(
                        KEY, current.revision(), "ff".repeat(32))),
                List.of(new StreamedGlobalExtensionMutation.Upsert(emptyCheckpoint)),
                () -> true);
        assertTrue(store.commitTransaction(stale).status()
                != StreamedChunkStore.CommitResult.Status.SUCCESS);
        assertEquals(1, store.readCurrentAuthority(SAVE_ID)
                .index().orElseThrow().entries().size());

        StreamedPersistenceTransaction exact = new StreamedPersistenceTransaction(
                List.of(new StreamedChunkMutation.Remove(
                        KEY, current.revision(), current.payloadHash())),
                List.of(new StreamedGlobalExtensionMutation.Upsert(emptyCheckpoint)),
                () -> true);
        assertEquals(StreamedChunkStore.CommitResult.Status.SUCCESS,
                store.commitTransaction(exact).status());
        assertTrue(store.readCurrentAuthority(SAVE_ID)
                .index().orElseThrow().entries().isEmpty());
        try (WorldItemPageReadView view = backend(store).openReadView()) {
            assertTrue(view.checkpoint().pages().isEmpty());
        }
    }

    @Test
    void storeMetadataCachesStayBoundedAcrossHistoricalUniquePayloadPools()
            throws Exception {
        Path root = Files.createDirectory(tempDirectory.resolve("metadata-bound"));
        StreamedChunkStore store = reopen(root, new JdkSaveFileOperations());
        var ensure = StreamedChunkStore.class.getDeclaredMethod(
                "ensurePayloadPool", ChunkKey.class);
        ensure.setAccessible(true);
        for (int index = 0; index < 160; index++) {
            ensure.invoke(store, new ChunkKey(index, -index));
        }

        assertTrue(privateContainerSize(store, "shardIdentities") <= 128);
        assertTrue(privateContainerSize(store, "payloadSlots") <= 256);
        assertTrue(privateContainerSize(store, "initializedPayloadPools") <= 128);
    }

    private static int privateContainerSize(Object target, String fieldName)
            throws Exception {
        var field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        Object value = field.get(target);
        if (value instanceof Map<?, ?> map) {
            return map.size();
        }
        return ((java.util.Collection<?>) value).size();
    }

    @ParameterizedTest
    @EnumSource(value = ProtocolStage.class, names = {
            "WRITE_PAYLOAD_B", "FORCE_PAYLOAD_B",
            "WRITE_RECOVERY", "FORCE_RECOVERY",
            "WRITE_MAIN", "FORCE_MAIN"})
    void crashAtPageOrIndexPublicationReopensExactOldOrCompleteNew(
            ProtocolStage stage) throws Exception {
        Path root = Files.createDirectory(tempDirectory.resolve("fault-" + stage));
        ProtocolFileOperations files = new ProtocolFileOperations(
                new JdkSaveFileOperations());
        StreamedChunkStore store = reopen(root, files);
        assertEquals(StreamedChunkStore.CommitResult.Status.SUCCESS,
                store.commitTransaction(publishedPage(1L, 100L).transaction()).status());

        files.before(stage, ignored -> {
            throw new IOException("injected TTL page transaction failure");
        });
        store.commitTransaction(publishedPage(2L, 200L).transaction());

        StreamedChunkStore reopened = reopen(root, new JdkSaveFileOperations());
        try (WorldItemPageReadView view = backend(reopened).openReadView()) {
            assertTrue(Set.of(100L, 200L).contains(view.checkpoint().worldTick()));
            WorldItemPageDescriptor descriptor = view.checkpoint().pages().get(0);
            assertEquals(view.checkpoint().worldTick() == 100L ? 1L : 2L,
                    descriptor.pageRevision());
            assertEquals(descriptor.pageRevision(),
                    view.read(descriptor).pageRevision());
        }
    }

    private static StreamedChunkStore reopen(
            Path root, com.gaia.save.store.SaveFileOperations files) {
        return new StreamedChunkStore(
                root, SAVE_ID, new StreamedChunkCodec(),
                new StreamedChunkIndexCodec(), files);
    }

    private static StreamedWorldItemPageBackend backend(StreamedChunkStore store) {
        return new StreamedWorldItemPageBackend(store);
    }

    private static PublishedPage publishedPage(long revision, long worldTick) {
        WorldItemRestoreEntry entry = new WorldItemRestoreEntry(
                new WorldItemRuntimeSnapshot(
                        new WorldItemSnapshot(
                                new WorldItemId(7L),
                                new ItemStack(ResourceLocation.of("gaia", "test/drop"), 3),
                                1.25d, 2.5d, 3.75d,
                                0.0d, -0.1d, 0.0d,
                                revision),
                        Optional.empty(),
                        21L,
                        23L,
                        18_021L),
                WorldItemPhysicalState.FROZEN_UNLOADED);
        WorldItemPageSnapshot page = new WorldItemPageSnapshot(
                KEY, revision, List.of(entry));
        WorldItemPageCodec pageCodec = new WorldItemPageCodec();
        byte[] pageBytes = pageCodec.encode(SAVE, page);
        String pageHash = HexFormat.of().formatHex(StreamedChunkCodec.sha256(pageBytes));
        WorldItemPageDescriptor descriptor = new WorldItemPageDescriptor(
                KEY, revision, pageHash, 1, 1);
        WorldItemPagingCheckpoint checkpoint = new WorldItemPagingCheckpoint(
                SAVE, revision, worldTick, 8L, false, 1, List.of(descriptor));
        byte[] checkpointBytes = new WorldItemPagingCheckpointCodec().encode(checkpoint);

        StreamedChunkPayload payload = new StreamedChunkPayload(
                SAVE_ID,
                KEY,
                "v15",
                BASE_HASH,
                revision,
                revision - 1L,
                true,
                false,
                1,
                new byte[16 * 16],
                List.of(new StreamedChunkPayload.ExtensionDescriptor(
                        SaveSectionId.WORLD_ITEM_PAGE, 1, true, pageBytes)));
        StreamedGlobalExtension global = new StreamedGlobalExtension(
                SaveSectionId.WORLD_ITEM_CHECKPOINT,
                1,
                true,
                Optional.of(new RequiredChunkExtensionDependency(
                        SaveSectionId.WORLD_ITEM_PAGE, 1)),
                checkpointBytes);
        StreamedPersistenceTransaction transaction = new StreamedPersistenceTransaction(
                List.of(new StreamedChunkMutation.Upsert(
                        new StreamedChunkStore.ExactChunkCapture(payload, () -> true))),
                List.of(new StreamedGlobalExtensionMutation.Upsert(global)),
                () -> true);
        return new PublishedPage(page, descriptor, transaction);
    }

    private void assertSemanticReadAttackRejected(
            String directory,
            long worldTick,
            long nextItemId,
            List<PageData> pages) throws Exception {
        Path root = Files.createDirectory(tempDirectory.resolve(directory));
        StreamedChunkStore store = reopen(root, new JdkSaveFileOperations());
        assertEquals(StreamedChunkStore.CommitResult.Status.SUCCESS,
                store.commitTransaction(transactionForPages(
                        1L, worldTick, nextItemId, pages)).status());
        assertThrows(IllegalStateException.class, () -> backend(store).openReadView());
    }

    private static StreamedPersistenceTransaction transactionForPages(
            long checkpointRevision,
            long worldTick,
            long nextItemId,
            List<PageData> pages) {
        List<WorldItemPageDescriptor> descriptors = pages.stream()
                .map(PageData::descriptor)
                .toList();
        int liveCount = descriptors.stream()
                .mapToInt(WorldItemPageDescriptor::expectedLiveCountAtCheckpointTick)
                .sum();
        WorldItemPagingCheckpoint checkpoint = new WorldItemPagingCheckpoint(
                SAVE,
                checkpointRevision,
                worldTick,
                nextItemId,
                false,
                liveCount,
                descriptors);
        StreamedGlobalExtension global = new StreamedGlobalExtension(
                SaveSectionId.WORLD_ITEM_CHECKPOINT,
                1,
                true,
                Optional.of(new RequiredChunkExtensionDependency(
                        SaveSectionId.WORLD_ITEM_PAGE, pages.size())),
                new WorldItemPagingCheckpointCodec().encode(checkpoint));
        return new StreamedPersistenceTransaction(
                pages.stream()
                        .map(page -> (StreamedChunkMutation) new StreamedChunkMutation.Upsert(
                                new StreamedChunkStore.ExactChunkCapture(
                                        page.payload(), () -> true)))
                        .toList(),
                List.of(new StreamedGlobalExtensionMutation.Upsert(global)),
                () -> true);
    }

    private static PageData pageData(
            ChunkKey key,
            long pageRevision,
            long chunkRevision,
            long id,
            long expiresAtWorldTick,
            int expectedLiveCount) {
        WorldItemRestoreEntry entry = new WorldItemRestoreEntry(
                new WorldItemRuntimeSnapshot(
                        new WorldItemSnapshot(
                                new WorldItemId(id),
                                new ItemStack(ResourceLocation.of("gaia", "test/drop"), 3),
                                1.25d, 2.5d, 3.75d,
                                0.0d, -0.1d, 0.0d,
                                pageRevision),
                        Optional.empty(),
                        21L,
                        23L,
                        expiresAtWorldTick),
                WorldItemPhysicalState.FROZEN_UNLOADED);
        WorldItemPageSnapshot page = new WorldItemPageSnapshot(
                key, pageRevision, List.of(entry));
        byte[] pageBytes = new WorldItemPageCodec().encode(SAVE, page);
        WorldItemPageDescriptor descriptor = new WorldItemPageDescriptor(
                key,
                pageRevision,
                HexFormat.of().formatHex(StreamedChunkCodec.sha256(pageBytes)),
                1,
                expectedLiveCount);
        StreamedChunkPayload payload = new StreamedChunkPayload(
                SAVE_ID,
                key,
                "v15",
                BASE_HASH,
                chunkRevision,
                chunkRevision - 1L,
                true,
                false,
                1,
                new byte[16 * 16],
                List.of(new StreamedChunkPayload.ExtensionDescriptor(
                        SaveSectionId.WORLD_ITEM_PAGE, 1, true, pageBytes)));
        return new PageData(page, descriptor, payload);
    }

    private static PageData voxelModified(PageData source) {
        StreamedChunkPayload payload = source.payload();
        return new PageData(
                source.page(),
                source.descriptor(),
                new StreamedChunkPayload(
                        payload.saveGameId(),
                        payload.key(),
                        payload.generatorVersion(),
                        payload.baseHash(),
                        payload.revision(),
                        payload.persistedRevision(),
                        true,
                        true,
                        payload.worldHeight(),
                        payload.copyCanonicalVoxels(),
                        payload.extensions()));
    }

    private static StreamedPersistenceTransaction transaction(
            PublishedPage published, java.util.function.BooleanSupplier freshness) {
        return new StreamedPersistenceTransaction(
                published.transaction().chunks(),
                published.transaction().globalExtensionMutations(),
                freshness);
    }

    private record PublishedPage(
            WorldItemPageSnapshot page,
            WorldItemPageDescriptor descriptor,
            StreamedPersistenceTransaction transaction) {}

    private record PageData(
            WorldItemPageSnapshot page,
            WorldItemPageDescriptor descriptor,
            StreamedChunkPayload payload) {}
}

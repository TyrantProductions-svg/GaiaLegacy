package com.gaia.save.streaming;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gaia.interaction.GameMode;
import com.gaia.save.archive.SaveArchiveReadResult;
import com.gaia.save.archive.SaveArchiveReader;
import com.gaia.save.archive.SaveArchiveWriter;
import com.gaia.save.archive.SaveDiagnostic;
import com.gaia.save.codec.ChunkSectionCodec;
import com.gaia.save.codec.InventorySectionCodec;
import com.gaia.save.codec.PlayerSectionCodec;
import com.gaia.save.codec.SaveSnapshotCodec;
import com.gaia.save.codec.WorldItemsSectionCodec;
import com.gaia.save.format.SaveFormatVersion;
import com.gaia.save.format.SaveGameId;
import com.gaia.save.format.SaveSectionId;
import com.gaia.save.snapshot.InventorySaveSnapshot;
import com.gaia.save.snapshot.PlayerSaveSnapshot;
import com.gaia.save.snapshot.SaveGameSnapshot;
import com.gaia.save.snapshot.WorldItemsSaveSnapshot;
import com.gaia.save.store.FileSaveCatalog;
import com.gaia.save.store.JdkSaveFileOperations;
import com.gaia.save.store.SaveDeleteResult;
import com.gaia.save.store.SaveFileOperations;
import com.gaia.save.store.SaveRepository;
import com.gaia.shell.save.SaveSummary;
import com.gaia.world.GaiaWorldGenerator;
import com.gaia.world.generation.DeterministicCoordinateSampler;
import com.gaia.world.generation.GenerationBlockPalette;
import com.gaia.world.generation.GenerationContext;
import com.gaia.world.generation.WorldGenerationConfig;
import com.gaia.world.generation.WorldGenerationHasher;
import com.gaia.world.generation.WorldGenerationResult;
import com.gaia.world.generation.WorldGenerator;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.overlord.interaction.api.EntityRef;
import com.overlord.inventory.api.BodySlot;
import com.overlord.assets.ResourceLocation;
import com.overlord.inventory.api.ItemStack;
import com.overlord.voxel.ChunkGenerationData;
import com.overlord.voxel.ChunkKey;
import com.overlord.voxel.ChunkRepositorySnapshot;
import com.overlord.voxel.ChunkSnapshot;
import com.overlord.worlditem.api.WorldItemId;
import com.overlord.worlditem.api.WorldItemPhysicalState;
import com.overlord.worlditem.api.WorldItemRestoreEntry;
import com.overlord.worlditem.api.WorldItemRuntimeSnapshot;
import com.overlord.worlditem.api.WorldItemSnapshot;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Gate 15C contract for conservative, recoverable Phase 14 archive migration. */
class Phase14SaveMigrationTest {
    private static final SaveGameId SAVE_ID = SaveGameId.parse(
            "123e4567-e89b-12d3-a456-426614174015");
    private static final Instant CREATED = Instant.parse("2026-08-12T01:00:00Z");
    private static final Instant MODIFIED = Instant.parse("2026-08-12T01:05:00Z");
    private static final EntityRef OWNER = new EntityRef(1500);
    private static final WorldGenerationConfig GENERATION =
            WorldGenerationConfig.visualRevisionCandidate();
    private static final String GENERATOR_VERSION =
            "gaia-v" + GENERATION.algorithmVersion();
    private static final SaveSnapshotCodec SNAPSHOT_CODEC = new SaveSnapshotCodec(
            new ChunkSectionCodec(),
            new PlayerSectionCodec(),
            new InventorySectionCodec(),
            new WorldItemsSectionCodec());
    private static final StreamedChunkCodec STREAMED_CODEC = new StreamedChunkCodec();
    private static final StreamedChunkIndexCodec INDEX_CODEC =
            new StreamedChunkIndexCodec();
    private static final GeneratedFixture GENERATED = generatedFixture();
    private static final GeneratedFixture THREE_CHUNKS = threeChunkFixture();

    @TempDir
    Path tempDir;

    @Test
    void importsEveryGeneratedBaseIdenticalV1ChunkAndReopensCompleteV2Idempotently()
            throws Exception {
        MigrationFixture fixture = prepare("complete", GENERATED);
        List<Phase14SaveMigrator.Checkpoint> observed = new ArrayList<>();
        Phase14SaveMigrator migrator = migrator(
                fixture.root(), observed::add);

        Phase14MigrationResult migrated = migrator.migrate(SAVE_ID);
        Map<String, byte[]> afterFirstMigration = exactRegularFiles(fixture.root());
        Phase14MigrationResult repeated = migrator.migrate(SAVE_ID);

        assertAll(
                () -> assertEquals(
                        Phase14MigrationResult.Status.MIGRATED,
                        migrated.status(),
                        () -> diagnosticSummary(migrated.diagnostics())),
                () -> assertTrue(migrated.diagnostics().isEmpty()),
                () -> assertEquals(
                        SaveFormatVersion.STREAMED_CHUNKS,
                        migrated.validatedManifest().orElseThrow().formatVersion()),
                () -> assertEquals(
                        Set.of(
                                SaveSectionId.PLAYER,
                                SaveSectionId.INVENTORY,
                                SaveSectionId.WORLD_ITEMS,
                                SaveSectionId.STREAMED_CHUNKS),
                        migrated.validatedManifest().orElseThrow().sections().stream()
                                .map(section -> section.sectionId())
                                .collect(java.util.stream.Collectors.toSet())),
                () -> assertEquals(
                        81, migrated.validatedIndex().orElseThrow().entries().size()),
                () -> assertTrue(migrated.validatedIndex().orElseThrow().entries()
                        .stream().allMatch(StreamedChunkIndex.Entry::modified)),
                () -> assertEquals(
                        Phase14MigrationResult.Status.NOT_REQUIRED, repeated.status()),
                () -> assertTrue(repeated.diagnostics().isEmpty()),
                () -> assertEquals(
                        migrated.validatedManifest(), repeated.validatedManifest()),
                () -> assertEquals(
                        migrated.validatedIndex().orElseThrow().saveGameId(),
                        repeated.validatedIndex().orElseThrow().saveGameId()),
                () -> assertEquals(
                        migrated.validatedIndex().orElseThrow().entries(),
                        repeated.validatedIndex().orElseThrow().entries()),
                () -> assertExactRegularFiles(
                        afterFirstMigration, exactRegularFiles(fixture.root())),
                () -> assertArrayEquals(
                        fixture.exactV1(), Files.readAllBytes(fixture.backup()),
                        "the recovery backup must be the exact original v1 archive"));

        assertEveryCheckpointOnceInCanonicalOrder(observed);
        assertCompleteV2Authority(fixture.root(), GENERATED);
        SaveArchiveReadResult retainedV1 = reader().read(fixture.backup());
        assertEquals(SaveArchiveReadResult.Status.VALID, retainedV1.status());
        assertSnapshotContentEquals(
                GENERATED.snapshot(), retainedV1.snapshot().orElseThrow());

        try (var worldItems = new StreamedWorldItemPageBackend(
                        streamedStore(fixture.root(), new JdkSaveFileOperations()))
                .openReadView()) {
            assertEquals(GENERATED.snapshot().fixedTick(),
                    worldItems.checkpoint().worldTick());
            assertEquals(GENERATED.snapshot().worldItems().nextItemId(),
                    worldItems.checkpoint().nextItemId());
            assertEquals(0, worldItems.checkpoint().totalLiveItemCount());
            assertTrue(worldItems.checkpoint().pages().isEmpty());
        }
    }

    @Test
    void migrationPublishesLegacyWorldItemsAsTtlPagesAndAllocatorCheckpoint()
            throws Exception {
        GeneratedFixture source = generatedFixtureWithWorldItem();
        MigrationFixture fixture = prepare("worlditem-v1-to-v2", source);

        Phase14MigrationResult migrated = migrator(fixture.root(), ignored -> {})
                .migrate(SAVE_ID);

        assertEquals(
                Phase14MigrationResult.Status.MIGRATED,
                migrated.status(),
                () -> migrated.diagnostics().stream()
                        .map(value -> value.code() + ":" + value.message()
                                + ":" + value.cause())
                        .toList().toString());
        try (var view = new StreamedWorldItemPageBackend(
                        streamedStore(fixture.root(), new JdkSaveFileOperations()))
                .openReadView()) {
            assertEquals(source.snapshot().fixedTick(), view.checkpoint().worldTick());
            assertEquals(701L, view.checkpoint().nextItemId());
            assertEquals(1, view.checkpoint().totalLiveItemCount());
            assertEquals(1, view.checkpoint().pages().size());
            WorldItemRestoreEntry restored = view.read(
                    view.checkpoint().pages().get(0)).entries().get(0);
            assertEquals(new WorldItemId(700L), restored.runtime().item().id());
            assertEquals(
                    1_000L + WorldItemRuntimeSnapshot.WORLD_ITEM_TTL_TICKS,
                    restored.runtime().expiresAtWorldTick());
            assertTrue(view.checkpoint().pages().get(0).chunkKey().x() < 0);
        }
        assertArrayEquals(fixture.exactV1(), Files.readAllBytes(fixture.backup()));
    }

    @Test
    void migrationPublishesAllThreeWorldItemPagesAcrossTheRealEightyOneChunkAuthority()
            throws Exception {
        GeneratedFixture source = generatedFixtureWithWorldItems();
        MigrationFixture fixture = prepare("worlditem-three-pages-complete", source);

        Phase14MigrationResult migrated = migrator(fixture.root(), ignored -> {})
                .migrate(SAVE_ID);

        assertEquals(Phase14MigrationResult.Status.MIGRATED, migrated.status());
        assertEquals(81, migrated.validatedIndex().orElseThrow().entries().size());
        StreamedChunkStore store = streamedStore(
                fixture.root(), new JdkSaveFileOperations());
        StreamedChunkStore.BatchReadResult chunks = store.readModifiedBatch(
                SAVE_ID, migrated.validatedIndex().orElseThrow());
        assertEquals(StreamedChunkStore.BatchReadResult.Status.FOUND, chunks.status());
        assertEquals(81, chunks.payloads().size());
        Map<ChunkKey, ChunkSnapshot> expectedChunks = source.snapshot().chunks().chunks()
                .stream().collect(java.util.stream.Collectors.toMap(
                        ChunkSnapshot::key,
                        java.util.function.Function.identity()));
        for (StreamedChunkPayload payload : chunks.payloads()) {
            ChunkSnapshot expected = expectedChunks.get(payload.key());
            assertArrayEquals(expected.copyBlocks(), payload.copyCanonicalVoxels());
            assertEquals(expected.revision(), payload.revision());
            assertEquals(source.baseHashes().get(payload.key()), payload.baseHash());
            assertTrue(payload.extensions().size() <= 1);
            if (!payload.extensions().isEmpty()) {
                assertEquals(
                        SaveSectionId.WORLD_ITEM_PAGE,
                        payload.extensions().get(0).sectionId());
            }
        }

        try (var view = new StreamedWorldItemPageBackend(store).openReadView()) {
            assertEquals(source.snapshot().fixedTick(), view.checkpoint().worldTick());
            assertEquals(703L, view.checkpoint().nextItemId());
            assertEquals(3, view.checkpoint().totalLiveItemCount());
            assertEquals(3, view.checkpoint().pages().size());
            List<WorldItemRestoreEntry> restored = view.checkpoint().pages().stream()
                    .map(view::read)
                    .flatMap(page -> page.entries().stream())
                    .sorted(java.util.Comparator.comparingLong(
                            entry -> entry.runtime().item().id().value()))
                    .toList();
            assertEquals(List.of(700L, 701L, 702L), restored.stream()
                    .map(entry -> entry.runtime().item().id().value()).toList());
            assertEquals(List.of(19_000L, 19_001L, 19_002L), restored.stream()
                    .map(entry -> entry.runtime().expiresAtWorldTick()).toList());
        }
        assertArrayEquals(fixture.exactV1(), Files.readAllBytes(fixture.backup()));
        SaveArchiveReadResult visible = SaveRepository.open(
                        fixture.root(), reader(), new JdkSaveFileOperations())
                .load(SAVE_ID);
        assertEquals(SaveArchiveReadResult.Status.VALID, visible.status());
        assertEquals(source.snapshot().fixedTick(),
                visible.snapshot().orElseThrow().fixedTick());
        assertEquals(
                com.overlord.worlditem.api.LogicalWorldItemSnapshot.Completeness.PAGED_PARTIAL,
                visible.snapshot().orElseThrow().worldItems().completeness(),
                "the first post-migration load must select the streamed paging authority");
        assertTrue(visible.snapshot().orElseThrow().worldItems().entries().isEmpty(),
                "paged save snapshots do not duplicate dormant page DTOs");
        assertEquals(source.snapshot().worldItems().nextItemId(),
                visible.snapshot().orElseThrow().worldItems().nextItemId());
    }

    @ParameterizedTest(name = "worlditem-publication-{0}-{1}")
    @MethodSource("worldItemPublicationFaults")
    void interruptedWorldItemPageOrCheckpointPublicationExposesOnlyOldOrCompleteNew(
            Phase14SaveMigrator.CheckpointStage stage,
            Phase14SaveMigrator.CheckpointSide side,
            int pageOrdinal) throws Exception {
        MigrationFixture fixture = prepare(
                "worlditem-publication-" + stage + "-" + side + "-" + pageOrdinal,
                generatedFixtureWithWorldItems());
        InjectedMigrationFailure injected = new InjectedMigrationFailure();
        Phase14MigrationResult result = migrator(
                fixture.root(),
                checkpoint -> {
                    if (checkpoint.stage() == stage
                            && checkpoint.side() == side
                            && (stage != Phase14SaveMigrator.CheckpointStage.WORLD_ITEM_PAGES
                                    || checkpoint.chunkOrdinal() == pageOrdinal)) {
                        throw injected;
                    }
                }).migrate(SAVE_ID);

        assertTrue(result.status() == Phase14MigrationResult.Status.FAILED
                || result.status() == Phase14MigrationResult.Status.BLOCKING_FAILURE);
        assertSame(injected, primaryCause(result.diagnostics().get(0)));
        assertExactOldOrCompleteNew(fixture);
        SaveArchiveReadResult visible = SaveRepository.open(
                        fixture.root(), reader(), new JdkSaveFileOperations())
                .load(SAVE_ID);
        if (visible.snapshot().orElseThrow().metadata().formatVersion()
                .equals(SaveFormatVersion.STREAMED_CHUNKS)) {
            try (var worldItems = new StreamedWorldItemPageBackend(
                            streamedStore(fixture.root(), new JdkSaveFileOperations()))
                    .openReadView()) {
                assertEquals(GENERATED.snapshot().fixedTick(),
                        worldItems.checkpoint().worldTick());
                assertEquals(3, worldItems.checkpoint().pages().size());
            }
        }
    }

    @Test
    void publishedMigrationValidatesDistantChunkWithoutMaterializingItInSessionSnapshot()
            throws Exception {
        MigrationFixture fixture = prepare("later-streamed-commit", THREE_CHUNKS);
        Phase14MigrationResult migrated = migrator(fixture.root(), ignored -> {})
                .migrate(SAVE_ID);
        assertEquals(Phase14MigrationResult.Status.MIGRATED, migrated.status());

        StreamedChunkStore store = streamedStore(fixture.root(), new JdkSaveFileOperations());
        StreamedChunkStore.BatchReadResult imported = store.readModifiedBatch(
                SAVE_ID, migrated.validatedIndex().orElseThrow());
        assertEquals(StreamedChunkStore.BatchReadResult.Status.FOUND, imported.status());
        StreamedChunkPayload old = imported.payloads().get(0);
        byte[] revisedVoxels = old.copyCanonicalVoxels();
        revisedVoxels[0] = (byte) (revisedVoxels[0] == 7 ? 8 : 7);
        StreamedChunkPayload revised = new StreamedChunkPayload(
                SAVE_ID,
                old.key(),
                old.generatorVersion(),
                old.baseHash(),
                old.revision() + 1L,
                old.revision(),
                true,
                old.worldHeight(),
                revisedVoxels,
                List.of());
        ChunkKey distantKey = new ChunkKey(4_096, -4_096);
        byte[] distantVoxels = new byte[16 * old.worldHeight() * 16];
        distantVoxels[distantVoxels.length - 1] = 11;
        StreamedChunkPayload distant = new StreamedChunkPayload(
                SAVE_ID,
                distantKey,
                old.generatorVersion(),
                generatedBaseHash(distantKey),
                1L,
                0L,
                true,
                old.worldHeight(),
                distantVoxels,
                List.of());

        StreamedChunkStore.CommitResult committed = store.commitModifiedBatch(
                List.of(
                        new StreamedChunkStore.ExactChunkCapture(revised, () -> true),
                        new StreamedChunkStore.ExactChunkCapture(distant, () -> true)),
                new StreamedChunkStore.WorldItemHibernatePayload(
                        new byte[0], () -> true));
        assertEquals(StreamedChunkStore.CommitResult.Status.SUCCESS, committed.status());

        SaveRepository reopened = SaveRepository.open(
                fixture.root(), reader(), new JdkSaveFileOperations());
        SaveArchiveReadResult loaded = reopened.load(SAVE_ID);
        List<SaveSummary> rows = new FileSaveCatalog(reopened).summaries();
        Phase14MigrationResult repeated = reopened.migratePhase14(SAVE_ID);
        Map<ChunkKey, ChunkSnapshot> chunks = loaded.snapshot().orElseThrow()
                .chunks().chunks().stream().collect(java.util.stream.Collectors.toMap(
                        ChunkSnapshot::key, chunk -> chunk));
        StreamedChunkStore.CurrentAuthorityReadResult durable =
                streamedStore(fixture.root(), new JdkSaveFileOperations())
                        .readCurrentAuthority(SAVE_ID);

        assertAll(
                () -> assertEquals(SaveArchiveReadResult.Status.VALID, loaded.status()),
                () -> assertEquals(1, rows.size()),
                () -> assertEquals(
                        SaveFormatVersion.STREAMED_CHUNKS,
                        rows.get(0).formatVersion().orElseThrow()),
                () -> assertEquals(
                        Phase14MigrationResult.Status.NOT_REQUIRED, repeated.status()),
                () -> assertEquals(4, repeated.validatedIndex().orElseThrow()
                        .entries().size()),
                () -> assertArrayEquals(
                        revisedVoxels, chunks.get(old.key()).copyBlocks()),
                () -> assertFalse(
                        chunks.containsKey(distantKey),
                        "historical streamed Chunks must remain durable but nonresident"),
                () -> assertTrue(durable.payloads().stream().anyMatch(payload ->
                        payload.key().equals(distantKey)
                                && java.util.Arrays.equals(
                                        distantVoxels,
                                        payload.copyCanonicalVoxels()))));
    }

    @ParameterizedTest(name = "post-publication-extension-{0}")
    @MethodSource("postPublicationEvolutionTargets")
    void publishedMigrationAcceptsCodecValidOptionalDetailExtensionEvolution(
            PostPublicationEvolutionTarget target) throws Exception {
        MigrationFixture fixture = prepare("later-extension-" + target, THREE_CHUNKS);
        Phase14MigrationResult migrated = migrator(fixture.root(), ignored -> {})
                .migrate(SAVE_ID);
        assertEquals(Phase14MigrationResult.Status.MIGRATED, migrated.status());

        StreamedChunkStore store = streamedStore(fixture.root(), new JdkSaveFileOperations());
        StreamedChunkStore.BatchReadResult imported = store.readModifiedBatch(
                SAVE_ID, migrated.validatedIndex().orElseThrow());
        StreamedChunkPayload floor = imported.payloads().get(0);
        assertTrue(floor.extensions().isEmpty(),
                "the initial migration floor must remain extension-free");
        byte[] detailBytes = new byte[] {4, 1, 5, 9, 2, 6};
        StreamedChunkPayload.ExtensionDescriptor detail =
                new StreamedChunkPayload.ExtensionDescriptor(
                        SaveSectionId.DETAIL_BLOCKS, 1, false, detailBytes);
        ChunkKey evolvedKey = target == PostPublicationEvolutionTarget.IMPORTED
                ? floor.key()
                : new ChunkKey(8_192, -8_193);
        byte[] evolvedVoxels = target == PostPublicationEvolutionTarget.IMPORTED
                ? floor.copyCanonicalVoxels()
                : new byte[16 * floor.worldHeight() * 16];
        evolvedVoxels[target == PostPublicationEvolutionTarget.IMPORTED
                ? 0
                : evolvedVoxels.length - 1] ^= 0x2a;
        StreamedChunkPayload evolved = new StreamedChunkPayload(
                SAVE_ID,
                evolvedKey,
                floor.generatorVersion(),
                target == PostPublicationEvolutionTarget.IMPORTED
                        ? floor.baseHash()
                        : generatedBaseHash(evolvedKey),
                target == PostPublicationEvolutionTarget.IMPORTED
                        ? floor.revision() + 1L
                        : 1L,
                target == PostPublicationEvolutionTarget.IMPORTED
                        ? floor.revision()
                        : 0L,
                true,
                floor.worldHeight(),
                evolvedVoxels,
                List.of(detail));
        StreamedChunkStore.CommitResult committed = store.commitModified(
                new StreamedChunkStore.ExactChunkCapture(evolved, () -> true),
                new StreamedChunkStore.WorldItemHibernatePayload(
                        new byte[0], () -> true));
        assertEquals(StreamedChunkStore.CommitResult.Status.SUCCESS, committed.status());

        SaveRepository reopened = SaveRepository.open(
                fixture.root(), reader(), new JdkSaveFileOperations());
        SaveArchiveReadResult loaded = reopened.load(SAVE_ID);
        List<SaveSummary> rows = new FileSaveCatalog(reopened).summaries();
        Phase14MigrationResult repeated = reopened.migratePhase14(SAVE_ID);
        StreamedChunkStore.CurrentAuthorityReadResult authority = streamedStore(
                fixture.root(), new JdkSaveFileOperations())
                .readCurrentAuthority(SAVE_ID);
        StreamedChunkPayload reread = authority.payloads().stream()
                .filter(payload -> payload.key().equals(evolvedKey))
                .findFirst()
                .orElseThrow();

        assertAll(
                () -> assertEquals(SaveArchiveReadResult.Status.VALID, loaded.status()),
                () -> assertEquals(1, rows.size()),
                () -> assertEquals(SaveSummary.Health.VALID, rows.get(0).health()),
                () -> assertEquals(
                        SaveFormatVersion.STREAMED_CHUNKS,
                        rows.get(0).formatVersion().orElseThrow()),
                () -> assertEquals(
                        Phase14MigrationResult.Status.NOT_REQUIRED, repeated.status()),
                () -> assertEquals(
                        StreamedChunkStore.CurrentAuthorityReadResult.Status.FOUND,
                        authority.status()),
                () -> assertEquals(1, reread.extensions().size()),
                () -> assertEquals(
                        SaveSectionId.DETAIL_BLOCKS,
                        reread.extensions().get(0).sectionId()),
                () -> assertFalse(reread.extensions().get(0).required()),
                () -> assertArrayEquals(
                        detailBytes, reread.extensions().get(0).copyBytes()));
    }

    @ParameterizedTest(name = "published-{0}-corruption-blocks-v1-fallback")
    @MethodSource("publishedCorruptions")
    void anyInvalidPublishedV2AuthorityIsBlockingAndNeverFallsBackToReadableV1(
            PublishedCorruption corruption) throws Exception {
        MigrationFixture fixture = prepare("published-corrupt-" + corruption, THREE_CHUNKS);
        Phase14MigrationResult migrated = migrator(fixture.root(), ignored -> {})
                .migrate(SAVE_ID);
        assertEquals(Phase14MigrationResult.Status.MIGRATED, migrated.status());

        corruption.apply(fixture.root().resolve(SAVE_ID.value()));

        SaveRepository reopened = SaveRepository.open(
                fixture.root(), reader(), new JdkSaveFileOperations());
        SaveArchiveReadResult loaded = reopened.load(SAVE_ID);
        List<SaveSummary> rows = new FileSaveCatalog(reopened).summaries();
        Phase14MigrationResult retried = reopened.migratePhase14(SAVE_ID);

        assertAll(
                () -> assertEquals(SaveArchiveReadResult.Status.CORRUPT, loaded.status()),
                () -> assertEquals(1, rows.size()),
                () -> assertEquals(SaveSummary.Health.CORRUPT, rows.get(0).health()),
                () -> assertFalse(rows.get(0).loadEnabled()),
                () -> assertEquals(
                        Phase14MigrationResult.Status.BLOCKING_FAILURE,
                        retried.status()),
                () -> assertArrayEquals(
                        fixture.exactV1(), Files.readAllBytes(fixture.current()),
                        "a published-v2 fault must not silently reinstate current v1"));
    }

    @ParameterizedTest(name = "lost-publication-side-files-{0}")
    @MethodSource("lostPublicationSideFiles")
    void durableTask4MigrationHistoryPreventsV1FallbackWhenPublicationSideFilesDisappear(
            LostPublicationSideFiles damage) throws Exception {
        MigrationFixture fixture = prepare("lost-publication-side-files-" + damage,
                THREE_CHUNKS);
        assertEquals(
                Phase14MigrationResult.Status.MIGRATED,
                migrator(fixture.root(), ignored -> {}).migrate(SAVE_ID).status());
        Path world = fixture.root().resolve(SAVE_ID.value());
        StreamedChunkStore.CurrentAuthorityReadResult beforeDamage = streamedStore(
                fixture.root(), new JdkSaveFileOperations())
                .readCurrentAuthority(SAVE_ID);
        assertEquals(
                StreamedChunkStore.CurrentAuthorityReadResult.Status.FOUND,
                beforeDamage.status(),
                "the Task4 v2 authority must remain intact for this attack");

        damage.apply(world);

        StreamedChunkStore.CurrentAuthorityReadResult afterDamage = streamedStore(
                fixture.root(), new JdkSaveFileOperations())
                .readCurrentAuthority(SAVE_ID);
        SaveRepository reopened = SaveRepository.open(
                fixture.root(), reader(), new JdkSaveFileOperations());
        SaveArchiveReadResult loaded = reopened.load(SAVE_ID);
        List<SaveSummary> rows = new FileSaveCatalog(reopened).summaries();
        Phase14MigrationResult retried = reopened.migratePhase14(SAVE_ID);

        assertAll(
                () -> assertEquals(
                        StreamedChunkStore.CurrentAuthorityReadResult.Status.FOUND,
                        afterDamage.status(),
                        "side-file damage must not alter the Task4 v2 authority"),
                () -> assertEquals(SaveArchiveReadResult.Status.CORRUPT, loaded.status()),
                () -> assertEquals(1, rows.size()),
                () -> assertEquals(SaveSummary.Health.CORRUPT, rows.get(0).health()),
                () -> assertFalse(rows.get(0).loadEnabled()),
                () -> assertEquals(
                        Phase14MigrationResult.Status.BLOCKING_FAILURE,
                        retried.status()),
                () -> assertArrayEquals(
                        fixture.exactV1(), Files.readAllBytes(fixture.current()),
                        "lost side files must not erase durable migration history"));
    }

    @ParameterizedTest(name = "no-side-task4-authority-{0}-blocks-v1")
    @MethodSource("degradedTask4AuthoritiesWithoutSides")
    void degradedTask4AuthorityWithoutSideFilesNeverRestoresV1Fallback(
            DegradedTask4AuthorityWithoutSides damage) throws Exception {
        MigrationFixture fixture = prepare("no-side-task4-" + damage, THREE_CHUNKS);
        assertEquals(
                Phase14MigrationResult.Status.MIGRATED,
                migrator(fixture.root(), ignored -> {}).migrate(SAVE_ID).status());
        Path world = fixture.root().resolve(SAVE_ID.value());
        deleteMigrationSideFiles(world);
        damage.apply(fixture.root(), world);

        Phase14SaveMigrator.PublicationObservation publication =
                Phase14SaveMigrator.observePublished(
                        fixture.root(), SAVE_ID, reader(), new JdkSaveFileOperations());
        SaveRepository repository = SaveRepository.open(
                fixture.root(), reader(), new JdkSaveFileOperations());
        SaveArchiveReadResult loaded = repository.load(SAVE_ID);
        List<SaveSummary> rows = new FileSaveCatalog(repository).summaries();
        Phase14MigrationResult retried = repository.migratePhase14(SAVE_ID);

        assertAll(
                () -> assertEquals(
                        Phase14SaveMigrator.PublicationStatus.PUBLISHED_INVALID,
                        publication.status()),
                () -> assertEquals(SaveArchiveReadResult.Status.CORRUPT, loaded.status()),
                () -> assertEquals(1, rows.size()),
                () -> assertEquals(SaveSummary.Health.CORRUPT, rows.get(0).health()),
                () -> assertFalse(rows.get(0).loadEnabled()),
                () -> assertEquals(
                        Phase14MigrationResult.Status.BLOCKING_FAILURE,
                        retried.status()),
                () -> assertArrayEquals(
                        fixture.exactV1(), Files.readAllBytes(fixture.current()),
                        "degraded Task4 history must not mutate or reinstate v1"));
    }

    @Test
    void repositoryDeleteRemovesTheExactBoundedMigratedV2Tree() throws Exception {
        MigrationFixture fixture = prepare("delete-migrated", THREE_CHUNKS);
        assertEquals(
                Phase14MigrationResult.Status.MIGRATED,
                migrator(fixture.root(), ignored -> {}).migrate(SAVE_ID).status());
        SaveRepository repository = SaveRepository.open(
                fixture.root(), reader(), new JdkSaveFileOperations());

        SaveDeleteResult deleted = repository.delete(SAVE_ID);

        assertAll(
                () -> assertEquals(SaveDeleteResult.Status.SUCCESS, deleted.status()),
                () -> assertFalse(Files.exists(
                        fixture.root().resolve(SAVE_ID.value()),
                        LinkOption.NOFOLLOW_LINKS)),
                () -> assertTrue(new FileSaveCatalog(repository).summaries().isEmpty()));
    }

    @Test
    void repositoryDeleteAcceptsOwnedOrphanPayloadPoolAfterExactRemoval()
            throws Exception {
        MigrationFixture fixture = prepare("delete-owned-orphan", THREE_CHUNKS);
        assertEquals(Phase14MigrationResult.Status.MIGRATED,
                migrator(fixture.root(), ignored -> {}).migrate(SAVE_ID).status());
        StreamedChunkStore store = streamedStore(
                fixture.root(), new JdkSaveFileOperations());
        StreamedChunkPayload template = store.readCurrentAuthority(SAVE_ID)
                .payloads().get(0);
        ChunkKey orphanKey = new ChunkKey(4_321, -4_321);
        StreamedChunkPayload orphan = new StreamedChunkPayload(
                SAVE_ID, orphanKey, template.generatorVersion(),
                generatedBaseHash(orphanKey), 1L, 0L, true,
                template.worldHeight(), template.copyCanonicalVoxels(), List.of());
        assertEquals(StreamedChunkStore.CommitResult.Status.SUCCESS,
                store.commitTransaction(new StreamedPersistenceTransaction(
                        List.of(new StreamedChunkMutation.Upsert(
                                new StreamedChunkStore.ExactChunkCapture(
                                        orphan, () -> true))),
                        List.of(), () -> true)).status());
        StreamedChunkIndex.Entry entry = store.readCurrentAuthority(SAVE_ID)
                .index().orElseThrow().entry(orphanKey).orElseThrow();
        assertEquals(StreamedChunkStore.CommitResult.Status.SUCCESS,
                store.commitTransaction(new StreamedPersistenceTransaction(
                        List.of(new StreamedChunkMutation.Remove(
                                orphanKey, entry.revision(), entry.payloadHash())),
                        List.of(), () -> true)).status());

        SaveRepository repository = SaveRepository.open(
                fixture.root(), reader(), new JdkSaveFileOperations());
        SaveDeleteResult deleted = repository.delete(SAVE_ID);

        assertEquals(SaveDeleteResult.Status.SUCCESS, deleted.status());
        assertFalse(Files.exists(fixture.root().resolve(SAVE_ID.value()),
                LinkOption.NOFOLLOW_LINKS));
    }

    @Test
    void repositoryDeleteRejectsForeignV2EntriesAndHardLinkedPayloadsWithoutTouchingSentinels()
            throws Exception {
        MigrationFixture foreignFixture = prepare("delete-foreign", THREE_CHUNKS);
        assertEquals(
                Phase14MigrationResult.Status.MIGRATED,
                migrator(foreignFixture.root(), ignored -> {}).migrate(SAVE_ID).status());
        Path foreign = foreignFixture.root().resolve(SAVE_ID.value())
                .resolve("streamed-chunks").resolve("foreign.keep");
        byte[] foreignBytes = "foreign-sentinel".getBytes(StandardCharsets.UTF_8);
        Files.write(foreign, foreignBytes);
        SaveDeleteResult foreignDelete = SaveRepository.open(
                foreignFixture.root(), reader(), new JdkSaveFileOperations())
                .delete(SAVE_ID);
        assertAll(
                () -> assertEquals(
                        SaveDeleteResult.Status.UNSAFE_TARGET, foreignDelete.status()),
                () -> assertArrayEquals(foreignBytes, Files.readAllBytes(foreign)));

        MigrationFixture linkFixture = prepare("delete-hardlink", THREE_CHUNKS);
        assertEquals(
                Phase14MigrationResult.Status.MIGRATED,
                migrator(linkFixture.root(), ignored -> {}).migrate(SAVE_ID).status());
        Path external = tempDir.resolve("external-payload-sentinel.bin");
        byte[] externalBytes = "external-payload-sentinel"
                .getBytes(StandardCharsets.UTF_8);
        Files.write(external, externalBytes);
        Path payload = firstNonEmptyPayload(
                linkFixture.root().resolve(SAVE_ID.value()));
        Files.delete(payload);
        Files.createLink(payload, external);
        SaveDeleteResult linkDelete = SaveRepository.open(
                linkFixture.root(), reader(), new JdkSaveFileOperations())
                .delete(SAVE_ID);
        assertAll(
                () -> assertEquals(
                        SaveDeleteResult.Status.UNSAFE_TARGET, linkDelete.status()),
                () -> assertArrayEquals(externalBytes, Files.readAllBytes(external)),
                () -> assertTrue(Files.exists(payload, LinkOption.NOFOLLOW_LINKS)));
    }

    @ParameterizedTest(name = "delete-rejects-unproven-{0}")
    @MethodSource("unprovenDeleteEntries")
    void repositoryDeleteRejectsUnprovenCanonicalNamesAndLeavesExactBytesUntouched(
            UnprovenDeleteEntry attack) throws Exception {
        MigrationFixture fixture = prepare("delete-unproven-" + attack, THREE_CHUNKS);
        assertEquals(
                Phase14MigrationResult.Status.MIGRATED,
                migrator(fixture.root(), ignored -> {}).migrate(SAVE_ID).status());
        Path world = fixture.root().resolve(SAVE_ID.value());
        TouchedSentinel sentinel = attack.apply(world);
        SaveRepository repository = SaveRepository.open(
                fixture.root(), reader(), new JdkSaveFileOperations());

        SaveDeleteResult deleted = repository.delete(SAVE_ID);

        assertAll(
                () -> assertEquals(
                        SaveDeleteResult.Status.UNSAFE_TARGET, deleted.status()),
                () -> assertTrue(Files.exists(world, LinkOption.NOFOLLOW_LINKS)),
                () -> assertTrue(Files.exists(
                        sentinel.path(), LinkOption.NOFOLLOW_LINKS)),
                () -> assertArrayEquals(
                        sentinel.exactBytes(), Files.readAllBytes(sentinel.path()),
                        "delete validation must not mutate an unproven entry"));
    }

    @ParameterizedTest(name = "anchored-{0}-replacement")
    @MethodSource("anchoredReplacements")
    void migrationRejectsRootWorldOrMarkerReplacementAfterInitialization(
            AnchoredReplacement replacement) throws Exception {
        MigrationFixture fixture = prepare("anchored-replacement-" + replacement,
                THREE_CHUNKS);
        Phase14SaveMigrator migrator = migrator(
                fixture.root(),
                checkpoint -> {
                    if (checkpoint.stage()
                                    == Phase14SaveMigrator.CheckpointStage.V2_MANIFEST
                            && checkpoint.side()
                                    == Phase14SaveMigrator.CheckpointSide.BEFORE) {
                        replacement.apply(fixture.root());
                    }
                });

        Phase14MigrationResult result = migrator.migrate(SAVE_ID);

        assertTrue(
                result.status() == Phase14MigrationResult.Status.FAILED
                        || result.status()
                                == Phase14MigrationResult.Status.BLOCKING_FAILURE,
                "an authority path replaced after initialization must fail closed");
        SaveRepository reopened = SaveRepository.open(
                fixture.root(), reader(), new JdkSaveFileOperations());
        assertEquals(
                SaveFormatVersion.CURRENT,
                new FileSaveCatalog(reopened).summaries().get(0)
                        .formatVersion().orElseThrow());
    }

    @Test
    void migrationRejectsUnavailableRootOrWorldDirectoryIdentityBeforePublication()
            throws Exception {
        MigrationFixture fixture = prepare("null-directory-identity", THREE_CHUNKS);
        SaveFileOperations files = new NullDirectoryIdentityOperations();
        Phase14SaveMigrator migrator = new Phase14SaveMigrator(
                fixture.root(), reader(), STREAMED_CODEC, INDEX_CODEC, files);

        Phase14MigrationResult result = migrator.migrate(SAVE_ID);

        assertTrue(
                result.status() == Phase14MigrationResult.Status.FAILED
                        || result.status()
                                == Phase14MigrationResult.Status.BLOCKING_FAILURE,
                "migration must not publish without anchored provider directory identities");
        assertEquals(
                SaveFormatVersion.CURRENT,
                new FileSaveCatalog(SaveRepository.open(
                        fixture.root(), reader(), new JdkSaveFileOperations()))
                        .summaries().get(0).formatVersion().orElseThrow());
    }

    @ParameterizedTest(name = "published-payload-{0}-must-bind-to-v1")
    @MethodSource("semanticPayloadAttacks")
    void publishedMigrationRejectsInternallyConsistentPayloadThatViolatesV1BaseContract(
            SemanticPayloadAttack attack) throws Exception {
        MigrationFixture fixture = prepare("semantic-attack-" + attack, THREE_CHUNKS);
        assertEquals(
                Phase14MigrationResult.Status.MIGRATED,
                migrator(fixture.root(), ignored -> {}).migrate(SAVE_ID).status());

        rewritePublishedAuthority(
                fixture.root(), attack::mutate);
        assertInternallyConsistentPublishedEnvelope(fixture.root());

        SaveRepository reopened = SaveRepository.open(
                fixture.root(), reader(), new JdkSaveFileOperations());
        SaveArchiveReadResult loaded = reopened.load(SAVE_ID);
        List<SaveSummary> rows = new FileSaveCatalog(reopened).summaries();
        assertAll(
                () -> assertEquals(SaveArchiveReadResult.Status.CORRUPT, loaded.status()),
                () -> assertEquals(1, rows.size()),
                () -> assertEquals(SaveSummary.Health.CORRUPT, rows.get(0).health()),
                () -> assertFalse(rows.get(0).loadEnabled()));
    }

    @Test
    void migrationCompatibilityProbeReadsOnlyCrashSafeIndexEnvelopes()
            throws Exception {
        MigrationFixture fixture = prepare("compatibility-probe", THREE_CHUNKS);
        assertEquals(
                Phase14MigrationResult.Status.MIGRATED,
                migrator(fixture.root(), ignored -> {}).migrate(SAVE_ID).status());
        CountingPayloadReadsOperations files = new CountingPayloadReadsOperations();
        StreamedChunkStore store = streamedStore(fixture.root(), files);
        files.resetPayloadReads();

        StreamedChunkStore.MigrationCompatibilityReadResult result =
                store.readMigrationCompatibility(SAVE_ID);

        assertAll(
                () -> assertEquals(
                        StreamedChunkStore.MigrationCompatibilityReadResult.Status.FOUND,
                        result.status()),
                () -> assertEquals(
                        sha256Hex(fixture.exactV1()),
                        result.compatibility()
                                .orElseThrow()
                                .sourceArchiveSha256()),
                () -> assertEquals(
                        0,
                        files.payloadReads(),
                        "compatibility probing must not validate streamed payloads"));
    }

    @Test
    void unpublishedInitializedCandidateObservationDoesNotReadStagedPayloads()
            throws Exception {
        MigrationFixture fixture = prepare(
                "unpublished-observation-skips-staged-payloads", THREE_CHUNKS);
        PublicationIndexFailure injected = new PublicationIndexFailure();
        Phase14MigrationResult interrupted = new Phase14SaveMigrator(
                        fixture.root(),
                        reader(),
                        STREAMED_CODEC,
                        INDEX_CODEC,
                        new PublicationIndexFaultOperations(
                                Task4PublicationSlot.RECOVERY,
                                PublicationIndexFaultStage.BEFORE_WRITE,
                                injected))
                .migrate(SAVE_ID);
        CountingPayloadReadsOperations files = new CountingPayloadReadsOperations();

        Phase14SaveMigrator.PublicationObservation observation =
                Phase14SaveMigrator.observePublished(
                        fixture.root(), SAVE_ID, reader(), files);

        assertAll(
                () -> assertTrue(
                        interrupted.status() == Phase14MigrationResult.Status.FAILED
                                || interrupted.status()
                                        == Phase14MigrationResult.Status.BLOCKING_FAILURE,
                        "the final publication boundary must be reached"),
                () -> assertEquals(
                        Phase14SaveMigrator.PublicationStatus.UNPUBLISHED,
                        observation.status()),
                () -> assertEquals(
                        0,
                        files.payloadReads(),
                        "an invisible candidate cannot block legacy authority observation"));
    }

    @Test
    void publishedMigrationRejectsSameRevisionPersistedRevisionTamper()
            throws Exception {
        MigrationFixture fixture = prepare("same-revision-persisted-tamper", THREE_CHUNKS);
        assertEquals(
                Phase14MigrationResult.Status.MIGRATED,
                migrator(fixture.root(), ignored -> {}).migrate(SAVE_ID).status());

        rewriteCurrentAuthorityOnly(fixture.root(), source ->
                new StreamedChunkPayload(
                        source.saveGameId(),
                        source.key(),
                        source.generatorVersion(),
                        source.baseHash(),
                        source.revision(),
                        source.revision(),
                        source.modified(),
                        source.worldHeight(),
                        source.copyCanonicalVoxels(),
                        source.extensions()));
        StreamedChunkStore.CurrentAuthorityReadResult current = streamedStore(
                fixture.root(), new JdkSaveFileOperations())
                .readCurrentAuthority(SAVE_ID);
        assertEquals(
                StreamedChunkStore.CurrentAuthorityReadResult.Status.FOUND,
                current.status(),
                "the tamper must remain internally consistent to Task4");

        SaveRepository reopened = SaveRepository.open(
                fixture.root(), reader(), new JdkSaveFileOperations());
        SaveArchiveReadResult loaded = reopened.load(SAVE_ID);
        List<SaveSummary> rows = new FileSaveCatalog(reopened).summaries();
        Phase14MigrationResult repeated = reopened.migratePhase14(SAVE_ID);

        assertAll(
                () -> assertEquals(SaveArchiveReadResult.Status.CORRUPT, loaded.status()),
                () -> assertEquals(1, rows.size()),
                () -> assertEquals(SaveSummary.Health.CORRUPT, rows.get(0).health()),
                () -> assertFalse(rows.get(0).loadEnabled()),
                () -> assertEquals(
                        Phase14MigrationResult.Status.BLOCKING_FAILURE,
                        repeated.status()));
    }

    @ParameterizedTest(name = "side-init-{0}-{1}-reopens-v1-and-retries")
    @MethodSource("sideInitializationFaults")
    void sideSlotInitializationHardFaultReopensExactV1AndRetryCompletes(
            SideInitializationSlot slot,
            SideInitializationFaultStage stage) throws Exception {
        MigrationFixture fixture = prepare(
                "side-init-" + slot + "-" + stage, THREE_CHUNKS);
        SideInitializationFailure injected = new SideInitializationFailure();
        SideInitializationFaultOperations files =
                new SideInitializationFaultOperations(slot, stage, injected);

        Phase14MigrationResult interrupted = new Phase14SaveMigrator(
                        fixture.root(), reader(), STREAMED_CODEC, INDEX_CODEC, files)
                .migrate(SAVE_ID);
        SaveRepository reopened = SaveRepository.open(
                fixture.root(), reader(), new JdkSaveFileOperations());
        SaveArchiveReadResult loaded = reopened.load(SAVE_ID);
        List<SaveSummary> rows = new FileSaveCatalog(reopened).summaries();
        Phase14MigrationResult retried = reopened.migratePhase14(SAVE_ID);

        assertAll(
                () -> assertTrue(
                        interrupted.status() == Phase14MigrationResult.Status.FAILED
                                || interrupted.status()
                                        == Phase14MigrationResult.Status.BLOCKING_FAILURE),
                () -> assertSame(
                        injected,
                        primaryCause(interrupted.diagnostics().get(0))),
                () -> assertEquals(SaveArchiveReadResult.Status.VALID, loaded.status()),
                () -> assertEquals(1, rows.size()),
                () -> assertEquals(SaveSummary.Health.VALID, rows.get(0).health()),
                () -> assertEquals(
                        SaveFormatVersion.CURRENT,
                        rows.get(0).formatVersion().orElseThrow()),
                () -> assertArrayEquals(
                        fixture.exactV1(), Files.readAllBytes(fixture.current())),
                () -> assertEquals(
                        Phase14MigrationResult.Status.MIGRATED,
                        retried.status(),
                        () -> diagnosticSummary(retried.diagnostics())));
    }

    @Test
    void task4EmptyAuthorityIsDurableBeforeTheFirstMarkerSlotCreation()
            throws Exception {
        MigrationFixture fixture = prepare("task4-before-side", THREE_CHUNKS);
        Task4BeforeSideObservationOperations files =
                new Task4BeforeSideObservationOperations(fixture.root());

        Phase14MigrationResult migrated = new Phase14SaveMigrator(
                        fixture.root(), reader(), STREAMED_CODEC, INDEX_CODEC, files)
                .migrate(SAVE_ID);

        assertAll(
                () -> assertEquals(
                        Phase14MigrationResult.Status.MIGRATED,
                        migrated.status(),
                        () -> diagnosticSummary(migrated.diagnostics())),
                () -> assertTrue(
                        files.observedFirstSideCreation(),
                        "the ordering probe must observe the first marker creation"),
                () -> assertTrue(
                        files.task4WasDurableBeforeSide(),
                        "complete proof-free Task4 dual authority must precede every marker slot"));
    }

    @ParameterizedTest(name = "task4-init-{0}-reopens-v1-and-retries")
    @MethodSource("task4ConstructorInitializationFaults")
    void task4ConstructorInitializationHardFaultReopensExactV1AndRetryCompletes(
            Task4ConstructorInitializationBoundary boundary) throws Exception {
        MigrationFixture fixture = prepare("task4-init-" + boundary, THREE_CHUNKS);
        Task4InitializationFailure injected = new Task4InitializationFailure();
        Task4ConstructorInitializationFaultOperations files =
                new Task4ConstructorInitializationFaultOperations(
                        fixture.root(), boundary, injected);

        Phase14MigrationResult interrupted = new Phase14SaveMigrator(
                        fixture.root(), reader(), STREAMED_CODEC, INDEX_CODEC, files)
                .migrate(SAVE_ID);
        SaveRepository reopened = SaveRepository.open(
                fixture.root(), reader(), new JdkSaveFileOperations());
        SaveArchiveReadResult loaded = reopened.load(SAVE_ID);
        List<SaveSummary> rows = new FileSaveCatalog(reopened).summaries();
        Phase14MigrationResult retried = reopened.migratePhase14(SAVE_ID);

        assertAll(
                () -> assertTrue(
                        interrupted.status() == Phase14MigrationResult.Status.FAILED
                                || interrupted.status()
                                        == Phase14MigrationResult.Status.BLOCKING_FAILURE,
                        "the selected Task4 constructor boundary must be reached"),
                () -> assertFalse(interrupted.diagnostics().isEmpty()),
                () -> assertSame(
                        injected,
                        primaryCause(interrupted.diagnostics().get(0))),
                () -> assertEquals(SaveArchiveReadResult.Status.VALID, loaded.status()),
                () -> assertEquals(1, rows.size()),
                () -> assertEquals(SaveSummary.Health.VALID, rows.get(0).health()),
                () -> assertEquals(
                        SaveFormatVersion.CURRENT,
                        rows.get(0).formatVersion().orElseThrow()),
                () -> assertArrayEquals(
                        fixture.exactV1(), Files.readAllBytes(fixture.current())),
                () -> assertEquals(
                        Phase14MigrationResult.Status.MIGRATED,
                        retried.status(),
                        () -> diagnosticSummary(retried.diagnostics())));
    }

    @ParameterizedTest(name = "initializing-intent-{0}-{1}-precedes-task4")
    @MethodSource("initializingIntentFaults")
    void initializingIntentHardFaultLeavesNoTask4EvidenceAndRetryCompletes(
            InitializingIntentSlot slot,
            InitializingIntentFaultStage stage) throws Exception {
        MigrationFixture fixture = prepare(
                "initializing-intent-" + slot + "-" + stage, THREE_CHUNKS);
        InitializingIntentFailure injected = new InitializingIntentFailure();
        InitializingIntentFaultOperations files = new InitializingIntentFaultOperations(
                fixture.root(), slot, stage, injected);

        Phase14MigrationResult interrupted = new Phase14SaveMigrator(
                        fixture.root(), reader(), STREAMED_CODEC, INDEX_CODEC, files)
                .migrate(SAVE_ID);
        SaveRepository reopened = SaveRepository.open(
                fixture.root(), reader(), new JdkSaveFileOperations());
        SaveArchiveReadResult loaded = reopened.load(SAVE_ID);
        List<SaveSummary> rows = new FileSaveCatalog(reopened).summaries();
        Phase14MigrationResult retried = reopened.migratePhase14(SAVE_ID);

        assertAll(
                () -> assertTrue(
                        interrupted.status() == Phase14MigrationResult.Status.FAILED
                                || interrupted.status()
                                        == Phase14MigrationResult.Status.BLOCKING_FAILURE,
                        "the selected initializing-intent boundary must be reached"),
                () -> assertFalse(interrupted.diagnostics().isEmpty()),
                () -> assertSame(
                        injected,
                        primaryCause(interrupted.diagnostics().get(0))),
                () -> assertTrue(files.task4WasAbsentAtFault(),
                        "no Task4 mutation may precede the initializing intent quorum"),
                () -> assertEquals(SaveArchiveReadResult.Status.VALID, loaded.status()),
                () -> assertEquals(1, rows.size()),
                () -> assertEquals(SaveSummary.Health.VALID, rows.get(0).health()),
                () -> assertEquals(
                        SaveFormatVersion.CURRENT,
                        rows.get(0).formatVersion().orElseThrow()),
                () -> assertArrayEquals(
                        fixture.exactV1(), Files.readAllBytes(fixture.current())),
                () -> assertEquals(
                        Phase14MigrationResult.Status.MIGRATED,
                        retried.status(),
                        () -> diagnosticSummary(retried.diagnostics())));
    }

    @Test
    void initializingIntentQuorumIsDurableBeforeTheFirstTask4Mutation()
            throws Exception {
        MigrationFixture fixture = prepare("intent-before-task4", THREE_CHUNKS);
        InitializingIntentBeforeTask4ObservationOperations files =
                new InitializingIntentBeforeTask4ObservationOperations(fixture.root());

        Phase14MigrationResult migrated = new Phase14SaveMigrator(
                        fixture.root(), reader(), STREAMED_CODEC, INDEX_CODEC, files)
                .migrate(SAVE_ID);

        assertAll(
                () -> assertEquals(
                        Phase14MigrationResult.Status.MIGRATED,
                        migrated.status(),
                        () -> diagnosticSummary(migrated.diagnostics())),
                () -> assertTrue(
                        files.observedFirstTask4Mutation(),
                        "the ordering probe must observe the first Task4 mutation"),
                () -> assertTrue(
                        files.equalNonEmptyIntentQuorumBeforeTask4(),
                        "equal nonempty dual INITIALIZING intent must be durable first"));
    }

    private static void assertInternallyConsistentPublishedEnvelope(Path root)
            throws IOException {
        Path world = root.resolve(SAVE_ID.value());
        byte[] markerA = Files.readAllBytes(world.resolve("streamed-migration.a.v2"));
        byte[] markerB = Files.readAllBytes(world.resolve("streamed-migration.b.v2"));
        assertAll(
                () -> assertArrayEquals(markerA, markerB),
                () -> assertPublishedFloorQuorum(world, markerA));
        StreamedChunkStore.CurrentAuthorityReadResult current = streamedStore(
                root, new JdkSaveFileOperations()).readCurrentAuthority(SAVE_ID);
        assertEquals(
                StreamedChunkStore.CurrentAuthorityReadResult.Status.FOUND,
                current.status(),
                "the attack must pass marker/floor and Task4 internal validation");
    }

    private static void assertPublishedFloorQuorum(Path world, byte[] markerBytes)
            throws IOException {
        byte[] a = Files.readAllBytes(
                world.resolve("streamed-migration.published.a.v2"));
        byte[] b = Files.readAllBytes(
                world.resolve("streamed-migration.published.b.v2"));
        assertArrayEquals(a, b);
        String[] parts = new String(a, StandardCharsets.UTF_8).split("\\|", -1);
        assertAll(
                () -> assertEquals(8, parts.length),
                () -> assertEquals("GaiaLegacy.StreamedMigrationPublication", parts[0]),
                () -> assertEquals("2", parts[1]),
                () -> assertEquals("PUBLISHED", parts[2]),
                () -> assertEquals(SAVE_ID.value(), parts[3]),
                () -> assertTrue(parts[4].matches("[0-9a-f]{64}")),
                () -> assertTrue(parts[5].matches("[0-9a-f]{32}")),
                () -> assertEquals(sha256Hex(markerBytes), parts[6]),
                () -> assertEquals(
                        sha256Hex(String.join("|", java.util.Arrays.copyOf(parts, 7))
                                .getBytes(StandardCharsets.UTF_8)),
                        parts[7]));
    }

    private static byte[] rewritePublishedFloorMarkerHash(
            byte[] existingFloor, byte[] markerBytes) {
        String[] parts = new String(existingFloor, StandardCharsets.UTF_8)
                .split("\\|", -1);
        if (parts.length != 8 || !parts[2].equals("PUBLISHED")) {
            throw new IllegalArgumentException("test fixture publication floor is invalid");
        }
        parts[6] = sha256Hex(markerBytes);
        String prefix = String.join("|", java.util.Arrays.copyOf(parts, 7));
        parts[7] = sha256Hex(prefix.getBytes(StandardCharsets.UTF_8));
        return String.join("|", parts).getBytes(StandardCharsets.UTF_8);
    }

    @ParameterizedTest(name = "payload-{0}-ordinal-{1}")
    @MethodSource("payloadOperationFaults")
    void realPayloadWriteOrForceFaultAtFirstMiddleOrLastReopensOnlyOldV1OrCompleteV2(
            PayloadFaultStage stage,
            int ordinal) throws Exception {
        MigrationFixture fixture = prepare(
                "payload-fault-" + stage + "-" + ordinal, THREE_CHUNKS);
        PayloadOperationFailure injected = new PayloadOperationFailure();
        PayloadFaultOperations files = new PayloadFaultOperations(
                stage, ordinal, injected);
        Phase14SaveMigrator migrator = new Phase14SaveMigrator(
                fixture.root(),
                reader(),
                STREAMED_CODEC,
                INDEX_CODEC,
                files);

        Phase14MigrationResult result = migrator.migrate(SAVE_ID);

        assertTrue(
                result.status() == Phase14MigrationResult.Status.FAILED
                        || result.status()
                                == Phase14MigrationResult.Status.BLOCKING_FAILURE);
        assertFalse(result.diagnostics().isEmpty());
        assertSame(injected, primaryCause(result.diagnostics().get(0)));
        assertExactOldOrCompleteNew(fixture);
    }

    @ParameterizedTest(name = "publication-floor-{0}-{1}")
    @MethodSource("publicationFloorFaults")
    void publicationFloorWriteOrForceFaultNeverCreatesAmbiguousFallbackState(
            char slot,
            FloorFaultStage stage) throws Exception {
        MigrationFixture fixture = prepare(
                "floor-fault-" + slot + "-" + stage, THREE_CHUNKS);
        FloorOperationFailure injected = new FloorOperationFailure();
        FloorFaultOperations files = new FloorFaultOperations(slot, stage, injected);
        Phase14MigrationResult result = new Phase14SaveMigrator(
                fixture.root(), reader(), STREAMED_CODEC, INDEX_CODEC, files)
                .migrate(SAVE_ID);

        assertTrue(
                result.status() == Phase14MigrationResult.Status.FAILED
                        || result.status()
                                == Phase14MigrationResult.Status.BLOCKING_FAILURE,
                "the selected publication-floor fault must be reached");
        assertFalse(result.diagnostics().isEmpty());
        assertSame(injected, primaryCause(result.diagnostics().get(0)));
        assertExactOldOrCompleteNew(fixture);
    }

    @ParameterizedTest(name = "task4-publication-{0}-{1}")
    @MethodSource("task4PublicationFaults")
    void task4MigrationPublicationWriteOrForceFaultReopensOnlyOldV1OrCompleteV2(
            Task4PublicationSlot slot,
            PublicationIndexFaultStage stage) throws Exception {
        MigrationFixture fixture = prepare(
                "task4-publication-fault-" + slot + "-" + stage,
                THREE_CHUNKS);
        PublicationIndexFailure injected = new PublicationIndexFailure();
        PublicationIndexFaultOperations files = new PublicationIndexFaultOperations(
                slot, stage, injected);
        Phase14MigrationResult result = new Phase14SaveMigrator(
                fixture.root(), reader(), STREAMED_CODEC, INDEX_CODEC, files)
                .migrate(SAVE_ID);

        assertTrue(
                result.status() == Phase14MigrationResult.Status.FAILED
                        || result.status()
                                == Phase14MigrationResult.Status.BLOCKING_FAILURE,
                "the selected Task4 publication fault must be reached");
        assertFalse(result.diagnostics().isEmpty());
        assertSame(injected, primaryCause(result.diagnostics().get(0)));
        assertExactOldOrCompleteNew(fixture);
    }

    @ParameterizedTest(name = "{0}-{1}-chunk-{2}")
    @MethodSource("representativeFaults")
    void everyRepresentativeBoundaryFailureLeavesExactV1OrCompleteV2Authority(
            Phase14SaveMigrator.CheckpointStage stage,
            Phase14SaveMigrator.CheckpointSide side,
            int chunkOrdinal) throws Exception {
        MigrationFixture fixture = prepare(
                "fault-" + stage + "-" + side + "-" + chunkOrdinal,
                THREE_CHUNKS);
        InjectedMigrationFailure injected = new InjectedMigrationFailure();
        Phase14SaveMigrator migrator = migrator(
                fixture.root(),
                checkpoint -> {
                    if (checkpoint.stage() == stage
                            && checkpoint.side() == side
                            && (stage != Phase14SaveMigrator.CheckpointStage.CHUNK
                                    || checkpoint.chunkOrdinal() == chunkOrdinal)) {
                        throw injected;
                    }
                });

        Phase14MigrationResult result = migrator.migrate(SAVE_ID);

        assertTrue(
                result.status() == Phase14MigrationResult.Status.FAILED
                        || result.status()
                                == Phase14MigrationResult.Status.BLOCKING_FAILURE,
                "a reached migration fault must never report success");
        assertFalse(result.diagnostics().isEmpty());
        assertSame(
                injected,
                primaryCause(result.diagnostics().get(0)),
                () -> diagnosticSummary(result.diagnostics()));
        assertClosedDiagnostics(result.diagnostics(), fixture.root());
        assertExactOldOrCompleteNew(fixture);
    }

    @Test
    void fatalErrorEscapesExactlyAfterReconciliationAndNeverExposesHalfMigration()
            throws Exception {
        MigrationFixture fixture = prepare("fatal", THREE_CHUNKS);
        FatalMigrationError fatal = new FatalMigrationError();
        Phase14SaveMigrator migrator = migrator(
                fixture.root(),
                checkpoint -> {
                    if (checkpoint.stage()
                                    == Phase14SaveMigrator.CheckpointStage.V2_MANIFEST
                            && checkpoint.side()
                                    == Phase14SaveMigrator.CheckpointSide.AFTER) {
                        throw fatal;
                    }
                });

        FatalMigrationError escaped = assertThrows(
                FatalMigrationError.class, () -> migrator.migrate(SAVE_ID));

        assertSame(fatal, escaped);
        assertExactOldOrCompleteNew(fixture);
    }

    private Phase14SaveMigrator migrator(
            Path root, Phase14SaveMigrator.CheckpointHook checkpointHook) {
        SaveFileOperations files = new JdkSaveFileOperations();
        return new Phase14SaveMigrator(
                root,
                reader(),
                STREAMED_CODEC,
                INDEX_CODEC,
                files,
                checkpointHook);
    }

    private static StreamedChunkStore streamedStore(
            Path root, SaveFileOperations files) {
        return new StreamedChunkStore(
                root, SAVE_ID, STREAMED_CODEC, INDEX_CODEC, files);
    }

    private static Stream<PublishedCorruption> publishedCorruptions() {
        return Stream.of(PublishedCorruption.values());
    }

    private static Stream<LostPublicationSideFiles> lostPublicationSideFiles() {
        return Stream.of(LostPublicationSideFiles.values());
    }

    private static Stream<DegradedTask4AuthorityWithoutSides>
            degradedTask4AuthoritiesWithoutSides() {
        return Stream.of(DegradedTask4AuthorityWithoutSides.values());
    }

    private static Stream<UnprovenDeleteEntry> unprovenDeleteEntries() {
        return Stream.of(UnprovenDeleteEntry.values());
    }

    private static Stream<AnchoredReplacement> anchoredReplacements() {
        return Stream.of(AnchoredReplacement.values());
    }

    private static Stream<SemanticPayloadAttack> semanticPayloadAttacks() {
        return Stream.of(SemanticPayloadAttack.values());
    }

    private static Stream<Arguments> payloadOperationFaults() {
        List<Arguments> faults = new ArrayList<>();
        for (PayloadFaultStage stage : PayloadFaultStage.values()) {
            for (int ordinal : List.of(0, 1, 2)) {
                faults.add(Arguments.of(stage, ordinal));
            }
        }
        return faults.stream();
    }

    private static Stream<PostPublicationEvolutionTarget>
            postPublicationEvolutionTargets() {
        return Stream.of(PostPublicationEvolutionTarget.values());
    }

    private static Stream<Arguments> publicationFloorFaults() {
        List<Arguments> faults = new ArrayList<>();
        for (char slot : new char[] {'a', 'b'}) {
            for (FloorFaultStage stage : FloorFaultStage.values()) {
                faults.add(Arguments.of(slot, stage));
            }
        }
        return faults.stream();
    }

    private static Stream<Arguments> task4PublicationFaults() {
        List<Arguments> faults = new ArrayList<>();
        for (Task4PublicationSlot slot : Task4PublicationSlot.values()) {
            for (PublicationIndexFaultStage stage
                    : PublicationIndexFaultStage.values()) {
                faults.add(Arguments.of(slot, stage));
            }
        }
        return faults.stream();
    }

    private static Stream<Arguments> sideInitializationFaults() {
        List<Arguments> faults = new ArrayList<>();
        for (SideInitializationSlot slot : SideInitializationSlot.values()) {
            for (SideInitializationFaultStage stage
                    : SideInitializationFaultStage.values()) {
                faults.add(Arguments.of(slot, stage));
            }
        }
        return faults.stream();
    }

    private static Stream<Task4ConstructorInitializationBoundary>
            task4ConstructorInitializationFaults() {
        return Stream.of(Task4ConstructorInitializationBoundary.values());
    }

    private static Stream<Arguments> initializingIntentFaults() {
        List<Arguments> faults = new ArrayList<>();
        for (InitializingIntentSlot slot : InitializingIntentSlot.values()) {
            for (InitializingIntentFaultStage stage
                    : InitializingIntentFaultStage.values()) {
                faults.add(Arguments.of(slot, stage));
            }
        }
        return faults.stream();
    }

    private static void rewritePublishedAuthority(
            Path root, UnaryOperator<StreamedChunkPayload> mutation)
            throws IOException {
        Path world = root.resolve(SAVE_ID.value());
        StreamedChunkIndex oldIndex = reopenedBatchIndex(root);
        StreamedChunkStore.BatchReadResult oldBatch = streamedStore(
                root, new JdkSaveFileOperations()).readModifiedBatch(SAVE_ID, oldIndex);
        assertEquals(StreamedChunkStore.BatchReadResult.Status.FOUND, oldBatch.status());
        StreamedChunkPayload changed = mutation.apply(oldBatch.payloads().get(0));
        byte[] payloadBytes = STREAMED_CODEC.encode(changed);
        Path shard = world.resolve("streamed-chunks")
                .resolve(signedCoordinate(changed.key().x()));
        for (char slot : new char[] {'a', 'b'}) {
            Files.write(
                    shard.resolve(signedCoordinate(changed.key().z())
                            + "." + slot + ".glchunk"),
                    payloadBytes);
        }
        StreamedChunkIndex.Entry changedEntry = new StreamedChunkIndex.Entry(
                changed.key(),
                changed.generatorVersion(),
                changed.baseHash(),
                changed.revision(),
                payloadBytes.length,
                StreamedChunkCodec.sha256Hex(payloadBytes),
                true);
        StreamedChunkIndex changedIndex = oldIndex.with(changedEntry);
        byte[] indexBytes = INDEX_CODEC.encode(changedIndex);
        long sequence = Math.max(
                slotSequence(Files.readAllBytes(world.resolve("streamed-chunks.idx"))),
                slotSequence(Files.readAllBytes(
                        world.resolve("streamed-chunks.prev.idx")))) + 1L;
        byte[] envelope = encodeIndexEnvelope(sequence, indexBytes);
        Files.write(world.resolve("streamed-chunks.idx"), envelope);
        Files.write(world.resolve("streamed-chunks.prev.idx"), envelope);

        JsonObject marker = JsonParser.parseString(new String(
                Files.readAllBytes(world.resolve("streamed-migration.b.v2")),
                StandardCharsets.UTF_8)).getAsJsonObject();
        marker.addProperty("index", Base64.getEncoder().encodeToString(indexBytes));
        JsonArray sections = marker.getAsJsonArray("sections");
        JsonObject indexSection = sections.get(3).getAsJsonObject();
        indexSection.addProperty("uncompressedSize", indexBytes.length);
        indexSection.addProperty("sha256", sha256Hex(indexBytes));
        byte[] markerBytes = marker.toString().getBytes(StandardCharsets.UTF_8);
        Files.write(world.resolve("streamed-migration.a.v2"), markerBytes);
        Files.write(world.resolve("streamed-migration.b.v2"), markerBytes);
        byte[] floorBytes = rewritePublishedFloorMarkerHash(
                Files.readAllBytes(
                        world.resolve("streamed-migration.published.a.v2")),
                markerBytes);
        Files.write(
                world.resolve("streamed-migration.published.a.v2"), floorBytes);
        Files.write(
                world.resolve("streamed-migration.published.b.v2"), floorBytes);
    }

    private static void rewriteCurrentAuthorityOnly(
            Path root, UnaryOperator<StreamedChunkPayload> mutation)
            throws IOException {
        Path world = root.resolve(SAVE_ID.value());
        StreamedChunkIndex oldIndex = reopenedBatchIndex(root);
        StreamedChunkStore.BatchReadResult oldBatch = streamedStore(
                root, new JdkSaveFileOperations()).readModifiedBatch(SAVE_ID, oldIndex);
        assertEquals(StreamedChunkStore.BatchReadResult.Status.FOUND, oldBatch.status());
        StreamedChunkPayload changed = mutation.apply(oldBatch.payloads().get(0));
        byte[] payloadBytes = STREAMED_CODEC.encode(changed);
        Path shard = world.resolve("streamed-chunks")
                .resolve(signedCoordinate(changed.key().x()));
        for (char slot : new char[] {'a', 'b'}) {
            Files.write(
                    shard.resolve(signedCoordinate(changed.key().z())
                            + "." + slot + ".glchunk"),
                    payloadBytes);
        }
        StreamedChunkIndex.Entry changedEntry = new StreamedChunkIndex.Entry(
                changed.key(),
                changed.generatorVersion(),
                changed.baseHash(),
                changed.revision(),
                payloadBytes.length,
                StreamedChunkCodec.sha256Hex(payloadBytes),
                true);
        byte[] indexBytes = INDEX_CODEC.encode(oldIndex.with(changedEntry));
        long sequence = Math.max(
                slotSequence(Files.readAllBytes(world.resolve("streamed-chunks.idx"))),
                slotSequence(Files.readAllBytes(
                        world.resolve("streamed-chunks.prev.idx")))) + 1L;
        byte[] envelope = encodeIndexEnvelope(sequence, indexBytes);
        Files.write(world.resolve("streamed-chunks.idx"), envelope);
        Files.write(world.resolve("streamed-chunks.prev.idx"), envelope);
    }

    private static byte[] encodeIndexEnvelope(long sequence, byte[] indexBytes)
            throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(body)) {
            output.writeInt(0x47495332);
            output.writeInt(2);
            output.writeLong(sequence);
            output.writeInt(indexBytes.length);
            output.write(indexBytes);
        }
        ByteArrayOutputStream envelope = new ByteArrayOutputStream();
        envelope.write(body.toByteArray());
        envelope.write(sha256Bytes(body.toByteArray()));
        return envelope.toByteArray();
    }

    private static String signedCoordinate(int coordinate) {
        return (coordinate < 0 ? "n" : "p")
                + String.format(
                        java.util.Locale.ROOT,
                        "%08x",
                        Math.abs((long) coordinate));
    }

    private static void deleteMigrationSideFiles(Path world) throws IOException {
        Files.delete(world.resolve("streamed-migration.a.v2"));
        Files.delete(world.resolve("streamed-migration.b.v2"));
        Files.delete(world.resolve("streamed-migration.published.a.v2"));
        Files.delete(world.resolve("streamed-migration.published.b.v2"));
    }

    private static void copyTree(Path source, Path destination) throws IOException {
        try (Stream<Path> paths = Files.walk(source)) {
            for (Path from : paths.toList()) {
                Path to = destination.resolve(source.relativize(from));
                if (Files.isDirectory(from, LinkOption.NOFOLLOW_LINKS)) {
                    Files.createDirectories(to);
                } else {
                    Files.copy(from, to);
                }
            }
        }
    }

    private static Path firstNonEmptyPayload(Path world) throws IOException {
        try (Stream<Path> files = Files.walk(world.resolve("streamed-chunks"))) {
            return files
                    .filter(path -> Files.isRegularFile(
                            path, LinkOption.NOFOLLOW_LINKS))
                    .filter(path -> path.getFileName().toString()
                            .endsWith(".glchunk"))
                    .filter(path -> {
                        try {
                            return Files.size(path) > 0L;
                        } catch (IOException failure) {
                            throw new java.io.UncheckedIOException(failure);
                        }
                    })
                    .findFirst()
                    .orElseThrow();
        } catch (java.io.UncheckedIOException failure) {
            throw failure.getCause();
        }
    }

    private static StreamedChunkIndex reopenedBatchIndex(Path root)
            throws IOException {
        Path world = root.resolve(SAVE_ID.value());
        byte[] main = Files.readAllBytes(world.resolve("streamed-chunks.idx"));
        byte[] recovery = Files.readAllBytes(
                world.resolve("streamed-chunks.prev.idx"));
        byte[] chosen = slotSequence(main) >= slotSequence(recovery)
                ? main
                : recovery;
        int indexLength = java.nio.ByteBuffer.wrap(chosen, 16, Integer.BYTES)
                .getInt();
        return INDEX_CODEC.decode(java.util.Arrays.copyOfRange(
                chosen, 20, 20 + indexLength));
    }

    private static long slotSequence(byte[] envelope) {
        return java.nio.ByteBuffer.wrap(envelope, 8, Long.BYTES).getLong();
    }

    private MigrationFixture prepare(
            String suffix, GeneratedFixture generated) throws IOException {
        Path root = Files.createDirectories(tempDir.resolve("root-" + suffix));
        Path world = Files.createDirectories(root.resolve(SAVE_ID.value()));
        Path current = world.resolve("current.glsave");
        Path backup = world.resolve("backup.glsave");
        new SaveArchiveWriter().write(
                current, SNAPSHOT_CODEC.encode(generated.snapshot(), MODIFIED));
        new SaveArchiveWriter().write(
                backup,
                SNAPSHOT_CODEC.encode(
                        generated.snapshot(), MODIFIED.minusSeconds(60)));
        byte[] exactV1 = Files.readAllBytes(current);
        assertNotEquals(
                java.util.HexFormat.of().formatHex(exactV1),
                java.util.HexFormat.of().formatHex(Files.readAllBytes(backup)),
                "the prior backup fixture must differ from current v1");
        return new MigrationFixture(root, current, backup, exactV1, generated);
    }

    private static Stream<Arguments> representativeFaults() {
        List<Arguments> cases = new ArrayList<>();
        for (int ordinal : List.of(0, 1, 2)) {
            cases.add(Arguments.of(
                    Phase14SaveMigrator.CheckpointStage.CHUNK,
                    Phase14SaveMigrator.CheckpointSide.BEFORE,
                    ordinal));
            cases.add(Arguments.of(
                    Phase14SaveMigrator.CheckpointStage.CHUNK,
                    Phase14SaveMigrator.CheckpointSide.AFTER,
                    ordinal));
        }
        for (Phase14SaveMigrator.CheckpointStage stage : List.of(
                Phase14SaveMigrator.CheckpointStage.INDEX,
                Phase14SaveMigrator.CheckpointStage.V2_MANIFEST,
                Phase14SaveMigrator.CheckpointStage.V1_BACKUP,
                Phase14SaveMigrator.CheckpointStage.FINAL_REREAD,
                Phase14SaveMigrator.CheckpointStage.CATALOG_OPEN)) {
            cases.add(Arguments.of(
                    stage, Phase14SaveMigrator.CheckpointSide.BEFORE, -1));
            cases.add(Arguments.of(
                    stage, Phase14SaveMigrator.CheckpointSide.AFTER, -1));
        }
        return cases.stream();
    }

    private static Stream<Arguments> worldItemPublicationFaults() {
        Stream<Arguments> pages = java.util.stream.IntStream.range(0, 3)
                .boxed()
                .flatMap(ordinal -> Stream.of(
                        Arguments.of(
                                Phase14SaveMigrator.CheckpointStage.WORLD_ITEM_PAGES,
                                Phase14SaveMigrator.CheckpointSide.BEFORE,
                                ordinal),
                        Arguments.of(
                                Phase14SaveMigrator.CheckpointStage.WORLD_ITEM_PAGES,
                                Phase14SaveMigrator.CheckpointSide.AFTER,
                                ordinal)));
        Stream<Arguments> checkpoint = Stream.of(
                Arguments.of(
                        Phase14SaveMigrator.CheckpointStage.WORLD_ITEM_CHECKPOINT,
                        Phase14SaveMigrator.CheckpointSide.BEFORE,
                        -1),
                Arguments.of(
                        Phase14SaveMigrator.CheckpointStage.WORLD_ITEM_CHECKPOINT,
                        Phase14SaveMigrator.CheckpointSide.AFTER,
                        -1));
        return Stream.concat(pages, checkpoint);
    }

    private static void assertEveryCheckpointOnceInCanonicalOrder(
            List<Phase14SaveMigrator.Checkpoint> observed) {
        List<ChunkKey> expectedKeys = GENERATED.snapshot().chunks().chunks().stream()
                .map(ChunkSnapshot::key)
                .sorted(Comparator.comparingInt(ChunkKey::x)
                        .thenComparingInt(ChunkKey::z))
                .toList();
        for (Phase14SaveMigrator.CheckpointSide side
                : Phase14SaveMigrator.CheckpointSide.values()) {
            List<Phase14SaveMigrator.Checkpoint> chunks = observed.stream()
                    .filter(checkpoint -> checkpoint.stage()
                                    == Phase14SaveMigrator.CheckpointStage.CHUNK
                            && checkpoint.side() == side)
                    .toList();
            assertEquals(81, chunks.size());
            assertEquals(
                    expectedKeys,
                    chunks.stream().map(Phase14SaveMigrator.Checkpoint::chunkKey)
                            .toList());
            assertEquals(
                    java.util.stream.IntStream.range(0, 81).boxed().toList(),
                    chunks.stream()
                            .map(Phase14SaveMigrator.Checkpoint::chunkOrdinal)
                            .toList());
        }
        for (Phase14SaveMigrator.CheckpointStage stage : List.of(
                Phase14SaveMigrator.CheckpointStage.INDEX,
                Phase14SaveMigrator.CheckpointStage.V2_MANIFEST,
                Phase14SaveMigrator.CheckpointStage.V1_BACKUP,
                Phase14SaveMigrator.CheckpointStage.FINAL_REREAD,
                Phase14SaveMigrator.CheckpointStage.CATALOG_OPEN)) {
            for (Phase14SaveMigrator.CheckpointSide side
                    : Phase14SaveMigrator.CheckpointSide.values()) {
                assertEquals(
                        1L,
                        observed.stream()
                                .filter(checkpoint -> checkpoint.stage() == stage
                                        && checkpoint.side() == side)
                                .count(),
                        () -> stage + " " + side + " must be observed exactly once");
            }
        }
    }

    private static void assertExactOldOrCompleteNew(MigrationFixture fixture)
            throws IOException {
        SaveRepository repository = SaveRepository.open(
                fixture.root(), reader(), new JdkSaveFileOperations());
        List<SaveSummary> rows = new FileSaveCatalog(repository).summaries();
        assertEquals(1, rows.size(), "migration must not expose a half catalog row");
        SaveSummary row = rows.get(0);
        assertEquals(SaveSummary.Health.VALID, row.health());
        assertTrue(row.loadEnabled());
        SaveArchiveReadResult loaded = repository.load(SAVE_ID);
        assertEquals(SaveArchiveReadResult.Status.VALID, loaded.status());
        SaveFormatVersion authoritativeVersion = row.formatVersion().orElseThrow();
        if (authoritativeVersion.equals(SaveFormatVersion.CURRENT)) {
            assertSnapshotContentEquals(
                    fixture.expected().snapshot(), loaded.snapshot().orElseThrow());
            assertTrue(
                    exactV1Exists(fixture),
                    "v1 authority must retain the exact original archive bytes");
        } else {
            assertEquals(SaveFormatVersion.STREAMED_CHUNKS, authoritativeVersion);
            assertSnapshotContentEquals(
                    pagedSnapshot(fixture.expected().snapshot()),
                    loaded.snapshot().orElseThrow());
            assertCompleteV2Authority(fixture.root(), fixture.expected());
            assertArrayEquals(
                    fixture.exactV1(), Files.readAllBytes(fixture.backup()),
                    "published v2 must retain exact readable v1 recovery bytes");
        }
    }

    private static SaveGameSnapshot pagedSnapshot(SaveGameSnapshot source) {
        return new SaveGameSnapshot(
                source.metadata(),
                source.fixedTick(),
                source.chunks(),
                source.player(),
                source.inventory(),
                new WorldItemsSaveSnapshot(
                        source.fixedTick(),
                        List.of(),
                        source.worldItems().nextItemId(),
                        source.worldItems().itemIdsExhausted(),
                        com.overlord.worlditem.api.LogicalWorldItemSnapshot
                                .Completeness.PAGED_PARTIAL));
    }

    private static boolean exactV1Exists(MigrationFixture fixture) throws IOException {
        return (Files.isRegularFile(fixture.current())
                        && java.util.Arrays.equals(
                                fixture.exactV1(), Files.readAllBytes(fixture.current())))
                || (Files.isRegularFile(fixture.backup())
                        && java.util.Arrays.equals(
                                fixture.exactV1(), Files.readAllBytes(fixture.backup())));
    }

    private static void assertCompleteV2Authority(
            Path root, GeneratedFixture generated) {
        SaveGameSnapshot expected = generated.snapshot();
        StreamedChunkStore store = new StreamedChunkStore(
                root,
                SAVE_ID,
                STREAMED_CODEC,
                INDEX_CODEC,
                new JdkSaveFileOperations());
        Map<ChunkKey, ChunkSnapshot> expectedChunks = new LinkedHashMap<>();
        for (ChunkSnapshot chunk : expected.chunks().chunks()) {
            expectedChunks.put(chunk.key(), chunk);
        }
        assertEquals(expected.chunks().chunks().size(), expectedChunks.size());
        StreamedChunkIndex index;
        try {
            index = reopenedBatchIndex(root);
        } catch (IOException failure) {
            throw new AssertionError("streamed index is unreadable", failure);
        }
        StreamedChunkStore.BatchReadResult read = store.readModifiedBatch(
                SAVE_ID, index);
        assertEquals(StreamedChunkStore.BatchReadResult.Status.FOUND, read.status());
        Map<ChunkKey, StreamedChunkPayload> payloads = new LinkedHashMap<>();
        for (StreamedChunkPayload payload : read.payloads()) {
            payloads.put(payload.key(), payload);
        }
        for (Map.Entry<ChunkKey, ChunkSnapshot> entry : expectedChunks.entrySet()) {
            String baseHash = generated.baseHashes().get(entry.getKey());
            StreamedChunkPayload payload = payloads.get(entry.getKey());
            assertEquals(
                    entry.getKey(),
                    payload.key(),
                    () -> "missing authoritative imported Chunk " + entry.getKey());
            assertAll(
                    () -> assertEquals(SAVE_ID, payload.saveGameId()),
                    () -> assertEquals(entry.getKey(), payload.key()),
                    () -> assertEquals(GENERATOR_VERSION, payload.generatorVersion()),
                    () -> assertEquals(baseHash, payload.baseHash()),
                    () -> assertTrue(payload.modified(),
                            "matching generated bytes are still authoritative v1 data"),
                    () -> assertEquals(entry.getValue().revision(), payload.revision()),
                    () -> assertEquals(entry.getValue().worldHeight(), payload.worldHeight()),
                    () -> assertArrayEquals(
                            entry.getValue().copyBlocks(),
                            payload.copyCanonicalVoxels()),
                    () -> assertTrue(payload.extensions().isEmpty()),
                    () -> assertTrue(read.diagnostics().isEmpty()));
        }
    }

    private static void assertSnapshotContentEquals(
            SaveGameSnapshot expected, SaveGameSnapshot actual) {
        assertAll(
                () -> assertEquals(
                        expected.metadata().saveGameId(), actual.metadata().saveGameId()),
                () -> assertEquals(
                        expected.metadata().displayName(), actual.metadata().displayName()),
                () -> assertEquals(
                        expected.metadata().createdAt(), actual.metadata().createdAt()),
                () -> assertEquals(
                        expected.metadata().worldSeed(), actual.metadata().worldSeed()),
                () -> assertEquals(
                        expected.metadata().generatorVersion(),
                        actual.metadata().generatorVersion()),
                () -> assertEquals(
                        expected.metadata().generatorConfigFingerprint(),
                        actual.metadata().generatorConfigFingerprint()),
                () -> assertEquals(expected.fixedTick(), actual.fixedTick()),
                () -> assertEquals(expected.chunks(), actual.chunks()),
                () -> assertEquals(expected.player(), actual.player()),
                () -> assertEquals(expected.inventory(), actual.inventory()),
                () -> assertEquals(expected.worldItems(), actual.worldItems()));
    }

    private static Throwable primaryCause(SaveDiagnostic diagnostic) {
        Throwable cause = diagnostic.cause().orElseThrow();
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause;
    }

    private static String diagnosticSummary(List<SaveDiagnostic> diagnostics) {
        return diagnostics.stream()
                .map(diagnostic -> diagnostic.code()
                        + ":"
                        + diagnostic.message()
                        + ":"
                        + diagnostic.cause().map(cause -> {
                            Throwable root = cause;
                            while (root.getCause() != null && root.getCause() != root) {
                                root = root.getCause();
                            }
                            return cause.getClass().getName()
                                    + "->"
                                    + root.getClass().getName()
                                    + ":"
                                    + root.getMessage();
                        }).orElse("no-cause"))
                .toList()
                .toString();
    }

    private static void assertClosedDiagnostics(
            List<SaveDiagnostic> diagnostics, Path secretRoot) {
        assertFalse(diagnostics.isEmpty());
        for (SaveDiagnostic diagnostic : diagnostics) {
            assertTrue(diagnostic.code().codePointCount(0, diagnostic.code().length()) <= 96);
            assertTrue(diagnostic.message().codePointCount(0, diagnostic.message().length())
                    <= SaveDiagnostic.MAX_MESSAGE_CODE_POINTS);
            assertFalse(diagnostic.message().contains(secretRoot.toString()));
            assertFalse(diagnostic.message().contains("injected"));
        }
    }

    private static Map<String, byte[]> exactRegularFiles(Path root) throws IOException {
        Map<String, byte[]> files = new java.util.TreeMap<>();
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                files.put(
                        root.relativize(path).toString().replace('\\', '/'),
                        Files.readAllBytes(path));
            }
        }
        return Map.copyOf(files);
    }

    private static void assertExactRegularFiles(
            Map<String, byte[]> expected, Map<String, byte[]> actual) {
        assertEquals(
                expected.keySet(),
                actual.keySet(),
                "a repeated migration must not add or remove persisted files");
        for (String name : expected.keySet()) {
            assertArrayEquals(
                    expected.get(name),
                    actual.get(name),
                    "a repeated migration must not rewrite " + name);
        }
    }

    private static SaveArchiveReader reader() {
        return new SaveArchiveReader(SNAPSHOT_CODEC);
    }

    private static GeneratedFixture generatedFixture() {
        WorldGenerator generator =
                GaiaWorldGenerator.createVisualRevisionCandidate();
        GenerationContext context = new GenerationContext(
                GENERATION,
                new GenerationBlockPalette(
                        (byte) 0, (byte) 1, (byte) 2, (byte) 3, (byte) 4, (byte) 5),
                new DeterministicCoordinateSampler(
                        GENERATION.seed(), GENERATION.algorithmVersion()));
        List<ChunkSnapshot> chunks = new ArrayList<>(81);
        Map<ChunkKey, String> baseHashes = new LinkedHashMap<>();
        long revision = 0L;
        for (int x = -4; x <= 4; x++) {
            for (int z = -4; z <= 4; z++) {
                ChunkKey key = new ChunkKey(x, z);
                WorldGenerationResult result = generator.generate(context, key);
                if (!result.succeeded()) {
                    throw new AssertionError("fixture generation failed for " + key);
                }
                ChunkGenerationData generated = result.chunkData().orElseThrow();
                chunks.add(ChunkSnapshot.of(
                        key,
                        ++revision,
                        generated.worldHeight(),
                        generated.copyBlocks()));
                baseHashes.put(
                        key, WorldGenerationHasher.hashChunk(GENERATION, generated));
            }
        }
        int worldHeight = chunks.get(0).worldHeight();
        SaveGameSnapshot snapshot = new SaveGameSnapshot(
                new SaveGameSnapshot.StaticMetadata(
                        SaveFormatVersion.CURRENT,
                        "0.3.0-phase15-test",
                        SAVE_ID,
                        "Phase 14 generated-base fixture",
                        CREATED,
                        GENERATION.seed(),
                        GENERATOR_VERSION,
                        sha256(GENERATION.canonicalFingerprintInput()),
                        4,
                        worldHeight,
                        Optional.of("Exact 81 Chunk conservative migration fixture")),
                1500L,
                new ChunkRepositorySnapshot(worldHeight, revision, chunks),
                new PlayerSaveSnapshot(
                        OWNER,
                        8.5,
                        91.0,
                        -12.25,
                        0.0,
                        0.0,
                        0.0,
                        135.0,
                        -12.0,
                        GameMode.SURVIVAL,
                        false),
                new InventorySaveSnapshot(
                        OWNER, Map.of(), BodySlot.LEFT_HAND, false, 1L),
                new WorldItemsSaveSnapshot(1500L, List.of(), 0L, false));
        return new GeneratedFixture(snapshot, Map.copyOf(baseHashes));
    }

    private static GeneratedFixture threeChunkFixture() {
        List<ChunkSnapshot> chunks = List.of(
                GENERATED.snapshot().chunks().chunks().get(0),
                GENERATED.snapshot().chunks().chunks().get(40),
                GENERATED.snapshot().chunks().chunks().get(80));
        Map<ChunkKey, String> baseHashes = new LinkedHashMap<>();
        for (ChunkSnapshot chunk : chunks) {
            baseHashes.put(chunk.key(), GENERATED.baseHashes().get(chunk.key()));
        }
        SaveGameSnapshot source = GENERATED.snapshot();
        SaveGameSnapshot subset = new SaveGameSnapshot(
                source.metadata(),
                source.fixedTick(),
                new ChunkRepositorySnapshot(
                        source.chunks().worldHeight(),
                        source.chunks().revisionHighWater(),
                        chunks),
                source.player(),
                source.inventory(),
                source.worldItems());
        return new GeneratedFixture(subset, Map.copyOf(baseHashes));
    }

    private static GeneratedFixture generatedFixtureWithWorldItem() {
        GeneratedFixture base = threeChunkFixture();
        SaveGameSnapshot source = base.snapshot();
        ChunkKey owner = source.chunks().chunks().get(0).key();
        WorldItemRestoreEntry entry = new WorldItemRestoreEntry(
                new WorldItemRuntimeSnapshot(
                        new WorldItemSnapshot(
                                new WorldItemId(700L),
                                new ItemStack(ResourceLocation.of("gaia", "test/drop"), 3),
                                owner.worldOriginX() + 0.5,
                                4.0,
                                owner.worldOriginZ() + 0.5,
                                0.0,
                                0.0,
                                0.0,
                                1L),
                        Optional.empty(),
                        1_000L,
                        1_000L),
                WorldItemPhysicalState.FROZEN_UNLOADED);
        SaveGameSnapshot withItem = new SaveGameSnapshot(
                source.metadata(),
                source.fixedTick(),
                source.chunks(),
                source.player(),
                source.inventory(),
                new WorldItemsSaveSnapshot(
                        source.fixedTick(), List.of(entry), 701L, false));
        return new GeneratedFixture(withItem, base.baseHashes());
    }

    private static GeneratedFixture generatedFixtureWithWorldItems() {
        SaveGameSnapshot source = GENERATED.snapshot();
        List<ChunkSnapshot> chunks = source.chunks().chunks();
        List<WorldItemRestoreEntry> entries = new ArrayList<>();
        for (int index = 0; index < 3; index++) {
            ChunkKey owner = chunks.get(index == 0 ? 0 : index == 1 ? 40 : 80).key();
            long id = 700L + index;
            entries.add(new WorldItemRestoreEntry(
                    new WorldItemRuntimeSnapshot(
                            new WorldItemSnapshot(
                                    new WorldItemId(id),
                                    new ItemStack(
                                            ResourceLocation.of("gaia", "test/drop"),
                                            index + 1),
                                    owner.worldOriginX() + 0.5,
                                    4.0,
                                    owner.worldOriginZ() + 0.5,
                                    0.0,
                                    0.0,
                                    0.0,
                                    1L),
                            Optional.empty(),
                            1_000L + index,
                            1_000L + index),
                    WorldItemPhysicalState.FROZEN_UNLOADED));
        }
        SaveGameSnapshot withItems = new SaveGameSnapshot(
                source.metadata(), source.fixedTick(), source.chunks(),
                source.player(), source.inventory(),
                new WorldItemsSaveSnapshot(
                        source.fixedTick(), entries, 703L, false));
        return new GeneratedFixture(withItems, GENERATED.baseHashes());
    }

    private static String sha256(String value) {
        return sha256Hex(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String generatedBaseHash(ChunkKey key) {
        GenerationContext context = new GenerationContext(
                GENERATION,
                new GenerationBlockPalette(
                        (byte) 0, (byte) 1, (byte) 2, (byte) 3, (byte) 4, (byte) 5),
                new DeterministicCoordinateSampler(
                        GENERATION.seed(), GENERATION.algorithmVersion()));
        WorldGenerationResult generated =
                GaiaWorldGenerator.createVisualRevisionCandidate()
                .generate(context, key);
        if (!generated.succeeded()) {
            throw new AssertionError("distant base generation failed");
        }
        return WorldGenerationHasher.hashChunk(
                GENERATION, generated.chunkData().orElseThrow());
    }

    private static String sha256Hex(byte[] bytes) {
        return java.util.HexFormat.of().formatHex(sha256Bytes(bytes));
    }

    private static byte[] sha256Bytes(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private record GeneratedFixture(
            SaveGameSnapshot snapshot, Map<ChunkKey, String> baseHashes) {}

    private record MigrationFixture(
            Path root,
            Path current,
            Path backup,
            byte[] exactV1,
            GeneratedFixture expected) {
        private MigrationFixture {
            exactV1 = exactV1.clone();
            java.util.Objects.requireNonNull(expected, "expected");
        }

        @Override
        public byte[] exactV1() {
            return exactV1.clone();
        }
    }

    private record TouchedSentinel(Path path, byte[] exactBytes) {
        private TouchedSentinel {
            exactBytes = exactBytes.clone();
        }

        @Override
        public byte[] exactBytes() {
            return exactBytes.clone();
        }
    }

    private static final class InjectedMigrationFailure extends IOException {
        private InjectedMigrationFailure() {
            super("injected migration checkpoint failure");
        }
    }

    private static final class FatalMigrationError extends Error {}

    private enum PostPublicationEvolutionTarget {
        IMPORTED,
        DISTANT
    }

    private enum SemanticPayloadAttack {
        GENERATOR_VERSION {
            @Override
            StreamedChunkPayload mutate(StreamedChunkPayload source) {
                return copy(source, "gaia-v-review-wrong", source.baseHash(), List.of());
            }
        },
        BASE_HASH {
            @Override
            StreamedChunkPayload mutate(StreamedChunkPayload source) {
                return copy(
                        source,
                        source.generatorVersion(),
                        sha256("review-wrong-base-hash"),
                        List.of());
            }
        },
        NONEMPTY_MIGRATION_EXTENSION {
            @Override
            StreamedChunkPayload mutate(StreamedChunkPayload source) {
                return copy(
                        source,
                        source.generatorVersion(),
                        source.baseHash(),
                        List.of(new StreamedChunkPayload.ExtensionDescriptor(
                                SaveSectionId.DETAIL_BLOCKS,
                                1,
                                false,
                                new byte[] {1})));
            }
        };

        abstract StreamedChunkPayload mutate(StreamedChunkPayload source);

        private static StreamedChunkPayload copy(
                StreamedChunkPayload source,
                String generatorVersion,
                String baseHash,
                List<StreamedChunkPayload.ExtensionDescriptor> extensions) {
            return new StreamedChunkPayload(
                    source.saveGameId(),
                    source.key(),
                    generatorVersion,
                    baseHash,
                    source.revision(),
                    source.persistedRevision(),
                    true,
                    source.worldHeight(),
                    source.copyCanonicalVoxels(),
                    extensions);
        }
    }

    private enum AnchoredReplacement {
        ROOT {
            @Override
            void apply(Path root) throws IOException {
                Path displaced = root.resolveSibling(root.getFileName() + ".displaced");
                Files.move(root, displaced);
                copyTree(displaced, root);
            }
        },
        WORLD {
            @Override
            void apply(Path root) throws IOException {
                Path world = root.resolve(SAVE_ID.value());
                Path displaced = root.resolve(SAVE_ID.value() + ".displaced");
                Files.move(world, displaced);
                copyTree(displaced, world);
            }
        },
        MARKER {
            @Override
            void apply(Path root) throws IOException {
                Path marker = root.resolve(SAVE_ID.value())
                        .resolve("streamed-migration.a.v2");
                Files.delete(marker);
                Files.write(marker, new byte[0]);
            }
        };

        abstract void apply(Path root) throws IOException;
    }

    private static class ForwardingSaveFileOperations implements SaveFileOperations {
        final SaveFileOperations delegate = new JdkSaveFileOperations();

        @Override
        public void createDirectory(Path directory, MutationGuard guard)
                throws IOException {
            delegate.createDirectory(directory, guard);
        }

        @Override
        public Path createSiblingTemp(
                Path directory, String targetName, MutationGuard guard)
                throws IOException {
            return delegate.createSiblingTemp(directory, targetName, guard);
        }

        @Override
        public void forceFile(Path file, MutationGuard guard) throws IOException {
            delegate.forceFile(file, guard);
        }

        @Override
        public void writeBounded(
                Path file, byte[] bytes, long maximumBytes, MutationGuard guard)
                throws IOException {
            delegate.writeBounded(file, bytes, maximumBytes, guard);
        }

        @Override
        public void createBounded(
                Path file, byte[] bytes, long maximumBytes, MutationGuard guard)
                throws IOException {
            delegate.createBounded(file, bytes, maximumBytes, guard);
        }

        @Override
        public void writeExistingBounded(
                Path file, byte[] bytes, long maximumBytes, MutationGuard guard)
                throws IOException {
            delegate.writeExistingBounded(file, bytes, maximumBytes, guard);
        }

        @Override
        public byte[] readBounded(
                Path file, long maximumBytes, MutationGuard guard) throws IOException {
            return delegate.readBounded(file, maximumBytes, guard);
        }

        @Override
        public void moveAtomicReplacing(
                Path source, Path destination, MutationGuard guard) throws IOException {
            delegate.moveAtomicReplacing(source, destination, guard);
        }

        @Override
        public void moveReplacing(
                Path source, Path destination, MutationGuard guard) throws IOException {
            delegate.moveReplacing(source, destination, guard);
        }

        @Override
        public void copyReplacing(
                Path source, Path destination, MutationGuard guard) throws IOException {
            delegate.copyReplacing(source, destination, guard);
        }

        @Override
        public boolean deleteIfExists(Path path, MutationGuard guard)
                throws IOException {
            return delegate.deleteIfExists(path, guard);
        }

        @Override
        public void forceDirectoryBestEffort(Path directory, MutationGuard guard)
                throws IOException {
            delegate.forceDirectoryBestEffort(directory, guard);
        }

        @Override
        public void forceDirectoryDurably(Path directory, MutationGuard guard)
                throws IOException {
            delegate.forceDirectoryDurably(directory, guard);
        }

        @Override
        public Object readFileKey(Path path, MutationGuard guard) throws IOException {
            return delegate.readFileKey(path, guard);
        }

        @Override
        public Object readFileIdentity(
                Path path, long maximumBytes, MutationGuard guard) throws IOException {
            return delegate.readFileIdentity(path, maximumBytes, guard);
        }

        @Override
        public ManagedFileIdentity readManagedFileIdentity(
                Path path, long maximumBytes, MutationGuard guard) throws IOException {
            return delegate.readManagedFileIdentity(path, maximumBytes, guard);
        }

        @Override
        public Object readDirectoryKey(Path path, MutationGuard guard)
                throws IOException {
            return delegate.readDirectoryKey(path, guard);
        }
    }

    private static final class NullDirectoryIdentityOperations
            extends ForwardingSaveFileOperations {
        @Override
        public Object readDirectoryKey(Path path, MutationGuard guard)
                throws IOException {
            guard.validate();
            return null;
        }
    }

    private static final class CountingPayloadReadsOperations
            extends ForwardingSaveFileOperations {
        private int payloadReads;

        @Override
        public byte[] readBounded(
                Path file, long maximumBytes, MutationGuard guard) throws IOException {
            if (file.getFileName().toString().endsWith(".glchunk")) {
                payloadReads++;
            }
            return super.readBounded(file, maximumBytes, guard);
        }

        private int payloadReads() {
            return payloadReads;
        }

        private void resetPayloadReads() {
            payloadReads = 0;
        }
    }

    private enum SideInitializationSlot {
        MARKER_A("streamed-migration.a.v2"),
        MARKER_B("streamed-migration.b.v2"),
        FLOOR_A("streamed-migration.published.a.v2"),
        FLOOR_B("streamed-migration.published.b.v2");

        private final String fileName;

        SideInitializationSlot(String fileName) {
            this.fileName = fileName;
        }

        private boolean matches(Path path) {
            return path.getFileName().toString().equals(fileName);
        }
    }

    private enum SideInitializationFaultStage {
        AFTER_CREATE,
        AFTER_FILE_FORCE,
        AFTER_PARENT_FORCE
    }

    private static final class SideInitializationFailure extends IOException {
        private SideInitializationFailure() {
            super("injected side initialization failure");
        }
    }

    private static final class SideInitializationFaultOperations
            extends ForwardingSaveFileOperations {
        private final SideInitializationSlot slot;
        private final SideInitializationFaultStage stage;
        private final SideInitializationFailure failure;
        private boolean armedParentForce;
        private boolean fired;

        private SideInitializationFaultOperations(
                SideInitializationSlot slot,
                SideInitializationFaultStage stage,
                SideInitializationFailure failure) {
            this.slot = slot;
            this.stage = stage;
            this.failure = failure;
        }

        @Override
        public void createBounded(
                Path file, byte[] bytes, long maximumBytes, MutationGuard guard)
                throws IOException {
            super.createBounded(file, bytes, maximumBytes, guard);
            if (!fired && slot.matches(file)
                    && stage == SideInitializationFaultStage.AFTER_CREATE) {
                fired = true;
                throw failure;
            }
        }

        @Override
        public void forceFile(Path file, MutationGuard guard) throws IOException {
            super.forceFile(file, guard);
            if (fired || !slot.matches(file)) {
                return;
            }
            if (stage == SideInitializationFaultStage.AFTER_FILE_FORCE) {
                fired = true;
                throw failure;
            }
            if (stage == SideInitializationFaultStage.AFTER_PARENT_FORCE) {
                armedParentForce = true;
            }
        }

        @Override
        public void forceDirectoryDurably(Path directory, MutationGuard guard)
                throws IOException {
            super.forceDirectoryDurably(directory, guard);
            if (!fired && armedParentForce) {
                fired = true;
                throw failure;
            }
        }
    }

    private static final class Task4BeforeSideObservationOperations
            extends ForwardingSaveFileOperations {
        private final Path root;
        private boolean observedFirstSideCreation;
        private boolean task4WasDurableBeforeSide;

        private Task4BeforeSideObservationOperations(Path root) {
            this.root = root;
        }

        @Override
        public void createBounded(
                Path file, byte[] bytes, long maximumBytes, MutationGuard guard)
                throws IOException {
            if (!observedFirstSideCreation && isMarker(file)) {
                observedFirstSideCreation = true;
                task4WasDurableBeforeSide = exactProofFreeTask4Authority(root);
            }
            super.createBounded(file, bytes, maximumBytes, guard);
        }

        private boolean observedFirstSideCreation() {
            return observedFirstSideCreation;
        }

        private boolean task4WasDurableBeforeSide() {
            return task4WasDurableBeforeSide;
        }

        private static boolean isMarker(Path path) {
            String name = path.getFileName().toString();
            return name.equals("streamed-migration.a.v2")
                    || name.equals("streamed-migration.b.v2");
        }

        private static boolean exactProofFreeTask4Authority(Path root) {
            try {
                Path world = root.resolve(SAVE_ID.value());
                Path main = world.resolve("streamed-chunks.idx");
                Path recovery = world.resolve("streamed-chunks.prev.idx");
                if (!Files.isDirectory(
                                world.resolve("streamed-chunks"),
                                LinkOption.NOFOLLOW_LINKS)
                        || !Files.isRegularFile(main, LinkOption.NOFOLLOW_LINKS)
                        || !Files.isRegularFile(recovery, LinkOption.NOFOLLOW_LINKS)) {
                    return false;
                }
                byte[] mainBytes = Files.readAllBytes(main);
                byte[] recoveryBytes = Files.readAllBytes(recovery);
                StreamedChunkIndex mainIndex = decodeIndexSlot(mainBytes);
                StreamedChunkIndex recoveryIndex = decodeIndexSlot(recoveryBytes);
                return slotSequence(mainBytes) >= 0L
                        && slotSequence(recoveryBytes) >= 0L
                        && mainIndex.migrationCompatibility().isEmpty()
                        && recoveryIndex.migrationCompatibility().isEmpty()
                        && (slotSequence(mainBytes) != slotSequence(recoveryBytes)
                                || java.util.Arrays.equals(mainBytes, recoveryBytes));
            } catch (IOException | RuntimeException invalid) {
                return false;
            }
        }

        private static StreamedChunkIndex decodeIndexSlot(byte[] envelope) {
            int indexLength = ByteBuffer.wrap(envelope, 16, Integer.BYTES).getInt();
            return INDEX_CODEC.decode(java.util.Arrays.copyOfRange(
                    envelope, 20, 20 + indexLength));
        }
    }

    private enum PayloadFaultStage {
        AFTER_WRITE,
        AFTER_FORCE
    }

    private static final class PayloadOperationFailure extends IOException {
        private PayloadOperationFailure() {
            super("injected payload file-operation failure");
        }
    }

    private static final class PayloadFaultOperations
            extends ForwardingSaveFileOperations {
        private final PayloadFaultStage stage;
        private final int targetOrdinal;
        private final PayloadOperationFailure failure;
        private int writes;
        private Path forceTarget;

        private PayloadFaultOperations(
                PayloadFaultStage stage,
                int targetOrdinal,
                PayloadOperationFailure failure) {
            this.stage = stage;
            this.targetOrdinal = targetOrdinal;
            this.failure = failure;
        }

        @Override
        public void writeExistingBounded(
                Path file, byte[] bytes, long maximumBytes, MutationGuard guard)
                throws IOException {
            super.writeExistingBounded(file, bytes, maximumBytes, guard);
            if (isPayload(file) && bytes.length > 0) {
                int ordinal = writes++;
                if (ordinal == targetOrdinal) {
                    if (stage == PayloadFaultStage.AFTER_WRITE) {
                        throw failure;
                    }
                    forceTarget = file.toAbsolutePath().normalize();
                }
            }
        }

        @Override
        public void forceFile(Path file, MutationGuard guard) throws IOException {
            super.forceFile(file, guard);
            if (stage == PayloadFaultStage.AFTER_FORCE
                    && forceTarget != null
                    && forceTarget.equals(file.toAbsolutePath().normalize())) {
                forceTarget = null;
                throw failure;
            }
        }

        private static boolean isPayload(Path file) {
            return file.getFileName().toString().endsWith(".glchunk");
        }
    }

    private enum FloorFaultStage {
        BEFORE_WRITE,
        AFTER_WRITE,
        BEFORE_FORCE,
        AFTER_FORCE
    }

    private static final class FloorOperationFailure extends IOException {
        private FloorOperationFailure() {
            super("injected publication-floor operation failure");
        }
    }

    private static final class FloorFaultOperations
            extends ForwardingSaveFileOperations {
        private final char slot;
        private final FloorFaultStage stage;
        private final FloorOperationFailure failure;
        private int targetWrites;
        private boolean publicationWritten;

        private FloorFaultOperations(
                char slot, FloorFaultStage stage, FloorOperationFailure failure) {
            this.slot = slot;
            this.stage = stage;
            this.failure = failure;
        }

        @Override
        public void writeExistingBounded(
                Path file, byte[] bytes, long maximumBytes, MutationGuard guard)
                throws IOException {
            if (!isTarget(file)) {
                super.writeExistingBounded(file, bytes, maximumBytes, guard);
                return;
            }
            int ordinal = targetWrites++;
            boolean publication = ordinal == 1;
            if (publication && stage == FloorFaultStage.BEFORE_WRITE) {
                throw failure;
            }
            super.writeExistingBounded(file, bytes, maximumBytes, guard);
            if (publication) {
                publicationWritten = true;
                if (stage == FloorFaultStage.AFTER_WRITE) {
                    throw failure;
                }
            }
        }

        @Override
        public void forceFile(Path file, MutationGuard guard) throws IOException {
            if (isTarget(file)
                    && publicationWritten
                    && stage == FloorFaultStage.BEFORE_FORCE) {
                throw failure;
            }
            super.forceFile(file, guard);
            if (isTarget(file) && publicationWritten) {
                publicationWritten = false;
                if (stage == FloorFaultStage.AFTER_FORCE) {
                    throw failure;
                }
            }
        }

        private boolean isTarget(Path file) {
            return file.getFileName().toString().equals(
                    "streamed-migration.published." + slot + ".v2");
        }
    }

    private enum Task4PublicationSlot {
        RECOVERY("streamed-chunks.prev.idx"),
        MAIN("streamed-chunks.idx");

        private final String fileName;

        Task4PublicationSlot(String fileName) {
            this.fileName = fileName;
        }
    }

    private enum PublicationIndexFaultStage {
        BEFORE_WRITE,
        AFTER_WRITE,
        BEFORE_FORCE,
        AFTER_FORCE
    }

    private static final class PublicationIndexFailure extends IOException {
        private PublicationIndexFailure() {
            super("injected Task4 migration publication failure");
        }
    }

    private static final class PublicationIndexFaultOperations
            extends ForwardingSaveFileOperations {
        private final Task4PublicationSlot slot;
        private final PublicationIndexFaultStage stage;
        private final PublicationIndexFailure failure;
        private int targetWrites;
        private boolean publicationWritten;

        private PublicationIndexFaultOperations(
                Task4PublicationSlot slot,
                PublicationIndexFaultStage stage,
                PublicationIndexFailure failure) {
            this.slot = slot;
            this.stage = stage;
            this.failure = failure;
        }

        @Override
        public void writeExistingBounded(
                Path file, byte[] bytes, long maximumBytes, MutationGuard guard)
                throws IOException {
            if (!isTarget(file)) {
                super.writeExistingBounded(file, bytes, maximumBytes, guard);
                return;
            }
            int ordinal = targetWrites++;
            boolean publication = ordinal == 1;
            if (publication && stage == PublicationIndexFaultStage.BEFORE_WRITE) {
                throw failure;
            }
            super.writeExistingBounded(file, bytes, maximumBytes, guard);
            if (publication) {
                publicationWritten = true;
                if (stage == PublicationIndexFaultStage.AFTER_WRITE) {
                    throw failure;
                }
            }
        }

        @Override
        public void forceFile(Path file, MutationGuard guard) throws IOException {
            if (isTarget(file)
                    && publicationWritten
                    && stage == PublicationIndexFaultStage.BEFORE_FORCE) {
                throw failure;
            }
            super.forceFile(file, guard);
            if (isTarget(file) && publicationWritten) {
                publicationWritten = false;
                if (stage == PublicationIndexFaultStage.AFTER_FORCE) {
                    throw failure;
                }
            }
        }

        private boolean isTarget(Path file) {
            return file.getFileName().toString().equals(slot.fileName);
        }
    }

    private enum Task4ConstructorInitializationBoundary {
        WORLD_PARENT_FORCE,
        CHUNK_CREATE,
        CHUNK_PARENT_FORCE,
        MAIN_CREATE,
        MAIN_FILE_FORCE,
        MAIN_PARENT_FORCE,
        RECOVERY_CREATE,
        RECOVERY_FILE_FORCE,
        RECOVERY_PARENT_FORCE
    }

    private static final class Task4InitializationFailure extends IOException {
        private Task4InitializationFailure() {
            super("injected Task4 constructor initialization failure");
        }
    }

    private static final class Task4ConstructorInitializationFaultOperations
            extends ForwardingSaveFileOperations {
        private final Path root;
        private final Path world;
        private final Path chunkDirectory;
        private final Path main;
        private final Path recovery;
        private final Task4ConstructorInitializationBoundary boundary;
        private final Task4InitializationFailure failure;
        private boolean mainForced;
        private boolean recoveryForced;
        private boolean fired;

        private Task4ConstructorInitializationFaultOperations(
                Path root,
                Task4ConstructorInitializationBoundary boundary,
                Task4InitializationFailure failure) {
            this.root = root.toAbsolutePath().normalize();
            this.world = this.root.resolve(SAVE_ID.value());
            this.chunkDirectory = world.resolve("streamed-chunks");
            this.main = world.resolve("streamed-chunks.idx");
            this.recovery = world.resolve("streamed-chunks.prev.idx");
            this.boundary = boundary;
            this.failure = failure;
        }

        @Override
        public void createDirectory(Path directory, MutationGuard guard)
                throws IOException {
            super.createDirectory(directory, guard);
            if (!fired
                    && boundary == Task4ConstructorInitializationBoundary.CHUNK_CREATE
                    && normalized(directory).equals(chunkDirectory)) {
                fire();
            }
        }

        @Override
        public void createBounded(
                Path file, byte[] bytes, long maximumBytes, MutationGuard guard)
                throws IOException {
            super.createBounded(file, bytes, maximumBytes, guard);
            Path normalized = normalized(file);
            if (!fired
                    && boundary == Task4ConstructorInitializationBoundary.MAIN_CREATE
                    && normalized.equals(main)) {
                fire();
            }
            if (!fired
                    && boundary == Task4ConstructorInitializationBoundary.RECOVERY_CREATE
                    && normalized.equals(recovery)) {
                fire();
            }
        }

        @Override
        public void forceFile(Path file, MutationGuard guard) throws IOException {
            super.forceFile(file, guard);
            Path normalized = normalized(file);
            if (normalized.equals(main)) {
                mainForced = true;
                if (!fired
                        && boundary
                                == Task4ConstructorInitializationBoundary.MAIN_FILE_FORCE) {
                    fire();
                }
            }
            if (normalized.equals(recovery)) {
                recoveryForced = true;
                if (!fired
                        && boundary
                                == Task4ConstructorInitializationBoundary.RECOVERY_FILE_FORCE) {
                    fire();
                }
            }
        }

        @Override
        public void forceDirectoryDurably(Path directory, MutationGuard guard)
                throws IOException {
            super.forceDirectoryDurably(directory, guard);
            Path normalized = normalized(directory);
            if (!fired
                    && boundary
                            == Task4ConstructorInitializationBoundary.WORLD_PARENT_FORCE
                    && normalized.equals(root)) {
                fire();
            }
            if (!fired
                    && boundary
                            == Task4ConstructorInitializationBoundary.CHUNK_PARENT_FORCE
                    && normalized.equals(world)
                    && Files.isDirectory(chunkDirectory, LinkOption.NOFOLLOW_LINKS)
                    && !Files.exists(main, LinkOption.NOFOLLOW_LINKS)) {
                fire();
            }
            if (!fired
                    && boundary
                            == Task4ConstructorInitializationBoundary.MAIN_PARENT_FORCE
                    && normalized.equals(world)
                    && mainForced
                    && !recoveryForced) {
                fire();
            }
            if (!fired
                    && boundary
                            == Task4ConstructorInitializationBoundary.RECOVERY_PARENT_FORCE
                    && normalized.equals(world)
                    && recoveryForced) {
                fire();
            }
        }

        private void fire() throws Task4InitializationFailure {
            fired = true;
            throw failure;
        }
    }

    private enum InitializingIntentSlot {
        A("streamed-migration.published.a.v2"),
        B("streamed-migration.published.b.v2");

        private final String fileName;

        InitializingIntentSlot(String fileName) {
            this.fileName = fileName;
        }

        private boolean matches(Path path) {
            return path.getFileName().toString().equals(fileName);
        }
    }

    private enum InitializingIntentFaultStage {
        AFTER_CREATE,
        AFTER_WRITE,
        AFTER_FILE_FORCE,
        AFTER_PARENT_FORCE
    }

    private static final class InitializingIntentFailure extends IOException {
        private InitializingIntentFailure() {
            super("injected initializing-intent failure");
        }
    }

    private static final class InitializingIntentFaultOperations
            extends ForwardingSaveFileOperations {
        private final Path task4Directory;
        private final Path main;
        private final Path recovery;
        private final InitializingIntentSlot slot;
        private final InitializingIntentFaultStage stage;
        private final InitializingIntentFailure failure;
        private boolean armedParentForce;
        private boolean intentWritten;
        private boolean task4WasAbsentAtFault;
        private boolean fired;

        private InitializingIntentFaultOperations(
                Path root,
                InitializingIntentSlot slot,
                InitializingIntentFaultStage stage,
                InitializingIntentFailure failure) {
            Path world = root.toAbsolutePath().normalize().resolve(SAVE_ID.value());
            this.task4Directory = world.resolve("streamed-chunks");
            this.main = world.resolve("streamed-chunks.idx");
            this.recovery = world.resolve("streamed-chunks.prev.idx");
            this.slot = slot;
            this.stage = stage;
            this.failure = failure;
        }

        @Override
        public void createBounded(
                Path file, byte[] bytes, long maximumBytes, MutationGuard guard)
                throws IOException {
            super.createBounded(file, bytes, maximumBytes, guard);
            if (!fired && slot.matches(file)
                    && stage == InitializingIntentFaultStage.AFTER_CREATE) {
                fire();
            }
        }

        @Override
        public void writeExistingBounded(
                Path file, byte[] bytes, long maximumBytes, MutationGuard guard)
                throws IOException {
            super.writeExistingBounded(file, bytes, maximumBytes, guard);
            if (fired || !slot.matches(file)) {
                return;
            }
            intentWritten = true;
            if (stage == InitializingIntentFaultStage.AFTER_WRITE) {
                fire();
            }
        }

        @Override
        public void forceFile(Path file, MutationGuard guard) throws IOException {
            super.forceFile(file, guard);
            if (fired || !slot.matches(file) || !intentWritten) {
                return;
            }
            if (stage == InitializingIntentFaultStage.AFTER_FILE_FORCE) {
                fire();
            }
            if (stage == InitializingIntentFaultStage.AFTER_PARENT_FORCE) {
                armedParentForce = true;
            }
        }

        @Override
        public void forceDirectoryDurably(Path directory, MutationGuard guard)
                throws IOException {
            super.forceDirectoryDurably(directory, guard);
            if (!fired && armedParentForce) {
                fire();
            }
        }

        private boolean task4WasAbsentAtFault() {
            return task4WasAbsentAtFault;
        }

        private void fire() throws InitializingIntentFailure {
            task4WasAbsentAtFault = !Files.exists(
                            task4Directory, LinkOption.NOFOLLOW_LINKS)
                    && !Files.exists(main, LinkOption.NOFOLLOW_LINKS)
                    && !Files.exists(recovery, LinkOption.NOFOLLOW_LINKS);
            fired = true;
            throw failure;
        }
    }

    private static final class InitializingIntentBeforeTask4ObservationOperations
            extends ForwardingSaveFileOperations {
        private final Path world;
        private boolean observedFirstTask4Mutation;
        private boolean equalNonEmptyIntentQuorumBeforeTask4;

        private InitializingIntentBeforeTask4ObservationOperations(Path root) {
            this.world = root.toAbsolutePath().normalize().resolve(SAVE_ID.value());
        }

        @Override
        public void createDirectory(Path directory, MutationGuard guard)
                throws IOException {
            observeIfFirstTask4(directory);
            super.createDirectory(directory, guard);
        }

        @Override
        public void createBounded(
                Path file, byte[] bytes, long maximumBytes, MutationGuard guard)
                throws IOException {
            observeIfFirstTask4(file);
            super.createBounded(file, bytes, maximumBytes, guard);
        }

        private void observeIfFirstTask4(Path path) {
            if (observedFirstTask4Mutation || !isTask4(path)) {
                return;
            }
            observedFirstTask4Mutation = true;
            Path a = world.resolve("streamed-migration.published.a.v2");
            Path b = world.resolve("streamed-migration.published.b.v2");
            try {
                byte[] aBytes = Files.readAllBytes(a);
                byte[] bBytes = Files.readAllBytes(b);
                equalNonEmptyIntentQuorumBeforeTask4 = aBytes.length > 0
                        && java.util.Arrays.equals(aBytes, bBytes);
            } catch (IOException absentOrUnreadable) {
                equalNonEmptyIntentQuorumBeforeTask4 = false;
            }
        }

        private boolean isTask4(Path path) {
            Path normalized = normalized(path);
            return normalized.equals(world.resolve("streamed-chunks"))
                    || normalized.equals(world.resolve("streamed-chunks.idx"))
                    || normalized.equals(world.resolve("streamed-chunks.prev.idx"));
        }

        private boolean observedFirstTask4Mutation() {
            return observedFirstTask4Mutation;
        }

        private boolean equalNonEmptyIntentQuorumBeforeTask4() {
            return equalNonEmptyIntentQuorumBeforeTask4;
        }
    }

    private static Path normalized(Path path) {
        return path.toAbsolutePath().normalize();
    }

    private enum PublishedCorruption {
        PAYLOAD {
            @Override
            void apply(Path world) throws IOException {
                Path referenced = firstNonEmptyPayload(world);
                byte[] bytes = Files.readAllBytes(referenced);
                bytes[bytes.length / 2] ^= 0x5a;
                Files.write(referenced, bytes);
            }
        },
        INDEX {
            @Override
            void apply(Path world) throws IOException {
                Files.write(world.resolve("streamed-chunks.idx"), new byte[] {1, 2, 3});
                Files.write(
                        world.resolve("streamed-chunks.prev.idx"),
                        new byte[] {4, 5, 6});
            }
        },
        NEWEST_INDEX_CORRUPT_OLDER_UNPUBLISHED {
            @Override
            void apply(Path world) throws IOException {
                StreamedChunkIndex published = reopenedBatchIndex(world.getParent());
                StreamedChunkIndex unpublished = new StreamedChunkIndex(
                        published.saveGameId(), published.entries());
                long sequence = Math.max(
                        slotSequence(Files.readAllBytes(
                                world.resolve("streamed-chunks.idx"))),
                        slotSequence(Files.readAllBytes(
                                world.resolve("streamed-chunks.prev.idx"))));
                Files.write(
                        world.resolve("streamed-chunks.idx"),
                        new byte[] {1, 2, 3});
                Files.write(
                        world.resolve("streamed-chunks.prev.idx"),
                        encodeIndexEnvelope(
                                Math.max(0L, sequence - 1L),
                                INDEX_CODEC.encode(unpublished)));
            }
        },
        BACKUP {
            @Override
            void apply(Path world) throws IOException {
                Files.write(world.resolve("backup.glsave"), new byte[] {7, 8, 9});
            }
        },
        ONE_MARKER {
            @Override
            void apply(Path world) throws IOException {
                Files.write(world.resolve("streamed-migration.a.v2"), new byte[0]);
            }
        };

        abstract void apply(Path world) throws IOException;
    }

    private enum LostPublicationSideFiles {
        TRUNCATE_BOTH_FLOORS_AND_CORRUPT_ONE_MARKER {
            @Override
            void apply(Path world) throws IOException {
                Files.write(
                        world.resolve("streamed-migration.published.a.v2"),
                        new byte[0]);
                Files.write(
                        world.resolve("streamed-migration.published.b.v2"),
                        new byte[0]);
                Files.write(
                        world.resolve("streamed-migration.a.v2"),
                        new byte[] {1, 2, 3});
            }
        },
        DELETE_ONE_FLOOR_EMPTY_OTHER_AND_CORRUPT_MARKER {
            @Override
            void apply(Path world) throws IOException {
                Files.delete(world.resolve("streamed-migration.published.a.v2"));
                Files.write(
                        world.resolve("streamed-migration.published.b.v2"),
                        new byte[0]);
                Files.write(
                        world.resolve("streamed-migration.b.v2"),
                        new byte[] {4, 5, 6});
            }
        },
        DELETE_ALL_FOUR_SIDE_FILES {
            @Override
            void apply(Path world) throws IOException {
                Files.delete(world.resolve("streamed-migration.a.v2"));
                Files.delete(world.resolve("streamed-migration.b.v2"));
                Files.delete(world.resolve("streamed-migration.published.a.v2"));
                Files.delete(world.resolve("streamed-migration.published.b.v2"));
            }
        };

        abstract void apply(Path world) throws IOException;
    }

    private enum DegradedTask4AuthorityWithoutSides {
        BOTH_INDEX_SLOTS_CORRUPT {
            @Override
            void apply(Path root, Path world) throws IOException {
                Files.write(world.resolve("streamed-chunks.idx"), new byte[] {1, 2, 3});
                Files.write(
                        world.resolve("streamed-chunks.prev.idx"),
                        new byte[] {4, 5, 6});
            }
        },
        ONE_INDEX_MISSING_SURVIVOR_PROOF_FREE {
            @Override
            void apply(Path root, Path world) throws IOException {
                StreamedChunkIndex published = reopenedBatchIndex(root);
                long sequence = Math.max(
                        slotSequence(Files.readAllBytes(
                                world.resolve("streamed-chunks.idx"))),
                        slotSequence(Files.readAllBytes(
                                world.resolve("streamed-chunks.prev.idx"))));
                Files.delete(world.resolve("streamed-chunks.idx"));
                Files.write(
                        world.resolve("streamed-chunks.prev.idx"),
                        proofFreeEnvelope(published, Math.max(0L, sequence - 1L)));
            }
        },
        NEWEST_INDEX_CORRUPT_OLDER_PROOF_FREE {
            @Override
            void apply(Path root, Path world) throws IOException {
                StreamedChunkIndex published = reopenedBatchIndex(root);
                long sequence = Math.max(
                        slotSequence(Files.readAllBytes(
                                world.resolve("streamed-chunks.idx"))),
                        slotSequence(Files.readAllBytes(
                                world.resolve("streamed-chunks.prev.idx"))));
                Files.write(world.resolve("streamed-chunks.idx"), new byte[] {7, 8, 9});
                Files.write(
                        world.resolve("streamed-chunks.prev.idx"),
                        proofFreeEnvelope(published, Math.max(0L, sequence - 1L)));
            }
        },
        EQUAL_SEQUENCE_CONFLICT {
            @Override
            void apply(Path root, Path world) throws IOException {
                StreamedChunkIndex published = reopenedBatchIndex(root);
                long sequence = Math.max(
                        slotSequence(Files.readAllBytes(
                                world.resolve("streamed-chunks.idx"))),
                        slotSequence(Files.readAllBytes(
                                world.resolve("streamed-chunks.prev.idx"))));
                Files.write(
                        world.resolve("streamed-chunks.idx"),
                        encodeIndexEnvelope(sequence, INDEX_CODEC.encode(published)));
                Files.write(
                        world.resolve("streamed-chunks.prev.idx"),
                        proofFreeEnvelope(published, sequence));
            }
        };

        abstract void apply(Path root, Path world) throws IOException;

        private static byte[] proofFreeEnvelope(
                StreamedChunkIndex published, long sequence) throws IOException {
            StreamedChunkIndex proofFree = new StreamedChunkIndex(
                    published.saveGameId(), published.entries());
            return encodeIndexEnvelope(
                    sequence, INDEX_CODEC.encode(proofFree));
        }
    }

    private enum UnprovenDeleteEntry {
        CANONICAL_PAYLOAD_NAME_WITH_ARBITRARY_BYTES {
            @Override
            TouchedSentinel apply(Path world) throws IOException {
                Path payload = firstNonEmptyPayload(world);
                byte[] arbitrary = "not-a-streamed-chunk-payload"
                        .getBytes(StandardCharsets.UTF_8);
                Files.write(payload, arbitrary);
                return new TouchedSentinel(payload, arbitrary);
            }
        },
        REPLACED_TOP_AUTHORITY_BYTES {
            @Override
            TouchedSentinel apply(Path world) throws IOException {
                Path index = world.resolve("streamed-chunks.idx");
                byte[] arbitrary = "not-a-task4-index-slot"
                        .getBytes(StandardCharsets.UTF_8);
                Files.write(index, arbitrary);
                return new TouchedSentinel(index, arbitrary);
            }
        },
        REPLACED_MIGRATION_MARKER_BYTES {
            @Override
            TouchedSentinel apply(Path world) throws IOException {
                Path marker = world.resolve("streamed-migration.a.v2");
                byte[] arbitrary = "not-a-migration-marker"
                        .getBytes(StandardCharsets.UTF_8);
                Files.write(marker, arbitrary);
                return new TouchedSentinel(marker, arbitrary);
            }
        },
        REPLACED_CURRENT_ARCHIVE_BYTES {
            @Override
            TouchedSentinel apply(Path world) throws IOException {
                Path current = world.resolve("current.glsave");
                byte[] arbitrary = "not-the-migration-source-archive"
                        .getBytes(StandardCharsets.UTF_8);
                Files.write(current, arbitrary);
                return new TouchedSentinel(current, arbitrary);
            }
        },
        FOREIGN_SENTINEL_TMP {
            @Override
            TouchedSentinel apply(Path world) throws IOException {
                Path sentinel = world.resolve("foreign-sentinel.tmp");
                byte[] arbitrary = "foreign-temp-must-survive"
                        .getBytes(StandardCharsets.UTF_8);
                Files.write(sentinel, arbitrary);
                return new TouchedSentinel(sentinel, arbitrary);
            }
        };

        abstract TouchedSentinel apply(Path world) throws IOException;
    }
}

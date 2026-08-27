package com.gaia;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gaia.interaction.GameMode;
import com.gaia.save.archive.SaveArchiveReader;
import com.gaia.save.archive.SaveArchiveWriter;
import com.gaia.save.codec.ChunkSectionCodec;
import com.gaia.save.codec.InventorySectionCodec;
import com.gaia.save.codec.PlayerSectionCodec;
import com.gaia.save.codec.SaveSnapshotCodec;
import com.gaia.save.codec.WorldItemsSectionCodec;
import com.gaia.save.format.SaveFormatVersion;
import com.gaia.save.format.SaveGameId;
import com.gaia.save.format.SaveSectionId;
import com.gaia.save.snapshot.SaveGameSnapshot;
import com.gaia.save.snapshot.WorldItemsSaveSnapshot;
import com.gaia.save.store.AtomicSaveStore;
import com.gaia.save.store.JdkSaveFileOperations;
import com.gaia.save.store.SaveRepository;
import com.gaia.save.streaming.Phase14MigrationResult;
import com.gaia.save.streaming.Phase14SaveMigrator;
import com.gaia.save.streaming.StreamedChunkCodec;
import com.gaia.save.streaming.StreamedChunkIndexCodec;
import com.gaia.save.streaming.StreamedChunkPayload;
import com.gaia.save.streaming.StreamedChunkStore;
import com.gaia.save.streaming.StreamedWorldItemPageBackend;
import com.gaia.save.streaming.StreamedGlobalExtension;
import com.gaia.save.streaming.StreamedGlobalExtensionMutation;
import com.gaia.save.streaming.StreamedPersistenceTransaction;
import com.gaia.save.streaming.StreamedSessionSaveTarget;
import com.gaia.save.streaming.WorldItemPageCodec;
import com.gaia.save.store.SaveDeleteResult;
import com.gaia.session.GameSession;
import com.gaia.session.GameSessionConfig;
import com.gaia.session.GameSessionFrame;
import com.gaia.session.GameSessionFactory;
import com.gaia.session.GameSessionPersistenceTestFixture;
import com.gaia.session.GameSessionSaveLifecycleTest;
import com.gaia.session.GameSessionState;
import com.gaia.session.LoadWorldRequest;
import com.gaia.session.NewWorldRequest;
import com.gaia.session.SessionPersistenceRevision;
import com.gaia.session.SessionSaveCaptureResult;
import com.gaia.shell.ProductLoop;
import com.overlord.assets.ResourceLocation;
import com.overlord.core.thread.MainThreadGuard;
import com.overlord.core.input.MouseDelta;
import com.overlord.inventory.api.ItemStack;
import com.overlord.worlditem.LogicalWorldItemService;
import com.overlord.worlditem.api.LogicalWorldItemSnapshot;
import com.overlord.worlditem.api.SaveIdentity;
import com.overlord.worlditem.api.WorldItemDurableProof;
import com.overlord.worlditem.api.WorldItemPageCachePolicy;
import com.overlord.worlditem.api.WorldItemPageDescriptor;
import com.overlord.worlditem.api.WorldItemPersistencePlan;
import com.overlord.worlditem.api.WorldItemPersistenceTicket;
import com.overlord.worlditem.api.WorldItemRestoreResult;
import com.overlord.worlditem.api.WorldItemSpawnRequest;
import com.overlord.worlditem.api.WorldItemSnapshot;
import com.overlord.voxel.ChunkKey;
import com.overlord.voxel.ChunkSnapshot;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GameBootstrapSaveCompositionTest {
    @TempDir
    Path temporaryRoot;

    @Test
    void realCompositionCreatesLoadsRefreshesAndDeletesOneStableWorldSlot() {
        NewWorldRequest request = new NewWorldRequest(
                GameSessionSaveLifecycleTest.ID, "New World", 12345L);
        AtomicReference<SaveGameSnapshot> restored = new AtomicReference<>();
        GameBootstrap.SaveComposition composition = GameBootstrap.composeSaveLoad(
                temporaryRoot.resolve("saves"),
                (createdRequest, config) -> {
                    assertEquals(request, createdRequest);
                    assertEquals(12345L, config.seed());
                    assertEquals(2, config.chunkRadius());
                    return new PersistenceSession(GameSessionSaveLifecycleTest.snapshot());
                },
                snapshot -> {
                    restored.set(snapshot);
                    return new PersistenceSession(snapshot);
                },
                () -> new GameSessionConfig(999L, 2, GameMode.SURVIVAL, false),
                () -> Instant.parse("2026-08-12T01:00:00Z"),
                () -> request.saveGameId());
        ProductLoop.PersistenceServices services = composition.persistenceServices();

        GameSession created = services.sessions().newWorld(request);
        created.pollLoad();
        assertEquals(GameSessionState.READY, created.state());
        services.worldSlots().refresh();
        assertEquals(
                request.saveGameId(),
                services.worldSlots().snapshot().rows().get(0).id());
        created.close();

        GameSession loaded = services.sessions().loadWorld(
                new LoadWorldRequest(request.saveGameId()));
        loaded.pollLoad();
        assertEquals(GameSessionState.READY, loaded.state());
        assertEquals(request.saveGameId(),
                restored.get().metadata().saveGameId());
        loaded.close();

        SaveDeleteResult deleted = services.worldSlotOperations()
                .delete(request.saveGameId());
        assertTrue(deleted.status() == SaveDeleteResult.Status.SUCCESS
                || deleted.status()
                        == SaveDeleteResult.Status.DELETED_WITH_CLEANUP_WARNING);
        services.worldSlots().refresh();
        assertTrue(services.worldSlots().snapshot().rows().isEmpty());
    }

    @Test
    void productionStreamedTargetBootstrapsFreshNewWorldAuthority() throws Exception {
        Path saveRoot = Files.createDirectory(temporaryRoot.resolve("fresh-streamed"));
        SaveGameId id = GameSessionSaveLifecycleTest.ID;
        SaveSnapshotCodec codec = snapshotCodec();
        JdkSaveFileOperations files = new JdkSaveFileOperations();
        StreamedChunkStore store = new StreamedChunkStore(
                saveRoot, id, new StreamedChunkCodec(),
                new StreamedChunkIndexCodec(), files);
        GameSessionFactory.StreamingBackends graph =
                new GameSessionFactory.StreamingBackends(
                        store, new StreamedWorldItemPageBackend(store));
        var method = GameBootstrap.class.getDeclaredMethod(
                "composeStreamedSaveTarget",
                Path.class,
                SaveGameId.class,
                GameSessionFactory.StreamingBackends.class);
        method.setAccessible(true);
        var target = (com.gaia.save.session.SaveCoordinator.SaveTarget)
                method.invoke(null, saveRoot, id, graph);

        SaveGameSnapshot legacy = GameSessionSaveLifecycleTest.snapshot();
        SaveGameSnapshot paged = new SaveGameSnapshot(
                legacy.metadata(),
                legacy.fixedTick(),
                legacy.chunks(),
                legacy.player(),
                legacy.inventory(),
                new WorldItemsSaveSnapshot(
                        0L,
                        List.of(),
                        0L,
                        false,
                        LogicalWorldItemSnapshot.Completeness.PAGED_PARTIAL));
        LogicalWorldItemService logical = service(graph.worldItems(), id);
        var preparation = logical.prepareSavePersistence();
        var atomic = target.saveAtomically(
                paged,
                Instant.parse("2026-08-12T01:00:00Z"),
                preparation.persistencePlan(),
                key -> legacy.chunks().chunks().stream()
                        .filter(chunk -> chunk.key().equals(key))
                        .findFirst());
        var result = atomic.writeResult();

        assertEquals(com.gaia.save.store.SaveWriteResult.Status.SUCCESS,
                result.status(), () -> result.diagnostics().stream()
                        .map(diagnostic -> diagnostic.code() + ": "
                                + diagnostic.message() + " cause="
                                + diagnostic.cause().map(Throwable::toString)
                                        .orElse("none"))
                        .toList().toString());
        var published = com.gaia.save.streaming.Phase14SaveMigrator.readPublished(
                saveRoot, id, new SaveArchiveReader(codec), files).orElseThrow();
        assertTrue(published.snapshot().chunks().chunks().isEmpty(),
                "fresh deterministic base Chunks must not become legacy durable payloads");
        assertTrue(published.index().entries().isEmpty(),
                "fresh bootstrap migration must not write reproducible base Chunks");
        assertTrue(store.readCurrentAuthority(id).index().orElseThrow()
                .globalExtension(SaveSectionId.STREAMED_SESSION_CHECKPOINT)
                .isPresent());
        logical.commitPersistence(
                preparation.persistenceTicket().orElseThrow(),
                atomic.worldItemProof().orElseThrow());
    }

    @Test
    void productionFreshBootstrapRejectsNonEmptyPagedAuthorityWithoutV1Downgrade()
            throws Exception {
        Path saveRoot = Files.createDirectory(
                temporaryRoot.resolve("fresh-streamed-nonempty"));
        SaveGameId id = GameSessionSaveLifecycleTest.ID;
        JdkSaveFileOperations files = new JdkSaveFileOperations();
        StreamedChunkStore store = new StreamedChunkStore(
                saveRoot, id, new StreamedChunkCodec(),
                new StreamedChunkIndexCodec(), files);
        GameSessionFactory.StreamingBackends graph =
                new GameSessionFactory.StreamingBackends(
                        store, new StreamedWorldItemPageBackend(store));
        var method = GameBootstrap.class.getDeclaredMethod(
                "composeStreamedSaveTarget",
                Path.class,
                SaveGameId.class,
                GameSessionFactory.StreamingBackends.class);
        method.setAccessible(true);
        var target = (com.gaia.save.session.SaveCoordinator.SaveTarget)
                method.invoke(null, saveRoot, id, graph);
        LogicalWorldItemService logical = service(graph.worldItems(), id);
        logical.spawn(new WorldItemSpawnRequest(
                new ItemStack(ResourceLocation.of("gaia", "test/no-downgrade"), 1),
                0.5, 1.0, 0.5, 0.0, 0.0, 0.0, Optional.empty(), 0L));
        SaveGameSnapshot legacy = GameSessionSaveLifecycleTest.snapshot();
        SaveGameSnapshot paged = new SaveGameSnapshot(
                legacy.metadata(),
                0L,
                legacy.chunks(),
                legacy.player(),
                legacy.inventory(),
                new WorldItemsSaveSnapshot(0L, logical.canonicalSnapshot()));
        var preparation = logical.prepareSavePersistence();

        var atomic = target.saveAtomically(
                paged,
                Instant.parse("2026-08-12T01:00:00Z"),
                preparation.persistencePlan(),
                key -> legacy.chunks().chunks().stream()
                        .filter(chunk -> chunk.key().equals(key))
                        .findFirst());

        assertEquals(com.gaia.save.store.SaveWriteResult.Status.FAILED,
                atomic.writeResult().status());
        assertTrue(atomic.worldItemProof().isEmpty());
        assertFalse(Files.exists(
                saveRoot.resolve(id.value()).resolve("current.glsave"),
                java.nio.file.LinkOption.NOFOLLOW_LINKS));
        assertTrue(Phase14SaveMigrator.readPublished(
                saveRoot, id, new SaveArchiveReader(snapshotCodec()), files).isEmpty());
        logical.cancelPersistence(preparation.persistenceTicket().orElseThrow());
    }

    @Test
    void realCompositionWritesAndReopensStreamedV2WithoutLossyLegacyRewrite()
            throws Exception {
        Path saveRoot = temporaryRoot.resolve("streamed-saves");
        Files.createDirectory(saveRoot);
        SaveGameId id = GameSessionSaveLifecycleTest.ID;
        SaveGameSnapshot legacy = GameSessionSaveLifecycleTest.snapshot();
        SaveSnapshotCodec codec = snapshotCodec();
        JdkSaveFileOperations files = new JdkSaveFileOperations();
        AtomicSaveStore v1 = new AtomicSaveStore(
                saveRoot, id, codec, new SaveArchiveWriter(),
                new SaveArchiveReader(codec), files);
        assertEquals(com.gaia.save.store.SaveWriteResult.Status.SUCCESS,
                v1.save(legacy, Instant.parse("2026-08-12T00:30:00Z")).status());
        SaveRepository repository = SaveRepository.open(
                saveRoot, new SaveArchiveReader(codec), files);
        assertEquals(Phase14MigrationResult.Status.MIGRATED,
                repository.migratePhase14(id).status());
        Path worldRoot = saveRoot.resolve(id.value());
        byte[] exactCurrentBefore = Files.readAllBytes(worldRoot.resolve("current.glsave"));
        byte[] exactBackupBefore = Files.readAllBytes(worldRoot.resolve("backup.glsave"));
        AtomicReference<StreamedPersistenceSession> delegate = new AtomicReference<>();
        ChunkKey distant = new ChunkKey(20, -20);

        GameBootstrap.SaveComposition composition = GameBootstrap.composeSaveLoad(
                saveRoot,
                (request, config) -> {
                    throw new AssertionError("new-world path is not expected");
                },
                restored -> {
                    StreamedWorldItemPageBackend backend = backend(saveRoot, id);
                    LogicalWorldItemService worldItems = service(backend, id);
                    assertEquals(WorldItemRestoreResult.Status.RESTORED,
                            backend.restoreFresh(
                                    worldItems,
                                    new SaveIdentity(UUID.fromString(id.value())),
                                    restored.fixedTick()).status());
                    WorldItemSnapshot item = worldItems.spawn(new WorldItemSpawnRequest(
                            new ItemStack(ResourceLocation.of("gaia", "test/restart"), 2),
                            distant.worldOriginX() + 0.5,
                            4.0,
                            distant.worldOriginZ() + 0.5,
                            0.0, 0.0, 0.0,
                            Optional.empty(), restored.fixedTick()))
                            .item().orElseThrow();
                    worldItems.deliverWorldTick(10_800L);
                    StreamedPersistenceSession session = new StreamedPersistenceSession(
                            restored, worldItems, item, distant);
                    delegate.set(session);
                    return session;
                },
                () -> new GameSessionConfig(999L, 2, GameMode.SURVIVAL, false),
                () -> Instant.parse("2026-08-12T01:00:00Z"),
                () -> id);

        GameSession loaded = composition.persistenceServices().sessions()
                .loadWorld(new LoadWorldRequest(id));
        loaded.pollLoad();
        assertEquals(GameSessionState.READY, loaded.state());
        assertEquals(com.gaia.session.GameSessionSaveResult.Status.SUCCESS,
                loaded.save().status());
        assertFalse(delegate.get().closed());

        assertArrayEquals(exactCurrentBefore,
                Files.readAllBytes(worldRoot.resolve("current.glsave")),
                "streamed save must not rewrite a lossy v1 current archive");
        assertArrayEquals(exactBackupBefore,
                Files.readAllBytes(worldRoot.resolve("backup.glsave")),
                "streamed save must retain the exact Phase14 recovery archive");
        assertEquals(SaveFormatVersion.STREAMED_CHUNKS,
                composition.catalog().summaries().get(0).formatVersion().orElseThrow());
        assertEquals(com.gaia.shell.save.SaveSummary.Health.VALID,
                composition.catalog().summaries().get(0).health());
        assertEquals(Instant.parse("2026-08-12T01:00:00Z"),
                composition.catalog().summaries().get(0).modifiedTime());

        SaveRepository relaunchedRepository = SaveRepository.open(
                saveRoot, new SaveArchiveReader(snapshotCodec()),
                new JdkSaveFileOperations());
        var reopened = relaunchedRepository.load(id);
        assertEquals(com.gaia.save.archive.SaveArchiveReadResult.Status.VALID,
                reopened.status());
        assertEquals(10_800L, reopened.snapshot().orElseThrow().fixedTick());
        assertEquals(LogicalWorldItemSnapshot.Completeness.PAGED_PARTIAL,
                reopened.snapshot().orElseThrow().worldItems().logicalSnapshot()
                        .completeness());
        StreamedWorldItemPageBackend relaunched = backend(saveRoot, id);
        LogicalWorldItemService fresh = service(relaunched, id);
        assertEquals(WorldItemRestoreResult.Status.RESTORED,
                relaunched.restoreFresh(
                        fresh,
                        new SaveIdentity(UUID.fromString(id.value())),
                        10_800L).status());
        assertEquals(List.of(delegate.get().item().id()), fresh.liveMetadata().stream()
                .map(row -> row.id()).toList());
        assertEquals(18_000L, fresh.liveMetadata().get(0).expiresAtWorldTick());
        assertEquals(10_800L, fresh.currentWorldTick());
        assertEquals(distant, fresh.liveMetadata().get(0).intendedChunkKey(),
                "the first page in a previously absent Chunk must persist exactly");
        loaded.close();

        byte[] mainIndexBeforeRejectedSave = Files.readAllBytes(
                worldRoot.resolve("streamed-chunks.idx"));
        byte[] recoveryIndexBeforeRejectedSave = Files.readAllBytes(
                worldRoot.resolve("streamed-chunks.prev.idx"));
        StreamedSessionSaveTarget target = new StreamedSessionSaveTarget(
                saveRoot,
                id,
                new SaveArchiveReader(snapshotCodec()),
                new JdkSaveFileOperations());
        assertEquals(com.gaia.save.store.SaveWriteResult.Status.FAILED,
                target.save(
                        reopened.snapshot().orElseThrow(),
                        reopened.snapshot().orElseThrow().metadata().createdAt()
                                .minusSeconds(1L)).status());
        assertArrayEquals(mainIndexBeforeRejectedSave,
                Files.readAllBytes(worldRoot.resolve("streamed-chunks.idx")),
                "invalid manifest time must fail before durable root publication");
        assertArrayEquals(recoveryIndexBeforeRejectedSave,
                Files.readAllBytes(worldRoot.resolve("streamed-chunks.prev.idx")),
                "invalid manifest time must not rotate the recovery root");

        delegate.get().worldItems().deliverWorldTick(10_801L);
        var unsafePreparation = delegate.get().worldItems().prepareSavePersistence();
        assertThrows(UnsupportedOperationException.class,
                () -> target.persistWorldItems(
                        unsafePreparation.persistencePlan().orElseThrow()),
                "a page-only publication could leave the required session root stale");
        delegate.get().worldItems().cancelPersistence(
                unsafePreparation.persistenceTicket().orElseThrow());

        StreamedChunkStore corruptingStore = new StreamedChunkStore(
                saveRoot,
                id,
                new StreamedChunkCodec(),
                new StreamedChunkIndexCodec(),
                new JdkSaveFileOperations());
        assertEquals(StreamedChunkStore.CommitResult.Status.SUCCESS,
                corruptingStore.commitTransaction(new StreamedPersistenceTransaction(
                        List.of(),
                        List.of(new StreamedGlobalExtensionMutation.Upsert(
                                new StreamedGlobalExtension(
                                        SaveSectionId.STREAMED_SESSION_CHECKPOINT,
                                        1,
                                        true,
                                        Optional.empty(),
                                        new byte[] {1, 2, 3}))),
                        () -> true)).status());
        assertEquals(com.gaia.shell.save.SaveSummary.Health.CORRUPT,
                composition.catalog().summaries().get(0).health(),
                "catalog health must validate the latest streamed session root");
    }

    @Test
    void streamedSavePublishesPreparedResidentModificationWithSessionRoot()
            throws Exception {
        Path saveRoot = temporaryRoot.resolve("dirty-resident-save");
        Files.createDirectory(saveRoot);
        SaveGameId id = GameSessionSaveLifecycleTest.ID;
        SaveSnapshotCodec codec = snapshotCodec();
        JdkSaveFileOperations files = new JdkSaveFileOperations();
        SaveGameSnapshot legacy = GameSessionSaveLifecycleTest.snapshot();
        assertEquals(
                com.gaia.save.store.SaveWriteResult.Status.SUCCESS,
                new AtomicSaveStore(
                                saveRoot,
                                id,
                                codec,
                                new SaveArchiveWriter(),
                                new SaveArchiveReader(codec),
                                files)
                        .save(legacy, Instant.parse("2026-08-12T00:30:00Z"))
                        .status());
        SaveRepository repository = SaveRepository.open(
                saveRoot, new SaveArchiveReader(codec), files);
        assertEquals(
                Phase14MigrationResult.Status.MIGRATED,
                repository.migratePhase14(id).status());
        SaveGameSnapshot restored = repository.load(id).snapshot().orElseThrow();
        ChunkSnapshot previous = restored.chunks().chunks().get(0);
        byte[] modifiedBlocks = previous.copyBlocks();
        modifiedBlocks[0] = (byte) (modifiedBlocks[0] == 91 ? 92 : 91);
        ChunkSnapshot modified = ChunkSnapshot.of(
                previous.key(),
                previous.revision() + 1L,
                previous.worldHeight(),
                modifiedBlocks);
        List<ChunkSnapshot> currentChunks = restored.chunks().chunks().stream()
                .map(chunk -> chunk.key().equals(modified.key()) ? modified : chunk)
                .toList();
        SaveGameSnapshot captured = new SaveGameSnapshot(
                restored.metadata(),
                restored.fixedTick(),
                new com.overlord.voxel.ChunkRepositorySnapshot(
                        restored.chunks().worldHeight(),
                        modified.revision(),
                        currentChunks),
                restored.player(),
                restored.inventory(),
                restored.worldItems());
        StreamedSessionSaveTarget target = new StreamedSessionSaveTarget(
                saveRoot,
                id,
                new SaveArchiveReader(codec),
                files);
        byte[] oldRoot = new StreamedChunkIndexCodec().encode(
                chunkStore(saveRoot, id, files).readCurrentAuthority(id)
                        .index()
                        .orElseThrow());
        AtomicBoolean freshness = new AtomicBoolean(false);
        var dirty = new com.gaia.save.session.SaveCoordinator
                .PreparedDirtyChunkCapture(modified, freshness::get);

        var staleWrite = target.saveAtomically(
                captured,
                Instant.parse("2026-08-12T01:30:00Z"),
                Optional.empty(),
                key -> Optional.of(modified).filter(chunk -> chunk.key().equals(key)),
                List.of(dirty));
        assertEquals(com.gaia.save.store.SaveWriteResult.Status.FAILED,
                staleWrite.writeResult().status());
        assertArrayEquals(oldRoot, new StreamedChunkIndexCodec().encode(
                chunkStore(saveRoot, id, files).readCurrentAuthority(id)
                        .index()
                        .orElseThrow()),
                "stale dirty capture must leave the old root authoritative");

        freshness.set(true);

        var write = target.saveAtomically(
                captured,
                Instant.parse("2026-08-12T02:00:00Z"),
                Optional.empty(),
                key -> Optional.of(modified).filter(chunk -> chunk.key().equals(key)),
                List.of(dirty));

        assertEquals(
                com.gaia.save.store.SaveWriteResult.Status.SUCCESS,
                write.writeResult().status());
        StreamedChunkStore.CurrentAuthorityReadResult durable =
                chunkStore(saveRoot, id, files).readCurrentAuthority(id);
        StreamedChunkPayload reread = durable.payloads().stream()
                .filter(payload -> payload.key().equals(modified.key()))
                .findFirst()
                .orElseThrow();
        assertEquals(modified.revision(), reread.revision());
        assertArrayEquals(modifiedBlocks, reread.copyCanonicalVoxels());
    }

    private static SaveSnapshotCodec snapshotCodec() {
        return new SaveSnapshotCodec(
                new ChunkSectionCodec(),
                new PlayerSectionCodec(),
                new InventorySectionCodec(),
                new WorldItemsSectionCodec());
    }

    private static StreamedWorldItemPageBackend backend(Path worldRoot, SaveGameId id) {
        return new StreamedWorldItemPageBackend(chunkStore(
                worldRoot, id, new JdkSaveFileOperations()));
    }

    private static StreamedChunkStore chunkStore(
            Path worldRoot,
            SaveGameId id,
            JdkSaveFileOperations files) {
        return new StreamedChunkStore(
                worldRoot,
                id,
                new StreamedChunkCodec(),
                new StreamedChunkIndexCodec(),
                files);
    }

    private static LogicalWorldItemService service(
            StreamedWorldItemPageBackend backend, SaveGameId id) {
        SaveIdentity identity = new SaveIdentity(UUID.fromString(id.value()));
        WorldItemPageCodec pageCodec = new WorldItemPageCodec();
        return new LogicalWorldItemService(
                MainThreadGuard.captureCurrentThread(),
                1_024,
                0L,
                identity,
                new WorldItemPageCachePolicy(
                        1_024, 32, 16L * 1_024L * 1_024L,
                        64, 1_024, 16L * 1_024L * 1_024L,
                        64, 64L * 1_024L),
                backend.durabilityVerifier(),
                page -> {
                    byte[] bytes = pageCodec.encode(identity, page);
                    return new WorldItemPageDescriptor(
                            page.chunkKey(),
                            page.pageRevision(),
                            HexFormat.of().formatHex(sha256(bytes)),
                            page.entries().size(),
                            page.entries().size());
                });
    }

    private static byte[] sha256(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static final class PersistenceSession implements GameSession {
        private final SaveGameSnapshot snapshot;
        private GameSessionState state = GameSessionState.LOADING;

        private PersistenceSession(SaveGameSnapshot snapshot) {
            this.snapshot = snapshot;
        }

        @Override public GameSessionState state() { return state; }
        @Override public void pollLoad() { state = GameSessionState.READY; }
        @Override public GameSessionFrame advancePlaying(
                double delta, MouseDelta look, boolean focused) {
            throw new AssertionError("gameplay not expected");
        }
        @Override public GameSessionFrame capturePaused() {
            throw new AssertionError("pause capture not expected");
        }
        @Override public SessionSaveCaptureResult captureSave() {
            return GameSessionPersistenceTestFixture.runtimeCaptured(snapshot, 0L);
        }
        @Override public void markSaved(SessionPersistenceRevision revision) {}
        @Override public boolean hasUnsavedChanges() { return false; }
        @Override public void discardFixedTime() {}
        @Override public void close() { state = GameSessionState.CLOSED; }
    }

    private static final class StreamedPersistenceSession implements GameSession {
        private final SaveGameSnapshot restored;
        private final LogicalWorldItemService worldItems;
        private final WorldItemSnapshot item;
        private final ChunkKey initialPageChunk;
        private GameSessionState state = GameSessionState.LOADING;
        private WorldItemPersistenceTicket ticket;
        private boolean closed;

        private LogicalWorldItemService worldItems() {
            return worldItems;
        }

        private StreamedPersistenceSession(
                SaveGameSnapshot restored,
                LogicalWorldItemService worldItems,
                WorldItemSnapshot item,
                ChunkKey initialPageChunk) {
            this.restored = restored;
            this.worldItems = worldItems;
            this.item = item;
            this.initialPageChunk = initialPageChunk;
        }

        @Override
        public Optional<WorldItemPersistencePlan> prepareWorldItemPersistence() {
            var prepared = worldItems.prepareSavePersistence();
            ticket = prepared.persistenceTicket().orElseThrow();
            return prepared.persistencePlan();
        }

        @Override
        public void commitWorldItemPersistence(WorldItemDurableProof proof) {
            worldItems.commitPersistence(ticket, proof);
            ticket = null;
        }

        @Override
        public void cancelWorldItemPersistence() {
            if (ticket != null) {
                worldItems.cancelPersistence(ticket);
                ticket = null;
            }
        }

        @Override public GameSessionState state() { return state; }
        @Override public void pollLoad() { state = GameSessionState.READY; }
        @Override public GameSessionFrame advancePlaying(
                double delta, MouseDelta look, boolean focused) {
            throw new AssertionError("gameplay not expected");
        }
        @Override public GameSessionFrame capturePaused() {
            throw new AssertionError("pause capture not expected");
        }
        @Override
        public SessionSaveCaptureResult captureSave() {
            long tick = worldItems.currentWorldTick();
            java.util.ArrayList<ChunkSnapshot> residentChunks =
                    new java.util.ArrayList<>(restored.chunks().chunks());
            long revisionHighWater = restored.chunks().revisionHighWater();
            if (residentChunks.stream()
                    .noneMatch(chunk -> chunk.key().equals(initialPageChunk))) {
                revisionHighWater = Math.addExact(revisionHighWater, 1L);
                residentChunks.add(ChunkSnapshot.empty(
                        initialPageChunk,
                        revisionHighWater,
                        restored.metadata().worldHeight()));
            }
            SaveGameSnapshot captured = new SaveGameSnapshot(
                    restored.metadata(), tick,
                    new com.overlord.voxel.ChunkRepositorySnapshot(
                            restored.chunks().worldHeight(),
                            revisionHighWater,
                            residentChunks),
                    restored.player(),
                    restored.inventory(), new WorldItemsSaveSnapshot(
                            tick, worldItems.canonicalSnapshot()));
            return GameSessionPersistenceTestFixture.runtimeCaptured(captured, 6L);
        }
        @Override
        public Optional<ChunkSnapshot> captureWorldItemChunk(ChunkKey key) {
            if (!initialPageChunk.equals(key)) {
                return Optional.empty();
            }
            return Optional.of(ChunkSnapshot.empty(
                    key, 1L, restored.metadata().worldHeight()));
        }
        @Override public void markSaved(SessionPersistenceRevision revision) {}
        @Override public boolean hasUnsavedChanges() { return true; }
        @Override public void discardFixedTime() {}
        @Override public void close() { closed = true; state = GameSessionState.CLOSED; }

        private WorldItemSnapshot item() { return item; }
        private boolean closed() { return closed; }
    }
}

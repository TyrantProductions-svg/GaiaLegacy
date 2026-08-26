package com.gaia.save.streaming;

import com.gaia.save.archive.SaveArchiveReader;
import com.gaia.save.archive.SaveDiagnostic;
import com.gaia.save.codec.InventorySectionCodec;
import com.gaia.save.codec.PlayerSectionCodec;
import com.gaia.save.format.SaveFormatVersion;
import com.gaia.save.format.SaveGameId;
import com.gaia.save.format.SaveGameManifest;
import com.gaia.save.format.SaveSectionDescriptor;
import com.gaia.save.format.SaveSectionId;
import com.gaia.save.session.SaveCoordinator;
import com.gaia.save.snapshot.SaveGameSnapshot;
import com.gaia.save.snapshot.WorldItemsSaveSnapshot;
import com.gaia.save.store.SaveFileOperations;
import com.gaia.save.store.SaveWriteResult;
import com.overlord.worlditem.api.LogicalWorldItemSnapshot;
import com.overlord.worlditem.api.WorldItemDurableProof;
import com.overlord.worlditem.api.WorldItemPageReadView;
import com.overlord.worlditem.api.WorldItemPagingCheckpoint;
import com.overlord.worlditem.api.WorldItemPersistencePlan;
import com.overlord.voxel.ChunkKey;
import com.overlord.voxel.ChunkSnapshot;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.concurrent.atomic.AtomicReference;

/** Real streamed-v2 save target layered on the single Task 4 authority. */
public final class StreamedSessionSaveTarget implements SaveCoordinator.SaveTarget {
    private final Path saveRoot;
    private final SaveGameId saveGameId;
    private final SaveArchiveReader archiveReader;
    private final SaveFileOperations files;
    private final StreamedChunkStore store;
    private final StreamedWorldItemPageBackend pages;
    private final FreshAuthorityBootstrap freshAuthorityBootstrap;

    public StreamedSessionSaveTarget(
            Path saveRoot,
            SaveGameId saveGameId,
            SaveArchiveReader archiveReader,
            SaveFileOperations files) {
        this(
                saveRoot,
                saveGameId,
                archiveReader,
                files,
                new StreamedChunkStore(
                        Objects.requireNonNull(saveRoot, "saveRoot")
                                .toAbsolutePath().normalize(),
                        Objects.requireNonNull(saveGameId, "saveGameId"),
                        new StreamedChunkCodec(),
                        new StreamedChunkIndexCodec(),
                        Objects.requireNonNull(files, "files")),
                null,
                null);
    }

    public StreamedSessionSaveTarget(
            Path saveRoot,
            SaveGameId saveGameId,
            SaveArchiveReader archiveReader,
            SaveFileOperations files,
            StreamedChunkStore store,
            StreamedWorldItemPageBackend pages) {
        this(saveRoot, saveGameId, archiveReader, files, store, pages, null);
    }

    public StreamedSessionSaveTarget(
            Path saveRoot,
            SaveGameId saveGameId,
            SaveArchiveReader archiveReader,
            SaveFileOperations files,
            StreamedChunkStore store,
            StreamedWorldItemPageBackend pages,
            FreshAuthorityBootstrap freshAuthorityBootstrap) {
        this.saveRoot = Objects.requireNonNull(saveRoot, "saveRoot")
                .toAbsolutePath().normalize();
        this.saveGameId = Objects.requireNonNull(saveGameId, "saveGameId");
        this.archiveReader = Objects.requireNonNull(archiveReader, "archiveReader");
        this.files = Objects.requireNonNull(files, "files");
        this.store = Objects.requireNonNull(store, "store");
        this.pages = pages == null
                ? new StreamedWorldItemPageBackend(this.store)
                : pages;
        this.freshAuthorityBootstrap = freshAuthorityBootstrap;
    }

    @Override
    public SaveWriteResult save(SaveGameSnapshot snapshot, Instant modifiedTime) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(modifiedTime, "modifiedTime");
        try {
            requireSnapshotIdentity(snapshot);
            requireValidModifiedTime(snapshot, modifiedTime);
            Phase14MigrationResult.PublishedMigration migration =
                    requirePublishedAuthority(snapshot, modifiedTime);
            CheckpointBinding binding = checkpointBinding(snapshot);
            byte[] sessionBytes = new StreamedSessionCheckpointCodec().encode(
                    new StreamedSessionCheckpoint(
                            saveGameId,
                            snapshot.fixedTick(),
                            binding.checkpointRevision(),
                            binding.checkpointDigest(),
                            binding.indexSequence(),
                            modifiedTime,
                            snapshot.player(),
                            snapshot.inventory()));
            StreamedGlobalExtension extension = new StreamedGlobalExtension(
                    SaveSectionId.STREAMED_SESSION_CHECKPOINT,
                    StreamedSessionCheckpointCodec.CODEC_VERSION,
                    true,
                    Optional.empty(),
                    sessionBytes);
            StreamedPersistenceTransaction transaction =
                    new StreamedPersistenceTransaction(
                            List.of(),
                            List.of(new StreamedGlobalExtensionMutation.Upsert(extension)),
                            () -> checkpointStillCurrent(binding));
            StreamedChunkStore.CommitResult committed = store.commitTransaction(transaction);
            if (committed.status() != StreamedChunkStore.CommitResult.Status.SUCCESS) {
                SaveDiagnostic diagnostic = committed.diagnostics().isEmpty()
                        ? SaveDiagnostic.of(
                                "save-write.streamed-session-failed",
                                "The streamed session checkpoint was not published")
                        : committed.diagnostics().get(0);
                return committed.status()
                                == StreamedChunkStore.CommitResult.Status.BLOCKING_FAILURE
                        ? SaveWriteResult.blockingFailure(diagnostic)
                        : SaveWriteResult.failed(diagnostic);
            }
            return SaveWriteResult.success(reopenManifest(
                    snapshot, modifiedTime, migration.manifest(), sessionBytes));
        } catch (Error fatal) {
            throw fatal;
        } catch (RuntimeException failure) {
            return SaveWriteResult.failed(SaveDiagnostic.of(
                    "save-write.streamed-session-failed",
                    "The streamed v2 session could not be committed safely",
                    failure));
        }
    }

    @Override
    public SaveCoordinator.AtomicSaveWrite saveAtomically(
            SaveGameSnapshot snapshot,
            Instant modifiedTime,
            Optional<WorldItemPersistencePlan> worldItems,
            Function<ChunkKey, Optional<ChunkSnapshot>> chunks) {
        return saveAtomically(
                snapshot, modifiedTime, worldItems, chunks, List.of());
    }

    @Override
    public SaveCoordinator.AtomicSaveWrite saveAtomically(
            SaveGameSnapshot snapshot,
            Instant modifiedTime,
            Optional<WorldItemPersistencePlan> worldItems,
            Function<ChunkKey, Optional<ChunkSnapshot>> chunks,
            List<SaveCoordinator.PreparedDirtyChunkCapture> dirtyChunks) {
        Objects.requireNonNull(worldItems, "worldItems");
        Function<ChunkKey, Optional<ChunkSnapshot>> chunkSource =
                Objects.requireNonNull(chunks, "chunks");
        try {
            requireSnapshotIdentity(snapshot);
            requireValidModifiedTime(snapshot, modifiedTime);
            Phase14MigrationResult.PublishedMigration migration =
                    requirePublishedAuthority(snapshot, modifiedTime);
            List<StreamedChunkStore.ExactChunkCapture> dirtyCaptures =
                    exactDirtyCaptures(snapshot, dirtyChunks);
            BooleanSupplier dirtyStillCurrent = () -> dirtyChunks.stream()
                    .allMatch(capture -> capture.stillCurrent().getAsBoolean());
            if (worldItems.isEmpty()) {
                CheckpointBinding binding = checkpointBinding(snapshot);
                byte[] encoded = sessionBytes(snapshot, modifiedTime,
                        new StreamedWorldItemPageBackend.AtomicCheckpointBinding(
                                binding.checkpointRevision(),
                                binding.checkpointDigest(),
                                binding.indexSequence()));
                publishDirtySessionCandidate(
                        dirtyCaptures,
                        List.of(new StreamedGlobalExtensionMutation.Upsert(
                                sessionExtension(encoded))),
                        () -> checkpointStillCurrent(binding)
                                && dirtyStillCurrent.getAsBoolean());
                return new SaveCoordinator.AtomicSaveWrite(
                        SaveWriteResult.success(reopenManifest(
                                snapshot,
                                modifiedTime,
                                migration.manifest(),
                                encoded)),
                        Optional.empty());
            }
            requireAtomicWorldItemBinding(
                    snapshot, worldItems.orElseThrow().intendedCheckpoint());
            StreamedWorldItemPageBackend atomicPages =
                    new StreamedWorldItemPageBackend(
                            store,
                            (identity, page, pageBytes) -> initialChunkCapture(
                                    snapshot, chunkSource, page, pageBytes));
            AtomicReference<byte[]> sessionBytes = new AtomicReference<>();
            StreamedWorldItemPageBackend.AtomicPersistenceResult committed =
                    atomicPages.persistAtomically(
                            worldItems.orElseThrow(),
                            binding -> {
                                byte[] encoded = sessionBytes(
                                        snapshot, modifiedTime, binding);
                                sessionBytes.set(encoded);
                                return List.of(new StreamedGlobalExtensionMutation.Upsert(
                                        sessionExtension(encoded)));
                             },
                            dirtyCaptures,
                            dirtyStillCurrent);
            try {
                return new SaveCoordinator.AtomicSaveWrite(
                        SaveWriteResult.success(reopenManifest(
                                snapshot,
                                modifiedTime,
                                migration.manifest(),
                                sessionBytes.get())),
                        Optional.of(committed.proof()));
            } catch (RuntimeException reopenFailure) {
                return new SaveCoordinator.AtomicSaveWrite(
                        SaveWriteResult.failed(SaveDiagnostic.of(
                                "save-write.streamed-session-reopen-failed",
                                "The durable streamed root could not be reopened for its manifest",
                                reopenFailure)),
                        Optional.of(committed.proof()));
            }
        } catch (Error fatal) {
            throw fatal;
        } catch (RuntimeException failure) {
            return new SaveCoordinator.AtomicSaveWrite(
                    SaveWriteResult.failed(SaveDiagnostic.of(
                            "save-write.streamed-session-failed",
                            "The streamed page and session root could not be committed safely",
                            failure)),
                    Optional.empty());
        }
    }

    static void requireAtomicWorldItemBinding(
            SaveGameSnapshot snapshot,
            WorldItemPagingCheckpoint checkpoint) {
        if (checkpoint.worldTick() != snapshot.fixedTick()
                || checkpoint.nextItemId()
                        != snapshot.worldItems().nextItemId()
                || checkpoint.itemIdsExhausted()
                        != snapshot.worldItems().itemIdsExhausted()) {
            throw new IllegalStateException(
                    "The atomic session snapshot and WorldItem checkpoint disagree");
        }
    }

    private Phase14MigrationResult.PublishedMigration requirePublishedAuthority(
            SaveGameSnapshot snapshot, Instant modifiedTime) {
        Path current = saveRoot.resolve(saveGameId.value()).resolve("current.glsave");
        if (freshAuthorityBootstrap != null
                && !Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
            freshAuthorityBootstrap.bootstrap(snapshot, modifiedTime);
            return Phase14SaveMigrator.readPublished(
                            saveRoot, saveGameId, archiveReader, files)
                    .orElseThrow(() -> new IllegalStateException(
                            "Fresh streamed authority bootstrap did not publish completely"));
        }
        Phase14SaveMigrator.PublicationObservation observed =
                Phase14SaveMigrator.observePublished(
                        saveRoot, saveGameId, archiveReader, files);
        if (observed.status()
                == Phase14SaveMigrator.PublicationStatus.PUBLISHED_VALID) {
            return observed.migration();
        }
        if (observed.status()
                == Phase14SaveMigrator.PublicationStatus.PUBLISHED_INVALID) {
            throw new IllegalStateException(
                    "The published streamed migration authority is invalid",
                    observed.diagnostic().cause().orElse(null));
        }
        if (freshAuthorityBootstrap == null) {
            throw new IllegalStateException(
                    "The streamed migration authority is not published");
        }
        freshAuthorityBootstrap.bootstrap(snapshot, modifiedTime);
        return Phase14SaveMigrator.readPublished(
                        saveRoot, saveGameId, archiveReader, files)
                .orElseThrow(() -> new IllegalStateException(
                        "Fresh streamed authority bootstrap did not publish completely"));
    }

    @FunctionalInterface
    public interface FreshAuthorityBootstrap {
        void bootstrap(SaveGameSnapshot snapshot, Instant modifiedTime);
    }

    /** Overlays a fully validated session boundary on a validated migration floor. */
    public static Optional<SaveGameSnapshot> restoreSnapshot(
            Path saveRoot,
            SaveGameId saveGameId,
            Phase14MigrationResult.PublishedMigration migration,
            SaveFileOperations files) {
        return restoreSession(saveRoot, saveGameId, migration, files)
                .map(RestoredSession::snapshot);
    }

    /** Returns the same validated root plus its durable catalog timestamp. */
    public static Optional<RestoredSession> restoreSession(
            Path saveRoot,
            SaveGameId saveGameId,
            Phase14MigrationResult.PublishedMigration migration,
            SaveFileOperations files) {
        Objects.requireNonNull(migration, "migration");
        StreamedChunkStore store = new StreamedChunkStore(
                saveRoot,
                saveGameId,
                new StreamedChunkCodec(),
                new StreamedChunkIndexCodec(),
                files);
        try (StreamedChunkStore.BoundedReadView bounded = store.openBoundedReadView()) {
            Optional<StreamedGlobalExtension> value = bounded.index()
                    .globalExtension(SaveSectionId.STREAMED_SESSION_CHECKPOINT);
            if (value.isEmpty()) {
                StreamedWorldItemPageBackend backend =
                        new StreamedWorldItemPageBackend(store);
                try (WorldItemPageReadView pageView = backend.openReadView(bounded)) {
                    WorldItemPagingCheckpoint floor = pageView.checkpoint();
                    if (floor.checkpointRevision() != 1L
                            || floor.worldTick() != migration.snapshot().fixedTick()
                            || floor.nextItemId()
                                    != migration.snapshot().worldItems().nextItemId()
                            || floor.itemIdsExhausted()
                                    != migration.snapshot().worldItems().itemIdsExhausted()) {
                        throw new IllegalStateException(
                                "A streamed page root advanced without its session checkpoint");
                    }
                    SaveGameSnapshot base = migration.snapshot();
                    return Optional.of(new RestoredSession(new SaveGameSnapshot(
                            base.metadata(),
                            floor.worldTick(),
                            base.chunks(),
                            base.player(),
                            base.inventory(),
                            new WorldItemsSaveSnapshot(
                                    floor.worldTick(),
                                    List.of(),
                                    floor.nextItemId(),
                                    floor.itemIdsExhausted(),
                                    LogicalWorldItemSnapshot.Completeness.PAGED_PARTIAL)),
                            migration.manifest().modifiedAt()));
                }
            }
            StreamedGlobalExtension extension = value.orElseThrow();
            if (!extension.required()
                    || extension.codecVersion()
                            != StreamedSessionCheckpointCodec.CODEC_VERSION
                    || extension.dependency().isPresent()) {
                throw new IllegalStateException(
                        "The streamed session extension descriptor is invalid");
            }
            StreamedSessionCheckpoint session = new StreamedSessionCheckpointCodec()
                    .decode(extension.copyPayloadBytes());
            StreamedWorldItemPageBackend backend =
                    new StreamedWorldItemPageBackend(store);
            try (WorldItemPageReadView pageView = backend.openReadView(bounded)) {
                WorldItemPagingCheckpoint checkpoint = pageView.checkpoint();
                if (!session.saveGameId().equals(saveGameId)
                        || session.fixedTick() != checkpoint.worldTick()
                        || session.worldItemCheckpointRevision()
                                != checkpoint.checkpointRevision()
                        || !session.worldItemCheckpointDigest().equals(
                                pageView.checkpointDigest())
                        || session.worldItemSourceIndexSequence()
                                > bounded.sequence()) {
                    throw new IllegalStateException(
                            "The streamed session checkpoint does not match its page authority");
                }
                SaveGameSnapshot base = migration.snapshot();
                return Optional.of(new RestoredSession(new SaveGameSnapshot(
                        base.metadata(),
                        session.fixedTick(),
                        base.chunks(),
                        session.player(),
                        session.inventory(),
                        new WorldItemsSaveSnapshot(
                                session.fixedTick(),
                                List.of(),
                                checkpoint.nextItemId(),
                                checkpoint.itemIdsExhausted(),
                                LogicalWorldItemSnapshot.Completeness.PAGED_PARTIAL)),
                        session.modifiedTime()));
            }
        }
    }

    public record RestoredSession(
            SaveGameSnapshot snapshot, Instant modifiedTime) {
        public RestoredSession {
            snapshot = Objects.requireNonNull(snapshot, "snapshot");
            modifiedTime = Objects.requireNonNull(modifiedTime, "modifiedTime");
        }
    }

    private void requireSnapshotIdentity(SaveGameSnapshot snapshot) {
        if (!snapshot.metadata().saveGameId().equals(saveGameId)
                || snapshot.worldItems().completeness()
                        != LogicalWorldItemSnapshot.Completeness.PAGED_PARTIAL) {
            throw new IllegalArgumentException(
                    "A streamed save requires matching paged WorldItem state");
        }
    }

    private static void requireValidModifiedTime(
            SaveGameSnapshot snapshot, Instant modifiedTime) {
        if (modifiedTime.isBefore(snapshot.metadata().createdAt())) {
            throw new IllegalArgumentException(
                    "Modified time must not precede created time");
        }
    }

    private byte[] sessionBytes(
            SaveGameSnapshot snapshot,
            Instant modifiedTime,
            StreamedWorldItemPageBackend.AtomicCheckpointBinding binding) {
        return new StreamedSessionCheckpointCodec().encode(
                new StreamedSessionCheckpoint(
                        saveGameId,
                        snapshot.fixedTick(),
                        binding.checkpointRevision(),
                        binding.checkpointDigest(),
                        binding.intendedIndexSequence(),
                        modifiedTime,
                        snapshot.player(),
                        snapshot.inventory()));
    }

    private static StreamedGlobalExtension sessionExtension(byte[] bytes) {
        return new StreamedGlobalExtension(
                SaveSectionId.STREAMED_SESSION_CHECKPOINT,
                StreamedSessionCheckpointCodec.CODEC_VERSION,
                true,
                Optional.empty(),
                bytes);
    }

    private StreamedChunkStore.ExactChunkCapture initialChunkCapture(
            SaveGameSnapshot snapshot,
            Function<ChunkKey, Optional<ChunkSnapshot>> chunks,
            com.overlord.worlditem.api.WorldItemPageSnapshot page,
            byte[] pageBytes) {
        ChunkSnapshot captured = chunks.apply(page.chunkKey())
                .orElseThrow(() -> new IllegalStateException(
                        "A first WorldItem page requires its exact resident Chunk"));
        String baseHash = Phase14SaveMigrator.reproducedBaseHash(
                snapshot.metadata(), captured.key());
        StreamedChunkPayload payload = new StreamedChunkPayload(
                saveGameId,
                captured.key(),
                snapshot.metadata().generatorVersion(),
                baseHash,
                captured.revision(),
                0L,
                true,
                true,
                captured.worldHeight(),
                captured.copyBlocks(),
                List.of(new StreamedChunkPayload.ExtensionDescriptor(
                        SaveSectionId.WORLD_ITEM_PAGE,
                        WorldItemPageCodec.CODEC_VERSION,
                        true,
                        pageBytes)));
        return new StreamedChunkStore.ExactChunkCapture(
                payload,
                () -> chunks.apply(captured.key())
                        .map(current -> exactChunk(current, captured))
                        .orElse(false));
    }

    private List<StreamedChunkStore.ExactChunkCapture> exactDirtyCaptures(
            SaveGameSnapshot snapshot,
            List<SaveCoordinator.PreparedDirtyChunkCapture> dirtyChunks) {
        List<SaveCoordinator.PreparedDirtyChunkCapture> checked = List.copyOf(
                Objects.requireNonNull(dirtyChunks, "dirtyChunks"));
        if (checked.size() > 1_024) {
            throw new IllegalArgumentException(
                    "A streamed save exceeds the bounded dirty-Chunk capture limit");
        }
        Map<ChunkKey, ChunkSnapshot> canonical = snapshot.chunks().chunks().stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(
                        ChunkSnapshot::key, chunk -> chunk));
        StreamedChunkIndex index = store.readCurrentIndex();
        Set<ChunkKey> keys = new HashSet<>();
        List<StreamedChunkStore.ExactChunkCapture> captures = new ArrayList<>();
        for (SaveCoordinator.PreparedDirtyChunkCapture chunk : checked) {
            SaveCoordinator.PreparedDirtyChunkCapture prepared =
                    Objects.requireNonNull(chunk, "dirty Chunk");
            ChunkSnapshot exact = prepared.snapshot();
            if (!keys.add(exact.key()) || !exact.equals(canonical.get(exact.key()))) {
                throw new IllegalArgumentException(
                        "A dirty Chunk capture is duplicated or not in the save snapshot");
            }
            StreamedChunkIndex.Entry previous = index.entry(exact.key()).orElse(null);
            long persistedRevision = previous == null ? 0L : previous.revision();
            List<StreamedChunkPayload.ExtensionDescriptor> extensions = List.of();
            if (previous != null) {
                StreamedChunkStore.ReadResult read = store.read(
                        saveGameId,
                        exact.key(),
                        new StreamedChunkStore.ExpectedBase(
                                previous.generatorVersion(), previous.baseHash()));
                if (read.status() != StreamedChunkStore.ReadResult.Status.FOUND) {
                    throw new IllegalStateException(
                            "The dirty Chunk durable base is unreadable");
                }
                StreamedChunkPayload current = read.payload().orElseThrow();
                if (exact.revision() == persistedRevision
                        && java.util.Arrays.equals(
                                exact.copyBlocks(), current.copyCanonicalVoxels())) {
                    continue;
                }
                extensions = current.extensions();
            }
            if (exact.revision() <= persistedRevision) {
                throw new IllegalStateException(
                        "The dirty Chunk capture does not advance durable state");
            }
            String baseHash = Phase14SaveMigrator.reproducedBaseHash(
                    snapshot.metadata(), exact.key());
            if (previous != null
                    && (!previous.generatorVersion().equals(
                                    snapshot.metadata().generatorVersion())
                            || !previous.baseHash().equals(baseHash))) {
                throw new IllegalStateException(
                        "The dirty Chunk base identity conflicts with durable state");
            }
            StreamedChunkPayload payload = new StreamedChunkPayload(
                    saveGameId,
                    exact.key(),
                    snapshot.metadata().generatorVersion(),
                    baseHash,
                    exact.revision(),
                    persistedRevision,
                    true,
                    true,
                    exact.worldHeight(),
                    exact.copyBlocks(),
                    extensions);
            captures.add(new StreamedChunkStore.ExactChunkCapture(
                    payload, prepared.stillCurrent()));
        }
        return List.copyOf(captures);
    }

    private void publishDirtySessionCandidate(
            List<StreamedChunkStore.ExactChunkCapture> captures,
            List<StreamedGlobalExtensionMutation> globals,
            BooleanSupplier stillCurrent) {
        try (StreamedChunkStore.StagedTransaction staged =
                store.beginStagedTransaction(stillCurrent)) {
            List<StreamedChunkMutation> batch = new ArrayList<>();
            int physicalBlobs = 0;
            long bytes = 0L;
            for (StreamedChunkStore.ExactChunkCapture capture : captures) {
                int nextBlobs = staged.baseIndex().entry(
                        capture.payload().key()).isPresent() ? 1 : 2;
                long nextBytes = StreamedChunkCodec.canonicalEncodedSize(
                        capture.payload());
                if (!batch.isEmpty()
                        && (physicalBlobs + nextBlobs
                                        > StreamedPersistenceTransaction.MAX_CHUNKS
                                || bytes + nextBytes
                                        > StreamedPersistenceTransaction
                                                .MAX_CANDIDATE_BYTES)) {
                    requireStagingSuccess(staged.stageBatch(batch));
                    batch.clear();
                    physicalBlobs = 0;
                    bytes = 0L;
                }
                batch.add(new StreamedChunkMutation.Upsert(capture));
                physicalBlobs = Math.addExact(physicalBlobs, nextBlobs);
                bytes = Math.addExact(bytes, nextBytes);
            }
            if (!batch.isEmpty()) {
                requireStagingSuccess(staged.stageBatch(batch));
            }
            requireStagingSuccess(staged.publish(globals));
        }
    }

    private static void requireStagingSuccess(
            StreamedChunkStore.CommitResult result) {
        if (result.status() != StreamedChunkStore.CommitResult.Status.SUCCESS) {
            throw new IllegalStateException(
                    "The streamed save candidate did not publish: "
                            + result.status());
        }
    }

    private static boolean exactChunk(ChunkSnapshot first, ChunkSnapshot second) {
        return first.key().equals(second.key())
                && first.revision() == second.revision()
                && first.worldHeight() == second.worldHeight()
                && java.util.Arrays.equals(first.copyBlocks(), second.copyBlocks());
    }

    private CheckpointBinding checkpointBinding(SaveGameSnapshot snapshot) {
        try (WorldItemPageReadView view = pages.openReadView()) {
            WorldItemPagingCheckpoint checkpoint = view.checkpoint();
            if (checkpoint.worldTick() != snapshot.fixedTick()
                    || checkpoint.nextItemId() != snapshot.worldItems().nextItemId()
                    || checkpoint.itemIdsExhausted()
                            != snapshot.worldItems().itemIdsExhausted()) {
                throw new IllegalStateException(
                        "The session snapshot is not bound to the durable WorldItem checkpoint");
            }
            return new CheckpointBinding(
                    checkpoint.checkpointRevision(),
                    view.checkpointDigest(),
                    view.indexSequence());
        }
    }

    private boolean checkpointStillCurrent(CheckpointBinding expected) {
        try (WorldItemPageReadView view = pages.openReadView()) {
            return view.checkpoint().checkpointRevision()
                            == expected.checkpointRevision()
                    && view.checkpointDigest().equals(expected.checkpointDigest())
                    && view.indexSequence() == expected.indexSequence();
        }
    }

    private SaveGameManifest reopenManifest(
            SaveGameSnapshot snapshot,
            Instant modifiedTime,
            Phase14MigrationResult.ValidatedV2Manifest migration,
            byte[] sessionBytes) {
        PlayerSectionCodec playerCodec = new PlayerSectionCodec();
        InventorySectionCodec inventoryCodec = new InventorySectionCodec();
        byte[] player = playerCodec.encode(snapshot.player());
        byte[] inventory = inventoryCodec.encode(snapshot.inventory());
        try (StreamedChunkStore.BoundedReadView bounded = store.openBoundedReadView();
                WorldItemPageReadView pageView = pages.openReadView(bounded)) {
            StreamedGlobalExtension reopened = bounded.index()
                    .globalExtension(SaveSectionId.STREAMED_SESSION_CHECKPOINT)
                    .orElseThrow();
            if (!java.util.Arrays.equals(
                    sessionBytes, reopened.copyPayloadBytes())) {
                throw new IllegalStateException(
                        "The streamed session checkpoint did not reopen exactly");
            }
            byte[] index = new StreamedChunkIndexCodec().encode(bounded.index());
            byte[] checkpoint = new WorldItemPagingCheckpointCodec()
                    .encode(pageView.checkpoint());
            List<SaveSectionDescriptor> sections = List.of(
                    descriptor(
                            SaveSectionId.STREAMED_CHUNKS,
                            new StreamedChunkIndexCodec().codecVersion(),
                            index),
                    descriptor(SaveSectionId.PLAYER, playerCodec.codecVersion(), player),
                    descriptor(
                            SaveSectionId.INVENTORY,
                            inventoryCodec.codecVersion(),
                            inventory),
                    descriptor(
                            SaveSectionId.WORLD_ITEMS,
                            WorldItemPagingCheckpointCodec.CODEC_VERSION,
                            checkpoint));
            return new SaveGameManifest(
                    SaveFormatVersion.STREAMED_CHUNKS,
                    snapshot.metadata().gameVersion(),
                    saveGameId,
                    snapshot.metadata().displayName(),
                    snapshot.metadata().createdAt(),
                    modifiedTime,
                    snapshot.metadata().worldSeed(),
                    snapshot.metadata().generatorVersion(),
                    snapshot.metadata().generatorConfigFingerprint(),
                    snapshot.metadata().chunkRadius(),
                    snapshot.metadata().worldHeight(),
                    snapshot.fixedTick(),
                    snapshot.metadata().summary().orElse(null),
                    sections);
        }
    }

    private static SaveSectionDescriptor descriptor(
            SaveSectionId id, int codecVersion, byte[] bytes) {
        return new SaveSectionDescriptor(
                id,
                codecVersion,
                true,
                bytes.length,
                StreamedChunkCodec.sha256Hex(bytes));
    }

    private record CheckpointBinding(
            long checkpointRevision, String checkpointDigest, long indexSequence) {}
}

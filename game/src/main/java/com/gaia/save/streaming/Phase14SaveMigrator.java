package com.gaia.save.streaming;

import com.gaia.save.archive.SaveArchiveLimits;
import com.gaia.save.archive.SaveArchiveReadResult;
import com.gaia.save.archive.SaveArchiveReader;
import com.gaia.save.archive.SaveDiagnostic;
import com.gaia.save.archive.SaveManifestCodec;
import com.gaia.save.format.SaveFormatVersion;
import com.gaia.save.format.SaveGameId;
import com.gaia.save.format.SaveGameManifest;
import com.gaia.save.format.SaveSectionDescriptor;
import com.gaia.save.format.SaveSectionId;
import com.gaia.save.snapshot.SaveGameSnapshot;
import com.gaia.save.store.SaveFileOperations;
import com.gaia.world.GaiaWorldGenerator;
import com.gaia.world.generation.DeterministicCoordinateSampler;
import com.gaia.world.generation.GenerationBlockPalette;
import com.gaia.world.generation.GenerationContext;
import com.gaia.world.generation.WorldGenerationConfig;
import com.gaia.world.generation.WorldGenerationHasher;
import com.gaia.world.generation.WorldGenerationResult;
import com.gaia.world.generation.WorldGenerator;
import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.overlord.voxel.ChunkCoordinatePolicy;
import com.overlord.voxel.ChunkKey;
import com.overlord.voxel.ChunkRepositorySnapshot;
import com.overlord.voxel.ChunkSnapshot;
import com.overlord.worlditem.api.SaveIdentity;
import com.overlord.worlditem.api.WorldItemPageDescriptor;
import com.overlord.worlditem.api.WorldItemPageReadView;
import com.overlord.worlditem.api.WorldItemPageSnapshot;
import com.overlord.worlditem.api.WorldItemPagingCheckpoint;
import com.overlord.worlditem.api.WorldItemRestoreEntry;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Conservative, restartable import of a complete Phase 14 v1 archive. */
public final class Phase14SaveMigrator {
    public enum CheckpointStage {
        CHUNK,
        INDEX,
        WORLD_ITEM_PAGES,
        WORLD_ITEM_CHECKPOINT,
        V2_MANIFEST,
        V1_BACKUP,
        FINAL_REREAD,
        CATALOG_OPEN
    }

    public enum CheckpointSide {
        BEFORE,
        AFTER
    }

    public record Checkpoint(
            CheckpointStage stage,
            CheckpointSide side,
            int chunkOrdinal,
            ChunkKey chunkKey) {
        public Checkpoint {
            Objects.requireNonNull(stage, "stage");
            Objects.requireNonNull(side, "side");
            if (stage == CheckpointStage.CHUNK
                    || stage == CheckpointStage.WORLD_ITEM_PAGES) {
                if (chunkOrdinal < 0 || chunkKey == null) {
                    throw new IllegalArgumentException(
                            "A Chunk checkpoint requires its ordinal and key");
                }
            } else if (chunkOrdinal != -1 || chunkKey != null) {
                throw new IllegalArgumentException(
                        "A non-Chunk checkpoint has no Chunk identity");
            }
        }
    }

    @FunctionalInterface
    public interface CheckpointHook {
        void reach(Checkpoint checkpoint) throws IOException;
    }

    public enum PublicationStatus {
        ABSENT,
        UNPUBLISHED,
        PUBLISHED_VALID,
        PUBLISHED_INVALID
    }

    /** Closed observation: only ABSENT/UNPUBLISHED may consult Phase 14 v1. */
    public record PublicationObservation(
            PublicationStatus status,
            Phase14MigrationResult.PublishedMigration migration,
            SaveDiagnostic diagnostic) {
        public PublicationObservation {
            Objects.requireNonNull(status, "status");
            boolean valid = status == PublicationStatus.PUBLISHED_VALID;
            boolean invalid = status == PublicationStatus.PUBLISHED_INVALID;
            if (valid != (migration != null)
                    || invalid != (diagnostic != null)
                    || (!valid && migration != null)
                    || (!invalid && diagnostic != null)) {
                throw new IllegalArgumentException(
                        "Publication observation payload does not match its status");
            }
        }

        public static PublicationObservation absent() {
            return new PublicationObservation(
                    PublicationStatus.ABSENT, null, null);
        }

        public static PublicationObservation unpublished() {
            return new PublicationObservation(
                    PublicationStatus.UNPUBLISHED, null, null);
        }

        public static PublicationObservation valid(
                Phase14MigrationResult.PublishedMigration migration) {
            return new PublicationObservation(
                    PublicationStatus.PUBLISHED_VALID,
                    Objects.requireNonNull(migration, "migration"),
                    null);
        }

        public static PublicationObservation invalid(Throwable cause) {
            return new PublicationObservation(
                    PublicationStatus.PUBLISHED_INVALID,
                    null,
                    SaveDiagnostic.of(
                            "phase14-migration.published-authority-invalid",
                            "The published streamed save authority is invalid",
                            Objects.requireNonNull(cause, "cause")));
        }
    }

    private static final String CURRENT_NAME = "current.glsave";
    private static final String BACKUP_NAME = "backup.glsave";
    private static final String MARKER_A_NAME = "streamed-migration.a.v2";
    private static final String MARKER_B_NAME = "streamed-migration.b.v2";
    private static final String PUBLICATION_FLOOR_A_NAME =
            "streamed-migration.published.a.v2";
    private static final String PUBLICATION_FLOOR_B_NAME =
            "streamed-migration.published.b.v2";
    private static final int MARKER_SCHEMA = 1;
    private static final int PUBLICATION_FLOOR_SCHEMA = 2;
    private static final long MAX_PUBLICATION_FLOOR_BYTES = 512L;
    private static final long MAX_MARKER_BYTES =
            StreamedChunkIndexCodec.MAX_FILE_BYTES * 2L;
    private static final Set<String> MARKER_FIELDS = Set.of(
            "schema",
            "formatVersion",
            "gameVersion",
            "saveGameId",
            "displayName",
            "createdAt",
            "modifiedAt",
            "worldSeed",
            "generatorVersion",
            "generatorConfigFingerprint",
            "chunkRadius",
            "worldHeight",
            "fixedTick",
            "summary",
            "sourceArchiveSha256",
            "sections",
            "index");
    private static final Set<String> SECTION_FIELDS = Set.of(
            "sectionId",
            "codecVersion",
            "required",
            "uncompressedSize",
            "sha256");

    private final Path saveRoot;
    private final SaveArchiveReader archiveReader;
    private final StreamedChunkCodec payloadCodec;
    private final StreamedChunkIndexCodec indexCodec;
    private final SaveFileOperations files;
    private final CheckpointHook checkpoints;

    public Phase14SaveMigrator(
            Path saveRoot,
            SaveArchiveReader archiveReader,
            StreamedChunkCodec payloadCodec,
            StreamedChunkIndexCodec indexCodec,
            SaveFileOperations files) {
        this(
                saveRoot,
                archiveReader,
                payloadCodec,
                indexCodec,
                files,
                ignored -> {});
    }

    public Phase14SaveMigrator(
            Path saveRoot,
            SaveArchiveReader archiveReader,
            StreamedChunkCodec payloadCodec,
            StreamedChunkIndexCodec indexCodec,
            SaveFileOperations files,
            CheckpointHook checkpoints) {
        this.saveRoot = Objects.requireNonNull(saveRoot, "saveRoot")
                .toAbsolutePath()
                .normalize();
        this.archiveReader = Objects.requireNonNull(archiveReader, "archiveReader");
        this.payloadCodec = Objects.requireNonNull(payloadCodec, "payloadCodec");
        this.indexCodec = Objects.requireNonNull(indexCodec, "indexCodec");
        this.files = Objects.requireNonNull(files, "files");
        this.checkpoints = Objects.requireNonNull(checkpoints, "checkpoints");
    }

    public Phase14MigrationResult migrate(SaveGameId saveGameId) {
        Objects.requireNonNull(saveGameId, "saveGameId");
        boolean publicationObserved = false;
        try {
            PublicationObservation existing = observePublished(
                    saveRoot, saveGameId, archiveReader, files);
            if (existing.status() == PublicationStatus.PUBLISHED_VALID) {
                return Phase14MigrationResult.notRequired(existing.migration());
            }
            if (existing.status() == PublicationStatus.PUBLISHED_INVALID) {
                return failedResult(
                        true,
                        "phase14-migration.published-authority-invalid",
                        "The published streamed save authority is invalid",
                        existing.diagnostic().cause().orElseGet(
                                MigrationValidationFailure::new));
            }

            MigrationPaths paths = paths(saveRoot, saveGameId);
            requireWorld(paths);
            SaveArchiveReadResult v1Read = archiveReader.readPhase14(paths.current());
            if (v1Read.status() != SaveArchiveReadResult.Status.VALID) {
                throw failure(
                        false,
                        "phase14-migration.source-invalid",
                        "The Phase 14 source archive is not readable",
                        diagnosticCause(v1Read));
            }
            SaveGameSnapshot source = v1Read.snapshot().orElseThrow();
            if (!source.metadata().saveGameId().equals(saveGameId)
                    || !SaveFormatVersion.CURRENT.equals(
                            source.metadata().formatVersion())) {
                throw failure(
                        false,
                        "phase14-migration.source-identity-mismatch",
                        "The Phase 14 source identity does not match");
            }
            SaveGameManifest v1Manifest = readV1Manifest(paths.current());
            if (!v1Manifest.saveGameId().equals(saveGameId)) {
                throw failure(
                        false,
                        "phase14-migration.source-identity-mismatch",
                        "The Phase 14 source identity does not match");
            }

            String sourceHash = sha256File(paths.current(), files);
            MigrationSession session = initializeSession(paths, sourceHash);
            StreamedChunkStore store = new StreamedChunkStore(
                    saveRoot, saveGameId, payloadCodec, indexCodec, files);
            session = initializeMarkerSlots(session);

            List<ChunkSnapshot> chunks = source.chunks().chunks().stream()
                    .sorted(Comparator.comparingInt((ChunkSnapshot chunk) -> chunk.key().x())
                            .thenComparingInt(chunk -> chunk.key().z()))
                    .toList();
            List<StreamedChunkIndex.Entry> entries = new ArrayList<>(chunks.size());
            List<StreamedChunkStore.ExactChunkCapture> captures =
                    new ArrayList<>(chunks.size());
            WorldItemMigrationData worldItems = worldItemMigrationData(
                    source, saveGameId);
            for (int ordinal = 0; ordinal < chunks.size(); ordinal++) {
                ChunkSnapshot chunk = chunks.get(ordinal);
                checkpoint(CheckpointStage.CHUNK, CheckpointSide.BEFORE, ordinal, chunk.key());
                String baseHash = reproducedBaseHash(
                        source.metadata(), chunk.key());
                StreamedChunkPayload intended = new StreamedChunkPayload(
                        saveGameId,
                        chunk.key(),
                        source.metadata().generatorVersion(),
                        baseHash,
                        chunk.revision(),
                        0L,
                        true,
                        chunk.worldHeight(),
                        chunk.copyBlocks(),
                        Optional.ofNullable(worldItems.pageExtensions().get(chunk.key()))
                                .map(List::of)
                                .orElseGet(List::of));
                captures.add(new StreamedChunkStore.ExactChunkCapture(
                        intended, () -> true));
                byte[] payloadBytes = payloadCodec.encode(intended);
                entries.add(new StreamedChunkIndex.Entry(
                        chunk.key(),
                        intended.generatorVersion(),
                        intended.baseHash(),
                        intended.revision(),
                        payloadBytes.length,
                        StreamedChunkCodec.sha256Hex(payloadBytes),
                        true));
            }
            for (int ordinal = 0; ordinal < worldItems.pages().size(); ordinal++) {
                checkpoint(
                        CheckpointStage.WORLD_ITEM_PAGES,
                        CheckpointSide.BEFORE,
                        ordinal,
                        worldItems.pages().get(ordinal).chunkKey());
            }
            checkpoint(
                    CheckpointStage.WORLD_ITEM_CHECKPOINT,
                    CheckpointSide.BEFORE,
                    -1,
                    null);
            persistExactBatch(store, captures, worldItems.checkpointExtension());
            checkpoint(
                    CheckpointStage.WORLD_ITEM_CHECKPOINT,
                    CheckpointSide.AFTER,
                    -1,
                    null);
            for (int ordinal = 0; ordinal < worldItems.pages().size(); ordinal++) {
                checkpoint(
                        CheckpointStage.WORLD_ITEM_PAGES,
                        CheckpointSide.AFTER,
                        ordinal,
                        worldItems.pages().get(ordinal).chunkKey());
            }
            for (int ordinal = 0; ordinal < chunks.size(); ordinal++) {
                ChunkSnapshot chunk = chunks.get(ordinal);
                checkpoint(CheckpointStage.CHUNK, CheckpointSide.AFTER, ordinal, chunk.key());
            }

            checkpoint(CheckpointStage.INDEX, CheckpointSide.BEFORE, -1, null);
            StreamedChunkIndex index = new StreamedChunkIndex(
                    saveGameId,
                    null,
                    entries,
                    List.of(worldItems.checkpointExtension()));
            byte[] indexBytes = indexCodec.encode(index);
            StreamedChunkIndex rereadIndex = indexCodec.decode(indexBytes);
            requireSameIndex(index, rereadIndex);
            checkpoint(CheckpointStage.INDEX, CheckpointSide.AFTER, -1, null);

            checkpoint(CheckpointStage.V2_MANIFEST, CheckpointSide.BEFORE, -1, null);
            Phase14MigrationResult.ValidatedV2Manifest manifest = v2Manifest(
                    v1Manifest, sourceHash, indexBytes);
            byte[] markerBytes = encodeMarker(manifest, indexBytes);
            Marker staged = decodeMarker(markerBytes);
            requireSameIndex(index, staged.index());
            writeFixedSlot(session, session.markerA(), markerBytes, MAX_MARKER_BYTES);
            validateStagedMarker(session, paths.current(), source, staged, files);
            checkpoint(CheckpointStage.V2_MANIFEST, CheckpointSide.AFTER, -1, null);

            checkpoint(CheckpointStage.V1_BACKUP, CheckpointSide.BEFORE, -1, null);
            preserveExactBackup(session, sourceHash);
            checkpoint(CheckpointStage.V1_BACKUP, CheckpointSide.AFTER, -1, null);

            checkpoint(CheckpointStage.FINAL_REREAD, CheckpointSide.BEFORE, -1, null);
            Marker finalStaged = readMarkerSlot(session, session.markerA(), files)
                    .orElseThrow(() -> new IOException("Staged migration manifest missing"));
            Phase14MigrationResult.PublishedMigration validated = validateAuthority(
                    session.directories(),
                    paths.backup(),
                    archiveReader,
                    files,
                    finalStaged,
                    true);
            checkpoint(CheckpointStage.FINAL_REREAD, CheckpointSide.AFTER, -1, null);

            checkpoint(CheckpointStage.CATALOG_OPEN, CheckpointSide.BEFORE, -1, null);
            writeFixedSlot(session, session.markerB(), markerBytes, MAX_MARKER_BYTES);
            StreamedChunkStore.CommitResult publicationCommit =
                    store.publishMigrationCompatibility(
                            new StreamedChunkIndex.MigrationCompatibility(
                                    sourceHash, sha256(markerBytes)));
            if (publicationCommit.status()
                    != StreamedChunkStore.CommitResult.Status.SUCCESS) {
                throw failure(
                        publicationCommit.status()
                                == StreamedChunkStore.CommitResult.Status.BLOCKING_FAILURE,
                        "phase14-migration.task4-publication-failed",
                        "The migration publication proof could not be committed",
                        diagnosticCause(publicationCommit.diagnostics()));
            }
            byte[] floorBytes = encodePublishedFloor(
                    session.initializingFloor(), markerBytes);
            writeFloorState(session, session.publicationFloorA(), floorBytes);
            writeFloorState(session, session.publicationFloorB(), floorBytes);
            publicationObserved = true;
            PublicationObservation publication = observePublished(
                    saveRoot, saveGameId, archiveReader, files);
            if (publication.status() != PublicationStatus.PUBLISHED_VALID) {
                throw new IOException(
                        "Published migration authority failed final validation");
            }
            Phase14MigrationResult.PublishedMigration published =
                    publication.migration();
            requireSameIndex(validated.index(), published.index());
            checkpoint(CheckpointStage.CATALOG_OPEN, CheckpointSide.AFTER, -1, null);
            return Phase14MigrationResult.migrated(published);
        } catch (MigrationFailure failure) {
            return failedResult(
                    failure.blocking || publicationObserved,
                    failure.code,
                    failure.getMessage(),
                    failure.getCause());
        } catch (IOException | RuntimeException failure) {
            PublicationObservation observation = observePublished(
                    saveRoot, saveGameId, archiveReader, files);
            boolean published = publicationObserved
                    || observation.status() == PublicationStatus.PUBLISHED_VALID
                    || observation.status() == PublicationStatus.PUBLISHED_INVALID;
            return failedResult(
                    published,
                    published
                            ? "phase14-migration.publication-uncertain"
                            : "phase14-migration.failed",
                    published
                            ? "The migration completed but its final acknowledgement failed"
                            : "The Phase 14 save could not be migrated safely",
                    failure);
        }
    }

    public static Optional<Phase14MigrationResult.PublishedMigration> readPublished(
            Path saveRoot,
            SaveGameId saveGameId,
            SaveArchiveReader archiveReader,
            SaveFileOperations files) {
        PublicationObservation observation = observePublished(
                saveRoot, saveGameId, archiveReader, files);
        return observation.status() == PublicationStatus.PUBLISHED_VALID
                ? Optional.of(observation.migration())
                : Optional.empty();
    }

    public static PublicationObservation observePublished(
            Path saveRoot,
            SaveGameId saveGameId,
            SaveArchiveReader archiveReader,
            SaveFileOperations files) {
        Objects.requireNonNull(saveRoot, "saveRoot");
        Objects.requireNonNull(saveGameId, "saveGameId");
        Objects.requireNonNull(archiveReader, "archiveReader");
        Objects.requireNonNull(files, "files");
        MigrationPaths paths = paths(saveRoot, saveGameId);
        if (!Files.exists(paths.world(), LinkOption.NOFOLLOW_LINKS)) {
            return PublicationObservation.absent();
        }
        Path mainIndex = paths.world().resolve("streamed-chunks.idx");
        Path recoveryIndex = paths.world().resolve("streamed-chunks.prev.idx");
        Path chunkTree = paths.world().resolve("streamed-chunks");
        boolean mainExists = Files.exists(mainIndex, LinkOption.NOFOLLOW_LINKS);
        boolean recoveryExists = Files.exists(
                recoveryIndex, LinkOption.NOFOLLOW_LINKS);
        boolean treeExists = Files.exists(chunkTree, LinkOption.NOFOLLOW_LINKS);
        boolean task4Evidence = mainExists || recoveryExists || treeExists;
        boolean sideEvidence = hasMigrationSideEvidence(paths);
        if (!sideEvidence && !task4Evidence) {
            return PublicationObservation.unpublished();
        }
        try {
            ReadSession session = openReadSession(paths, files);
            ObservedMarker first = observeMarker(session, paths.markerA(), files);
            ObservedMarker second = observeMarker(session, paths.markerB(), files);
            ObservedFloor floorA = observeFloor(
                    session, paths.publicationFloorA(), files);
            ObservedFloor floorB = observeFloor(
                    session, paths.publicationFloorB(), files);
            boolean initializingQuorum = matchingInitializingQuorum(
                    saveGameId,
                    sha256File(paths.current(), files),
                    floorA,
                    floorB);
            boolean safeMarkers = safePrepublicationMarkers(first, second);

            if (!task4Evidence) {
                return safePreTask4State(saveGameId, first, second, floorA, floorB)
                        ? PublicationObservation.unpublished()
                        : PublicationObservation.invalid(new IOException(
                                "Migration initialization evidence is invalid"));
            }

            boolean completeTask4Shape = completeTask4Shape(
                    mainIndex, recoveryIndex, chunkTree);
            if (!completeTask4Shape && (!initializingQuorum || !safeMarkers)) {
                return PublicationObservation.invalid(
                        new IOException("Task 4 streamed authority tree is degraded"));
            }
            StreamedChunkStore store = new StreamedChunkStore(
                    paths.root(),
                    paths.id(),
                    new StreamedChunkCodec(),
                    new StreamedChunkIndexCodec(),
                    files);
            if (!completeTask4Shape(mainIndex, recoveryIndex, chunkTree)) {
                return PublicationObservation.invalid(new IOException(
                        "Task 4 initialization repair did not complete"));
            }
            StreamedChunkStore.MigrationCompatibilityReadResult migrationProof =
                    store.readMigrationCompatibility(paths.id());
            if (migrationProof.status()
                    == StreamedChunkStore.MigrationCompatibilityReadResult.Status
                            .NOT_PUBLISHED) {
                if (initializingQuorum) {
                    if (!safeMarkers) {
                        return PublicationObservation.invalid(new IOException(
                                "Task 4 initializing markers are invalid"));
                    }
                    return PublicationObservation.unpublished();
                }
                if (!store.hasCompleteUnpublishedAuthority()) {
                    return PublicationObservation.invalid(new IOException(
                            "Task 4 unpublished authority is incomplete"));
                }
                if (sideEvidence) {
                    return PublicationObservation.invalid(new IOException(
                            "Task 4 unpublished authority lacks initializing quorum"));
                }
                return PublicationObservation.unpublished();
            }
            if (migrationProof.status()
                    != StreamedChunkStore.MigrationCompatibilityReadResult.Status.FOUND) {
                return PublicationObservation.invalid(
                        new IOException("Task 4 streamed authority is invalid"));
            }
            StreamedChunkIndex.MigrationCompatibility compatibility =
                    migrationProof.compatibility().orElseThrow();
            boolean matchingMarkers = first.valid()
                    && second.valid()
                    && Arrays.equals(first.bytes(), second.bytes());
            if (!matchingMarkers
                    || !matchingPublishedTransition(
                            saveGameId,
                            compatibility,
                            first.bytes(),
                            floorA,
                            floorB)
                    || !compatibility.migrationMarkerSha256()
                            .equals(sha256(first.bytes()))
                    || !compatibility.sourceArchiveSha256()
                            .equals(first.marker().manifest().sourceArchiveSha256())) {
                return PublicationObservation.invalid(
                        new IOException(
                                "Published migration compatibility evidence is invalid"));
            }
            return PublicationObservation.valid(validateAuthority(
                    session,
                    paths.backup(),
                    archiveReader,
                    files,
                    first.marker(),
                    true));
        } catch (IOException | RuntimeException invalid) {
            return PublicationObservation.invalid(invalid);
        }
    }

    private static boolean hasMigrationSideEvidence(MigrationPaths paths) {
        return Files.exists(paths.markerA(), LinkOption.NOFOLLOW_LINKS)
                || Files.exists(paths.markerB(), LinkOption.NOFOLLOW_LINKS)
                || Files.exists(paths.publicationFloorA(), LinkOption.NOFOLLOW_LINKS)
                || Files.exists(paths.publicationFloorB(), LinkOption.NOFOLLOW_LINKS);
    }

    private static boolean completeTask4Shape(
            Path mainIndex, Path recoveryIndex, Path chunkTree) {
        return Files.isRegularFile(mainIndex, LinkOption.NOFOLLOW_LINKS)
                && Files.isRegularFile(recoveryIndex, LinkOption.NOFOLLOW_LINKS)
                && Files.isDirectory(chunkTree, LinkOption.NOFOLLOW_LINKS)
                && !Files.isSymbolicLink(mainIndex)
                && !Files.isSymbolicLink(recoveryIndex)
                && !Files.isSymbolicLink(chunkTree);
    }

    private static boolean safePreTask4State(
            SaveGameId id,
            ObservedMarker first,
            ObservedMarker second,
            ObservedFloor floorA,
            ObservedFloor floorB) {
        if ((first.present() && !first.empty())
                || (second.present() && !second.empty())) {
            return false;
        }
        int invalid = 0;
        for (ObservedFloor floor : new ObservedFloor[] {floorA, floorB}) {
            if (floor.valid()) {
                if (!floor.initializing() || !floor.floor().saveGameId().equals(id)) {
                    return false;
                }
            } else if (floor.present() && !floor.empty()) {
                invalid++;
            }
        }
        if (invalid > 1) {
            return false;
        }
        return !floorA.valid()
                || !floorB.valid()
                || Arrays.equals(floorA.bytes(), floorB.bytes());
    }

    private static boolean matchingInitializingQuorum(
            SaveGameId id,
            String sourceArchiveSha256,
            ObservedFloor floorA,
            ObservedFloor floorB) {
        return floorA.initializing()
                && floorB.initializing()
                && floorA.floor().saveGameId().equals(id)
                && floorA.floor().sourceArchiveSha256().equals(sourceArchiveSha256)
                && Arrays.equals(floorA.bytes(), floorB.bytes());
    }

    private static boolean safePrepublicationMarkers(
            ObservedMarker first, ObservedMarker second) {
        if ((first.present() && !first.empty() && !first.valid())
                || (second.present() && !second.empty() && !second.valid())) {
            return false;
        }
        if (second.valid() && !first.valid()) {
            return false;
        }
        return !first.valid()
                || !second.valid()
                || Arrays.equals(first.bytes(), second.bytes());
    }

    private static boolean matchingPublishedTransition(
            SaveGameId id,
            StreamedChunkIndex.MigrationCompatibility compatibility,
            byte[] markerBytes,
            ObservedFloor floorA,
            ObservedFloor floorB) {
        if (!floorA.valid() || !floorB.valid()) {
            return false;
        }
        PublicationFloor first = floorA.floor();
        PublicationFloor second = floorB.floor();
        if (!first.saveGameId().equals(id)
                || !second.saveGameId().equals(id)
                || !first.sourceArchiveSha256().equals(second.sourceArchiveSha256())
                || !first.nonce().equals(second.nonce())
                || !first.sourceArchiveSha256().equals(
                        compatibility.sourceArchiveSha256())) {
            return false;
        }
        String markerHash = sha256(markerBytes);
        return (first.state() == PublicationFloorState.INITIALIZING
                        || markerHash.equals(first.markerSha256()))
                && (second.state() == PublicationFloorState.INITIALIZING
                        || markerHash.equals(second.markerSha256()));
    }

    private static WorldItemMigrationData worldItemMigrationData(
            SaveGameSnapshot source,
            SaveGameId saveGameId) throws IOException {
        SaveIdentity identity = new SaveIdentity(UUID.fromString(saveGameId.value()));
        Set<ChunkKey> sourceChunkKeys = source.chunks().chunks().stream()
                .map(ChunkSnapshot::key)
                .collect(java.util.stream.Collectors.toSet());
        Map<ChunkKey, List<WorldItemRestoreEntry>> grouped = new HashMap<>();
        for (WorldItemRestoreEntry entry : source.worldItems().entries()) {
            if (entry.runtime().expiresAtWorldTick() <= source.fixedTick()) {
                continue;
            }
            ChunkKey key = worldItemChunkKey(entry);
            if (!sourceChunkKeys.contains(key)) {
                throw new IOException(
                        "Legacy WorldItem lies outside the imported Chunk authority");
            }
            grouped.computeIfAbsent(key, ignored -> new ArrayList<>()).add(entry);
        }
        List<ChunkKey> orderedKeys = grouped.keySet().stream()
                .sorted(ChunkCoordinatePolicy.canonicalComparator())
                .toList();
        WorldItemPageCodec pageCodec = new WorldItemPageCodec();
        Map<ChunkKey, StreamedChunkPayload.ExtensionDescriptor> pageExtensions =
                new HashMap<>();
        List<WorldItemPageSnapshot> pages = new ArrayList<>();
        List<WorldItemPageDescriptor> descriptors = new ArrayList<>();
        for (ChunkKey key : orderedKeys) {
            List<WorldItemRestoreEntry> entries = grouped.get(key).stream()
                    .sorted(Comparator.comparingLong(
                            value -> value.runtime().item().id().value()))
                    .toList();
            WorldItemPageSnapshot page = new WorldItemPageSnapshot(key, 1L, entries);
            byte[] pageBytes = pageCodec.encode(identity, page);
            WorldItemPageDescriptor descriptor = new WorldItemPageDescriptor(
                    key,
                    page.pageRevision(),
                    StreamedChunkCodec.sha256Hex(pageBytes),
                    entries.size(),
                    entries.size());
            pageExtensions.put(key, new StreamedChunkPayload.ExtensionDescriptor(
                    SaveSectionId.WORLD_ITEM_PAGE,
                    WorldItemPageCodec.CODEC_VERSION,
                    true,
                    pageBytes));
            pages.add(page);
            descriptors.add(descriptor);
        }
        WorldItemPagingCheckpoint checkpoint = new WorldItemPagingCheckpoint(
                identity,
                1L,
                source.fixedTick(),
                source.worldItems().nextItemId(),
                source.worldItems().itemIdsExhausted(),
                descriptors.stream()
                        .mapToInt(WorldItemPageDescriptor::expectedLiveCountAtCheckpointTick)
                        .sum(),
                descriptors);
        byte[] checkpointBytes = new WorldItemPagingCheckpointCodec().encode(checkpoint);
        StreamedGlobalExtension checkpointExtension = new StreamedGlobalExtension(
                SaveSectionId.WORLD_ITEM_CHECKPOINT,
                WorldItemPagingCheckpointCodec.CODEC_VERSION,
                true,
                Optional.of(new RequiredChunkExtensionDependency(
                        SaveSectionId.WORLD_ITEM_PAGE,
                        descriptors.size())),
                checkpointBytes);
        return new WorldItemMigrationData(
                Map.copyOf(pageExtensions),
                List.copyOf(pages),
                checkpointExtension);
    }

    private static ChunkKey worldItemChunkKey(WorldItemRestoreEntry entry)
            throws IOException {
        double floorX = Math.floor(entry.runtime().item().positionX());
        double floorZ = Math.floor(entry.runtime().item().positionZ());
        if (!Double.isFinite(floorX)
                || !Double.isFinite(floorZ)
                || floorX < Integer.MIN_VALUE
                || floorX > Integer.MAX_VALUE
                || floorZ < Integer.MIN_VALUE
                || floorZ > Integer.MAX_VALUE) {
            throw new IOException("Legacy WorldItem position is outside the safe envelope");
        }
        return ChunkCoordinatePolicy.requireSafe(ChunkKey.fromWorld(
                (int) floorX, (int) floorZ));
    }

    private void persistExactBatch(
            StreamedChunkStore store,
            List<StreamedChunkStore.ExactChunkCapture> captures,
            StreamedGlobalExtension checkpointExtension) {
        StreamedChunkStore.CommitResult committed =
                store.commitMigrationBatch(captures, checkpointExtension);
        if (committed.status() != StreamedChunkStore.CommitResult.Status.SUCCESS) {
            throw failure(
                    committed.status()
                            == StreamedChunkStore.CommitResult.Status.BLOCKING_FAILURE,
                    "phase14-migration.chunk-batch-write-failed",
                    "The Phase 14 Chunk set could not be persisted",
                    diagnosticCause(committed.diagnostics()));
        }
    }

    private record WorldItemMigrationData(
            Map<ChunkKey, StreamedChunkPayload.ExtensionDescriptor> pageExtensions,
            List<WorldItemPageSnapshot> pages,
            StreamedGlobalExtension checkpointExtension) {}

    private void checkpoint(
            CheckpointStage stage,
            CheckpointSide side,
            int ordinal,
            ChunkKey key) throws IOException {
        checkpoints.reach(new Checkpoint(stage, side, ordinal, key));
    }

    private MigrationSession initializeSession(
            MigrationPaths paths, String sourceArchiveSha256) throws IOException {
        ReadSession directories = openReadSession(paths, files);
        SlotAnchor floorA = ensureFixedSlot(
                directories,
                paths.publicationFloorA(),
                MAX_PUBLICATION_FLOOR_BYTES);
        SlotAnchor floorB = ensureFixedSlot(
                directories,
                paths.publicationFloorB(),
                MAX_PUBLICATION_FLOOR_BYTES);
        MigrationSession slots = new MigrationSession(
                directories, null, null, floorA, floorB, null);
        PublicationFloor initializing = establishInitializingIntent(
                slots, sourceArchiveSha256);
        return new MigrationSession(
                directories, null, null, floorA, floorB, initializing);
    }

    private MigrationSession initializeMarkerSlots(MigrationSession session)
            throws IOException {
        MigrationPaths paths = session.directories().paths();
        SlotAnchor markerA = ensureFixedSlot(
                session.directories(), paths.markerA(), MAX_MARKER_BYTES);
        SlotAnchor markerB = ensureFixedSlot(
                session.directories(), paths.markerB(), MAX_MARKER_BYTES);
        return new MigrationSession(
                session.directories(),
                markerA,
                markerB,
                session.publicationFloorA(),
                session.publicationFloorB(),
                session.initializingFloor());
    }

    private PublicationFloor establishInitializingIntent(
            MigrationSession session, String sourceArchiveSha256) throws IOException {
        if (!sourceArchiveSha256.matches("[0-9a-f]{64}")) {
            throw new IOException("Migration source hash is invalid");
        }
        PublicationFloor first = readFloorSlot(
                session, session.publicationFloorA());
        PublicationFloor second = readFloorSlot(
                session, session.publicationFloorB());
        String nonce = null;
        for (PublicationFloor candidate : new PublicationFloor[] {first, second}) {
            if (candidate == null) {
                continue;
            }
            if (candidate.state() != PublicationFloorState.INITIALIZING
                    || !candidate.saveGameId().equals(session.directories().paths().id())
                    || !candidate.sourceArchiveSha256().equals(sourceArchiveSha256)) {
                throw new IOException("Migration initialization intent is incompatible");
            }
            if (nonce != null && !nonce.equals(candidate.nonce())) {
                throw new IOException("Migration initialization intent quorum conflicts");
            }
            nonce = candidate.nonce();
        }
        if (nonce == null) {
            byte[] random = new byte[16];
            new SecureRandom().nextBytes(random);
            nonce = HexFormat.of().formatHex(random);
        }
        PublicationFloor intent = PublicationFloor.initializing(
                session.directories().paths().id(), sourceArchiveSha256, nonce);
        byte[] bytes = encodePublicationFloor(intent);
        writeFloorState(session, session.publicationFloorA(), bytes);
        writeFloorState(session, session.publicationFloorB(), bytes);
        return intent;
    }

    private PublicationFloor readFloorSlot(
            MigrationSession session, SlotAnchor slot) throws IOException {
        byte[] bytes = files.readBounded(
                slot.path(),
                slot.maximumBytes(),
                () -> requireManaged(session.directories(), slot, files));
        if (bytes.length == 0) {
            return null;
        }
        try {
            return decodePublicationFloor(bytes);
        } catch (IOException tornPreQuorum) {
            return null;
        }
    }

    private void writeFloorState(
            MigrationSession session, SlotAnchor slot, byte[] bytes) throws IOException {
        writeFixedSlot(session, slot, bytes, MAX_PUBLICATION_FLOOR_BYTES);
        files.forceDirectoryDurably(
                session.directories().paths().world(),
                () -> requireReadSession(session.directories(), files));
        requireManaged(session.directories(), slot, files);
    }

    private SlotAnchor ensureFixedSlot(
            ReadSession session, Path slot, long maximumBytes) throws IOException {
        requireReadSession(session, files);
        if (!Files.exists(slot, LinkOption.NOFOLLOW_LINKS)) {
            files.createBounded(slot, new byte[0], maximumBytes, () -> {
                requireReadSession(session, files);
                requireDirectChild(session.paths().world(), slot);
                if (Files.exists(slot, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("Migration manifest slot already exists");
                }
            });
        }
        requireRegularDirectChild(session.paths().world(), slot);
        Object identity = files.readManagedFileIdentity(
                slot,
                maximumBytes,
                () -> {
                    requireReadSession(session, files);
                    requireRegularDirectChild(session.paths().world(), slot);
                });
        SlotAnchor anchor = new SlotAnchor(slot, maximumBytes, identity);
        files.forceFile(slot, () -> requireManaged(session, anchor, files));
        files.forceDirectoryDurably(
                session.paths().world(), () -> requireReadSession(session, files));
        requireManaged(session, anchor, files);
        return anchor;
    }

    private void writeFixedSlot(
            MigrationSession session,
            SlotAnchor slot,
            byte[] bytes,
            long maximumBytes) throws IOException {
        if (slot.maximumBytes() != maximumBytes) {
            throw new IOException("Migration slot bound mismatch");
        }
        requireManaged(session.directories(), slot, files);
        files.writeExistingBounded(
                slot.path(),
                bytes,
                maximumBytes,
                () -> requireManaged(session.directories(), slot, files));
        Object written = files.readManagedFileIdentity(
                slot.path(),
                maximumBytes,
                () -> {
                    requireReadSession(session.directories(), files);
                    requireRegularDirectChild(
                            session.directories().paths().world(), slot.path());
                });
        slot.updateIdentity(written);
        files.forceFile(
                slot.path(), () -> requireManaged(session.directories(), slot, files));
        Object forced = files.readManagedFileIdentity(
                slot.path(),
                maximumBytes,
                () -> requireManaged(session.directories(), slot, files));
        slot.updateIdentity(forced);
        byte[] reread = files.readBounded(
                slot.path(),
                maximumBytes,
                () -> requireManaged(session.directories(), slot, files));
        if (!Arrays.equals(bytes, reread)) {
            throw new IOException("Migration manifest slot reread mismatch");
        }
        if (maximumBytes == MAX_MARKER_BYTES) {
            decodeMarker(reread);
        } else {
            decodePublicationFloor(reread);
        }
    }

    private void preserveExactBackup(MigrationSession session, String sourceHash)
            throws IOException {
        MigrationPaths paths = session.directories().paths();
        Path temporary = null;
        boolean temporaryOwned = false;
        Throwable primary = null;
        try {
            temporary = files.createSiblingTemp(
                    paths.world(),
                    BACKUP_NAME,
                    () -> requireReadSession(session.directories(), files));
            temporary = temporary.toAbsolutePath().normalize();
            requireTemporary(paths, temporary);
            temporaryOwned = true;
            Path owned = temporary;
            files.copyReplacing(
                    paths.current(),
                    owned,
                    () -> {
                        requireReadSession(session.directories(), files);
                        requireRegularDirectChild(paths.world(), paths.current());
                        requireTemporary(paths, owned);
                    });
            files.forceFile(owned, () -> {
                requireReadSession(session.directories(), files);
                requireTemporary(paths, owned);
            });
            if (!sha256File(owned, files).equals(sourceHash)) {
                throw new IOException("Migration backup staging validation failed");
            }
            try {
                files.moveAtomicReplacing(
                        owned,
                        paths.backup(),
                        () -> {
                            requireReadSession(session.directories(), files);
                            requireTemporary(paths, owned);
                            requireReplaceableBackup(paths);
                        });
            } catch (AtomicMoveNotSupportedException unsupported) {
                files.moveReplacing(
                        owned,
                        paths.backup(),
                        () -> {
                            requireReadSession(session.directories(), files);
                            requireTemporary(paths, owned);
                            requireReplaceableBackup(paths);
                        });
            }
            temporaryOwned = false;
            requireRegularDirectChild(paths.world(), paths.backup());
            files.forceDirectoryDurably(
                    paths.world(),
                    () -> requireReadSession(session.directories(), files));
            if (!sha256File(paths.backup(), files).equals(sourceHash)
                    || Files.mismatch(paths.current(), paths.backup()) != -1L) {
                throw new IOException("Migration backup publication validation failed");
            }
        } catch (IOException | RuntimeException failure) {
            primary = failure;
            throw failure;
        } finally {
            if (temporaryOwned && temporary != null) {
                try {
                    Path owned = temporary;
                    files.deleteIfExists(owned, () -> {
                        requireReadSession(session.directories(), files);
                        requireTemporary(paths, owned);
                    });
                } catch (IOException | RuntimeException cleanup) {
                    if (primary != null && cleanup != primary) {
                        primary.addSuppressed(cleanup);
                    }
                }
            }
        }
    }

    private static Phase14MigrationResult.PublishedMigration validateAuthority(
            ReadSession session,
            Path sourceArchive,
            SaveArchiveReader archiveReader,
            SaveFileOperations files,
            Marker marker,
            boolean requireBackup) throws IOException {
        MigrationPaths paths = session.paths();
        requireReadSession(session, files);
        if (requireBackup && !sourceArchive.equals(paths.backup())) {
            throw new IOException("Published migration requires its recovery backup");
        }
        requireRegularDirectChild(paths.world(), sourceArchive);
        SlotAnchor sourceSlot = captureSlot(
                session, sourceArchive, SaveArchiveLimits.MAX_ARCHIVE_FILE_BYTES, files);
        String expectedSourceHash = marker.manifest().sourceArchiveSha256();
        if (!sha256Managed(session, sourceSlot, files).equals(expectedSourceHash)) {
            throw new IOException("Migration source archive hash mismatch");
        }
        SaveArchiveReadResult sourceRead = archiveReader.readPhase14(sourceArchive);
        if (sourceRead.status() != SaveArchiveReadResult.Status.VALID) {
            throw new IOException("Migration recovery archive is unreadable");
        }
        SaveGameSnapshot source = sourceRead.snapshot().orElseThrow();
        if (!source.metadata().saveGameId().equals(paths.id())) {
            throw new IOException("Migration recovery archive identity is invalid");
        }
        requireManaged(session, sourceSlot, files);
        if (requireBackup) {
            requireRegularDirectChild(paths.world(), paths.current());
            SlotAnchor currentSlot = captureSlot(
                    session,
                    paths.current(),
                    SaveArchiveLimits.MAX_ARCHIVE_FILE_BYTES,
                    files);
            if (!sha256Managed(session, currentSlot, files).equals(expectedSourceHash)
                    || Files.mismatch(paths.current(), paths.backup()) != -1L) {
                throw new IOException("Retained migration source archives differ");
            }
            SaveArchiveReadResult currentRead = archiveReader.readPhase14(paths.current());
            if (currentRead.status() != SaveArchiveReadResult.Status.VALID
                    || !currentRead.snapshot().orElseThrow().metadata().saveGameId()
                            .equals(paths.id())) {
                throw new IOException("Retained migration current archive is invalid");
            }
            requireManaged(session, currentSlot, files);
            requireManaged(session, sourceSlot, files);
        }
        validateManifestMetadata(marker.manifest(), source);
        validateIndexDescriptor(marker.manifest(), marker.index());

        StreamedChunkStore store = new StreamedChunkStore(
                paths.root(),
                paths.id(),
                new StreamedChunkCodec(),
                new StreamedChunkIndexCodec(),
                files);
        Map<ChunkKey, ChunkSnapshot> sourceChunks = new HashMap<>();
        for (ChunkSnapshot chunk : source.chunks().chunks()) {
            sourceChunks.put(chunk.key(), chunk);
        }
        if (sourceChunks.size() != marker.index().entries().size()) {
            throw new IOException("Migration index does not cover the source Chunk set");
        }
        WorldItemMigrationData migrationWorldItems = worldItemMigrationData(
                source, paths.id());
        if (!marker.index().globalExtensions().equals(
                List.of(migrationWorldItems.checkpointExtension()))) {
            throw new IOException(
                    "Migration WorldItem checkpoint differs from the v1 authority");
        }
        Map<ChunkKey, StreamedChunkIndex.Entry> migrationFloor = new HashMap<>();
        Map<ChunkKey, StreamedChunkPayload> migrationFloorPayloads = new HashMap<>();
        for (StreamedChunkIndex.Entry imported : marker.index().entries()) {
            ChunkSnapshot expected = sourceChunks.get(imported.key());
            String expectedBaseHash = expected == null
                    ? ""
                    : reproducedBaseHash(
                            source.metadata(), imported.key());
            StreamedChunkPayload expectedFloor = expected == null
                    ? null
                    : new StreamedChunkPayload(
                            paths.id(),
                            expected.key(),
                            source.metadata().generatorVersion(),
                            expectedBaseHash,
                            expected.revision(),
                            0L,
                            true,
                            expected.worldHeight(),
                            expected.copyBlocks(),
                            Optional.ofNullable(
                                            migrationWorldItems.pageExtensions()
                                                    .get(expected.key()))
                                    .map(List::of)
                                    .orElseGet(List::of));
            byte[] expectedFloorBytes = expectedFloor == null
                    ? new byte[0]
                    : new StreamedChunkCodec().encode(expectedFloor);
            if (expected == null
                    || !imported.modified()
                    || imported.revision() != expected.revision()
                    || !imported.generatorVersion().equals(
                            source.metadata().generatorVersion())
                    || !imported.baseHash().equals(expectedBaseHash)
                    || imported.payloadSize() != expectedFloorBytes.length
                    || !imported.payloadHash().equals(
                            StreamedChunkCodec.sha256Hex(expectedFloorBytes))) {
                throw new IOException(
                        "Migration publication floor is incompatible with v1");
            }
            migrationFloor.put(imported.key(), imported);
            migrationFloorPayloads.put(imported.key(), expectedFloor);
        }
        try (StreamedChunkStore.BoundedReadView current =
                store.openBoundedReadView()) {
            StreamedChunkIndex currentIndex = current.index();
            List<ChunkSnapshot> rereadChunks = new ArrayList<>(sourceChunks.size());
            long revisionHighWater = source.chunks().revisionHighWater();
            for (StreamedChunkIndex.Entry entry : currentIndex.entries()) {
                StreamedChunkPayload payload = current.payload(entry.key());
                if (payload == null) {
                    throw new IOException("Migration Chunk authority is incomplete");
                }
                ChunkSnapshot imported = sourceChunks.get(entry.key());
                StreamedChunkIndex.Entry floor = migrationFloor.get(entry.key());
                StreamedChunkPayload floorPayload =
                        migrationFloorPayloads.get(entry.key());
                if (!payload.modified()
                        || !payload.generatorVersion().equals(
                                source.metadata().generatorVersion())
                        || !payload.baseHash().equals(reproducedBaseHash(
                                source.metadata(), payload.key()))
                        || payload.worldHeight() != source.chunks().worldHeight()) {
                    throw new IOException(
                            "Migration Chunk base compatibility is invalid");
                }
                if (imported != null
                        && (floor == null
                                || floorPayload == null
                                || payload.revision() < floor.revision()
                                || (payload.revision() == floor.revision()
                                        && (!entry.equals(floor)
                                                || !payload.equals(floorPayload)))
                                || (payload.revision() > floor.revision()
                                        && (payload.persistedRevision()
                                                        < floorPayload.persistedRevision()
                                                || payload.persistedRevision()
                                                        > payload.revision())))) {
                    throw new IOException(
                            "Migration Chunk differs from its v1 authority");
                }
                if (imported != null) {
                    rereadChunks.add(ChunkSnapshot.of(
                            payload.key(),
                            payload.revision(),
                            payload.worldHeight(),
                            payload.copyCanonicalVoxels()));
                }
                revisionHighWater = Math.max(
                        revisionHighWater, payload.revision());
            }
            for (ChunkKey importedKey : sourceChunks.keySet()) {
                if (currentIndex.entry(importedKey).isEmpty()) {
                    throw new IOException("Migration index omits a source Chunk");
                }
            }
            try (WorldItemPageReadView ignored =
                    new StreamedWorldItemPageBackend(store).openReadView(current)) {
                if (ignored.checkpoint().worldTick() < source.fixedTick()
                        || ignored.checkpoint().nextItemId()
                                < source.worldItems().nextItemId()) {
                    throw new IOException(
                            "Migration WorldItem authority regressed its clock or allocator");
                }
            } catch (RuntimeException invalidWorldItems) {
                throw new IOException(
                        "Migration WorldItem authority is invalid", invalidWorldItems);
            }
            SaveGameSnapshot reread = new SaveGameSnapshot(
                    source.metadata(),
                    source.fixedTick(),
                    new ChunkRepositorySnapshot(
                            source.chunks().worldHeight(),
                            revisionHighWater,
                            rereadChunks),
                    source.player(),
                    source.inventory(),
                    source.worldItems());
            return new Phase14MigrationResult.PublishedMigration(
                    marker.manifest(), currentIndex, reread);
        } catch (RuntimeException invalidChunks) {
            throw new IOException(
                    "Migration Chunk authority is invalid", invalidChunks);
        }
    }

    private static void validateStagedMarker(
            MigrationSession session,
            Path sourceArchive,
            SaveGameSnapshot source,
            Marker expected,
            SaveFileOperations files) throws IOException {
        MigrationPaths paths = session.directories().paths();
        Marker reread = readMarkerSlot(session, session.markerA(), files)
                .orElseThrow(() -> new IOException(
                        "Staged migration manifest failed reread"));
        requireSameIndex(expected.index(), reread.index());
        validateManifestMetadata(reread.manifest(), source);
        validateIndexDescriptor(reread.manifest(), reread.index());
        if (!sha256File(sourceArchive, files)
                .equals(reread.manifest().sourceArchiveSha256())) {
            throw new IOException("Migration source archive hash mismatch");
        }
    }

    private static Phase14MigrationResult.ValidatedV2Manifest v2Manifest(
            SaveGameManifest source, String sourceHash, byte[] indexBytes) {
        List<SaveSectionDescriptor> sections = new ArrayList<>();
        for (SaveSectionDescriptor descriptor : source.sections()) {
            if (descriptor.sectionId().equals(SaveSectionId.PLAYER)
                    || descriptor.sectionId().equals(SaveSectionId.INVENTORY)
                    || descriptor.sectionId().equals(SaveSectionId.WORLD_ITEMS)) {
                sections.add(descriptor);
            }
        }
        sections.add(new SaveSectionDescriptor(
                SaveSectionId.STREAMED_CHUNKS,
                new StreamedChunkIndexCodec().codecVersion(),
                true,
                indexBytes.length,
                sha256(indexBytes)));
        return new Phase14MigrationResult.ValidatedV2Manifest(
                SaveFormatVersion.STREAMED_CHUNKS,
                source.gameVersion(),
                source.saveGameId(),
                source.displayName(),
                source.createdAt(),
                source.modifiedAt(),
                source.worldSeed(),
                source.generatorVersion(),
                source.generatorConfigFingerprint(),
                source.chunkRadius(),
                source.worldHeight(),
                source.fixedTick(),
                source.summary(),
                sourceHash,
                sections);
    }

    private static byte[] encodeMarker(
            Phase14MigrationResult.ValidatedV2Manifest manifest,
            byte[] indexBytes) {
        JsonObject root = new JsonObject();
        root.addProperty("schema", MARKER_SCHEMA);
        root.addProperty("formatVersion", manifest.formatVersion().value());
        root.addProperty("gameVersion", manifest.gameVersion());
        root.addProperty("saveGameId", manifest.saveGameId().value());
        root.addProperty("displayName", manifest.displayName());
        root.addProperty("createdAt", manifest.createdAt().toString());
        root.addProperty("modifiedAt", manifest.modifiedAt().toString());
        root.addProperty("worldSeed", manifest.worldSeed());
        root.addProperty("generatorVersion", manifest.generatorVersion());
        root.addProperty(
                "generatorConfigFingerprint",
                manifest.generatorConfigFingerprint());
        root.addProperty("chunkRadius", manifest.chunkRadius());
        root.addProperty("worldHeight", manifest.worldHeight());
        root.addProperty("fixedTick", manifest.fixedTick());
        root.add("summary", manifest.summary() == null
                ? JsonNull.INSTANCE
                : new com.google.gson.JsonPrimitive(manifest.summary()));
        root.addProperty("sourceArchiveSha256", manifest.sourceArchiveSha256());
        JsonArray sections = new JsonArray();
        for (SaveSectionDescriptor descriptor : manifest.sections()) {
            JsonObject section = new JsonObject();
            section.addProperty("sectionId", descriptor.sectionId().value());
            section.addProperty("codecVersion", descriptor.codecVersion());
            section.addProperty("required", descriptor.required());
            section.addProperty("uncompressedSize", descriptor.uncompressedSize());
            section.addProperty("sha256", descriptor.sha256());
            sections.add(section);
        }
        root.add("sections", sections);
        root.addProperty("index", Base64.getEncoder().encodeToString(indexBytes));
        byte[] encoded = root.toString().getBytes(StandardCharsets.UTF_8);
        if (encoded.length == 0 || encoded.length > MAX_MARKER_BYTES) {
            throw new IllegalArgumentException("Migration manifest exceeds its bound");
        }
        return encoded;
    }

    private static Marker decodeMarker(byte[] bytes) throws IOException {
        if (bytes.length == 0 || bytes.length > MAX_MARKER_BYTES) {
            throw new IOException("Migration manifest byte length is invalid");
        }
        try {
            JsonObject root = JsonParser.parseString(
                    new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
            if (!root.keySet().equals(MARKER_FIELDS)
                    || root.get("schema").getAsInt() != MARKER_SCHEMA
                    || root.get("formatVersion").getAsInt()
                            != SaveFormatVersion.STREAMED_CHUNKS.value()) {
                throw new IllegalArgumentException("Migration manifest fields are invalid");
            }
            JsonArray encodedSections = root.getAsJsonArray("sections");
            List<SaveSectionDescriptor> sections = new ArrayList<>();
            for (var element : encodedSections) {
                JsonObject section = element.getAsJsonObject();
                if (!section.keySet().equals(SECTION_FIELDS)) {
                    throw new IllegalArgumentException(
                            "Migration section fields are invalid");
                }
                sections.add(new SaveSectionDescriptor(
                        new SaveSectionId(section.get("sectionId").getAsString()),
                        section.get("codecVersion").getAsInt(),
                        section.get("required").getAsBoolean(),
                        section.get("uncompressedSize").getAsLong(),
                        section.get("sha256").getAsString()));
            }
            byte[] indexBytes = Base64.getDecoder().decode(
                    root.get("index").getAsString());
            StreamedChunkIndex index = new StreamedChunkIndexCodec().decode(indexBytes);
            Phase14MigrationResult.ValidatedV2Manifest manifest =
                    new Phase14MigrationResult.ValidatedV2Manifest(
                            SaveFormatVersion.STREAMED_CHUNKS,
                            root.get("gameVersion").getAsString(),
                            SaveGameId.parse(root.get("saveGameId").getAsString()),
                            root.get("displayName").getAsString(),
                            Instant.parse(root.get("createdAt").getAsString()),
                            Instant.parse(root.get("modifiedAt").getAsString()),
                            root.get("worldSeed").getAsLong(),
                            root.get("generatorVersion").getAsString(),
                            root.get("generatorConfigFingerprint").getAsString(),
                            root.get("chunkRadius").getAsInt(),
                            root.get("worldHeight").getAsInt(),
                            root.get("fixedTick").getAsLong(),
                            root.get("summary").isJsonNull()
                                    ? null
                                    : root.get("summary").getAsString(),
                            root.get("sourceArchiveSha256").getAsString(),
                            sections);
            byte[] canonical = encodeMarker(manifest, indexBytes);
            if (!Arrays.equals(bytes, canonical)) {
                throw new IllegalArgumentException(
                        "Migration manifest is not canonical");
            }
            if (!index.saveGameId().equals(manifest.saveGameId())) {
                throw new IllegalArgumentException(
                        "Migration index identity does not match its manifest");
            }
            validateIndexDescriptor(manifest, index);
            return new Marker(manifest, index);
        } catch (RuntimeException malformed) {
            throw new IOException("Migration manifest is malformed", malformed);
        }
    }

    private static byte[] encodePublishedFloor(
            PublicationFloor initializing, byte[] markerBytes) {
        if (initializing.state() != PublicationFloorState.INITIALIZING) {
            throw new IllegalArgumentException("Publication requires initializing floor");
        }
        return encodePublicationFloor(PublicationFloor.published(
                initializing.saveGameId(),
                initializing.sourceArchiveSha256(),
                initializing.nonce(),
                sha256(markerBytes)));
    }

    private static byte[] encodePublicationFloor(PublicationFloor floor) {
        String markerHash = floor.markerSha256() == null
                ? "-"
                : floor.markerSha256();
        String prefix = "GaiaLegacy.StreamedMigrationPublication|"
                + PUBLICATION_FLOOR_SCHEMA
                + "|"
                + floor.state().name()
                + "|"
                + floor.saveGameId().value()
                + "|"
                + floor.sourceArchiveSha256()
                + "|"
                + floor.nonce()
                + "|"
                + markerHash;
        String canonical = prefix + "|" + sha256(prefix);
        byte[] bytes = canonical.getBytes(StandardCharsets.UTF_8);
        if (bytes.length == 0 || bytes.length > MAX_PUBLICATION_FLOOR_BYTES) {
            throw new IllegalArgumentException("Publication floor exceeds its bound");
        }
        return bytes;
    }

    private static PublicationFloor decodePublicationFloor(byte[] bytes)
            throws IOException {
        if (bytes.length == 0 || bytes.length > MAX_PUBLICATION_FLOOR_BYTES) {
            throw new IOException("Publication floor byte length is invalid");
        }
        try {
            String[] parts = new String(bytes, StandardCharsets.UTF_8)
                    .split("\\|", -1);
            if (parts.length != 8
                    || !parts[0].equals(
                            "GaiaLegacy.StreamedMigrationPublication")
                    || Integer.parseInt(parts[1]) != PUBLICATION_FLOOR_SCHEMA
                    || !parts[4].matches("[0-9a-f]{64}")
                    || !parts[5].matches("[0-9a-f]{32}")
                    || !parts[7].matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("Publication floor is malformed");
            }
            PublicationFloorState state = PublicationFloorState.valueOf(parts[2]);
            String markerHash = parts[6].equals("-") ? null : parts[6];
            if ((state == PublicationFloorState.INITIALIZING && markerHash != null)
                    || (state == PublicationFloorState.PUBLISHED
                            && (markerHash == null
                                    || !markerHash.matches("[0-9a-f]{64}")))) {
                throw new IllegalArgumentException("Publication floor state is malformed");
            }
            PublicationFloor floor = new PublicationFloor(
                    state,
                    SaveGameId.parse(parts[3]),
                    parts[4],
                    parts[5],
                    markerHash);
            String prefix = String.join("|", Arrays.copyOf(parts, 7));
            if (!parts[7].equals(sha256(prefix))
                    || !Arrays.equals(bytes, encodePublicationFloor(floor))) {
                throw new IllegalArgumentException("Publication floor is not canonical");
            }
            return floor;
        } catch (RuntimeException malformed) {
            throw new IOException("Publication floor is malformed", malformed);
        }
    }

    private static Optional<Marker> readMarkerSlot(
            MigrationSession session, SlotAnchor slot, SaveFileOperations files)
            throws IOException {
        byte[] bytes = files.readBounded(
                slot.path(),
                slot.maximumBytes(),
                () -> requireManaged(session.directories(), slot, files));
        if (bytes.length == 0) {
            return Optional.empty();
        }
        return Optional.of(decodeMarker(bytes));
    }

    private static ObservedMarker observeMarker(
            ReadSession session, Path path, SaveFileOperations files) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return ObservedMarker.missing();
        }
        SlotAnchor slot = captureSlot(session, path, MAX_MARKER_BYTES, files);
        byte[] bytes = files.readBounded(
                path,
                MAX_MARKER_BYTES,
                () -> requireManaged(session, slot, files));
        if (bytes.length == 0) {
            return ObservedMarker.empty(bytes);
        }
        try {
            return ObservedMarker.valid(bytes, decodeMarker(bytes));
        } catch (IOException invalid) {
            return ObservedMarker.invalid(bytes);
        }
    }

    private static ObservedFloor observeFloor(
            ReadSession session, Path path, SaveFileOperations files) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return ObservedFloor.missing();
        }
        SlotAnchor slot = captureSlot(
                session, path, MAX_PUBLICATION_FLOOR_BYTES, files);
        byte[] bytes = files.readBounded(
                path,
                MAX_PUBLICATION_FLOOR_BYTES,
                () -> requireManaged(session, slot, files));
        if (bytes.length == 0) {
            return ObservedFloor.empty(bytes);
        }
        try {
            return ObservedFloor.valid(bytes, decodePublicationFloor(bytes));
        } catch (IOException invalid) {
            return ObservedFloor.invalid(bytes);
        }
    }

    private static void validateIndexDescriptor(
            Phase14MigrationResult.ValidatedV2Manifest manifest,
            StreamedChunkIndex index) throws IOException {
        List<SaveSectionId> ids = manifest.sections().stream()
                .map(SaveSectionDescriptor::sectionId)
                .toList();
        if (!ids.equals(List.of(
                SaveSectionId.PLAYER,
                SaveSectionId.INVENTORY,
                SaveSectionId.WORLD_ITEMS,
                SaveSectionId.STREAMED_CHUNKS))) {
            throw new IOException("Migration manifest section set is invalid");
        }
        byte[] bytes = new StreamedChunkIndexCodec().encode(index);
        SaveSectionDescriptor descriptor = manifest.sections().get(3);
        if (!descriptor.required()
                || descriptor.codecVersion()
                        != new StreamedChunkIndexCodec().codecVersion()
                || descriptor.uncompressedSize() != bytes.length
                || !descriptor.sha256().equals(sha256(bytes))) {
            throw new IOException("Migration index descriptor is invalid");
        }
    }

    private static void validateManifestMetadata(
            Phase14MigrationResult.ValidatedV2Manifest manifest,
            SaveGameSnapshot source) throws IOException {
        SaveGameSnapshot.StaticMetadata metadata = source.metadata();
        if (!manifest.saveGameId().equals(metadata.saveGameId())
                || !manifest.gameVersion().equals(metadata.gameVersion())
                || !manifest.displayName().equals(metadata.displayName())
                || !manifest.createdAt().equals(metadata.createdAt())
                || manifest.worldSeed() != metadata.worldSeed()
                || !manifest.generatorVersion().equals(metadata.generatorVersion())
                || !manifest.generatorConfigFingerprint().equals(
                        metadata.generatorConfigFingerprint())
                || manifest.chunkRadius() != metadata.chunkRadius()
                || manifest.worldHeight() != metadata.worldHeight()
                || manifest.fixedTick() != source.fixedTick()
                || !Objects.equals(manifest.summary(), metadata.summary().orElse(null))) {
            throw new IOException("Migration manifest metadata does not match v1");
        }
    }

    private static Optional<GenerationAuthority> generationAuthority(
            SaveGameSnapshot.StaticMetadata metadata) {
        Optional<GenerationAuthority> defaults = recognizedAuthority(
                metadata,
                WorldGenerationConfig.defaults(),
                GaiaWorldGenerator.createDefault());
        if (defaults.isPresent()) {
            return defaults;
        }
        return recognizedAuthority(
                metadata,
                WorldGenerationConfig.visualRevisionCandidate(),
                GaiaWorldGenerator.createVisualRevisionCandidate());
    }

    private static Optional<GenerationAuthority> recognizedAuthority(
            SaveGameSnapshot.StaticMetadata metadata,
            WorldGenerationConfig template,
            WorldGenerator generator) {
        WorldGenerationConfig candidate = configured(metadata, template);
        String version = "gaia-v" + candidate.algorithmVersion();
        if (!version.equals(metadata.generatorVersion())
                || !sha256(candidate.canonicalFingerprintInput())
                        .equals(metadata.generatorConfigFingerprint())) {
            return Optional.empty();
        }
        return Optional.of(new GenerationAuthority(
                new GenerationContext(
                        candidate,
                        new GenerationBlockPalette(
                                (byte) 0,
                                (byte) 1,
                                (byte) 2,
                                (byte) 3,
                                (byte) 4,
                                (byte) 5),
                        new DeterministicCoordinateSampler(
                                candidate.seed(), candidate.algorithmVersion())),
                Objects.requireNonNull(generator, "generator")));
    }

    private static WorldGenerationConfig configured(
            SaveGameSnapshot.StaticMetadata metadata,
            WorldGenerationConfig template) {
        return new WorldGenerationConfig(
                metadata.worldSeed(),
                template.algorithmVersion(),
                metadata.chunkRadius(),
                template.biome(),
                template.height(),
                template.cave(),
                template.surface(),
                template.decoration(),
                template.spawn());
    }

    static String reproducedBaseHash(
            SaveGameSnapshot.StaticMetadata metadata,
            ChunkKey key) {
        SaveGameSnapshot.StaticMetadata checkedMetadata = Objects.requireNonNull(
                metadata, "metadata");
        ChunkKey checkedKey = Objects.requireNonNull(key, "key");
        Optional<GenerationAuthority> authority = generationAuthority(
                checkedMetadata);
        if (authority.isPresent()) {
            GenerationAuthority recognized = authority.orElseThrow();
            WorldGenerationResult generated = recognized.generator().generate(
                    recognized.context(), checkedKey);
            if (!generated.succeeded()) {
                throw failure(
                        false,
                        "phase14-migration.base-generation-failed",
                        "A Phase 14 Chunk base could not be reproduced");
            }
            return WorldGenerationHasher.hashChunk(
                    recognized.context().config(),
                    generated.chunkData().orElseThrow());
        }
        String legacyIdentity = "GaiaLegacy.Phase14.ImportBase.v1|"
                + checkedMetadata.generatorVersion()
                + "|"
                + checkedMetadata.generatorConfigFingerprint()
                + "|"
                + checkedKey.x()
                + "|"
                + checkedKey.z();
        return sha256(legacyIdentity);
    }

    private record GenerationAuthority(
            GenerationContext context,
            WorldGenerator generator) {}

    private static SaveGameManifest readV1Manifest(Path archive) throws IOException {
        byte[] bytes;
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            ZipEntry entry = zip.getEntry("manifest.json");
            if (entry == null
                    || entry.isDirectory()
                    || entry.getSize() < 0L
                    || entry.getSize() > SaveArchiveLimits.MAX_MANIFEST_BYTES) {
                throw new IOException("Phase 14 manifest is unavailable");
            }
            try (InputStream input = zip.getInputStream(entry)) {
                bytes = readBounded(input, SaveArchiveLimits.MAX_MANIFEST_BYTES);
            }
        }
        try {
            return new SaveManifestCodec().decode(bytes);
        } catch (RuntimeException invalid) {
            throw new IOException("Phase 14 manifest is invalid", invalid);
        }
    }

    private static byte[] readBounded(InputStream input, long maximum)
            throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(
                (int) Math.min(maximum, 8192L));
        byte[] buffer = new byte[8192];
        long count = 0L;
        for (int read; (read = input.read(buffer)) != -1; ) {
            count = Math.addExact(count, read);
            if (count > maximum) {
                throw new IOException("Bounded migration read exceeds its maximum");
            }
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private static String sha256File(Path path, SaveFileOperations files)
            throws IOException {
        byte[] bytes = files.readBounded(
                path,
                SaveArchiveLimits.MAX_ARCHIVE_FILE_BYTES,
                () -> {
                    if (Files.isSymbolicLink(path)
                            || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                        throw new IOException("Migration archive is unavailable");
                    }
                });
        return sha256(bytes);
    }

    private static String sha256Managed(
            ReadSession session, SlotAnchor slot, SaveFileOperations files)
            throws IOException {
        byte[] bytes = files.readBounded(
                slot.path(),
                slot.maximumBytes(),
                () -> requireManaged(session, slot, files));
        requireManaged(session, slot, files);
        return sha256(bytes);
    }

    private static String sha256(String value) {
        return sha256(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static void requireSameIndex(
            StreamedChunkIndex expected, StreamedChunkIndex actual)
            throws IOException {
        if (!expected.saveGameId().equals(actual.saveGameId())
                || !expected.entries().equals(actual.entries())) {
            throw new IOException("Migration index reread mismatch");
        }
    }

    private static MigrationPaths paths(Path root, SaveGameId id) {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path world = normalizedRoot.resolve(id.value()).normalize();
        if (!Objects.equals(world.getParent(), normalizedRoot)) {
            throw new IllegalArgumentException("Migration world path is not confined");
        }
        return new MigrationPaths(
                normalizedRoot,
                id,
                world,
                world.resolve(CURRENT_NAME),
                world.resolve(BACKUP_NAME),
                world.resolve(MARKER_A_NAME),
                world.resolve(MARKER_B_NAME),
                world.resolve(PUBLICATION_FLOOR_A_NAME),
                world.resolve(PUBLICATION_FLOOR_B_NAME));
    }

    private static void requireWorld(MigrationPaths paths) throws IOException {
        requireDirectory(paths.root());
        requireDirectory(paths.world());
        if (!Objects.equals(paths.world().getParent(), paths.root())
                || !Objects.equals(
                        paths.world().toRealPath().getParent(),
                        paths.root().toRealPath())) {
            throw new IOException("Migration world is outside its save root");
        }
    }

    private static void requireDirectory(Path directory) throws IOException {
        if (Files.isSymbolicLink(directory)
                || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Migration directory identity is unsafe");
        }
    }

    private static void requireDirectChild(Path parent, Path child)
            throws IOException {
        Path normalized = child.toAbsolutePath().normalize();
        if (!Objects.equals(normalized.getParent(), parent)
                || normalized.getFileName() == null) {
            throw new IOException("Migration path is not a direct child");
        }
    }

    private static void requireRegularDirectChild(Path parent, Path child)
            throws IOException {
        requireDirectChild(parent, child);
        if (Files.isSymbolicLink(child)
                || !Files.isRegularFile(child, LinkOption.NOFOLLOW_LINKS)
                || !Objects.equals(child.toRealPath().getParent(), parent.toRealPath())) {
            throw new IOException("Migration file identity is unsafe");
        }
    }

    private static ReadSession openReadSession(
            MigrationPaths paths, SaveFileOperations files) throws IOException {
        requireWorld(paths);
        DirectoryAnchor root = captureDirectoryAnchor(paths.root(), null, files);
        DirectoryAnchor world = captureDirectoryAnchor(paths.world(), root, files);
        return new ReadSession(paths, root, world);
    }

    private static DirectoryAnchor captureDirectoryAnchor(
            Path path,
            DirectoryAnchor expectedParent,
            SaveFileOperations files) throws IOException {
        Path lexical = path.toAbsolutePath().normalize();
        requireDirectory(lexical);
        Path real = lexical.toRealPath();
        if (expectedParent != null
                && (!Objects.equals(lexical.getParent(), expectedParent.lexical())
                        || !Objects.equals(real.getParent(), expectedParent.real()))) {
            throw new IOException("Migration directory is outside its anchored parent");
        }
        Object key = files.readDirectoryKey(lexical, () -> requireDirectory(lexical));
        if (key == null) {
            throw new IOException("Migration directory provider identity is unavailable");
        }
        return new DirectoryAnchor(lexical, real, key);
    }

    private static void requireReadSession(
            ReadSession session, SaveFileOperations files) throws IOException {
        requireDirectoryAnchor(session.root(), null, files);
        requireDirectoryAnchor(session.world(), session.root(), files);
    }

    private static void requireDirectoryAnchor(
            DirectoryAnchor anchor,
            DirectoryAnchor expectedParent,
            SaveFileOperations files) throws IOException {
        requireDirectory(anchor.lexical());
        if (!Objects.equals(anchor.real(), anchor.lexical().toRealPath())
                || (expectedParent != null
                        && (!Objects.equals(
                                        anchor.lexical().getParent(),
                                        expectedParent.lexical())
                                || !Objects.equals(
                                        anchor.real().getParent(),
                                        expectedParent.real())))) {
            throw new IOException("Migration directory identity changed");
        }
        Object actual = files.readDirectoryKey(
                anchor.lexical(), () -> requireDirectory(anchor.lexical()));
        if (actual == null || !anchor.providerIdentity().equals(actual)) {
            throw new IOException("Migration directory provider identity changed");
        }
    }

    private static SlotAnchor captureSlot(
            ReadSession session,
            Path path,
            long maximumBytes,
            SaveFileOperations files) throws IOException {
        requireReadSession(session, files);
        requireRegularDirectChild(session.paths().world(), path);
        Object identity = files.readManagedFileIdentity(
                path,
                maximumBytes,
                () -> {
                    requireReadSession(session, files);
                    requireRegularDirectChild(session.paths().world(), path);
                });
        return new SlotAnchor(path, maximumBytes, identity);
    }

    private static void requireManaged(
            ReadSession session, SlotAnchor slot, SaveFileOperations files)
            throws IOException {
        requireReadSession(session, files);
        requireRegularDirectChild(session.paths().world(), slot.path());
        Object actual = files.readManagedFileIdentity(
                slot.path(),
                slot.maximumBytes(),
                () -> {
                    requireReadSession(session, files);
                    requireRegularDirectChild(
                            session.paths().world(), slot.path());
                });
        if (!Objects.equals(slot.identity(), actual)) {
            throw new IOException("Migration manifest slot identity changed");
        }
    }

    private static void requireTemporary(MigrationPaths paths, Path temporary)
            throws IOException {
        requireWorld(paths);
        requireRegularDirectChild(paths.world(), temporary);
        if (!temporary.getFileName().toString().endsWith(".tmp")) {
            throw new IOException("Migration temporary path is not owned");
        }
    }

    private static void requireReplaceableBackup(MigrationPaths paths)
            throws IOException {
        requireWorld(paths);
        requireDirectChild(paths.world(), paths.backup());
        if (Files.exists(paths.backup(), LinkOption.NOFOLLOW_LINKS)) {
            requireRegularDirectChild(paths.world(), paths.backup());
        }
    }

    private static Throwable diagnosticCause(SaveArchiveReadResult result) {
        return diagnosticCause(result.diagnostics());
    }

    private static Throwable diagnosticCause(List<SaveDiagnostic> diagnostics) {
        if (diagnostics.isEmpty()) {
            return new MigrationValidationFailure();
        }
        return diagnostics.get(0).cause().orElseGet(MigrationValidationFailure::new);
    }

    private static Phase14MigrationResult failedResult(
            boolean blocking,
            String code,
            String message,
            Throwable cause) {
        return Phase14MigrationResult.failed(
                blocking
                        ? Phase14MigrationResult.Status.BLOCKING_FAILURE
                        : Phase14MigrationResult.Status.FAILED,
                SaveDiagnostic.of(code, message, cause));
    }

    private static MigrationFailure failure(
            boolean blocking, String code, String message) {
        return failure(blocking, code, message, new MigrationValidationFailure());
    }

    private static MigrationFailure failure(
            boolean blocking, String code, String message, Throwable cause) {
        return new MigrationFailure(blocking, code, message, cause);
    }

    private record MigrationPaths(
            Path root,
            SaveGameId id,
            Path world,
            Path current,
            Path backup,
            Path markerA,
            Path markerB,
            Path publicationFloorA,
            Path publicationFloorB) {}

    private record DirectoryAnchor(Path lexical, Path real, Object providerIdentity) {}

    private record ReadSession(
            MigrationPaths paths, DirectoryAnchor root, DirectoryAnchor world) {}

    private record MigrationSession(
            ReadSession directories,
            SlotAnchor markerA,
            SlotAnchor markerB,
            SlotAnchor publicationFloorA,
            SlotAnchor publicationFloorB,
            PublicationFloor initializingFloor) {}

    private static final class SlotAnchor {
        private final Path path;
        private final long maximumBytes;
        private Object identity;

        private SlotAnchor(Path path, long maximumBytes, Object identity) {
            this.path = Objects.requireNonNull(path, "path");
            this.maximumBytes = maximumBytes;
            this.identity = Objects.requireNonNull(identity, "identity");
        }

        private Path path() {
            return path;
        }

        private long maximumBytes() {
            return maximumBytes;
        }

        private Object identity() {
            return identity;
        }

        private void updateIdentity(Object next) throws IOException {
            Object checked = Objects.requireNonNull(next, "next");
            if (identity instanceof SaveFileOperations.ManagedFileIdentity before
                    && checked instanceof SaveFileOperations.ManagedFileIdentity after
                    && !before.providerIdentity().equals(after.providerIdentity())) {
                throw new IOException("Migration manifest slot was replaced during write");
            }
            identity = checked;
        }
    }

    private enum PublicationFloorState {
        INITIALIZING,
        PUBLISHED
    }

    private record PublicationFloor(
            PublicationFloorState state,
            SaveGameId saveGameId,
            String sourceArchiveSha256,
            String nonce,
            String markerSha256) {
        private PublicationFloor {
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(saveGameId, "saveGameId");
            if (sourceArchiveSha256 == null
                    || !sourceArchiveSha256.matches("[0-9a-f]{64}")
                    || nonce == null
                    || !nonce.matches("[0-9a-f]{32}")) {
                throw new IllegalArgumentException("Publication floor identity is invalid");
            }
            if ((state == PublicationFloorState.INITIALIZING && markerSha256 != null)
                    || (state == PublicationFloorState.PUBLISHED
                            && (markerSha256 == null
                                    || !markerSha256.matches("[0-9a-f]{64}")))) {
                throw new IllegalArgumentException("Publication floor state is invalid");
            }
        }

        private static PublicationFloor initializing(
                SaveGameId id, String sourceHash, String nonce) {
            return new PublicationFloor(
                    PublicationFloorState.INITIALIZING,
                    id,
                    sourceHash,
                    nonce,
                    null);
        }

        private static PublicationFloor published(
                SaveGameId id,
                String sourceHash,
                String nonce,
                String markerHash) {
            return new PublicationFloor(
                    PublicationFloorState.PUBLISHED,
                    id,
                    sourceHash,
                    nonce,
                    markerHash);
        }
    }

    private record ObservedMarker(
            boolean present, boolean empty, boolean valid, byte[] bytes, Marker marker) {
        private static ObservedMarker missing() {
            return new ObservedMarker(false, false, false, new byte[0], null);
        }

        private static ObservedMarker empty(byte[] bytes) {
            return new ObservedMarker(true, true, false, bytes.clone(), null);
        }

        private static ObservedMarker valid(byte[] bytes, Marker marker) {
            return new ObservedMarker(true, false, true, bytes.clone(), marker);
        }

        private static ObservedMarker invalid(byte[] bytes) {
            return new ObservedMarker(true, false, false, bytes.clone(), null);
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }
    }

    private record ObservedFloor(
            boolean present,
            boolean empty,
            boolean valid,
            byte[] bytes,
            PublicationFloor floor) {
        private static ObservedFloor missing() {
            return new ObservedFloor(false, false, false, new byte[0], null);
        }

        private static ObservedFloor empty(byte[] bytes) {
            return new ObservedFloor(true, true, false, bytes.clone(), null);
        }

        private static ObservedFloor valid(byte[] bytes, PublicationFloor floor) {
            return new ObservedFloor(true, false, true, bytes.clone(), floor);
        }

        private static ObservedFloor invalid(byte[] bytes) {
            return new ObservedFloor(true, false, false, bytes.clone(), null);
        }

        private boolean nonEmpty() {
            return present && !empty;
        }

        private boolean matches(SaveGameId id, byte[] markerBytes) {
            return valid
                    && floor.state() == PublicationFloorState.PUBLISHED
                    && floor.saveGameId().equals(id)
                    && floor.markerSha256().equals(sha256(markerBytes));
        }

        private boolean initializing() {
            return valid && floor.state() == PublicationFloorState.INITIALIZING;
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }
    }

    private record Marker(
            Phase14MigrationResult.ValidatedV2Manifest manifest,
            StreamedChunkIndex index) {}

    private static final class MigrationFailure extends RuntimeException {
        private final boolean blocking;
        private final String code;

        private MigrationFailure(
                boolean blocking, String code, String message, Throwable cause) {
            super(message, Objects.requireNonNull(cause, "cause"));
            this.blocking = blocking;
            this.code = Objects.requireNonNull(code, "code");
        }
    }

    private static final class MigrationValidationFailure extends RuntimeException {
        private MigrationValidationFailure() {
            super("Migration validation failed", null, false, false);
        }
    }
}

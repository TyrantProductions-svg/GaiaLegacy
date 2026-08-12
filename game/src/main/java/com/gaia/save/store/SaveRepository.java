package com.gaia.save.store;

import com.gaia.save.archive.SaveArchiveLimits;
import com.gaia.save.archive.SaveArchiveReadResult;
import com.gaia.save.archive.SaveArchiveReader;
import com.gaia.save.archive.SaveDiagnostic;
import com.gaia.save.archive.SaveManifestCodec;
import com.gaia.save.format.SaveFormatVersion;
import com.gaia.save.format.SaveGameId;
import com.gaia.save.format.SaveGameManifest;
import com.gaia.save.snapshot.SaveGameSnapshot;
import com.gaia.shell.save.SaveSummary;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Root-confined local-save repository for catalog, recovery, and delete commands. */
public final class SaveRepository {
    private static final String CURRENT_NAME = "current.glsave";
    private static final String BACKUP_NAME = "backup.glsave";
    private static final String TRASH_NAME = ".trash";
    private static final int MAX_CATALOG_DIAGNOSTICS = 8;

    private final DirectoryIdentity saveRoot;
    private final SaveArchiveReader archiveReader;
    private final SaveManifestCodec manifestCodec;
    private final SaveFileOperations files;

    private SaveRepository(
            DirectoryIdentity saveRoot,
            SaveArchiveReader archiveReader,
            SaveFileOperations files) {
        this.saveRoot = saveRoot;
        this.archiveReader = archiveReader;
        this.files = files;
        manifestCodec = new SaveManifestCodec();
    }

    public static SaveRepository open(
            Path saveRoot,
            SaveArchiveReader archiveReader,
            SaveFileOperations files) {
        Objects.requireNonNull(saveRoot, "saveRoot");
        Objects.requireNonNull(archiveReader, "archiveReader");
        Objects.requireNonNull(files, "files");
        try {
            Path normalized = saveRoot.toAbsolutePath().normalize();
            Files.createDirectories(normalized);
            return new SaveRepository(
                    captureDirectory(normalized, null), archiveReader, files);
        } catch (IOException failure) {
            throw new IllegalArgumentException(
                    "The configured save root is unavailable", failure);
        }
    }

    List<SaveSummary> summaries() {
        try {
            requireDirectory(saveRoot);
            List<SaveSummary> summaries = new ArrayList<>();
            try (Stream<Path> children = Files.list(saveRoot.lexical())) {
                children.forEach(child -> scanDirectWorld(child).ifPresent(summaries::add));
            }
            summaries.sort(Comparator.comparing(SaveSummary::modifiedTime)
                    .reversed()
                    .thenComparing(summary -> summary.id().value()));
            return List.copyOf(summaries);
        } catch (IOException | RuntimeException failure) {
            return List.of();
        }
    }

    public SaveArchiveReadResult load(SaveGameId id) {
        Objects.requireNonNull(id, "id");
        final WorldIdentity world;
        try {
            world = existingWorld(id);
        } catch (IOException failure) {
            return SaveArchiveReadResult.corrupt(diagnostic(
                    "save-load.unsafe-target",
                    "The selected save path is unsafe",
                    failure));
        }
        if (world == null) {
            return SaveArchiveReadResult.corrupt(diagnostic(
                    "save-load.not-found",
                    "The selected save does not exist"));
        }
        ArchiveObservation current = observeArchive(world, world.current());
        return switch (current.status()) {
            case VALID -> SaveArchiveReadResult.valid(
                    current.snapshot().orElseThrow(), current.diagnostics());
            case UNSUPPORTED -> SaveArchiveReadResult.unsupported(
                    firstDiagnostic(
                            current.diagnostics(),
                            "save-load.unsupported-version",
                            "The selected save uses an unsupported version"));
            case MISSING, CORRUPT -> SaveArchiveReadResult.corrupt(
                    firstDiagnostic(
                            current.diagnostics(),
                            current.status() == ArchiveStatus.MISSING
                                    ? "save-load.current-missing"
                                    : "save-load.corrupt",
                            current.status() == ArchiveStatus.MISSING
                                    ? "The current save archive is missing"
                                    : "The current save archive is corrupt"));
        };
    }

    public SaveRecoveryResult recoverBackup(SaveGameId id) {
        Objects.requireNonNull(id, "id");
        WorldIdentity world;
        try {
            world = existingWorld(id);
            if (world == null) {
                return recoveryFailure(
                        SaveRecoveryResult.Status.NOT_FOUND,
                        "save-recovery.not-found",
                        "The selected save does not exist");
            }
        } catch (IOException failure) {
            return recoveryFailure(
                    SaveRecoveryResult.Status.FAILURE,
                    "save-recovery.unsafe-target",
                    "The selected save path is unsafe",
                    failure);
        }

        ArchiveObservation current = observeArchive(world, world.current());
        ArchiveObservation backup = observeArchive(world, world.backup());
        if (current.status() == ArchiveStatus.VALID
                || backup.status() != ArchiveStatus.VALID) {
            return recoveryFailure(
                    SaveRecoveryResult.Status.NOT_RECOVERABLE,
                    "save-recovery.not-recoverable",
                    "The selected save has no recoverable backup");
        }

        Path temporary = null;
        boolean temporaryOwned = false;
        Throwable primary = null;
        try {
            requireWorld(world);
            temporary = files.createSiblingTemp(
                    world.directory().lexical(),
                    "recovery.glsave",
                    () -> requireWorld(world));
            temporary = temporary.toAbsolutePath().normalize();
            requireOwnedTemp(world, temporary);
            temporaryOwned = true;

            Path recoveryTemp = temporary;
            files.copyReplacing(
                    world.backup(),
                    recoveryTemp,
                    () -> {
                        requireExpectedArchiveId(world, world.backup());
                        requireOwnedTemp(world, recoveryTemp);
                    });
            files.forceFile(recoveryTemp, () -> requireOwnedTemp(world, recoveryTemp));
            requireValidExactCopy(world, world.backup(), recoveryTemp);

            try {
                files.moveAtomicReplacing(
                        recoveryTemp,
                        world.current(),
                        () -> {
                            requireExpectedArchiveId(world, recoveryTemp);
                            requireExpectedArchiveId(world, world.backup());
                            requireSlot(world, world.current(), false);
                        });
            } catch (AtomicMoveNotSupportedException unsupported) {
                files.moveReplacing(
                        recoveryTemp,
                        world.current(),
                        () -> {
                            requireExpectedArchiveId(world, recoveryTemp);
                            requireExpectedArchiveId(world, world.backup());
                            requireSlot(world, world.current(), false);
                        });
            }
            temporaryOwned = false;
            requireValidExactCopy(world, world.backup(), world.current());
            files.forceDirectoryBestEffort(
                    world.directory().lexical(), () -> requireWorld(world));
            requireValidExactCopy(world, world.backup(), world.current());
            return SaveRecoveryResult.success();
        } catch (IOException | RuntimeException failure) {
            primary = failure;
            return recoveryFailure(
                    SaveRecoveryResult.Status.FAILURE,
                    "save-recovery.failed",
                    "The backup could not be recovered",
                    failure);
        } finally {
            if (temporaryOwned && temporary != null) {
                try {
                    Path owned = temporary;
                    files.deleteIfExists(owned, () -> requireOwnedTemp(world, owned));
                } catch (IOException | RuntimeException cleanupFailure) {
                    if (primary != null && cleanupFailure != primary) {
                        primary.addSuppressed(cleanupFailure);
                    }
                }
            }
        }
    }

    public SaveDeleteResult delete(SaveGameId id) {
        Objects.requireNonNull(id, "id");
        WorldIdentity world;
        try {
            world = existingWorld(id);
            if (world == null) {
                return deleteFailure(
                        SaveDeleteResult.Status.NOT_FOUND,
                        "save-delete.not-found",
                        "The selected save does not exist");
            }
            requireDeleteTreeShape(world);
        } catch (IOException failure) {
            return deleteFailure(
                    SaveDeleteResult.Status.UNSAFE_TARGET,
                    "save-delete.unsafe-target",
                    "The selected save path is unsafe",
                    failure);
        }

        DirectoryIdentity trash;
        Path destination;
        try {
            trash = openTrashDirectory();
            destination = trash.lexical().resolve(
                    id.value() + "." + UUID.randomUUID()).normalize();
            if (!Objects.equals(destination.getParent(), trash.lexical())) {
                throw new UnsafePathException();
            }
            try {
                files.moveAtomicReplacing(
                        world.directory().lexical(),
                        destination,
                        () -> requireDeleteMove(world, trash, destination));
            } catch (AtomicMoveNotSupportedException unsupported) {
                files.moveReplacing(
                        world.directory().lexical(),
                        destination,
                        () -> requireDeleteMove(world, trash, destination));
            }
        } catch (IOException | RuntimeException failure) {
            return deleteFailure(
                    SaveDeleteResult.Status.FAILURE,
                    "save-delete.move-failed",
                    "The save could not be moved to local trash",
                    failure);
        }

        try {
            TrashEntry moved = captureTrashEntry(trash, destination);
            cleanupTrashEntry(moved);
            return SaveDeleteResult.success();
        } catch (IOException | RuntimeException cleanupFailure) {
            return deleteFailure(
                    SaveDeleteResult.Status.DELETED_WITH_CLEANUP_WARNING,
                    "save-delete.cleanup-failed",
                    "The save was removed but local trash cleanup failed",
                    cleanupFailure);
        }
    }

    private Optional<SaveSummary> scanDirectWorld(Path candidate) {
        String name = candidate.getFileName().toString();
        SaveGameId id;
        try {
            id = SaveGameId.parse(name);
        } catch (IllegalArgumentException ignored) {
            return Optional.empty();
        }
        WorldIdentity world;
        try {
            world = existingWorld(id);
            if (world == null) {
                return Optional.empty();
            }
        } catch (IOException unsafe) {
            return Optional.empty();
        }
        if (!Files.exists(world.current(), LinkOption.NOFOLLOW_LINKS)
                && !Files.exists(world.backup(), LinkOption.NOFOLLOW_LINKS)) {
            return Optional.empty();
        }

        ArchiveObservation current = observeArchive(world, world.current());
        ArchiveObservation backup = observeArchive(world, world.backup());
        if (current.status() == ArchiveStatus.VALID) {
            return Optional.of(summaryFromValid(
                    id, current, SaveSummary.Health.VALID, current.diagnostics()));
        }
        if (backup.status() == ArchiveStatus.VALID) {
            return Optional.of(summaryFromValid(
                    id,
                    backup,
                    SaveSummary.Health.RECOVERABLE_BACKUP,
                    current.exists()
                            ? current.diagnostics()
                            : List.of(diagnostic(
                                    "save-catalog.current-missing",
                                    "The current save archive is missing"))));
        }
        ArchiveObservation unsupported = current.status() == ArchiveStatus.UNSUPPORTED
                ? current
                : backup.status() == ArchiveStatus.UNSUPPORTED ? backup : null;
        if (unsupported != null) {
            return Optional.of(new SaveSummary(
                    id,
                    id.value(),
                    Optional.empty(),
                    unsupported.modifiedTime(),
                    Optional.empty(),
                    unsupported.formatVersion(),
                    SaveSummary.Health.UNSUPPORTED_VERSION,
                    boundedDiagnostics(unsupported.diagnostics())));
        }
        List<SaveDiagnostic> diagnostics = new ArrayList<>();
        diagnostics.addAll(current.diagnostics());
        diagnostics.addAll(backup.diagnostics());
        if (diagnostics.isEmpty()) {
            diagnostics.add(diagnostic(
                    "save-catalog.corrupt", "The save has no readable archive"));
        }
        return Optional.of(new SaveSummary(
                id,
                id.value(),
                Optional.empty(),
                Instant.EPOCH,
                Optional.empty(),
                Optional.empty(),
                SaveSummary.Health.CORRUPT,
                boundedDiagnostics(diagnostics)));
    }

    private SaveSummary summaryFromValid(
            SaveGameId expectedId,
            ArchiveObservation observation,
            SaveSummary.Health health,
            List<SaveDiagnostic> diagnostics) {
        SaveGameManifest manifest = observation.manifest().orElseThrow();
        SaveGameSnapshot snapshot = observation.snapshot().orElseThrow();
        if (!manifest.saveGameId().equals(expectedId)
                || !snapshot.metadata().saveGameId().equals(expectedId)) {
            return new SaveSummary(
                    expectedId,
                    expectedId.value(),
                    Optional.empty(),
                    Instant.EPOCH,
                    Optional.empty(),
                    Optional.empty(),
                    SaveSummary.Health.CORRUPT,
                    List.of(diagnostic(
                            "save-catalog.identity-mismatch",
                            "The save archive identity does not match its directory")));
        }
        return new SaveSummary(
                expectedId,
                manifest.displayName(),
                Optional.of(manifest.createdAt()),
                manifest.modifiedAt(),
                Optional.of(manifest.worldSeed()),
                Optional.of(manifest.formatVersion()),
                health,
                boundedDiagnostics(diagnostics));
    }

    private ArchiveObservation observeArchive(WorldIdentity world, Path archive) {
        if (!Files.exists(archive, LinkOption.NOFOLLOW_LINKS)) {
            return ArchiveObservation.missing();
        }
        try {
            requireSlot(world, archive, true);
            SaveArchiveReadResult result = archiveReader.read(archive);
            if (result.status() == SaveArchiveReadResult.Status.VALID) {
                SaveGameManifest manifest = manifestCodec.decode(readManifestBytes(archive));
                SaveGameSnapshot snapshot = result.snapshot().orElseThrow();
                if (!manifest.saveGameId().equals(world.id())
                        || !snapshot.metadata().saveGameId().equals(world.id())) {
                    return ArchiveObservation.corrupt(List.of(diagnostic(
                            "save-catalog.identity-mismatch",
                            "The save archive identity does not match its directory")));
                }
                return ArchiveObservation.valid(
                        snapshot, manifest, result.diagnostics());
            }
            if (result.status() == SaveArchiveReadResult.Status.UNSUPPORTED_VERSION) {
                byte[] manifest = readManifestBytes(archive);
                int version = manifestCodec.formatVersion(manifest);
                return ArchiveObservation.unsupported(
                        new SaveFormatVersion(version),
                        futureModifiedTime(manifest),
                        result.diagnostics());
            }
            return ArchiveObservation.corrupt(result.diagnostics());
        } catch (IOException | RuntimeException failure) {
            return ArchiveObservation.corrupt(List.of(diagnostic(
                    "save-catalog.archive-unreadable",
                    "A save archive could not be validated",
                    failure)));
        }
    }

    private void requireValidExactCopy(
            WorldIdentity world, Path expected, Path actual) throws IOException {
        requireExpectedArchiveId(world, expected);
        requireExpectedArchiveId(world, actual);
        if (Files.mismatch(expected, actual) != -1L) {
            throw new IOException("Recovered archive validation failed");
        }
    }

    private void requireExpectedArchiveId(WorldIdentity world, Path archive)
            throws IOException {
        if (archive.equals(world.current()) || archive.equals(world.backup())) {
            requireSlot(world, archive, true);
        } else {
            requireOwnedTemp(world, archive);
        }
        SaveArchiveReadResult result = archiveReader.read(archive);
        if (result.status() != SaveArchiveReadResult.Status.VALID) {
            throw new IOException("Recovery archive validation failed");
        }
        SaveGameSnapshot snapshot = result.snapshot().orElseThrow();
        SaveGameManifest manifest;
        try {
            manifest = manifestCodec.decode(readManifestBytes(archive));
        } catch (RuntimeException failure) {
            throw new IOException("Recovery archive manifest validation failed", failure);
        }
        if (!snapshot.metadata().saveGameId().equals(world.id())
                || !manifest.saveGameId().equals(world.id())) {
            throw new IOException("Recovery archive identity does not match its directory");
        }
    }

    private WorldIdentity existingWorld(SaveGameId id) throws IOException {
        requireDirectory(saveRoot);
        Path world = saveRoot.lexical().resolve(id.value()).normalize();
        if (!Objects.equals(world.getParent(), saveRoot.lexical())) {
            throw new UnsafePathException();
        }
        if (!Files.exists(world, LinkOption.NOFOLLOW_LINKS)) {
            return null;
        }
        DirectoryIdentity identity = captureDirectory(world, saveRoot);
        return new WorldIdentity(
                id,
                identity,
                world.resolve(CURRENT_NAME),
                world.resolve(BACKUP_NAME));
    }

    private DirectoryIdentity openTrashDirectory() throws IOException {
        requireDirectory(saveRoot);
        Path trash = saveRoot.lexical().resolve(TRASH_NAME).normalize();
        if (!Objects.equals(trash.getParent(), saveRoot.lexical())) {
            throw new UnsafePathException();
        }
        if (!Files.exists(trash, LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectory(trash);
        }
        return captureDirectory(trash, saveRoot);
    }

    private void requireDeleteTreeShape(WorldIdentity world) throws IOException {
        requireWorld(world);
        try (Stream<Path> children = Files.list(world.directory().lexical())) {
            for (Path child : children.toList()) {
                String name = child.getFileName().toString();
                boolean allowedName = name.equals(CURRENT_NAME)
                        || name.equals(BACKUP_NAME)
                        || name.endsWith(".tmp");
                if (!allowedName
                        || Files.isSymbolicLink(child)
                        || !Files.isRegularFile(child, LinkOption.NOFOLLOW_LINKS)
                        || !Objects.equals(child.toRealPath().getParent(),
                                world.directory().real())) {
                    throw new UnsafePathException();
                }
            }
        }
    }

    private void requireDeleteMove(
            WorldIdentity world,
            DirectoryIdentity trash,
            Path destination) throws IOException {
        requireWorld(world);
        requireDeleteTreeShape(world);
        requireDirectory(trash);
        if (!Objects.equals(destination.getParent(), trash.lexical())
                || Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
            throw new UnsafePathException();
        }
    }

    private TrashEntry captureTrashEntry(
            DirectoryIdentity trash, Path destination) throws IOException {
        requireDirectory(trash);
        return new TrashEntry(trash, captureDirectory(destination, trash));
    }

    private void cleanupTrashEntry(TrashEntry moved) throws IOException {
        requireDirectory(moved.trash());
        requireDirectory(moved.entry());
        List<Path> children;
        try (Stream<Path> stream = Files.list(moved.entry().lexical())) {
            children = stream.toList();
        }
        for (Path child : children) {
            requireTrashChild(moved, child);
            files.deleteIfExists(child, () -> requireTrashChild(moved, child));
        }
        files.deleteIfExists(moved.entry().lexical(), () -> {
            requireDirectory(moved.trash());
            requireDirectory(moved.entry());
            try (Stream<Path> stream = Files.list(moved.entry().lexical())) {
                if (stream.findAny().isPresent()) {
                    throw new UnsafePathException();
                }
            }
        });
    }

    private void requireTrashChild(TrashEntry moved, Path child) throws IOException {
        requireDirectory(moved.trash());
        requireDirectory(moved.entry());
        Path normalized = child.toAbsolutePath().normalize();
        if (!Objects.equals(normalized.getParent(), moved.entry().lexical())
                || Files.isSymbolicLink(normalized)
                || !Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS)
                || !Objects.equals(normalized.toRealPath().getParent(), moved.entry().real())) {
            throw new UnsafePathException();
        }
    }

    private void requireWorld(WorldIdentity world) throws IOException {
        requireDirectory(saveRoot);
        requireDirectory(world.directory());
        if (!Objects.equals(world.directory().lexical().getParent(), saveRoot.lexical())
                || !Objects.equals(world.directory().real().getParent(), saveRoot.real())) {
            throw new UnsafePathException();
        }
    }

    private void requireSlot(WorldIdentity world, Path slot, boolean mustExist)
            throws IOException {
        requireWorld(world);
        Path normalized = slot.toAbsolutePath().normalize();
        if (!Objects.equals(normalized.getParent(), world.directory().lexical())
                || (!normalized.equals(world.current()) && !normalized.equals(world.backup()))) {
            throw new UnsafePathException();
        }
        if (!Files.exists(normalized, LinkOption.NOFOLLOW_LINKS)) {
            if (mustExist) {
                throw new UnsafePathException();
            }
            return;
        }
        if (Files.isSymbolicLink(normalized)
                || !Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS)
                || !Objects.equals(normalized.toRealPath().getParent(), world.directory().real())) {
            throw new UnsafePathException();
        }
    }

    private void requireOwnedTemp(WorldIdentity world, Path temporary) throws IOException {
        requireWorld(world);
        Path normalized = temporary.toAbsolutePath().normalize();
        if (!Objects.equals(normalized.getParent(), world.directory().lexical())
                || !normalized.getFileName().toString().endsWith(".tmp")
                || Files.isSymbolicLink(normalized)
                || !Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS)
                || !Objects.equals(normalized.toRealPath().getParent(), world.directory().real())) {
            throw new UnsafePathException();
        }
    }

    private static DirectoryIdentity captureDirectory(
            Path directory, DirectoryIdentity expectedParent) throws IOException {
        Path lexical = directory.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(lexical)
                || !Files.isDirectory(lexical, LinkOption.NOFOLLOW_LINKS)) {
            throw new UnsafePathException();
        }
        Path real = lexical.toRealPath();
        if (expectedParent != null
                && (!Objects.equals(lexical.getParent(), expectedParent.lexical())
                        || !Objects.equals(real.getParent(), expectedParent.real()))) {
            throw new UnsafePathException();
        }
        BasicFileAttributes attributes = Files.readAttributes(
                lexical, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        return new DirectoryIdentity(lexical, real, attributes.fileKey());
    }

    private static void requireDirectory(DirectoryIdentity expected) throws IOException {
        if (Files.isSymbolicLink(expected.lexical())
                || !Files.isDirectory(expected.lexical(), LinkOption.NOFOLLOW_LINKS)
                || !Objects.equals(expected.real(), expected.lexical().toRealPath())) {
            throw new UnsafePathException();
        }
        Object actualKey = Files.readAttributes(
                expected.lexical(), BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS)
                .fileKey();
        if (expected.fileKey() != null
                && actualKey != null
                && !expected.fileKey().equals(actualKey)) {
            throw new UnsafePathException();
        }
    }

    private byte[] readManifestBytes(Path archive) throws IOException {
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            ZipEntry entry = zip.getEntry("manifest.json");
            if (entry == null
                    || entry.isDirectory()
                    || entry.getSize() < 0
                    || entry.getSize() > SaveArchiveLimits.MAX_MANIFEST_BYTES) {
                throw new IOException("Validated archive manifest is unavailable");
            }
            try (InputStream input = zip.getInputStream(entry)) {
                ByteArrayOutputStream output = new ByteArrayOutputStream(
                        (int) Math.min(entry.getSize(), 8192));
                byte[] buffer = new byte[8192];
                long count = 0;
                for (int read; (read = input.read(buffer)) != -1; ) {
                    count = Math.addExact(count, read);
                    if (count > SaveArchiveLimits.MAX_MANIFEST_BYTES) {
                        throw new IOException("Validated archive manifest exceeds its bound");
                    }
                    output.write(buffer, 0, read);
                }
                return output.toByteArray();
            }
        } catch (ArithmeticException failure) {
            throw new IOException("Validated archive manifest exceeds its bound", failure);
        }
    }

    private static Instant futureModifiedTime(byte[] manifest) {
        try {
            JsonObject object = JsonParser.parseString(
                    new String(manifest, java.nio.charset.StandardCharsets.UTF_8))
                    .getAsJsonObject();
            return Instant.parse(object.get("modifiedAt").getAsString());
        } catch (RuntimeException failure) {
            return Instant.EPOCH;
        }
    }

    private static List<SaveDiagnostic> boundedDiagnostics(
            List<SaveDiagnostic> diagnostics) {
        if (diagnostics.size() <= MAX_CATALOG_DIAGNOSTICS) {
            return List.copyOf(diagnostics);
        }
        return List.copyOf(diagnostics.subList(0, MAX_CATALOG_DIAGNOSTICS));
    }

    private static SaveDiagnostic firstDiagnostic(
            List<SaveDiagnostic> diagnostics, String code, String message) {
        return diagnostics.isEmpty()
                ? diagnostic(code, message)
                : diagnostics.get(0);
    }

    private static SaveRecoveryResult recoveryFailure(
            SaveRecoveryResult.Status status, String code, String message) {
        return SaveRecoveryResult.failed(status, diagnostic(code, message));
    }

    private static SaveRecoveryResult recoveryFailure(
            SaveRecoveryResult.Status status,
            String code,
            String message,
            Throwable cause) {
        return SaveRecoveryResult.failed(status, diagnostic(code, message, cause));
    }

    private static SaveDeleteResult deleteFailure(
            SaveDeleteResult.Status status, String code, String message) {
        return SaveDeleteResult.failed(status, diagnostic(code, message));
    }

    private static SaveDeleteResult deleteFailure(
            SaveDeleteResult.Status status,
            String code,
            String message,
            Throwable cause) {
        return SaveDeleteResult.failed(status, diagnostic(code, message, cause));
    }

    private static SaveDiagnostic diagnostic(String code, String message) {
        return SaveDiagnostic.of(code, message);
    }

    private static SaveDiagnostic diagnostic(
            String code, String message, Throwable cause) {
        return SaveDiagnostic.of(code, message, cause);
    }

    private enum ArchiveStatus {
        MISSING,
        VALID,
        CORRUPT,
        UNSUPPORTED
    }

    private record ArchiveObservation(
            boolean exists,
            ArchiveStatus status,
            Optional<SaveGameSnapshot> snapshot,
            Optional<SaveGameManifest> manifest,
            Optional<SaveFormatVersion> formatVersion,
            Instant modifiedTime,
            List<SaveDiagnostic> diagnostics) {
        private ArchiveObservation {
            snapshot = Objects.requireNonNull(snapshot, "snapshot");
            manifest = Objects.requireNonNull(manifest, "manifest");
            formatVersion = Objects.requireNonNull(formatVersion, "formatVersion");
            modifiedTime = Objects.requireNonNull(modifiedTime, "modifiedTime");
            diagnostics = List.copyOf(diagnostics);
        }

        private static ArchiveObservation missing() {
            return new ArchiveObservation(
                    false,
                    ArchiveStatus.MISSING,
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Instant.EPOCH,
                    List.of());
        }

        private static ArchiveObservation valid(
                SaveGameSnapshot snapshot,
                SaveGameManifest manifest,
                List<SaveDiagnostic> diagnostics) {
            return new ArchiveObservation(
                    true,
                    ArchiveStatus.VALID,
                    Optional.of(snapshot),
                    Optional.of(manifest),
                    Optional.of(manifest.formatVersion()),
                    manifest.modifiedAt(),
                    diagnostics);
        }

        private static ArchiveObservation corrupt(List<SaveDiagnostic> diagnostics) {
            return new ArchiveObservation(
                    true,
                    ArchiveStatus.CORRUPT,
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Instant.EPOCH,
                    diagnostics);
        }

        private static ArchiveObservation unsupported(
                SaveFormatVersion version,
                Instant modifiedTime,
                List<SaveDiagnostic> diagnostics) {
            return new ArchiveObservation(
                    true,
                    ArchiveStatus.UNSUPPORTED,
                    Optional.empty(),
                    Optional.empty(),
                    Optional.of(version),
                    modifiedTime,
                    diagnostics);
        }
    }

    private record DirectoryIdentity(Path lexical, Path real, Object fileKey) {}

    private record WorldIdentity(
            SaveGameId id,
            DirectoryIdentity directory,
            Path current,
            Path backup) {}

    private record TrashEntry(
            DirectoryIdentity trash, DirectoryIdentity entry) {}

    private static final class UnsafePathException extends IOException {
        private UnsafePathException() {
            super("Save path identity validation failed");
        }
    }
}

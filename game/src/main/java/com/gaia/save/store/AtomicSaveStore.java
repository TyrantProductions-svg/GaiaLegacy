package com.gaia.save.store;

import com.gaia.save.archive.SaveArchiveReadResult;
import com.gaia.save.archive.SaveArchiveReader;
import com.gaia.save.archive.SaveArchiveWriter;
import com.gaia.save.archive.SaveDiagnostic;
import com.gaia.save.codec.EncodedSaveGame;
import com.gaia.save.codec.SaveSnapshotCodec;
import com.gaia.save.format.SaveGameId;
import com.gaia.save.snapshot.SaveGameSnapshot;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Owns the current/backup transaction for one world directory. */
public final class AtomicSaveStore {
    private static final String CURRENT_NAME = "current.glsave";
    private static final String BACKUP_NAME = "backup.glsave";
    private static final String TEMP_SUFFIX = ".tmp";

    private final Path configuredSaveRoot;
    private final DirectoryIdentity configuredSaveRootIdentity;
    private final SaveGameId configuredSaveGameId;
    private final Path worldDirectory;
    private final DirectoryIdentity worldDirectoryIdentity;
    private final Path currentArchive;
    private final Path backupArchive;
    private final SaveSnapshotCodec snapshotCodec;
    private final SaveArchiveWriter archiveWriter;
    private final SaveArchiveReader archiveReader;
    private final SaveFileOperations files;
    private boolean configuredRootForcePending;

    public AtomicSaveStore(
            Path saveRoot,
            SaveGameId saveGameId,
            SaveSnapshotCodec snapshotCodec,
            SaveArchiveWriter archiveWriter,
            SaveArchiveReader archiveReader,
            SaveFileOperations files) {
        this(
                resolveSafePaths(saveRoot, saveGameId),
                snapshotCodec,
                archiveWriter,
                archiveReader,
                files);
    }

    AtomicSaveStore(
            Path worldDirectory,
            SaveSnapshotCodec snapshotCodec,
            SaveArchiveWriter archiveWriter,
            SaveArchiveReader archiveReader,
            SaveFileOperations files) {
        this(
                resolveTestWorldPath(worldDirectory),
                snapshotCodec,
                archiveWriter,
                archiveReader,
                files);
    }

    private AtomicSaveStore(
            StorePaths paths,
            SaveSnapshotCodec snapshotCodec,
            SaveArchiveWriter archiveWriter,
            SaveArchiveReader archiveReader,
            SaveFileOperations files) {
        this.configuredSaveRoot = paths.saveRoot();
        this.configuredSaveRootIdentity = paths.saveRootIdentity();
        this.configuredSaveGameId = paths.saveGameId();
        this.worldDirectory = paths.worldDirectory();
        this.worldDirectoryIdentity = paths.worldDirectoryIdentity();
        this.currentArchive = this.worldDirectory.resolve(CURRENT_NAME);
        this.backupArchive = this.worldDirectory.resolve(BACKUP_NAME);
        this.snapshotCodec = Objects.requireNonNull(snapshotCodec, "snapshotCodec");
        this.archiveWriter = Objects.requireNonNull(archiveWriter, "archiveWriter");
        this.archiveReader = Objects.requireNonNull(archiveReader, "archiveReader");
        this.files = Objects.requireNonNull(files, "files");
        this.configuredRootForcePending = paths.worldDirectoryCreated();
    }

    public SaveWriteResult save(SaveGameSnapshot snapshot, Instant modifiedTime) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(modifiedTime, "modifiedTime");
        SaveGameId expectedSaveGameId = configuredSaveGameId == null
                ? snapshot.metadata().saveGameId()
                : configuredSaveGameId;
        if (!snapshot.metadata().saveGameId().equals(expectedSaveGameId)) {
            return failed(validationFailure(
                    "save-write.snapshot-identity-mismatch",
                    "The save snapshot identity does not match its target slot"));
        }
        try {
            requireAnchoredDirectories();
            forceConfiguredRootIfPending();
        } catch (StoreFailure boundaryFailure) {
            return failed(boundaryFailure);
        }

        EncodedSaveGame encoded;
        try {
            encoded = snapshotCodec.encode(snapshot, modifiedTime);
        } catch (RuntimeException failure) {
            return failed(new StoreFailure(
                    "save-write.section-encode-failed",
                    "The save snapshot could not be encoded",
                    failure));
        }

        TransactionOwnership ownership = new TransactionOwnership();
        StoreFailure failure;
        try {
            performTransaction(snapshot, encoded, expectedSaveGameId, ownership);
            return SaveWriteResult.success(encoded.manifest());
        } catch (StoreFailure transactionFailure) {
            failure = transactionFailure;
        }

        boolean cleanupFailed = cleanupOwnedTemps(ownership, failure);
        if (cleanupFailed && !hasKnownGoodArchive(expectedSaveGameId)) {
            failure.retainCleanupOwnershipUncertainty();
        }
        boolean blocking = failure.blocking();
        return blocking ? blocking(failure) : failed(failure);
    }

    private void performTransaction(
            SaveGameSnapshot intendedSnapshot,
            EncodedSaveGame encoded,
            SaveGameId expectedSaveGameId,
            TransactionOwnership ownership) {
        Path currentTemp = createOwnedTemp(
                CURRENT_NAME,
                "save-write.temp-create-failed",
                "A temporary save archive could not be created",
                ownership);
        try {
            requireAnchoredRegularTemp(currentTemp);
            archiveWriter.write(currentTemp, encoded);
        } catch (IOException | RuntimeException failure) {
            throw new StoreFailure(
                    "save-write.temp-write-failed",
                    "The temporary save archive could not be written",
                    failure);
        }
        forceFile(
                currentTemp,
                "save-write.temp-force-failed",
                "The temporary save archive could not be forced");
        requireValidForExpectedId(
                currentTemp,
                expectedSaveGameId,
                "save-write.temp-validation-failed",
                "The temporary save archive failed validation");
        ArchiveIdentity intendedIdentity = identity(
                currentTemp,
                "save-write.temp-validation-failed",
                "The temporary save archive identity could not be read");

        requireAnchoredSlot(currentArchive);
        if (Files.exists(currentArchive, LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.isRegularFile(currentArchive, LinkOption.NOFOLLOW_LINKS)) {
                throw validationFailure(
                        "save-write.current-validation-failed",
                        "The current save archive is not a direct regular file");
            }
            requireValidForExpectedId(
                    currentArchive,
                    expectedSaveGameId,
                    "save-write.current-validation-failed",
                    "The current save archive failed validation");
            installVerifiedBackupCandidate(expectedSaveGameId, ownership);
        }

        installCurrent(currentTemp, expectedSaveGameId, ownership);
        try {
            requireExpectedCurrent(intendedSnapshot, intendedIdentity);
            forceDirectory();
        } catch (StoreFailure postInstallFailure) {
            remediateInstalledCurrent(postInstallFailure);
            throw postInstallFailure;
        }
    }

    private void installVerifiedBackupCandidate(
            SaveGameId expectedSaveGameId,
            TransactionOwnership ownership) {
        Path candidate = createOwnedTemp(
                BACKUP_NAME,
                "save-write.backup-temp-create-failed",
                "A backup candidate could not be created",
                ownership);
        try {
            requireAnchoredSlot(currentArchive);
            requireAnchoredRegularTemp(candidate);
            files.copyReplacing(
                    currentArchive,
                    candidate,
                    () -> {
                        requireValidForExpectedId(
                                currentArchive,
                                expectedSaveGameId,
                                "save-write.current-validation-failed",
                                "The current save archive failed validation");
                        requireAnchoredRegularTemp(candidate);
                    });
        } catch (IOException | RuntimeException failure) {
            throw new StoreFailure(
                    "save-write.backup-copy-failed",
                    "The backup candidate could not be copied",
                    failure);
        }
        forceFile(
                candidate,
                "save-write.backup-force-failed",
                "The backup candidate could not be forced");
        requireValidForExpectedId(
                candidate,
                expectedSaveGameId,
                "save-write.backup-validation-failed",
                "The backup candidate failed validation");
        requireExactCopy(
                currentArchive,
                candidate,
                expectedSaveGameId,
                "The backup candidate did not preserve the current save");

        try {
            requireAnchoredRegularTemp(candidate);
            requireAnchoredSlot(backupArchive);
            files.moveAtomicReplacing(
                    candidate,
                    backupArchive,
                    () -> {
                        requireValidForExpectedId(
                                candidate,
                                expectedSaveGameId,
                                "save-write.backup-validation-failed",
                                "The backup candidate failed validation");
                        requireAnchoredSlot(backupArchive);
                    });
            ownership.transfer(candidate);
        } catch (AtomicMoveNotSupportedException unsupported) {
            try {
                requireAnchoredRegularTemp(candidate);
                requireAnchoredSlot(backupArchive);
                files.moveReplacing(
                        candidate,
                        backupArchive,
                        () -> {
                            requireValidForExpectedId(
                                    candidate,
                                    expectedSaveGameId,
                                    "save-write.backup-validation-failed",
                                    "The backup candidate failed validation");
                            requireAnchoredSlot(backupArchive);
                        });
                ownership.transfer(candidate);
            } catch (IOException | RuntimeException failure) {
                throw new StoreFailure(
                        "save-write.backup-replace-failed",
                        "The backup candidate could not be installed",
                        failure);
            }
        } catch (IOException | RuntimeException failure) {
            throw new StoreFailure(
                    "save-write.backup-move-failed",
                    "The backup candidate could not be installed atomically",
                    failure);
        }

        requireValidForExpectedId(
                backupArchive,
                expectedSaveGameId,
                "save-write.backup-validation-failed",
                "The installed backup archive failed validation");
        requireExactCopy(
                currentArchive,
                backupArchive,
                expectedSaveGameId,
                "The installed backup did not preserve the current save");
        forceDirectory();
    }

    private Path createOwnedTemp(
            String targetName,
            String failureCode,
            String failureMessage,
            TransactionOwnership ownership) {
        Path returned;
        try {
            returned = files.createSiblingTemp(
                    worldDirectory, targetName, this::requireAnchoredDirectories);
        } catch (IOException | RuntimeException failure) {
            throw new StoreFailure(failureCode, failureMessage, failure);
        }
        Path temporary = Objects.requireNonNull(returned, "created temporary path")
                .toAbsolutePath()
                .normalize();
        if (!temporary.getFileName().toString().endsWith(TEMP_SUFFIX)) {
            throw validationFailure(
                    "save-write.unsafe-temp-path",
                    "The filesystem returned an unsafe temporary save path");
        }
        requireAnchoredRegularTemp(temporary);
        ownership.own(temporary);
        return temporary;
    }

    private void forceFile(Path file, String code, String message) {
        try {
            requireAnchoredRegularTemp(file);
            files.forceFile(file, () -> requireAnchoredRegularTemp(file));
        } catch (IOException | RuntimeException failure) {
            throw new StoreFailure(code, message, failure);
        }
    }

    private void installCurrent(
            Path currentTemp,
            SaveGameId expectedSaveGameId,
            TransactionOwnership ownership) {
        try {
            requireAnchoredRegularTemp(currentTemp);
            requireAnchoredSlot(currentArchive);
            files.moveAtomicReplacing(
                    currentTemp,
                    currentArchive,
                    () -> {
                        requireValidForExpectedId(
                                currentTemp,
                                expectedSaveGameId,
                                "save-write.temp-validation-failed",
                                "The temporary save archive failed validation");
                        requireAnchoredSlot(currentArchive);
                    });
            ownership.transfer(currentTemp);
        } catch (AtomicMoveNotSupportedException unsupported) {
            try {
                requireAnchoredRegularTemp(currentTemp);
                requireAnchoredSlot(currentArchive);
                files.moveReplacing(
                        currentTemp,
                        currentArchive,
                        () -> {
                            requireValidForExpectedId(
                                    currentTemp,
                                    expectedSaveGameId,
                                    "save-write.temp-validation-failed",
                                    "The temporary save archive failed validation");
                            requireAnchoredSlot(currentArchive);
                        });
                ownership.transfer(currentTemp);
            } catch (IOException | RuntimeException failure) {
                throw new StoreFailure(
                        "save-write.current-replace-failed",
                        "The current save archive could not be replaced",
                        failure);
            }
        } catch (IOException | RuntimeException failure) {
            throw new StoreFailure(
                    "save-write.current-move-failed",
                    "The current save archive could not be installed",
                    failure);
        }
    }

    private void remediateInstalledCurrent(StoreFailure primaryFailure) {
        try {
            requireAnchoredSlot(currentArchive);
            files.deleteIfExists(
                    currentArchive, () -> requireAnchoredSlot(currentArchive));
            requireAnchoredDirectories();
        } catch (IOException | RuntimeException remediationFailure) {
            primaryFailure.retainRemediation(remediationFailure);
            return;
        }
        try {
            forceDirectory();
        } catch (StoreFailure remediationFailure) {
            Throwable suppressed = remediationFailure.getCause() == null
                    ? remediationFailure
                    : remediationFailure.getCause();
            primaryFailure.retainRemediation(suppressed);
        }
    }

    private void requireExpectedCurrent(
            SaveGameSnapshot intendedSnapshot, ArchiveIdentity intendedIdentity) {
        SaveArchiveReadResult result = read(
                currentArchive,
                "save-write.current-validation-failed",
                "The committed current archive failed validation");
        if (result.status() != SaveArchiveReadResult.Status.VALID) {
            throw validationFailure(
                    "save-write.current-validation-failed",
                    "The committed current archive failed validation",
                    diagnosticCause(result));
        }
        if (!result.snapshot().orElseThrow().equals(intendedSnapshot)) {
            throw validationFailure(
                    "save-write.current-manifest-mismatch",
                    "The committed current archive does not match the intended save");
        }
        ArchiveIdentity actualIdentity = identity(
                currentArchive,
                "save-write.current-validation-failed",
                "The committed current archive identity could not be read");
        if (!intendedIdentity.sameManifest(actualIdentity)
                || !intendedIdentity.sameArchive(actualIdentity)) {
            throw validationFailure(
                    "save-write.current-manifest-mismatch",
                    "The committed current archive does not match the intended save");
        }
    }

    private void requireValidForExpectedId(
            Path archive,
            SaveGameId expectedSaveGameId,
            String code,
            String message) {
        SaveArchiveReadResult result = read(archive, code, message);
        if (result.status() != SaveArchiveReadResult.Status.VALID) {
            throw validationFailure(code, message, diagnosticCause(result));
        }
        if (!result.snapshot().orElseThrow().metadata().saveGameId()
                .equals(expectedSaveGameId)) {
            throw validationFailure(code, message);
        }
    }

    private SaveArchiveReadResult read(Path archive, String code, String message) {
        try {
            requireAnchoredRegularArchive(archive);
            return archiveReader.read(archive);
        } catch (RuntimeException failure) {
            throw new StoreFailure(code, message, failure);
        }
    }

    private static Throwable diagnosticCause(SaveArchiveReadResult result) {
        return result.diagnostics().stream()
                .findFirst()
                .flatMap(SaveDiagnostic::cause)
                .orElse(null);
    }

    private void requireExactCopy(
            Path expected,
            Path actual,
            SaveGameId expectedSaveGameId,
            String message) {
        try {
            requireValidForExpectedId(
                    expected,
                    expectedSaveGameId,
                    "save-write.backup-validation-failed",
                    "The source archive failed expected-identity validation");
            requireValidForExpectedId(
                    actual,
                    expectedSaveGameId,
                    "save-write.backup-validation-failed",
                    "The copied archive failed expected-identity validation");
            if (Files.mismatch(expected, actual) != -1L) {
                throw validationFailure(
                        "save-write.backup-validation-failed", message);
            }
        } catch (IOException failure) {
            throw new StoreFailure(
                    "save-write.backup-validation-failed",
                    "The backup archive could not be compared",
                    failure);
        }
    }

    private ArchiveIdentity identity(Path archive, String code, String message) {
        try {
            requireAnchoredRegularArchive(archive);
            byte[] manifest;
            try (ZipFile zip = new ZipFile(archive.toFile())) {
                ZipEntry entry = zip.getEntry("manifest.json");
                if (entry == null) {
                    throw new IOException("validated archive has no manifest entry");
                }
                try (InputStream input = zip.getInputStream(entry)) {
                    manifest = input.readAllBytes();
                }
            }
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(archive)) {
                byte[] buffer = new byte[8192];
                for (int read; (read = input.read(buffer)) != -1; ) {
                    digest.update(buffer, 0, read);
                }
            }
            return new ArchiveIdentity(manifest, Files.size(archive), digest.digest());
        } catch (IOException | RuntimeException failure) {
            throw new StoreFailure(code, message, failure);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private void forceDirectory() {
        try {
            requireAnchoredDirectories();
            files.forceDirectoryBestEffort(
                    worldDirectory, this::requireAnchoredDirectories);
            requireAnchoredDirectories();
        } catch (IOException | RuntimeException failure) {
            throw new StoreFailure(
                    "save-write.directory-force-failed",
                    "The save directory could not be forced",
                    failure);
        }
    }

    private boolean cleanupOwnedTemps(
            TransactionOwnership ownership, StoreFailure failure) {
        boolean cleanupFailed = false;
        for (Path temporary : ownership.ownedTempsInReverseOrder()) {
            try {
                requireAnchoredParent(temporary);
                files.deleteIfExists(
                        temporary, () -> requireAnchoredParent(temporary));
                requireAnchoredDirectories();
            } catch (IOException | RuntimeException cleanupFailure) {
                failure.retainCleanup(cleanupFailure);
                cleanupFailed = true;
            }
        }
        return cleanupFailed;
    }

    private boolean hasKnownGoodArchive(SaveGameId expectedSaveGameId) {
        try {
            requireAnchoredDirectories();
            return isValidForExpectedId(currentArchive, expectedSaveGameId)
                    || isValidForExpectedId(backupArchive, expectedSaveGameId);
        } catch (StoreFailure unsafeDirectory) {
            return false;
        }
    }

    private boolean isValidForExpectedId(
            Path archive, SaveGameId expectedSaveGameId) {
        try {
            requireAnchoredRegularArchive(archive);
            SaveArchiveReadResult result = archiveReader.read(archive);
            return result.status() == SaveArchiveReadResult.Status.VALID
                    && result.snapshot().orElseThrow().metadata().saveGameId()
                            .equals(expectedSaveGameId);
        } catch (RuntimeException unreadable) {
            return false;
        }
    }

    private void forceConfiguredRootIfPending() {
        if (!configuredRootForcePending) {
            return;
        }
        try {
            requireAnchoredDirectories();
            files.forceDirectoryBestEffort(
                    configuredSaveRoot, this::requireAnchoredDirectories);
            requireAnchoredDirectories();
            configuredRootForcePending = false;
        } catch (IOException | RuntimeException failure) {
            throw new StoreFailure(
                    "save-write.root-directory-force-failed",
                    "The configured save root could not be forced",
                    failure);
        }
    }

    private void requireAnchoredDirectories() {
        if (configuredSaveRootIdentity != null
                && !configuredSaveRootIdentity.matches(configuredSaveRoot)) {
            throw validationFailure(
                    "save-write.unsafe-world-path",
                    "The configured save root identity changed during save");
        }
        if (!worldDirectoryIdentity.matches(worldDirectory)) {
            throw validationFailure(
                    "save-write.unsafe-world-path",
                    "The save world directory identity changed during save");
        }
    }

    private void requireAnchoredParent(Path path) {
        requireAnchoredDirectories();
        Path normalized = Objects.requireNonNull(path, "path")
                .toAbsolutePath()
                .normalize();
        if (!worldDirectory.equals(normalized.getParent())
                || !worldDirectoryIdentity.matches(normalized.getParent())) {
            throw validationFailure(
                    "save-write.unsafe-world-path",
                    "A save path escaped the anchored world directory");
        }
    }

    private void requireAnchoredSlot(Path slot) {
        requireAnchoredParent(slot);
        if (Files.exists(slot, LinkOption.NOFOLLOW_LINKS)
                && !Files.isRegularFile(slot, LinkOption.NOFOLLOW_LINKS)) {
            throw validationFailure(
                    "save-write.unsafe-world-path",
                    "A save slot is not a direct regular file");
        }
    }

    private void requireAnchoredRegularArchive(Path archive) {
        requireAnchoredParent(archive);
        if (!Files.isRegularFile(archive, LinkOption.NOFOLLOW_LINKS)) {
            throw validationFailure(
                    "save-write.unsafe-world-path",
                    "A save archive is not a direct regular file");
        }
    }

    private void requireAnchoredRegularTemp(Path temporary) {
        try {
            requireAnchoredParent(temporary);
        } catch (StoreFailure unsafeParent) {
            throw validationFailure(
                    "save-write.unsafe-temp-path",
                    "The filesystem returned an unsafe temporary save path",
                    unsafeParent.getCause());
        }
        if (!Files.isRegularFile(temporary, LinkOption.NOFOLLOW_LINKS)
                || !temporary.getFileName().toString().endsWith(TEMP_SUFFIX)) {
            throw validationFailure(
                    "save-write.unsafe-temp-path",
                    "The filesystem returned an unsafe temporary save path");
        }
    }

    private static StorePaths resolveSafePaths(Path saveRoot, SaveGameId saveGameId) {
        Path root = Objects.requireNonNull(saveRoot, "saveRoot")
                .toAbsolutePath()
                .normalize();
        SaveGameId id = Objects.requireNonNull(saveGameId, "saveGameId");
        requireDirectDirectory(root, "Configured save root");
        Path world = root.resolve(id.value()).normalize();
        if (!root.equals(world.getParent())) {
            throw new IllegalArgumentException("Save world must be a direct child of save root");
        }
        boolean created = false;
        try {
            if (Files.exists(world, LinkOption.NOFOLLOW_LINKS)) {
                requireDirectDirectory(world, "Save world directory");
            } else {
                Files.createDirectory(world);
                created = true;
                requireDirectDirectory(world, "Save world directory");
            }
        } catch (IOException failure) {
            throw new IllegalArgumentException(
                    "Save world directory could not be created safely", failure);
        }
        return new StorePaths(
                root,
                DirectoryIdentity.capture(root, "Configured save root"),
                id,
                world,
                DirectoryIdentity.capture(world, "Save world directory"),
                created);
    }

    private static StorePaths resolveTestWorldPath(Path worldDirectory) {
        Path world = Objects.requireNonNull(worldDirectory, "worldDirectory")
                .toAbsolutePath()
                .normalize();
        requireDirectDirectory(world, "Save world directory");
        return new StorePaths(
                null,
                null,
                null,
                world,
                DirectoryIdentity.capture(world, "Save world directory"),
                false);
    }

    private static void requireDirectDirectory(Path directory, String label) {
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)
                || !resolvesAsDirectEntry(directory)) {
            throw new IllegalArgumentException(label + " must be a direct directory");
        }
    }

    private static boolean resolvesAsDirectEntry(Path path) {
        Path parent = path.getParent();
        if (parent == null) {
            return false;
        }
        try {
            Path expected = parent.toRealPath().resolve(path.getFileName().toString());
            return expected.equals(path.toRealPath());
        } catch (IOException | RuntimeException failure) {
            return false;
        }
    }

    private static StoreFailure validationFailure(String code, String message) {
        return validationFailure(code, message, null);
    }

    private static StoreFailure validationFailure(
            String code, String message, Throwable cause) {
        Throwable primary = cause == null
                ? new ArchiveValidationFailure(message)
                : cause;
        return new StoreFailure(code, message, primary);
    }

    private static SaveWriteResult failed(StoreFailure failure) {
        return SaveWriteResult.failed(failure.diagnostic());
    }

    private static SaveWriteResult blocking(StoreFailure failure) {
        return SaveWriteResult.blockingFailure(failure.blockingDiagnostic());
    }

    private record StorePaths(
            Path saveRoot,
            DirectoryIdentity saveRootIdentity,
            SaveGameId saveGameId,
            Path worldDirectory,
            DirectoryIdentity worldDirectoryIdentity,
            boolean worldDirectoryCreated) {}

    private record DirectoryIdentity(Path realPath, Object fileKey) {
        private static DirectoryIdentity capture(Path directory, String label) {
            try {
                BasicFileAttributes attributes = Files.readAttributes(
                        directory,
                        BasicFileAttributes.class,
                        LinkOption.NOFOLLOW_LINKS);
                if (!attributes.isDirectory()) {
                    throw new IllegalArgumentException(label + " must be a directory");
                }
                return new DirectoryIdentity(directory.toRealPath(), attributes.fileKey());
            } catch (IOException failure) {
                throw new IllegalArgumentException(label + " could not be anchored", failure);
            }
        }

        private boolean matches(Path directory) {
            try {
                BasicFileAttributes attributes = Files.readAttributes(
                        directory,
                        BasicFileAttributes.class,
                        LinkOption.NOFOLLOW_LINKS);
                if (!attributes.isDirectory() || !realPath.equals(directory.toRealPath())) {
                    return false;
                }
                Object actualKey = attributes.fileKey();
                return fileKey == null || actualKey == null || fileKey.equals(actualKey);
            } catch (IOException | RuntimeException failure) {
                return false;
            }
        }
    }

    private record ArchiveIdentity(byte[] manifest, long size, byte[] sha256) {
        private ArchiveIdentity {
            manifest = manifest.clone();
            sha256 = sha256.clone();
        }

        private boolean sameManifest(ArchiveIdentity other) {
            return Arrays.equals(manifest, other.manifest);
        }

        private boolean sameArchive(ArchiveIdentity other) {
            return size == other.size && Arrays.equals(sha256, other.sha256);
        }
    }

    private static final class TransactionOwnership {
        private final List<Path> ownedTemps = new ArrayList<>();

        private void own(Path temporary) {
            ownedTemps.add(temporary);
        }

        private void transfer(Path temporary) {
            ownedTemps.remove(temporary);
        }

        private List<Path> ownedTempsInReverseOrder() {
            List<Path> reversed = new ArrayList<>(ownedTemps);
            java.util.Collections.reverse(reversed);
            return reversed;
        }
    }

    private static final class ArchiveValidationFailure extends RuntimeException {
        private ArchiveValidationFailure(String message) {
            super(message, null, true, false);
        }
    }

    private static final class StoreFailure extends RuntimeException {
        private final String code;
        private final String boundedMessage;
        private final Throwable primary;
        private BlockingSource blockingSource = BlockingSource.NONE;

        private StoreFailure(String code, String boundedMessage, Throwable primary) {
            super(boundedMessage, primary, false, false);
            this.code = Objects.requireNonNull(code, "code");
            this.boundedMessage = Objects.requireNonNull(boundedMessage, "boundedMessage");
            this.primary = Objects.requireNonNull(primary, "primary");
        }

        private void retainCleanup(Throwable cleanup) {
            Objects.requireNonNull(cleanup, "cleanup");
            if (primary != cleanup) {
                primary.addSuppressed(cleanup);
            }
        }

        private void retainRemediation(Throwable remediationFailure) {
            retainCleanup(remediationFailure);
            blockingSource = BlockingSource.CURRENT_REMEDIATION;
        }

        private void retainCleanupOwnershipUncertainty() {
            if (blockingSource == BlockingSource.NONE) {
                blockingSource = BlockingSource.TEMP_CLEANUP;
            }
        }

        private boolean blocking() {
            return blockingSource != BlockingSource.NONE;
        }

        private SaveDiagnostic diagnostic() {
            return SaveDiagnostic.of(code, boundedMessage, primary);
        }

        private SaveDiagnostic blockingDiagnostic() {
            return switch (blockingSource) {
                case TEMP_CLEANUP -> SaveDiagnostic.of(
                        "save-write.temp-cleanup-ownership-uncertain",
                        "Temporary save cleanup failed and archive ownership is uncertain",
                        primary);
                case CURRENT_REMEDIATION -> SaveDiagnostic.of(
                        "save-write.current-remediation-failed",
                        "Installed current remediation failed and archive ownership is uncertain",
                        primary);
                case NONE -> throw new IllegalStateException(
                        "A blocking diagnostic requires an uncertainty source");
            };
        }
    }

    private enum BlockingSource {
        NONE,
        TEMP_CLEANUP,
        CURRENT_REMEDIATION
    }
}

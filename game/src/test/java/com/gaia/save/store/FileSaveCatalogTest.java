package com.gaia.save.store;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.gaia.save.archive.SaveArchiveReadResult;
import com.gaia.save.archive.SaveArchiveReader;
import com.gaia.save.archive.SaveArchiveWriter;
import com.gaia.save.archive.SaveDiagnostic;
import com.gaia.save.format.SaveFormatVersion;
import com.gaia.save.format.SaveGameId;
import com.gaia.save.snapshot.SaveGameSnapshot;
import com.gaia.shell.save.SaveSummary;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileSaveCatalogTest {
    private static final Instant CREATED = Instant.parse("2026-08-10T12:00:00Z");

    @TempDir
    Path tempDir;

    @Test
    void classifiesDirectSlotsWithoutTreatingTempsOrLinksAsSaves() throws Exception {
        Path root = Files.createDirectories(tempDir.resolve("saves"));
        SaveGameId validId = SaveRepositoryTestSupport.id(1);
        SaveGameId recoverableId = SaveRepositoryTestSupport.id(2);
        SaveGameId corruptId = SaveRepositoryTestSupport.id(3);
        SaveGameId futureId = SaveRepositoryTestSupport.id(4);
        SaveGameId tempOnlyId = SaveRepositoryTestSupport.id(5);

        Instant validModified = Instant.parse("2026-08-10T12:40:00Z");
        SaveRepositoryTestSupport.writeCurrent(
                root, validId, "Gaia", 41L, validModified);

        Path recoverable = Files.createDirectories(root.resolve(recoverableId.value()));
        Path corruptCurrent = recoverable.resolve("current.glsave");
        Files.write(corruptCurrent, new byte[] {0x13, 0x37, 0x42});
        byte[] corruptCurrentBytes = Files.readAllBytes(corruptCurrent);
        Instant backupModified = Instant.parse("2026-08-10T12:30:00Z");
        SaveRepositoryTestSupport.writeArchive(
                recoverable.resolve("backup.glsave"),
                recoverableId,
                "Legacy",
                42L,
                backupModified);

        Path corrupt = Files.createDirectories(root.resolve(corruptId.value()));
        Files.write(corrupt.resolve("current.glsave"), new byte[] {1, 2, 3});
        Files.write(corrupt.resolve("backup.glsave"), new byte[] {4, 5, 6});

        SaveRepositoryTestSupport.writeFutureCurrent(
                root,
                futureId,
                "Future",
                44L,
                Instant.parse("2026-08-10T12:20:00Z"),
                2);

        Path tempOnly = Files.createDirectories(root.resolve(tempOnlyId.value()));
        SaveRepositoryTestSupport.writeArchive(
                tempOnly.resolve("current.glsave.crash.tmp"),
                tempOnlyId,
                "Half written",
                45L,
                Instant.parse("2026-08-10T12:50:00Z"));

        Files.createDirectories(root.resolve("not-a-save-id"));
        Files.createDirectories(root.resolve(".trash"));
        List<SaveSummary> summaries = SaveRepositoryTestSupport.catalog(root).summaries();

        assertEquals(
                List.of(validId, recoverableId, futureId, corruptId),
                summaries.stream().map(SaveSummary::id).toList());
        assertFalse(summaries.stream().anyMatch(row -> row.id().equals(tempOnlyId)));
        assertArrayEquals(corruptCurrentBytes, Files.readAllBytes(corruptCurrent),
                "catalog scans must never promote a backup");

        SaveSummary valid = SaveRepositoryTestSupport.summary(summaries, validId);
        assertEquals("Gaia", valid.name());
        assertEquals(Optional.of(CREATED), valid.createdTime());
        assertEquals(validModified, valid.modifiedTime());
        assertEquals(Optional.of(41L), valid.worldSeed());
        assertEquals(Optional.of(SaveFormatVersion.CURRENT), valid.formatVersion());
        assertEquals(SaveSummary.Health.VALID, valid.health());
        assertTrue(valid.loadEnabled());
        assertFalse(valid.recoveryEnabled());
        assertTrue(valid.deleteEnabled());

        SaveSummary recoverableRow = SaveRepositoryTestSupport.summary(
                summaries, recoverableId);
        assertEquals("Legacy", recoverableRow.name());
        assertEquals(backupModified, recoverableRow.modifiedTime());
        assertEquals(SaveSummary.Health.RECOVERABLE_BACKUP, recoverableRow.health());
        assertFalse(recoverableRow.loadEnabled());
        assertTrue(recoverableRow.recoveryEnabled());
        assertTrue(recoverableRow.deleteEnabled());

        SaveSummary corruptRow = SaveRepositoryTestSupport.summary(summaries, corruptId);
        assertEquals(SaveSummary.Health.CORRUPT, corruptRow.health());
        assertFalse(corruptRow.loadEnabled());
        assertFalse(corruptRow.recoveryEnabled());
        assertTrue(corruptRow.deleteEnabled());
        SaveRepositoryTestSupport.assertBoundedDiagnostics(corruptRow.diagnostics(), root);

        SaveSummary futureRow = SaveRepositoryTestSupport.summary(summaries, futureId);
        assertEquals(SaveSummary.Health.UNSUPPORTED_VERSION, futureRow.health());
        assertEquals(2, futureRow.formatVersion().orElseThrow().value());
        assertFalse(futureRow.loadEnabled());
        assertFalse(futureRow.recoveryEnabled());
        assertTrue(futureRow.deleteEnabled());
        SaveRepositoryTestSupport.assertBoundedDiagnostics(futureRow.diagnostics(), root);

        assertThrows(UnsupportedOperationException.class,
                () -> summaries.add(valid));
    }

    @Test
    void excludesContainedLinkedWorldWithoutReadingOrMutatingExternalState()
            throws Exception {
        Path root = Files.createDirectories(tempDir.resolve("saves"));
        Path external = Files.createDirectories(tempDir.resolve("external"));
        SaveGameId linkedId = SaveRepositoryTestSupport.id(6);
        Path externalArchive = external.resolve("current.glsave");
        SaveRepositoryTestSupport.writeArchive(
                externalArchive,
                linkedId,
                "Outside",
                46L,
                Instant.parse("2026-08-10T13:00:00Z"));
        byte[] externalArchiveBytes = Files.readAllBytes(externalArchive);
        Path sentinel = external.resolve("keep.txt");
        byte[] sentinelBytes = "outside".getBytes(StandardCharsets.UTF_8);
        Files.write(sentinel, sentinelBytes);

        Path linkedWorld = root.resolve(linkedId.value());
        assumeTrue(
                SaveRepositoryTestSupport.tryCreateDirectoryLink(
                        tempDir, linkedWorld, external),
                "directory-link capability unavailable; linked-world evidence pending");

        assertTrue(Files.exists(linkedWorld, LinkOption.NOFOLLOW_LINKS),
                "the capability helper must create a directory entry before scanning");
        BasicFileAttributes linkAttributes = Files.readAttributes(
                linkedWorld,
                BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);
        assertTrue(linkAttributes.isSymbolicLink() || linkAttributes.isOther(),
                "the fixture must be a symbolic-link or Windows reparse entry");
        assertTrue(Files.isSameFile(linkedWorld, external),
                "the linked world must resolve to the contained external fixture");

        List<SaveSummary> summaries = SaveRepositoryTestSupport.catalog(root).summaries();

        assertFalse(summaries.stream().anyMatch(row -> row.id().equals(linkedId)),
                "catalog discovery must exclude a direct linked world");
        assertArrayEquals(externalArchiveBytes, Files.readAllBytes(externalArchive),
                "catalog discovery must not mutate the external archive");
        assertArrayEquals(sentinelBytes, Files.readAllBytes(sentinel),
                "catalog discovery must not mutate the external sentinel");
    }

    @Test
    void sortsByModifiedDescendingThenSaveIdAscending() throws Exception {
        Path root = Files.createDirectories(tempDir.resolve("saves"));
        SaveGameId lowTie = SaveRepositoryTestSupport.id(11);
        SaveGameId highTie = SaveRepositoryTestSupport.id(12);
        SaveGameId newest = SaveRepositoryTestSupport.id(13);
        Instant tied = Instant.parse("2026-08-10T12:30:00Z");

        SaveRepositoryTestSupport.writeCurrent(root, highTie, "High", 12L, tied);
        SaveRepositoryTestSupport.writeCurrent(root, lowTie, "Low", 11L, tied);
        SaveRepositoryTestSupport.writeCurrent(
                root, newest, "Newest", 13L, tied.plusSeconds(1));

        assertEquals(
                List.of(newest, lowTie, highTie),
                SaveRepositoryTestSupport.catalog(root).summaries().stream()
                        .map(SaveSummary::id)
                        .toList());
    }

    @Test
    void eachScanReturnsADetachedImmutableSnapshot() throws Exception {
        Path root = Files.createDirectories(tempDir.resolve("saves"));
        SaveGameId firstId = SaveRepositoryTestSupport.id(21);
        SaveGameId laterId = SaveRepositoryTestSupport.id(22);
        SaveRepositoryTestSupport.writeCurrent(
                root,
                firstId,
                "First",
                21L,
                Instant.parse("2026-08-10T12:10:00Z"));

        FileSaveCatalog catalog = SaveRepositoryTestSupport.catalog(root);
        List<SaveSummary> firstSnapshot = catalog.summaries();
        SaveRepositoryTestSupport.writeCurrent(
                root,
                laterId,
                "Later",
                22L,
                Instant.parse("2026-08-10T12:20:00Z"));

        assertEquals(List.of(firstId), firstSnapshot.stream().map(SaveSummary::id).toList());
        assertEquals(
                List.of(laterId, firstId),
                catalog.summaries().stream().map(SaveSummary::id).toList());
        assertThrows(UnsupportedOperationException.class, firstSnapshot::clear);
    }

    @Test
    void wrongIdCurrentCannotMaskCorrectIdBackup() throws Exception {
        Path root = Files.createDirectories(tempDir.resolve("saves"));
        SaveGameId expectedId = SaveRepositoryTestSupport.id(31);
        SaveGameId wrongId = SaveRepositoryTestSupport.id(32);
        Path directory = Files.createDirectories(root.resolve(expectedId.value()));
        SaveRepositoryTestSupport.writeArchive(
                directory.resolve("current.glsave"),
                wrongId,
                "Wrong current",
                32L,
                Instant.parse("2026-08-10T12:40:00Z"));
        SaveRepositoryTestSupport.writeArchive(
                directory.resolve("backup.glsave"),
                expectedId,
                "Expected backup",
                31L,
                Instant.parse("2026-08-10T12:30:00Z"));

        SaveSummary summary = SaveRepositoryTestSupport.catalog(root).summaries().get(0);

        assertEquals(expectedId, summary.id());
        assertEquals("Expected backup", summary.name());
        assertEquals(SaveSummary.Health.RECOVERABLE_BACKUP, summary.health());
        assertFalse(summary.loadEnabled());
        assertTrue(summary.recoveryEnabled());
    }

    @Test
    void wrongIdBackupIsNotARecoveryCandidateForCorruptCurrent() throws Exception {
        Path root = Files.createDirectories(tempDir.resolve("saves"));
        SaveGameId expectedId = SaveRepositoryTestSupport.id(33);
        SaveGameId wrongId = SaveRepositoryTestSupport.id(34);
        Path directory = Files.createDirectories(root.resolve(expectedId.value()));
        Files.write(directory.resolve("current.glsave"), new byte[] {1, 2, 3});
        SaveRepositoryTestSupport.writeArchive(
                directory.resolve("backup.glsave"),
                wrongId,
                "Wrong backup",
                34L,
                Instant.parse("2026-08-10T12:30:00Z"));

        SaveSummary summary = SaveRepositoryTestSupport.catalog(root).summaries().get(0);

        assertEquals(expectedId, summary.id());
        assertEquals(SaveSummary.Health.CORRUPT, summary.health());
        assertFalse(summary.loadEnabled());
        assertFalse(summary.recoveryEnabled());
    }
}

final class SaveRepositoryTestSupport {
    private static final SaveArchiveReader READER =
            new SaveArchiveReader(AtomicSaveStoreTestSupport.codec());

    private SaveRepositoryTestSupport() {}

    static SaveGameId id(int suffix) {
        return SaveGameId.parse("00000000-0000-0000-0000-" + String.format("%012d", suffix));
    }

    static FileSaveCatalog catalog(Path root) {
        return new FileSaveCatalog(repository(root, new RepositoryRecordingFileOperations()));
    }

    static SaveRepository repository(Path root, RepositoryRecordingFileOperations files) {
        return SaveRepository.open(root, READER, files);
    }

    static SaveSummary summary(List<SaveSummary> summaries, SaveGameId id) {
        return summaries.stream()
                .filter(summary -> summary.id().equals(id))
                .findFirst()
                .orElseThrow();
    }

    static void writeCurrent(
            Path root,
            SaveGameId id,
            String name,
            long seed,
            Instant modified) throws IOException {
        Path directory = Files.createDirectories(root.resolve(id.value()));
        writeArchive(directory.resolve("current.glsave"), id, name, seed, modified);
    }

    static void writeArchive(
            Path archive,
            SaveGameId id,
            String name,
            long seed,
            Instant modified) throws IOException {
        Files.createDirectories(archive.getParent());
        SaveGameSnapshot snapshot = snapshot(id, name, seed);
        new SaveArchiveWriter().write(
                archive, AtomicSaveStoreTestSupport.codec().encode(snapshot, modified));
        SaveArchiveReadResult result = READER.read(archive);
        assertEquals(SaveArchiveReadResult.Status.VALID, result.status());
        assertEquals(snapshot, result.snapshot().orElseThrow());
    }

    static void writeFutureCurrent(
            Path root,
            SaveGameId id,
            String name,
            long seed,
            Instant modified,
            int futureVersion) throws IOException {
        Path directory = Files.createDirectories(root.resolve(id.value()));
        Path valid = directory.resolve("valid-source.glsave");
        writeArchive(valid, id, name, seed, modified);
        Path future = directory.resolve("current.glsave");
        rewriteZip(valid, future, (entryName, bytes) -> {
            if (!entryName.equals("manifest.json")) {
                return;
            }
            String json = new String(bytes, StandardCharsets.UTF_8);
            String original = "\"formatVersion\":1";
            String replacement = "\"formatVersion\":" + futureVersion;
            if (!json.contains(original)) {
                throw new AssertionError("fixture did not find v1 format field");
            }
            byte[] replaced = json.replace(original, replacement)
                    .getBytes(StandardCharsets.UTF_8);
            System.arraycopy(replaced, 0, bytes, 0, Math.min(bytes.length, replaced.length));
            if (replaced.length != bytes.length) {
                throw new AssertionError("future version fixture must preserve manifest length");
            }
        });
        Files.delete(valid);
        assertEquals(SaveArchiveReadResult.Status.UNSUPPORTED_VERSION, READER.read(future).status());
    }

    static SaveGameSnapshot snapshot(SaveGameId id, String name, long seed) {
        SaveGameSnapshot base = AtomicSaveStoreTestSupport.snapshot(name, seed, 40L);
        SaveGameSnapshot.StaticMetadata metadata = base.metadata();
        return new SaveGameSnapshot(
                new SaveGameSnapshot.StaticMetadata(
                        metadata.formatVersion(),
                        metadata.gameVersion(),
                        id,
                        name,
                        Instant.parse("2026-08-10T12:00:00Z"),
                        seed,
                        metadata.generatorVersion(),
                        metadata.generatorConfigFingerprint(),
                        metadata.chunkRadius(),
                        metadata.worldHeight(),
                        metadata.summary()),
                base.fixedTick(),
                base.chunks(),
                base.player(),
                base.inventory(),
                base.worldItems());
    }

    static boolean tryCreateDirectoryLink(Path containedRoot, Path link, Path target) {
        Path root = containedRoot.toAbsolutePath().normalize();
        Path normalizedLink = link.toAbsolutePath().normalize();
        Path normalizedTarget = target.toAbsolutePath().normalize();
        if (!normalizedLink.startsWith(root)
                || !normalizedTarget.startsWith(root)
                || !Files.isDirectory(normalizedTarget, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("Link fixture must remain inside its temp root");
        }
        try {
            Files.createSymbolicLink(normalizedLink, normalizedTarget);
            return true;
        } catch (IOException | UnsupportedOperationException | SecurityException unsupported) {
            if (!System.getProperty("os.name", "")
                    .toLowerCase(Locale.ROOT)
                    .contains("win")) {
                return false;
            }
        }
        try {
            Process process = new ProcessBuilder(
                    "cmd.exe",
                    "/d",
                    "/c",
                    "mklink",
                    "/J",
                    normalizedLink.toString(),
                    normalizedTarget.toString())
                    .redirectErrorStream(true)
                    .start();
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return false;
            }
            process.getInputStream().readNBytes(1024);
            return process.exitValue() == 0
                    && Files.isDirectory(normalizedLink);
        } catch (IOException | InterruptedException | SecurityException unsupported) {
            if (unsupported instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return false;
        }
    }

    static void assertBoundedDiagnostics(List<SaveDiagnostic> diagnostics, Path root) {
        assertFalse(diagnostics.isEmpty());
        for (SaveDiagnostic diagnostic : diagnostics) {
            assertTrue(diagnostic.code().codePointCount(0, diagnostic.code().length()) <= 96);
            assertTrue(diagnostic.message().codePointCount(0, diagnostic.message().length())
                    <= SaveDiagnostic.MAX_MESSAGE_CODE_POINTS);
            assertFalse(diagnostic.message().contains(root.toString()));
        }
    }

    private static void rewriteZip(
            Path source,
            Path destination,
            BiConsumer<String, byte[]> editor) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipInputStream input = new ZipInputStream(Files.newInputStream(source))) {
            for (ZipEntry entry; (entry = input.getNextEntry()) != null; ) {
                entries.put(entry.getName(), input.readAllBytes());
            }
        }
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(destination))) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                byte[] bytes = entry.getValue();
                editor.accept(entry.getKey(), bytes);
                output.putNextEntry(new ZipEntry(entry.getKey()));
                output.write(bytes);
                output.closeEntry();
            }
        }
    }
}

final class RepositoryRecordingFileOperations implements SaveFileOperations {
    private final SaveFileOperations delegate = new JdkSaveFileOperations();
    private final List<Move> moves = new ArrayList<>();
    private final List<Path> deletes = new ArrayList<>();
    private IOException moveFailure;
    private IOException cleanupFailure;
    private BiConsumer<Path, Path> beforeMove = (source, destination) -> {};
    private BiConsumer<Path, Path> afterMove = (source, destination) -> {};
    private BiConsumer<Path, Path> beforeCopy = (source, destination) -> {};
    private boolean directoryMoved;

    @Override
    public Path createSiblingTemp(
            Path directory, String targetName, MutationGuard mutationGuard) throws IOException {
        return delegate.createSiblingTemp(directory, targetName, mutationGuard);
    }

    @Override
    public void forceFile(Path file, MutationGuard mutationGuard) throws IOException {
        delegate.forceFile(file, mutationGuard);
    }

    @Override
    public void moveAtomicReplacing(
            Path source, Path destination, MutationGuard mutationGuard) throws IOException {
        move(source, destination, mutationGuard);
    }

    @Override
    public void moveReplacing(
            Path source, Path destination, MutationGuard mutationGuard) throws IOException {
        move(source, destination, mutationGuard);
    }

    @Override
    public void copyReplacing(
            Path source, Path destination, MutationGuard mutationGuard) throws IOException {
        beforeCopy.accept(source, destination);
        delegate.copyReplacing(source, destination, mutationGuard);
    }

    @Override
    public boolean deleteIfExists(Path path, MutationGuard mutationGuard) throws IOException {
        deletes.add(path.toAbsolutePath().normalize());
        if (directoryMoved && cleanupFailure != null) {
            throw cleanupFailure;
        }
        return delegate.deleteIfExists(path, mutationGuard);
    }

    @Override
    public void forceDirectoryBestEffort(
            Path directory, MutationGuard mutationGuard) throws IOException {
        delegate.forceDirectoryBestEffort(directory, mutationGuard);
    }

    void failMove(IOException failure) {
        moveFailure = failure;
    }

    void failCleanup(IOException failure) {
        cleanupFailure = failure;
    }

    void beforeMove(BiConsumer<Path, Path> action) {
        beforeMove = action;
    }

    void afterMove(BiConsumer<Path, Path> action) {
        afterMove = action;
    }

    void beforeCopy(BiConsumer<Path, Path> action) {
        beforeCopy = action;
    }

    List<Move> moves() {
        return List.copyOf(moves);
    }

    List<Path> deletes() {
        return List.copyOf(deletes);
    }

    private void move(
            Path source, Path destination, MutationGuard mutationGuard) throws IOException {
        moves.add(new Move(
                source.toAbsolutePath().normalize(),
                destination.toAbsolutePath().normalize()));
        beforeMove.accept(source, destination);
        if (moveFailure != null) {
            throw moveFailure;
        }
        delegate.moveReplacing(source, destination, mutationGuard);
        directoryMoved = Files.isDirectory(destination);
        afterMove.accept(source, destination);
    }

    record Move(Path source, Path destination) {}
}

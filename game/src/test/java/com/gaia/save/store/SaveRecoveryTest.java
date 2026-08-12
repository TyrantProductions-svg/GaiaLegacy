package com.gaia.save.store;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gaia.save.archive.SaveArchiveReadResult;
import com.gaia.save.archive.SaveArchiveReader;
import com.gaia.save.format.SaveGameId;
import com.gaia.shell.save.SaveSummary;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SaveRecoveryTest {
    @TempDir
    Path tempDir;

    @Test
    void catalogScanNeverSilentlyPromotesAValidBackup() throws Exception {
        RecoveryFixture fixture = recoverableFixture(1);
        byte[] corruptCurrent = Files.readAllBytes(fixture.current());
        byte[] validBackup = Files.readAllBytes(fixture.backup());

        SaveSummary row = SaveRepositoryTestSupport.catalog(fixture.root())
                .summaries().get(0);

        assertEquals(SaveSummary.Health.RECOVERABLE_BACKUP, row.health());
        assertFalse(row.loadEnabled());
        assertTrue(row.recoveryEnabled());
        assertArrayEquals(corruptCurrent, Files.readAllBytes(fixture.current()));
        assertArrayEquals(validBackup, Files.readAllBytes(fixture.backup()));
    }

    @Test
    void explicitRecoveryPreservesCurrentUntilCommitAndRereadsInstalledBackup()
            throws Exception {
        RecoveryFixture fixture = recoverableFixture(2);
        byte[] corruptCurrent = Files.readAllBytes(fixture.current());
        byte[] validBackup = Files.readAllBytes(fixture.backup());
        RepositoryRecordingFileOperations files = new RepositoryRecordingFileOperations();
        AtomicBoolean observedPrecommitState = new AtomicBoolean();
        files.beforeMove((source, destination) -> {
            if (destination.getFileName().toString().equals("current.glsave")) {
                try {
                    assertArrayEquals(corruptCurrent, Files.readAllBytes(fixture.current()));
                    assertArrayEquals(validBackup, Files.readAllBytes(fixture.backup()));
                    observedPrecommitState.set(true);
                } catch (IOException failure) {
                    throw new AssertionError(failure);
                }
            }
        });
        SaveRepository repository = SaveRepositoryTestSupport.repository(fixture.root(), files);

        SaveRecoveryResult result = repository.recoverBackup(fixture.id());

        assertEquals(SaveRecoveryResult.Status.SUCCESS, result.status());
        assertTrue(result.diagnostics().isEmpty());
        assertTrue(observedPrecommitState.get(),
                "corrupt current must remain available until the commit move");
        assertArrayEquals(validBackup, Files.readAllBytes(fixture.current()));
        assertArrayEquals(validBackup, Files.readAllBytes(fixture.backup()),
                "recovery must retain its last-known-good backup source");
        SaveArchiveReadResult reread = new SaveArchiveReader(
                AtomicSaveStoreTestSupport.codec()).read(fixture.current());
        assertEquals(SaveArchiveReadResult.Status.VALID, reread.status());
        assertEquals(fixture.snapshot(), reread.snapshot().orElseThrow());
        assertEquals(
                SaveSummary.Health.VALID,
                SaveRepositoryTestSupport.catalog(fixture.root())
                        .summaries().get(0).health());
    }

    @Test
    void recoveryDoesNotReportSuccessWhenInstalledCurrentFailsReread() throws Exception {
        RecoveryFixture fixture = recoverableFixture(3);
        byte[] validBackup = Files.readAllBytes(fixture.backup());
        RepositoryRecordingFileOperations files = new RepositoryRecordingFileOperations();
        files.afterMove((source, destination) -> {
            if (destination.getFileName().toString().equals("current.glsave")) {
                try {
                    Files.write(destination, new byte[] {9, 9, 9});
                } catch (IOException failure) {
                    throw new AssertionError(failure);
                }
            }
        });
        SaveRepository repository = SaveRepositoryTestSupport.repository(fixture.root(), files);

        SaveRecoveryResult result = repository.recoverBackup(fixture.id());

        assertEquals(SaveRecoveryResult.Status.FAILURE, result.status());
        SaveRepositoryTestSupport.assertBoundedDiagnostics(
                result.diagnostics(), fixture.root());
        assertArrayEquals(validBackup, Files.readAllBytes(fixture.backup()));
        assertEquals(
                SaveSummary.Health.RECOVERABLE_BACKUP,
                SaveRepositoryTestSupport.catalog(fixture.root())
                        .summaries().get(0).health());
    }

    @Test
    void recoveryMoveFailureRetainsExactCorruptCurrentAndValidBackup() throws Exception {
        RecoveryFixture fixture = recoverableFixture(4);
        byte[] corruptCurrent = Files.readAllBytes(fixture.current());
        byte[] validBackup = Files.readAllBytes(fixture.backup());
        IOException injected = new IOException("injected recovery move failure");
        RepositoryRecordingFileOperations files = new RepositoryRecordingFileOperations();
        files.failMove(injected);
        SaveRepository repository = SaveRepositoryTestSupport.repository(fixture.root(), files);

        SaveRecoveryResult result = repository.recoverBackup(fixture.id());

        assertEquals(SaveRecoveryResult.Status.FAILURE, result.status());
        assertArrayEquals(corruptCurrent, Files.readAllBytes(fixture.current()));
        assertArrayEquals(validBackup, Files.readAllBytes(fixture.backup()));
        assertEquals(injected, result.diagnostics().get(0).cause().orElseThrow());
        SaveRepositoryTestSupport.assertBoundedDiagnostics(
                result.diagnostics(), fixture.root());
    }

    @Test
    void recoveryReturnsClosedResultsForMissingAndNonrecoverableSlots() throws Exception {
        Path root = Files.createDirectories(tempDir.resolve("saves"));
        SaveGameId validId = SaveRepositoryTestSupport.id(5);
        SaveGameId corruptId = SaveRepositoryTestSupport.id(6);
        SaveGameId missingId = SaveRepositoryTestSupport.id(7);
        SaveRepositoryTestSupport.writeCurrent(
                root,
                validId,
                "Valid",
                5L,
                Instant.parse("2026-08-10T12:05:00Z"));
        Path corruptDirectory = Files.createDirectories(root.resolve(corruptId.value()));
        Files.write(corruptDirectory.resolve("current.glsave"), new byte[] {1});
        Files.write(corruptDirectory.resolve("backup.glsave"), new byte[] {2});
        SaveRepository repository = SaveRepositoryTestSupport.repository(
                root, new RepositoryRecordingFileOperations());

        SaveRecoveryResult valid = repository.recoverBackup(validId);
        SaveRecoveryResult corrupt = repository.recoverBackup(corruptId);
        SaveRecoveryResult missing = repository.recoverBackup(missingId);

        assertEquals(SaveRecoveryResult.Status.NOT_RECOVERABLE, valid.status());
        assertEquals(SaveRecoveryResult.Status.NOT_RECOVERABLE, corrupt.status());
        assertEquals(SaveRecoveryResult.Status.NOT_FOUND, missing.status());
        SaveRepositoryTestSupport.assertBoundedDiagnostics(valid.diagnostics(), root);
        SaveRepositoryTestSupport.assertBoundedDiagnostics(corrupt.diagnostics(), root);
        SaveRepositoryTestSupport.assertBoundedDiagnostics(missing.diagnostics(), root);
    }

    @Test
    void validWrongIdCurrentCannotMaskCorrectIdBackupDuringRecovery() throws Exception {
        Path root = Files.createDirectories(tempDir.resolve("saves-id-current"));
        SaveGameId expectedId = SaveRepositoryTestSupport.id(301);
        SaveGameId wrongId = SaveRepositoryTestSupport.id(302);
        Path directory = Files.createDirectories(root.resolve(expectedId.value()));
        SaveRepositoryTestSupport.writeArchive(
                directory.resolve("current.glsave"),
                wrongId,
                "Wrong current",
                302L,
                Instant.parse("2026-08-10T12:40:00Z"));
        SaveRepositoryTestSupport.writeArchive(
                directory.resolve("backup.glsave"),
                expectedId,
                "Expected backup",
                301L,
                Instant.parse("2026-08-10T12:30:00Z"));
        byte[] expectedBackup = Files.readAllBytes(directory.resolve("backup.glsave"));
        SaveRepository repository = SaveRepositoryTestSupport.repository(
                root, new RepositoryRecordingFileOperations());

        assertEquals(
                SaveSummary.Health.RECOVERABLE_BACKUP,
                SaveRepositoryTestSupport.catalog(root).summaries().get(0).health());
        SaveRecoveryResult result = repository.recoverBackup(expectedId);

        assertEquals(SaveRecoveryResult.Status.SUCCESS, result.status());
        assertArrayEquals(expectedBackup, Files.readAllBytes(directory.resolve("current.glsave")));
        assertEquals(
                expectedId,
                new SaveArchiveReader(AtomicSaveStoreTestSupport.codec())
                        .read(directory.resolve("current.glsave"))
                        .snapshot().orElseThrow().metadata().saveGameId());
        SaveSummary after = SaveRepositoryTestSupport.catalog(root).summaries().get(0);
        assertEquals(expectedId, after.id());
        assertEquals(SaveSummary.Health.VALID, after.health());
    }

    @Test
    void validWrongIdBackupIsNotRecoverableForCorruptExpectedCurrent() throws Exception {
        Path root = Files.createDirectories(tempDir.resolve("saves-id-backup"));
        SaveGameId expectedId = SaveRepositoryTestSupport.id(303);
        SaveGameId wrongId = SaveRepositoryTestSupport.id(304);
        Path directory = Files.createDirectories(root.resolve(expectedId.value()));
        Path current = directory.resolve("current.glsave");
        Path backup = directory.resolve("backup.glsave");
        Files.write(current, new byte[] {3, 0, 3});
        SaveRepositoryTestSupport.writeArchive(
                backup,
                wrongId,
                "Wrong backup",
                304L,
                Instant.parse("2026-08-10T12:30:00Z"));
        byte[] currentBytes = Files.readAllBytes(current);
        byte[] backupBytes = Files.readAllBytes(backup);

        SaveRecoveryResult result = SaveRepositoryTestSupport.repository(
                root, new RepositoryRecordingFileOperations()).recoverBackup(expectedId);

        assertEquals(SaveRecoveryResult.Status.NOT_RECOVERABLE, result.status());
        assertArrayEquals(currentBytes, Files.readAllBytes(current));
        assertArrayEquals(backupBytes, Files.readAllBytes(backup));
        assertEquals(
                SaveSummary.Health.CORRUPT,
                SaveRepositoryTestSupport.catalog(root).summaries().get(0).health());
    }

    @Test
    void backupIdentitySwapImmediatelyBeforeCopyFailsClosed() throws Exception {
        IdentitySwapFixture fixture = identitySwapFixture(5);
        RepositoryRecordingFileOperations files = new RepositoryRecordingFileOperations();
        files.beforeCopy((source, destination) -> fixture.installWrongBackup());

        SaveRecoveryResult result = SaveRepositoryTestSupport.repository(
                fixture.root(), files).recoverBackup(fixture.expectedId());

        assertEquals(SaveRecoveryResult.Status.FAILURE, result.status());
        assertArrayEquals(fixture.corruptCurrent(), Files.readAllBytes(fixture.current()));
        assertArrayEquals(fixture.wrongArchive(), Files.readAllBytes(fixture.backup()));
        assertFalse(nextCatalogIsValidExpected(fixture));
    }

    @Test
    void backupIdentitySwapImmediatelyBeforeCommitCannotInstallObservedBytes()
            throws Exception {
        IdentitySwapFixture fixture = identitySwapFixture(6);
        RepositoryRecordingFileOperations files = new RepositoryRecordingFileOperations();
        files.beforeMove((source, destination) -> {
            if (destination.getFileName().toString().equals("current.glsave")) {
                fixture.installWrongBackup();
            }
        });

        SaveRecoveryResult result = SaveRepositoryTestSupport.repository(
                fixture.root(), files).recoverBackup(fixture.expectedId());

        assertEquals(SaveRecoveryResult.Status.FAILURE, result.status());
        assertArrayEquals(fixture.corruptCurrent(), Files.readAllBytes(fixture.current()),
                "identity drift before commit must not replace current");
        assertArrayEquals(fixture.wrongArchive(), Files.readAllBytes(fixture.backup()));
        assertFalse(nextCatalogIsValidExpected(fixture));
    }

    @Test
    void installedCurrentIdentitySwapCannotPublishRecoverySuccess() throws Exception {
        IdentitySwapFixture fixture = identitySwapFixture(7);
        RepositoryRecordingFileOperations files = new RepositoryRecordingFileOperations();
        files.afterMove((source, destination) -> {
            if (destination.getFileName().toString().equals("current.glsave")) {
                try {
                    Files.copy(
                            fixture.wrongSource(),
                            destination,
                            StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException failure) {
                    throw new AssertionError(failure);
                }
            }
        });

        SaveRecoveryResult result = SaveRepositoryTestSupport.repository(
                fixture.root(), files).recoverBackup(fixture.expectedId());

        assertEquals(SaveRecoveryResult.Status.FAILURE, result.status());
        assertArrayEquals(fixture.correctBackup(), Files.readAllBytes(fixture.backup()));
        assertArrayEquals(fixture.wrongArchive(), Files.readAllBytes(fixture.current()));
        assertFalse(result.status() == SaveRecoveryResult.Status.SUCCESS);
        assertEquals(
                SaveSummary.Health.RECOVERABLE_BACKUP,
                SaveRepositoryTestSupport.catalog(fixture.root()).summaries().get(0).health());
    }

    @Test
    void linkedWorldRecoveryNeverTouchesExternalSentinelsWhereSupported()
            throws Exception {
        Path root = Files.createDirectories(tempDir.resolve("saves-link"));
        Path external = Files.createDirectories(tempDir.resolve("external-link"));
        SaveGameId id = SaveRepositoryTestSupport.id(308);
        Path current = external.resolve("current.glsave");
        Path backup = external.resolve("backup.glsave");
        Path sentinel = external.resolve("keep.txt");
        Files.write(current, new byte[] {3, 0, 8});
        SaveRepositoryTestSupport.writeArchive(
                backup,
                id,
                "External backup",
                308L,
                Instant.parse("2026-08-10T12:30:00Z"));
        Files.writeString(sentinel, "outside");
        byte[] currentBytes = Files.readAllBytes(current);
        byte[] backupBytes = Files.readAllBytes(backup);
        org.junit.jupiter.api.Assumptions.assumeTrue(
                SaveRepositoryTestSupport.tryCreateDirectoryLink(
                        tempDir, root.resolve(id.value()), external),
                "symbolic link or Windows junction is unavailable");

        SaveRecoveryResult result = SaveRepositoryTestSupport.repository(
                root, new RepositoryRecordingFileOperations()).recoverBackup(id);

        assertEquals(SaveRecoveryResult.Status.FAILURE, result.status());
        assertArrayEquals(currentBytes, Files.readAllBytes(current));
        assertArrayEquals(backupBytes, Files.readAllBytes(backup));
        assertEquals("outside", Files.readString(sentinel));
    }

    private IdentitySwapFixture identitySwapFixture(int suffix) throws IOException {
        Path root = Files.createDirectories(tempDir.resolve("saves-swap-" + suffix));
        SaveGameId expectedId = SaveRepositoryTestSupport.id(400 + suffix);
        SaveGameId wrongId = SaveRepositoryTestSupport.id(500 + suffix);
        Path directory = Files.createDirectories(root.resolve(expectedId.value()));
        Path current = directory.resolve("current.glsave");
        Path backup = directory.resolve("backup.glsave");
        Path wrongSource = root.resolve("wrong-" + suffix + ".glsave");
        Files.write(current, new byte[] {4, 0, (byte) suffix});
        SaveRepositoryTestSupport.writeArchive(
                backup,
                expectedId,
                "Expected " + suffix,
                400L + suffix,
                Instant.parse("2026-08-10T12:30:00Z"));
        SaveRepositoryTestSupport.writeArchive(
                wrongSource,
                wrongId,
                "Wrong " + suffix,
                500L + suffix,
                Instant.parse("2026-08-10T12:35:00Z"));
        return new IdentitySwapFixture(
                root,
                expectedId,
                current,
                backup,
                wrongSource,
                Files.readAllBytes(current),
                Files.readAllBytes(backup),
                Files.readAllBytes(wrongSource));
    }

    private static boolean nextCatalogIsValidExpected(IdentitySwapFixture fixture) {
        return SaveRepositoryTestSupport.catalog(fixture.root()).summaries().stream()
                .anyMatch(summary -> summary.id().equals(fixture.expectedId())
                        && summary.health() == SaveSummary.Health.VALID);
    }

    private RecoveryFixture recoverableFixture(int suffix) throws IOException {
        Path root = Files.createDirectories(tempDir.resolve("saves-" + suffix));
        SaveGameId id = SaveRepositoryTestSupport.id(100 + suffix);
        Path directory = Files.createDirectories(root.resolve(id.value()));
        Path current = directory.resolve("current.glsave");
        Path backup = directory.resolve("backup.glsave");
        Files.write(current, new byte[] {0x13, 0x37, (byte) suffix});
        var snapshot = SaveRepositoryTestSupport.snapshot(id, "Recover " + suffix, 100 + suffix);
        new com.gaia.save.archive.SaveArchiveWriter().write(
                backup,
                AtomicSaveStoreTestSupport.codec().encode(
                        snapshot, Instant.parse("2026-08-10T12:30:00Z")));
        assertEquals(
                SaveArchiveReadResult.Status.VALID,
                new SaveArchiveReader(AtomicSaveStoreTestSupport.codec()).read(backup).status());
        return new RecoveryFixture(root, id, current, backup, snapshot);
    }

    private record RecoveryFixture(
            Path root,
            SaveGameId id,
            Path current,
            Path backup,
            com.gaia.save.snapshot.SaveGameSnapshot snapshot) {}

    private record IdentitySwapFixture(
            Path root,
            SaveGameId expectedId,
            Path current,
            Path backup,
            Path wrongSource,
            byte[] corruptCurrent,
            byte[] correctBackup,
            byte[] wrongArchive) {
        private IdentitySwapFixture {
            corruptCurrent = corruptCurrent.clone();
            correctBackup = correctBackup.clone();
            wrongArchive = wrongArchive.clone();
        }

        @Override
        public byte[] corruptCurrent() {
            return corruptCurrent.clone();
        }

        @Override
        public byte[] correctBackup() {
            return correctBackup.clone();
        }

        @Override
        public byte[] wrongArchive() {
            return wrongArchive.clone();
        }

        private void installWrongBackup() {
            try {
                Files.copy(wrongSource, backup, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException failure) {
                throw new AssertionError(failure);
            }
        }
    }
}

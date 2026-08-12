package com.gaia.save.store;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.gaia.save.format.SaveGameId;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SaveDeleteTest {
    @TempDir
    Path tempDir;

    @Test
    void successfulDeleteMovesOnlyDirectIdDirectoryToRootLocalTrashBeforeCleanup()
            throws Exception {
        Path root = Files.createDirectories(tempDir.resolve("saves"));
        SaveGameId id = SaveRepositoryTestSupport.id(201);
        SaveRepositoryTestSupport.writeCurrent(
                root,
                id,
                "Delete me",
                201L,
                Instant.parse("2026-08-10T12:20:00Z"));
        Path worldDirectory = root.resolve(id.value());
        Path current = worldDirectory.resolve("current.glsave");
        byte[] currentBytes = Files.readAllBytes(current);
        Path outside = Files.createDirectories(tempDir.resolve("outside"));
        Path sentinel = outside.resolve("keep.txt");
        Files.writeString(sentinel, "keep");
        RepositoryRecordingFileOperations files = new RepositoryRecordingFileOperations();
        SaveRepository repository = SaveRepositoryTestSupport.repository(root, files);

        SaveDeleteResult first = repository.delete(id);
        SaveDeleteResult repeated = repository.delete(id);

        assertEquals(SaveDeleteResult.Status.SUCCESS, first.status());
        assertTrue(first.diagnostics().isEmpty());
        assertEquals(SaveDeleteResult.Status.NOT_FOUND, repeated.status());
        SaveRepositoryTestSupport.assertBoundedDiagnostics(repeated.diagnostics(), root);
        assertFalse(Files.exists(worldDirectory));
        assertTrue(SaveRepositoryTestSupport.catalog(root).summaries().isEmpty());
        assertEquals("keep", Files.readString(sentinel));

        RepositoryRecordingFileOperations.Move move = files.moves().get(0);
        assertEquals(worldDirectory.toAbsolutePath().normalize(), move.source());
        assertEquals(root.resolve(".trash").toAbsolutePath().normalize(),
                move.destination().getParent());
        assertFalse(move.destination().equals(root.toAbsolutePath().normalize()));
        assertTrue(files.deletes().stream().allMatch(path -> path.startsWith(move.destination())));
        assertTrue(files.deletes().stream().noneMatch(path -> path.equals(root)));
        assertTrue(files.deletes().stream().noneMatch(path -> path.startsWith(outside)));
        assertFalse(Files.exists(move.destination()),
                "successful bounded cleanup must remove the moved trash entry");
        assertFalse(Arrays.equals(currentBytes, Files.readAllBytes(sentinel)),
                "external data must not be replaced by save bytes");
    }

    @Test
    void nestedUnexpectedPathRejectsDeleteWithoutMovingAnything() throws Exception {
        Path root = Files.createDirectories(tempDir.resolve("saves"));
        SaveGameId id = SaveRepositoryTestSupport.id(202);
        SaveRepositoryTestSupport.writeCurrent(
                root,
                id,
                "Nested",
                202L,
                Instant.parse("2026-08-10T12:20:00Z"));
        Path worldDirectory = root.resolve(id.value());
        Path nested = Files.createDirectories(worldDirectory.resolve("unexpected"));
        Path nestedFile = nested.resolve("keep.txt");
        Files.writeString(nestedFile, "nested");
        RepositoryRecordingFileOperations files = new RepositoryRecordingFileOperations();

        SaveDeleteResult result = SaveRepositoryTestSupport.repository(root, files).delete(id);

        assertEquals(SaveDeleteResult.Status.UNSAFE_TARGET, result.status());
        assertTrue(files.moves().isEmpty());
        assertTrue(files.deletes().isEmpty());
        assertEquals("nested", Files.readString(nestedFile));
        SaveRepositoryTestSupport.assertBoundedDiagnostics(result.diagnostics(), root);
    }

    @Test
    void directSymlinkToNonDescendantIsRejectedAndExternalTreeIsUntouched()
            throws Exception {
        Path root = Files.createDirectories(tempDir.resolve("saves"));
        Path external = Files.createDirectories(tempDir.resolve("external"));
        Path sentinel = external.resolve("keep.txt");
        Files.writeString(sentinel, "outside");
        SaveGameId id = SaveRepositoryTestSupport.id(203);
        assumeTrue(SaveRepositoryTestSupport.tryCreateDirectoryLink(
                tempDir, root.resolve(id.value()), external),
                "symbolic directory links are not available on this test platform");
        RepositoryRecordingFileOperations files = new RepositoryRecordingFileOperations();

        SaveDeleteResult result = SaveRepositoryTestSupport.repository(root, files).delete(id);

        assertEquals(SaveDeleteResult.Status.UNSAFE_TARGET, result.status());
        assertTrue(files.moves().isEmpty());
        assertTrue(files.deletes().isEmpty());
        assertEquals("outside", Files.readString(sentinel));
        assertTrue(Files.isDirectory(external));
        SaveRepositoryTestSupport.assertBoundedDiagnostics(result.diagnostics(), root);
    }

    @Test
    void directSymlinkInsideWorldDirectoryRejectsDeleteWithoutFollowingIt()
            throws Exception {
        Path root = Files.createDirectories(tempDir.resolve("saves"));
        SaveGameId id = SaveRepositoryTestSupport.id(204);
        SaveRepositoryTestSupport.writeCurrent(
                root,
                id,
                "Linked child",
                204L,
                Instant.parse("2026-08-10T12:20:00Z"));
        Path external = Files.createDirectories(tempDir.resolve("external-child"));
        Path sentinel = external.resolve("keep.txt");
        Files.writeString(sentinel, "outside");
        Path link = root.resolve(id.value()).resolve("unexpected-link");
        assumeTrue(SaveRepositoryTestSupport.tryCreateDirectoryLink(
                        tempDir, link, external),
                "symbolic directory links are not available on this test platform");
        RepositoryRecordingFileOperations files = new RepositoryRecordingFileOperations();

        SaveDeleteResult result = SaveRepositoryTestSupport.repository(root, files).delete(id);

        assertEquals(SaveDeleteResult.Status.UNSAFE_TARGET, result.status());
        assertTrue(files.moves().isEmpty());
        assertTrue(files.deletes().isEmpty());
        assertEquals("outside", Files.readString(sentinel));
    }

    @Test
    void unknownIdAndRepeatedDeleteCannotTargetTheConfiguredRootItself() throws Exception {
        Path root = Files.createDirectories(tempDir.resolve("saves"));
        SaveGameId absent = SaveRepositoryTestSupport.id(205);
        RepositoryRecordingFileOperations files = new RepositoryRecordingFileOperations();
        SaveRepository repository = SaveRepositoryTestSupport.repository(root, files);

        SaveDeleteResult result = repository.delete(absent);

        assertEquals(SaveDeleteResult.Status.NOT_FOUND, result.status());
        assertTrue(Files.isDirectory(root));
        assertTrue(files.moves().isEmpty());
        assertTrue(files.deletes().isEmpty());
        SaveRepositoryTestSupport.assertBoundedDiagnostics(result.diagnostics(), root);
        for (Method method : SaveRepository.class.getMethods()) {
            if (method.getName().equals("delete")) {
                assertEquals(1, method.getParameterCount());
                assertEquals(SaveGameId.class, method.getParameterTypes()[0],
                        "public delete must accept identity, never an arbitrary Path");
            }
        }
    }

    @Test
    void moveToTrashFailureLeavesExactCatalogVisibleWorldUntouched() throws Exception {
        Path root = Files.createDirectories(tempDir.resolve("saves"));
        SaveGameId id = SaveRepositoryTestSupport.id(206);
        SaveRepositoryTestSupport.writeCurrent(
                root,
                id,
                "Move failure",
                206L,
                Instant.parse("2026-08-10T12:20:00Z"));
        Path current = root.resolve(id.value()).resolve("current.glsave");
        byte[] before = Files.readAllBytes(current);
        IOException injected = new IOException("injected trash move failure");
        RepositoryRecordingFileOperations files = new RepositoryRecordingFileOperations();
        files.failMove(injected);

        SaveDeleteResult result = SaveRepositoryTestSupport.repository(root, files).delete(id);

        assertEquals(SaveDeleteResult.Status.FAILURE, result.status());
        assertArrayEquals(before, Files.readAllBytes(current));
        assertFalse(SaveRepositoryTestSupport.catalog(root).summaries().isEmpty());
        assertEquals(injected, result.diagnostics().get(0).cause().orElseThrow());
        SaveRepositoryTestSupport.assertBoundedDiagnostics(result.diagnostics(), root);
    }

    @Test
    void trashCleanupFailureReportsWarningAfterRowHasSafelyDisappeared() throws Exception {
        Path root = Files.createDirectories(tempDir.resolve("saves"));
        SaveGameId id = SaveRepositoryTestSupport.id(207);
        SaveRepositoryTestSupport.writeCurrent(
                root,
                id,
                "Cleanup failure",
                207L,
                Instant.parse("2026-08-10T12:20:00Z"));
        IOException injected = new IOException("injected trash cleanup failure");
        RepositoryRecordingFileOperations files = new RepositoryRecordingFileOperations();
        files.failCleanup(injected);

        SaveDeleteResult result = SaveRepositoryTestSupport.repository(root, files).delete(id);

        assertEquals(SaveDeleteResult.Status.DELETED_WITH_CLEANUP_WARNING, result.status());
        assertFalse(Files.exists(root.resolve(id.value())));
        assertTrue(SaveRepositoryTestSupport.catalog(root).summaries().isEmpty());
        assertEquals(1, files.moves().size());
        assertTrue(Files.exists(files.moves().get(0).destination()),
                "failed cleanup keeps the confined trash entry for later diagnosis");
        assertEquals(injected, result.diagnostics().get(0).cause().orElseThrow());
        SaveRepositoryTestSupport.assertBoundedDiagnostics(result.diagnostics(), root);
    }
}

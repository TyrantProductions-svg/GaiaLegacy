package com.gaia.save.store;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gaia.save.archive.SaveArchiveReadResult;
import com.gaia.save.archive.SaveArchiveLimits;
import com.gaia.save.archive.SaveArchiveReader;
import com.gaia.save.archive.SaveArchiveWriter;
import com.gaia.save.codec.ChunkSectionCodec;
import com.gaia.save.codec.InventorySectionCodec;
import com.gaia.save.codec.PlayerSectionCodec;
import com.gaia.save.codec.SaveSnapshotCodec;
import com.gaia.save.codec.WorldItemsSectionCodec;
import com.gaia.save.codec.SaveCodecException;
import com.gaia.save.format.SaveSectionCodec;
import com.gaia.save.format.SaveSectionId;
import com.gaia.save.snapshot.InventorySaveSnapshot;
import com.gaia.save.snapshot.PlayerSaveSnapshot;
import com.gaia.save.snapshot.SaveGameSnapshot;
import com.gaia.save.snapshot.WorldItemsSaveSnapshot;
import com.overlord.voxel.ChunkRepositorySnapshot;
import com.overlord.worlditem.api.LogicalWorldItemSnapshot;
import com.overlord.assets.ResourceLocation;
import com.overlord.inventory.api.ItemStack;
import com.overlord.worlditem.api.WorldItemId;
import com.overlord.worlditem.api.WorldItemPhysicalState;
import com.overlord.worlditem.api.WorldItemRestoreEntry;
import com.overlord.worlditem.api.WorldItemRuntimeSnapshot;
import com.overlord.worlditem.api.WorldItemSnapshot;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class AtomicSaveStoreFaultInjectionTest {
    private static final Instant REPLACEMENT_MODIFIED =
            Instant.parse("2026-08-10T14:00:00Z");

    @TempDir Path tempDir;

    @ParameterizedTest
    @EnumSource(RequiredSection.class)
    void everyRequiredSectionEncodeFailurePreservesOldArchivesAndPublishesNoManifest(
            RequiredSection section) throws Exception {
        PreparedWorld world = AtomicSaveStoreTestSupport.preparedWorld(
                tempDir.resolve("encode-" + section.name().toLowerCase()));
        RecordingSaveFileOperations files = AtomicSaveStoreTestSupport.operations();
        IllegalStateException injected =
                new IllegalStateException("injected " + section + " encode failure");
        SaveSnapshotCodec faultingCodec = faultingCodec(section, injected);

        SaveWriteResult result = AtomicSaveStoreTestSupport.store(
                        world.directory(), files, faultingCodec)
                .save(replacement(), REPLACEMENT_MODIFIED);

        AtomicSaveStoreTestSupport.assertFailurePublishesNoCommittedManifest(
                result, world.directory());
        assertEquals(SaveWriteResult.Status.FAILED, result.status());
        assertEquals("save-write.section-encode-failed", result.diagnostics().get(0).code());
        assertTrue(files.calls().isEmpty());
        AtomicSaveStoreTestSupport.assertUnchangedOldSlots(world);
        AtomicSaveStoreTestSupport.assertNoTaskTemps(world.directory());
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(StoreFault.class)
    void everyFilesystemAndValidationFailureKeepsExactOldCurrentValidated(
            StoreFault fault) throws Exception {
        PreparedWorld world = AtomicSaveStoreTestSupport.preparedWorld(
                tempDir.resolve(fault.name().toLowerCase()));
        RecordingSaveFileOperations files = AtomicSaveStoreTestSupport.operations();
        fault.configure(files);

        SaveWriteResult result = AtomicSaveStoreTestSupport.store(world.directory(), files)
                .save(replacement(), REPLACEMENT_MODIFIED);

        AtomicSaveStoreTestSupport.assertFailurePublishesNoCommittedManifest(
                result, world.directory());
        assertEquals(SaveWriteResult.Status.FAILED, result.status());
        assertEquals(fault.diagnosticCode, result.diagnostics().get(0).code());
        AtomicSaveStoreTestSupport.assertOldCurrentSurvives(world);
        assertExpectedSlotLayout(fault.layout, world);
        if (fault == StoreFault.TEMP_CLEANUP) {
            assertTrue(Files.exists(files.createdTemps().get(0)));
            assertEquals(1, AtomicSaveStoreTestSupport.primaryFailure(result)
                    .getSuppressed().length);
        } else if (fault == StoreFault.NON_REGULAR_TEMP) {
            assertTrue(Files.isDirectory(
                    files.createdTemps().get(0), LinkOption.NOFOLLOW_LINKS));
        } else {
            AtomicSaveStoreTestSupport.assertNoTaskTemps(world.directory());
        }
    }

    @Test
    void fallbackBackupMustRereadValidBeforeReplacementCurrentCanBeExposed()
            throws Exception {
        PreparedWorld world = AtomicSaveStoreTestSupport.preparedWorld(
                tempDir.resolve("backup-reread-order"));
        RecordingSaveFileOperations files = AtomicSaveStoreTestSupport.operations();
        files.failBefore(
                FileOperation.MOVE_ATOMIC_BACKUP,
                AtomicSaveStoreTestSupport.unsupported("backup"));
        files.runAfter(FileOperation.COPY_BACKUP, (source, destination) -> Files.write(
                destination,
                "corrupt-backup".getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING));

        SaveWriteResult result = AtomicSaveStoreTestSupport.store(world.directory(), files)
                .save(replacement(), REPLACEMENT_MODIFIED);

        AtomicSaveStoreTestSupport.assertFailurePublishesNoCommittedManifest(
                result, world.directory());
        assertEquals("save-write.backup-validation-failed",
                result.diagnostics().get(0).code());
        assertFalse(files.calls().contains(FileOperation.MOVE_ATOMIC_CURRENT));
        assertFalse(files.calls().contains(FileOperation.MOVE_REPLACE_CURRENT));
        AtomicSaveStoreTestSupport.assertValidArchiveBytes(
                world.current(), world.oldCurrentBytes());
        AtomicSaveStoreTestSupport.assertValidArchiveBytes(
                world.backup(), world.oldBackupBytes());
    }

    @Test
    void atomicBackupInstallCorruptionBeforeRereadKeepsExactOldCurrentUntouched()
            throws Exception {
        PreparedWorld world = AtomicSaveStoreTestSupport.preparedWorld(
                tempDir.resolve("atomic-backup-reread"));
        RecordingSaveFileOperations files = AtomicSaveStoreTestSupport.operations();
        files.runAfter(FileOperation.MOVE_ATOMIC_BACKUP, (source, destination) ->
                Files.write(
                        destination,
                        "corrupt-atomic-backup".getBytes(StandardCharsets.UTF_8),
                        StandardOpenOption.WRITE,
                        StandardOpenOption.TRUNCATE_EXISTING));

        SaveWriteResult result = AtomicSaveStoreTestSupport.store(world.directory(), files)
                .save(replacement(), REPLACEMENT_MODIFIED);

        AtomicSaveStoreTestSupport.assertFailurePublishesNoCommittedManifest(
                result, world.directory());
        assertEquals("save-write.backup-validation-failed",
                result.diagnostics().get(0).code());
        AtomicSaveStoreTestSupport.assertValidArchiveBytes(
                world.current(), world.oldCurrentBytes());
        assertFalse(files.calls().contains(FileOperation.MOVE_ATOMIC_CURRENT));
        assertFalse(files.calls().contains(FileOperation.MOVE_REPLACE_CURRENT));
    }

    @Test
    void atomicCurrentRereadRejectsDifferentIndependentlyValidArchive()
            throws Exception {
        PreparedWorld world = AtomicSaveStoreTestSupport.preparedWorld(
                tempDir.resolve("current-manifest-mismatch"));
        Path otherArchive = tempDir.resolve("different-valid.glsave");
        SaveGameSnapshot different = AtomicSaveStoreTestSupport.snapshot(
                "Different Valid Save", 1919L, 190L);
        new SaveArchiveWriter().write(
                otherArchive,
                AtomicSaveStoreTestSupport.codec().encode(
                        different, Instant.parse("2026-08-10T15:00:00Z")));
        RecordingSaveFileOperations files = AtomicSaveStoreTestSupport.operations();
        files.runAfter(FileOperation.MOVE_ATOMIC_CURRENT, (source, destination) ->
                Files.copy(
                        otherArchive,
                        destination,
                        StandardCopyOption.REPLACE_EXISTING));

        SaveWriteResult result = AtomicSaveStoreTestSupport.store(world.directory(), files)
                .save(replacement(), REPLACEMENT_MODIFIED);

        AtomicSaveStoreTestSupport.assertFailurePublishesNoCommittedManifest(
                result, world.directory());
        assertEquals("save-write.current-manifest-mismatch",
                result.diagnostics().get(0).code());
        assertFalse(Files.exists(world.current()),
                "A rejected installed current must be removed or quarantined");
        AtomicSaveStoreTestSupport.assertValidArchiveBytes(
                world.backup(), world.oldCurrentBytes());
    }

    @Test
    void atomicCurrentCorruptionIsRejectedByNamedCurrentRereadBoundary()
            throws Exception {
        PreparedWorld world = AtomicSaveStoreTestSupport.preparedWorld(
                tempDir.resolve("atomic-current-reread"));
        RecordingSaveFileOperations files = AtomicSaveStoreTestSupport.operations();
        files.runAfter(FileOperation.MOVE_ATOMIC_CURRENT, (source, destination) ->
                Files.write(
                        destination,
                        "corrupt-current".getBytes(StandardCharsets.UTF_8),
                        StandardOpenOption.WRITE,
                        StandardOpenOption.TRUNCATE_EXISTING));

        SaveWriteResult result = AtomicSaveStoreTestSupport.store(world.directory(), files)
                .save(replacement(), REPLACEMENT_MODIFIED);

        AtomicSaveStoreTestSupport.assertFailurePublishesNoCommittedManifest(
                result, world.directory());
        assertEquals("save-write.current-validation-failed",
                result.diagnostics().get(0).code());
        assertFalse(Files.exists(world.current()),
                "A rejected installed current must be removed or quarantined");
        AtomicSaveStoreTestSupport.assertValidArchiveBytes(
                world.backup(), world.oldCurrentBytes());
    }

    @Test
    void firstSaveWrongValidInstalledCurrentIsRejectedWithoutCatalogValidSlot()
            throws Exception {
        Path worldDir = Files.createDirectories(tempDir.resolve("initial-wrong-valid"));
        Path otherArchive = tempDir.resolve("initial-different-valid.glsave");
        SaveGameSnapshot different = AtomicSaveStoreTestSupport.snapshot(
                "Initial Different Valid", 2020L, 202L);
        new SaveArchiveWriter().write(
                otherArchive,
                AtomicSaveStoreTestSupport.codec().encode(
                        different, Instant.parse("2026-08-10T15:10:00Z")));
        RecordingSaveFileOperations files = AtomicSaveStoreTestSupport.operations();
        files.runAfter(FileOperation.MOVE_ATOMIC_CURRENT, (source, destination) ->
                Files.copy(otherArchive, destination, StandardCopyOption.REPLACE_EXISTING));

        SaveWriteResult result = AtomicSaveStoreTestSupport.store(worldDir, files)
                .save(replacement(), REPLACEMENT_MODIFIED);

        AtomicSaveStoreTestSupport.assertFailurePublishesNoCommittedManifest(
                result, worldDir);
        assertEquals("save-write.current-manifest-mismatch",
                result.diagnostics().get(0).code());
        AtomicSaveStoreTestSupport.assertNoCatalogValidSlot(worldDir);
        assertFalse(files.deletedPaths().contains(files.createdTemps().get(0)));
    }

    @Test
    void firstSaveCorruptInstalledCurrentIsRejectedWithoutCatalogValidSlot()
            throws Exception {
        Path worldDir = Files.createDirectories(tempDir.resolve("initial-corrupt"));
        RecordingSaveFileOperations files = AtomicSaveStoreTestSupport.operations();
        files.runAfter(FileOperation.MOVE_ATOMIC_CURRENT, (source, destination) ->
                Files.write(
                        destination,
                        "initial-corrupt-current".getBytes(StandardCharsets.UTF_8),
                        StandardOpenOption.WRITE,
                        StandardOpenOption.TRUNCATE_EXISTING));

        SaveWriteResult result = AtomicSaveStoreTestSupport.store(worldDir, files)
                .save(replacement(), REPLACEMENT_MODIFIED);

        AtomicSaveStoreTestSupport.assertFailurePublishesNoCommittedManifest(
                result, worldDir);
        assertEquals("save-write.current-validation-failed",
                result.diagnostics().get(0).code());
        AtomicSaveStoreTestSupport.assertNoCatalogValidSlot(worldDir);
        assertFalse(files.deletedPaths().contains(files.createdTemps().get(0)));
    }

    @Test
    void installedCurrentRemediationDeleteFailureIsBlocking() throws Exception {
        PreparedWorld world = AtomicSaveStoreTestSupport.preparedWorld(
                tempDir.resolve("remediation-delete-failure"));
        Path otherArchive = tempDir.resolve("remediation-delete-wrong-valid.glsave");
        writeDifferentValidArchive(otherArchive, "Delete Remediation Other", 2121L, 211L);
        RecordingSaveFileOperations files = AtomicSaveStoreTestSupport.operations();
        files.runAfter(FileOperation.MOVE_ATOMIC_CURRENT, (source, destination) ->
                Files.copy(otherArchive, destination, StandardCopyOption.REPLACE_EXISTING));
        IOException deleteFailure = new IOException("injected remediation delete failure");
        files.failBefore(FileOperation.DELETE_TEMP, deleteFailure);

        SaveWriteResult result = AtomicSaveStoreTestSupport.store(world.directory(), files)
                .save(replacement(), REPLACEMENT_MODIFIED);

        AtomicSaveStoreTestSupport.assertFailurePublishesNoCommittedManifest(
                result, world.directory());
        assertEquals(SaveWriteResult.Status.BLOCKING_FAILURE, result.status());
        assertEquals("save-write.current-remediation-failed",
                result.diagnostics().get(0).code());
        String message = result.diagnostics().get(0).message().toLowerCase(Locale.ROOT);
        assertTrue(message.contains("remediation"));
        assertTrue(message.contains("ownership"));
        assertFalse(message.contains("cleanup"));
        Throwable primary = AtomicSaveStoreTestSupport.primaryFailure(result);
        assertNotSame(deleteFailure, primary);
        assertArrayEquals(new Throwable[] {deleteFailure}, primary.getSuppressed());
        AtomicSaveStoreTestSupport.assertValidArchiveBytes(
                world.backup(), world.oldCurrentBytes());
        assertFalse(files.deletedPaths().contains(files.createdTemps().get(0)));
    }

    @Test
    void installedCurrentRemediationDirectoryForceFailureIsBlocking()
            throws Exception {
        PreparedWorld world = AtomicSaveStoreTestSupport.preparedWorld(
                tempDir.resolve("remediation-directory-force-failure"));
        Path otherArchive = tempDir.resolve("remediation-force-wrong-valid.glsave");
        writeDifferentValidArchive(otherArchive, "Force Remediation Other", 2222L, 222L);
        RecordingSaveFileOperations files = AtomicSaveStoreTestSupport.operations();
        files.runAfter(FileOperation.MOVE_ATOMIC_CURRENT, (source, destination) ->
                Files.copy(otherArchive, destination, StandardCopyOption.REPLACE_EXISTING));
        IOException forceFailure = new IOException("injected remediation directory force");
        files.failBeforeOccurrence(FileOperation.FORCE_DIRECTORY, 2, forceFailure);

        SaveWriteResult result = AtomicSaveStoreTestSupport.store(world.directory(), files)
                .save(replacement(), REPLACEMENT_MODIFIED);

        AtomicSaveStoreTestSupport.assertFailurePublishesNoCommittedManifest(
                result, world.directory());
        assertEquals(SaveWriteResult.Status.BLOCKING_FAILURE, result.status());
        assertEquals("save-write.current-remediation-failed",
                result.diagnostics().get(0).code());
        String message = result.diagnostics().get(0).message().toLowerCase(Locale.ROOT);
        assertTrue(message.contains("remediation"));
        assertTrue(message.contains("ownership"));
        assertFalse(message.contains("cleanup"));
        Throwable primary = AtomicSaveStoreTestSupport.primaryFailure(result);
        assertNotSame(forceFailure, primary);
        assertArrayEquals(new Throwable[] {forceFailure}, primary.getSuppressed());
        assertFalse(Files.exists(world.current()));
        AtomicSaveStoreTestSupport.assertValidArchiveBytes(
                world.backup(), world.oldCurrentBytes());
        assertFalse(files.deletedPaths().contains(files.createdTemps().get(0)));
    }

    @Test
    void fallbackBackupDirectoryForceFailureOccursBeforeAnyCurrentMove()
            throws Exception {
        PreparedWorld world = AtomicSaveStoreTestSupport.preparedWorld(
                tempDir.resolve("fallback-directory-force-order"));
        RecordingSaveFileOperations files = AtomicSaveStoreTestSupport.operations();
        files.failBefore(
                FileOperation.MOVE_ATOMIC_BACKUP,
                AtomicSaveStoreTestSupport.unsupported("backup"));
        IOException forceFailure = new IOException("injected pre-current directory force");
        files.failBefore(FileOperation.FORCE_DIRECTORY, forceFailure);

        SaveWriteResult result = AtomicSaveStoreTestSupport.store(world.directory(), files)
                .save(replacement(), REPLACEMENT_MODIFIED);

        AtomicSaveStoreTestSupport.assertFailurePublishesNoCommittedManifest(
                result, world.directory());
        assertSame(forceFailure, AtomicSaveStoreTestSupport.primaryFailure(result));
        assertFalse(files.calls().contains(FileOperation.MOVE_ATOMIC_CURRENT));
        assertFalse(files.calls().contains(FileOperation.MOVE_REPLACE_CURRENT));
        AtomicSaveStoreTestSupport.assertValidArchiveBytes(
                world.current(), world.oldCurrentBytes());
        AtomicSaveStoreTestSupport.assertValidArchiveBytes(
                world.backup(), world.oldCurrentBytes());
    }

    @Test
    void nonSiblingTempFromFilesystemSeamIsRejectedWithoutExternalWriteOrDelete()
            throws Exception {
        Path worldDir = Files.createDirectories(tempDir.resolve("world"));
        Path externalDir = Files.createDirectories(tempDir.resolve("external-temp-owner"));
        Path externalTemp = externalDir.resolve("sentinel.tmp");
        byte[] sentinel = "external sentinel must survive".getBytes(StandardCharsets.UTF_8);
        Files.write(externalTemp, sentinel);
        RecordingSaveFileOperations files = AtomicSaveStoreTestSupport.operations();
        files.returnTemp(externalTemp);

        SaveWriteResult result = AtomicSaveStoreTestSupport.store(worldDir, files)
                .save(replacement(), REPLACEMENT_MODIFIED);

        AtomicSaveStoreTestSupport.assertFailurePublishesNoCommittedManifest(result, worldDir);
        assertEquals("save-write.unsafe-temp-path", result.diagnostics().get(0).code());
        assertArrayEquals(sentinel, Files.readAllBytes(externalTemp));
        assertFalse(files.calls().contains(FileOperation.DELETE_TEMP));
        AtomicSaveStoreTestSupport.assertNoCatalogValidSlot(worldDir);
    }

    @Test
    void saveRejectsWorldDirectorySwapDuringTempCreationBeforeExternalMutation()
            throws Exception {
        Path saveRoot = Files.createDirectories(tempDir.resolve("swap-save-root"));
        Path worldDir = saveRoot.resolve(
                AtomicSaveStoreTestSupport.saveGameId().value());
        PreparedWorld world = AtomicSaveStoreTestSupport.preparedWorld(worldDir);
        Path anchoredWorld = saveRoot.resolve("anchored-original-world");
        Path externalWorld = Files.createDirectories(tempDir.resolve("swap-external-world"));
        Path probeLink = tempDir.resolve("swap-link-probe");
        createDirectoryLinkOrSkip(probeLink, externalWorld);
        boolean symbolicLinks = Files.isSymbolicLink(probeLink);
        Files.delete(probeLink);
        byte[] sentinel = "external temp sentinel".getBytes(StandardCharsets.UTF_8);
        RecordingSaveFileOperations files = AtomicSaveStoreTestSupport.operations();
        files.runAfter(FileOperation.CREATE_TEMP, (temporary, ignored) -> {
            Files.move(worldDir, anchoredWorld);
            if (symbolicLinks) {
                Files.createSymbolicLink(worldDir, externalWorld.toAbsolutePath());
            } else {
                createJunction(worldDir, externalWorld);
            }
            Files.write(externalWorld.resolve(temporary.getFileName()), sentinel);
        });
        AtomicSaveStore store = AtomicSaveStoreTestSupport.publicStore(
                saveRoot, AtomicSaveStoreTestSupport.saveGameId(), files);

        SaveWriteResult result = store.save(replacement(), REPLACEMENT_MODIFIED);

        AtomicSaveStoreTestSupport.assertFailurePublishesNoCommittedManifest(
                result, worldDir);
        Path externalTemp = externalWorld.resolve(
                files.createdTemps().get(0).getFileName());
        assertArrayEquals(sentinel, Files.readAllBytes(externalTemp));
        assertFalse(Files.exists(externalWorld.resolve("current.glsave")));
        assertFalse(Files.exists(externalWorld.resolve("backup.glsave")));
        assertEquals(List.of(FileOperation.CREATE_TEMP), files.calls());
        assertFalse(files.deletedPaths().contains(files.createdTemps().get(0)));
        AtomicSaveStoreTestSupport.assertValidArchiveBytes(
                anchoredWorld.resolve("current.glsave"), world.oldCurrentBytes());
        AtomicSaveStoreTestSupport.assertValidArchiveBytes(
                anchoredWorld.resolve("backup.glsave"), world.oldBackupBytes());
    }

    @Test
    void copyBackupRevalidatesAnchoredIdentityInsideFilesystemOperation()
            throws Exception {
        Path saveRoot = Files.createDirectories(tempDir.resolve("copy-swap-save-root"));
        Path worldDir = saveRoot.resolve(AtomicSaveStoreTestSupport.saveGameId().value());
        PreparedWorld world = AtomicSaveStoreTestSupport.preparedWorld(worldDir);
        Path anchoredWorld = saveRoot.resolve("copy-anchored-original-world");
        Path externalWorld = Files.createDirectories(tempDir.resolve("copy-swap-external"));
        boolean symbolicLinks = linkModeFor(externalWorld, "copy-swap-probe");
        byte[] externalSourceBytes = "external current sentinel".getBytes(
                StandardCharsets.UTF_8);
        byte[] externalDestinationBytes = "external backup temp sentinel".getBytes(
                StandardCharsets.UTF_8);
        Path[] externalDestination = new Path[1];
        RecordingSaveFileOperations files = AtomicSaveStoreTestSupport.operations();
        files.runBefore(FileOperation.COPY_BACKUP, (source, destination) -> {
            Files.move(worldDir, anchoredWorld);
            createDirectoryLink(worldDir, externalWorld, symbolicLinks);
            Files.write(externalWorld.resolve(source.getFileName()), externalSourceBytes);
            externalDestination[0] = externalWorld.resolve(destination.getFileName());
            Files.write(externalDestination[0], externalDestinationBytes);
        });
        AtomicSaveStore store = AtomicSaveStoreTestSupport.publicStore(
                saveRoot, AtomicSaveStoreTestSupport.saveGameId(), files);

        SaveWriteResult result = store.save(replacement(), REPLACEMENT_MODIFIED);

        AtomicSaveStoreTestSupport.assertFailurePublishesNoCommittedManifest(
                result, worldDir);
        assertArrayEquals(externalSourceBytes,
                Files.readAllBytes(externalWorld.resolve("current.glsave")));
        assertArrayEquals(externalDestinationBytes,
                Files.readAllBytes(externalDestination[0]));
        assertFalse(Files.exists(externalWorld.resolve("backup.glsave")));
        assertTrue(files.deletedPaths().stream().noneMatch(
                path -> path.startsWith(externalWorld.toAbsolutePath().normalize())));
        AtomicSaveStoreTestSupport.assertValidArchiveBytes(
                anchoredWorld.resolve("current.glsave"), world.oldCurrentBytes());
        AtomicSaveStoreTestSupport.assertValidArchiveBytes(
                anchoredWorld.resolve("backup.glsave"), world.oldBackupBytes());
    }

    @Test
    void currentAtomicMoveRevalidatesAnchoredIdentityInsideFilesystemOperation()
            throws Exception {
        Path saveRoot = Files.createDirectories(tempDir.resolve("move-swap-save-root"));
        Path worldDir = saveRoot.resolve(AtomicSaveStoreTestSupport.saveGameId().value());
        PreparedWorld world = AtomicSaveStoreTestSupport.preparedWorld(worldDir);
        Path anchoredWorld = saveRoot.resolve("move-anchored-original-world");
        Path externalWorld = Files.createDirectories(tempDir.resolve("move-swap-external"));
        boolean symbolicLinks = linkModeFor(externalWorld, "move-swap-probe");
        byte[] externalSourceBytes = "external current temp sentinel".getBytes(
                StandardCharsets.UTF_8);
        byte[] externalDestinationBytes = "external current slot sentinel".getBytes(
                StandardCharsets.UTF_8);
        Path[] externalSource = new Path[1];
        RecordingSaveFileOperations files = AtomicSaveStoreTestSupport.operations();
        files.runBefore(FileOperation.MOVE_ATOMIC_CURRENT, (source, destination) -> {
            Files.move(worldDir, anchoredWorld);
            createDirectoryLink(worldDir, externalWorld, symbolicLinks);
            externalSource[0] = externalWorld.resolve(source.getFileName());
            Files.write(externalSource[0], externalSourceBytes);
            Files.write(
                    externalWorld.resolve(destination.getFileName()),
                    externalDestinationBytes);
        });
        AtomicSaveStore store = AtomicSaveStoreTestSupport.publicStore(
                saveRoot, AtomicSaveStoreTestSupport.saveGameId(), files);

        SaveWriteResult result = store.save(replacement(), REPLACEMENT_MODIFIED);

        AtomicSaveStoreTestSupport.assertFailurePublishesNoCommittedManifest(
                result, worldDir);
        assertTrue(Files.exists(externalSource[0]),
                "The guarded move must not consume the external matching source");
        assertArrayEquals(externalSourceBytes, Files.readAllBytes(externalSource[0]));
        assertArrayEquals(externalDestinationBytes,
                Files.readAllBytes(externalWorld.resolve("current.glsave")));
        assertFalse(Files.exists(externalWorld.resolve("backup.glsave")));
        assertTrue(files.deletedPaths().stream().noneMatch(
                path -> path.startsWith(externalWorld.toAbsolutePath().normalize())));
        AtomicSaveStoreTestSupport.assertValidArchiveBytes(
                anchoredWorld.resolve("current.glsave"), world.oldCurrentBytes());
        AtomicSaveStoreTestSupport.assertValidArchiveBytes(
                anchoredWorld.resolve("backup.glsave"), world.oldCurrentBytes());
    }

    @Test
    void filesystemMutationSeamCarriesInOperationAnchoredIdentityValidator() {
        assertTrue(hasGuardedMutationOverload("copyReplacing"),
                "copyReplacing requires an in-operation identity validator");
        assertTrue(hasGuardedMutationOverload("moveAtomicReplacing"),
                "moveAtomicReplacing requires an in-operation identity validator");
    }

    @Test
    void newlyCreatedWorldRequiresConfiguredRootForceBeforeCurrentExposure()
            throws Exception {
        Path saveRoot = Files.createDirectories(tempDir.resolve("new-world-root-force"));
        RecordingSaveFileOperations files = AtomicSaveStoreTestSupport.operations();
        IOException rootForceFailure = new IOException("injected configured root force");
        files.failBeforeOccurrence(FileOperation.FORCE_DIRECTORY, 1, rootForceFailure);
        AtomicSaveStore store = AtomicSaveStoreTestSupport.publicStore(
                saveRoot, AtomicSaveStoreTestSupport.saveGameId(), files);
        Path worldDir = saveRoot.resolve(AtomicSaveStoreTestSupport.saveGameId().value());

        SaveWriteResult result = store.save(replacement(), REPLACEMENT_MODIFIED);

        AtomicSaveStoreTestSupport.assertFailurePublishesNoCommittedManifest(
                result, worldDir);
        assertSame(rootForceFailure, AtomicSaveStoreTestSupport.primaryFailure(result));
        assertEquals(saveRoot.toAbsolutePath().normalize(), files.forcedDirectories().get(0));
        assertFalse(files.calls().contains(FileOperation.MOVE_ATOMIC_CURRENT));
        assertFalse(files.calls().contains(FileOperation.MOVE_REPLACE_CURRENT));
        AtomicSaveStoreTestSupport.assertNoCatalogValidSlot(worldDir);
    }

    @Test
    void regularOwnedTempWriterBoundFailureUsesTempWriteDiagnosticAndCleans()
            throws Exception {
        PreparedWorld world = AtomicSaveStoreTestSupport.preparedWorld(
                tempDir.resolve("regular-writer-bound-failure"));
        RecordingSaveFileOperations files = AtomicSaveStoreTestSupport.operations();
        SaveSnapshotCodec oversizedCodec = oversizedPlayerCodec();

        SaveWriteResult result = AtomicSaveStoreTestSupport.store(
                        world.directory(), files, oversizedCodec)
                .save(replacement(), REPLACEMENT_MODIFIED);

        AtomicSaveStoreTestSupport.assertFailurePublishesNoCommittedManifest(
                result, world.directory());
        assertEquals("save-write.temp-write-failed", result.diagnostics().get(0).code());
        assertEquals(1, files.createdTemps().size());
        assertEquals(world.directory().toAbsolutePath().normalize(),
                files.createdTemps().get(0).toAbsolutePath().normalize().getParent());
        AtomicSaveStoreTestSupport.assertUnchangedOldSlots(world);
        AtomicSaveStoreTestSupport.assertNoTaskTemps(world.directory());
        assertTrue(files.deletedPaths().contains(files.createdTemps().get(0)));
    }

    @Test
    void secondDirectoryForceFailureOnInitialSaveRemovesInstalledCurrent()
            throws Exception {
        Path saveRoot = Files.createDirectories(tempDir.resolve("initial-second-force"));
        RecordingSaveFileOperations files = AtomicSaveStoreTestSupport.operations();
        IOException forceFailure = new IOException("injected initial second force");
        files.failBeforeOccurrence(FileOperation.FORCE_DIRECTORY, 2, forceFailure);
        AtomicSaveStore store = AtomicSaveStoreTestSupport.publicStore(
                saveRoot, AtomicSaveStoreTestSupport.saveGameId(), files);
        Path worldDir = saveRoot.resolve(AtomicSaveStoreTestSupport.saveGameId().value());

        SaveWriteResult result = store.save(replacement(), REPLACEMENT_MODIFIED);

        AtomicSaveStoreTestSupport.assertFailurePublishesNoCommittedManifest(
                result, worldDir);
        assertSame(forceFailure, AtomicSaveStoreTestSupport.primaryFailure(result));
        AtomicSaveStoreTestSupport.assertNoCatalogValidSlot(worldDir);
        assertFalse(files.deletedPaths().contains(files.createdTemps().get(0)));
    }

    @Test
    void secondDirectoryForceFailureOnRepeatedSaveLeavesExactBackupPreferred()
            throws Exception {
        PreparedWorld world = AtomicSaveStoreTestSupport.preparedWorld(
                tempDir.resolve("repeated-second-force"));
        RecordingSaveFileOperations files = AtomicSaveStoreTestSupport.operations();
        IOException forceFailure = new IOException("injected repeated second force");
        files.failBeforeOccurrence(FileOperation.FORCE_DIRECTORY, 2, forceFailure);

        SaveWriteResult result = AtomicSaveStoreTestSupport.store(world.directory(), files)
                .save(replacement(), REPLACEMENT_MODIFIED);

        AtomicSaveStoreTestSupport.assertFailurePublishesNoCommittedManifest(
                result, world.directory());
        assertSame(forceFailure, AtomicSaveStoreTestSupport.primaryFailure(result));
        assertFalse(Files.exists(world.current()));
        AtomicSaveStoreTestSupport.assertValidArchiveBytes(
                world.backup(), world.oldCurrentBytes());
        assertFalse(files.deletedPaths().contains(files.createdTemps().get(0)));
    }

    @Test
    void publicBoundaryRejectsSaveRootLinkWithoutExternalMutation() throws Exception {
        Path externalRoot = Files.createDirectories(tempDir.resolve("external-root"));
        Path linkedRoot = tempDir.resolve("linked-save-root");
        createDirectoryLinkOrSkip(linkedRoot, externalRoot);
        Path sentinel = externalRoot.resolve("sentinel.txt");
        byte[] expected = "root sentinel".getBytes(StandardCharsets.UTF_8);
        Files.write(sentinel, expected);

        assertThrows(
                IllegalArgumentException.class,
                () -> AtomicSaveStoreTestSupport.publicStore(
                        linkedRoot,
                        AtomicSaveStoreTestSupport.saveGameId(),
                        AtomicSaveStoreTestSupport.operations()));

        assertArrayEquals(expected, Files.readAllBytes(sentinel));
        assertFalse(Files.exists(externalRoot
                .resolve(AtomicSaveStoreTestSupport.saveGameId().value())
                .resolve("current.glsave")));
    }

    @Test
    void publicBoundaryRejectsLinkedFinalWorldDirectoryWithoutExternalMutation()
            throws Exception {
        Path saveRoot = Files.createDirectories(tempDir.resolve("direct-save-root"));
        Path externalWorld = Files.createDirectories(tempDir.resolve("external-world"));
        Path linkedWorld = saveRoot.resolve(
                AtomicSaveStoreTestSupport.saveGameId().value());
        createDirectoryLinkOrSkip(linkedWorld, externalWorld);
        Path sentinel = externalWorld.resolve("sentinel.txt");
        byte[] expected = "world sentinel".getBytes(StandardCharsets.UTF_8);
        Files.write(sentinel, expected);

        assertThrows(
                IllegalArgumentException.class,
                () -> AtomicSaveStoreTestSupport.publicStore(
                        saveRoot,
                        AtomicSaveStoreTestSupport.saveGameId(),
                        AtomicSaveStoreTestSupport.operations()));

        assertArrayEquals(expected, Files.readAllBytes(sentinel));
        assertFalse(Files.exists(externalWorld.resolve("current.glsave")));
        assertFalse(Files.exists(externalWorld.resolve("backup.glsave")));
    }

    @Test
    void corruptRereadWithoutCauseMaterializesPrimaryBeforeCleanupSuppression()
            throws Exception {
        PreparedWorld world = AtomicSaveStoreTestSupport.preparedWorld(
                tempDir.resolve("validation-primary"));
        RecordingSaveFileOperations files = AtomicSaveStoreTestSupport.operations();
        files.runAfter(FileOperation.FORCE_TEMP, (temporary, ignored) -> Files.write(
                temporary,
                "corrupt-without-cause".getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING));
        IOException cleanup = new IOException("injected cleanup after validation");
        files.failBefore(FileOperation.DELETE_TEMP, cleanup);

        SaveWriteResult result = AtomicSaveStoreTestSupport.store(world.directory(), files)
                .save(replacement(), REPLACEMENT_MODIFIED);

        AtomicSaveStoreTestSupport.assertFailurePublishesNoCommittedManifest(
                result, world.directory());
        Throwable primary = AtomicSaveStoreTestSupport.primaryFailure(result);
        assertNotSame(cleanup, primary);
        assertArrayEquals(new Throwable[] {cleanup}, primary.getSuppressed());
        AtomicSaveStoreTestSupport.assertUnchangedOldSlots(world);
    }

    @Test
    void unsupportedDirectoryForceCapabilityIsToleratedThroughInjectableSeam()
            throws Exception {
        JdkSaveFileOperations files = jdkFilesWithDirectoryForcer(
                ignored -> {
                    throw new UnsupportedOperationException(
                            "injected unsupported directory force");
                });

        assertDoesNotThrow(() -> files.forceDirectoryBestEffort(tempDir, () -> {}));
    }

    @Test
    void pagedWorldItemsAreRejectedByV1BeforeAnyFilesystemMutation()
            throws Exception {
        PreparedWorld world = AtomicSaveStoreTestSupport.preparedWorld(
                tempDir.resolve("paged-v1-rejected"));
        RecordingSaveFileOperations files = AtomicSaveStoreTestSupport.operations();
        SaveGameSnapshot source = replacement();
        WorldItemsSaveSnapshot paged = new WorldItemsSaveSnapshot(
                source.fixedTick(),
                source.worldItems().entries(),
                source.worldItems().nextItemId(),
                source.worldItems().itemIdsExhausted(),
                LogicalWorldItemSnapshot.Completeness.PAGED_PARTIAL);
        SaveGameSnapshot lossy = new SaveGameSnapshot(
                source.metadata(), source.fixedTick(), source.chunks(),
                source.player(), source.inventory(), paged);

        SaveWriteResult result = AtomicSaveStoreTestSupport.store(world.directory(), files)
                .save(lossy, REPLACEMENT_MODIFIED);

        AtomicSaveStoreTestSupport.assertFailurePublishesNoCommittedManifest(
                result, world.directory());
        assertEquals("save-write.section-encode-failed",
                result.diagnostics().get(0).code());
        SaveCodecException primary = (SaveCodecException)
                AtomicSaveStoreTestSupport.primaryFailure(result);
        assertEquals("world-items-v1.paged-state-unsupported", primary.code());
        assertTrue(files.calls().isEmpty());
        AtomicSaveStoreTestSupport.assertUnchangedOldSlots(world);
        AtomicSaveStoreTestSupport.assertNoTaskTemps(world.directory());
    }

    @Test
    void expiryMismatchIsRejectedByV1BeforeAnyFilesystemMutation()
            throws Exception {
        PreparedWorld world = AtomicSaveStoreTestSupport.preparedWorld(
                tempDir.resolve("expiry-v1-rejected"));
        RecordingSaveFileOperations files = AtomicSaveStoreTestSupport.operations();
        SaveGameSnapshot source = replacement();
        WorldItemRestoreEntry entry = new WorldItemRestoreEntry(
                new WorldItemRuntimeSnapshot(
                        new WorldItemSnapshot(
                                new WorldItemId(0L),
                                new ItemStack(ResourceLocation.of("gaia", "test/drop"), 1),
                                0.5, 4.0, 0.5, 0.0, 0.0, 0.0, 1L),
                        java.util.Optional.empty(),
                        source.fixedTick(),
                        source.fixedTick(),
                        source.fixedTick()
                                + WorldItemRuntimeSnapshot.WORLD_ITEM_TTL_TICKS
                                + 1L),
                WorldItemPhysicalState.ACTIVE);
        WorldItemsSaveSnapshot mismatch = new WorldItemsSaveSnapshot(
                source.fixedTick(),
                List.of(entry),
                1L,
                false,
                LogicalWorldItemSnapshot.Completeness.LEGACY_COMPLETE);
        SaveGameSnapshot lossy = new SaveGameSnapshot(
                source.metadata(), source.fixedTick(), source.chunks(),
                source.player(), source.inventory(), mismatch);

        SaveWriteResult result = AtomicSaveStoreTestSupport.store(world.directory(), files)
                .save(lossy, REPLACEMENT_MODIFIED);

        assertEquals(SaveWriteResult.Status.FAILED, result.status());
        assertEquals("save-write.section-encode-failed",
                result.diagnostics().get(0).code());
        SaveCodecException primary = (SaveCodecException)
                AtomicSaveStoreTestSupport.primaryFailure(result);
        assertEquals("world-items-v1.expiry-mismatch", primary.code());
        assertTrue(files.calls().isEmpty());
        AtomicSaveStoreTestSupport.assertUnchangedOldSlots(world);
    }

    @Test
    void wrappedUnsupportedDirectoryForceCapabilityIsAlsoTolerated()
            throws Exception {
        JdkSaveFileOperations files = jdkFilesWithDirectoryForcer(
                ignored -> {
                    throw new IOException(
                            "provider wrapper",
                            new UnsupportedOperationException(
                                    "injected unsupported directory force"));
                });

        assertDoesNotThrow(() -> files.forceDirectoryBestEffort(tempDir, () -> {}));
    }

    @Test
    void genuineDirectoryAccessDeniedIsPropagatedThroughInjectableSeam()
            throws Exception {
        AccessDeniedException denied = new AccessDeniedException(
                "directory", null, "injected genuine access denial");
        JdkSaveFileOperations files = jdkFilesWithDirectoryForcer(
                ignored -> {
                    throw denied;
                });

        assertSame(
                denied,
                assertThrows(
                        AccessDeniedException.class,
                        () -> files.forceDirectoryBestEffort(tempDir, () -> {})));
    }

    @Test
    void failedInitialFallbackLeavesNoCatalogValidSlotAndCleansItsOwnedTemp()
            throws Exception {
        Path worldDir = Files.createDirectories(tempDir.resolve("failed-initial"));
        RecordingSaveFileOperations files = AtomicSaveStoreTestSupport.operations();
        files.failBefore(
                FileOperation.MOVE_ATOMIC_CURRENT,
                AtomicSaveStoreTestSupport.unsupported("current"));
        IOException replaceFailure = new IOException("injected fallback replace failure");
        files.failBefore(FileOperation.MOVE_REPLACE_CURRENT, replaceFailure);

        SaveWriteResult result = AtomicSaveStoreTestSupport.store(worldDir, files)
                .save(replacement(), REPLACEMENT_MODIFIED);

        AtomicSaveStoreTestSupport.assertFailurePublishesNoCommittedManifest(
                result, worldDir);
        assertEquals(SaveWriteResult.Status.FAILED, result.status());
        assertEquals("save-write.current-replace-failed", result.diagnostics().get(0).code());
        assertSame(replaceFailure, AtomicSaveStoreTestSupport.primaryFailure(result));
        AtomicSaveStoreTestSupport.assertNoCatalogValidSlot(worldDir);
        AtomicSaveStoreTestSupport.assertNoTaskTemps(worldDir);
        assertTrue(files.calls().contains(FileOperation.DELETE_TEMP));
    }

    @Test
    void distinctCleanupThrowableIsSuppressedUnderTheExactPrimaryThrowable()
            throws Exception {
        PreparedWorld world = AtomicSaveStoreTestSupport.preparedWorld(
                tempDir.resolve("suppressed-cleanup"));
        RecordingSaveFileOperations files = AtomicSaveStoreTestSupport.operations();
        IllegalStateException primary =
                new IllegalStateException("injected primary force failure");
        IOException cleanup = new IOException("injected cleanup failure");
        files.failBefore(FileOperation.FORCE_TEMP, primary);
        files.failBefore(FileOperation.DELETE_TEMP, cleanup);

        SaveWriteResult result = AtomicSaveStoreTestSupport.store(world.directory(), files)
                .save(replacement(), REPLACEMENT_MODIFIED);

        assertEquals(SaveWriteResult.Status.FAILED, result.status());
        Throwable retained = AtomicSaveStoreTestSupport.primaryFailure(result);
        assertSame(primary, retained);
        assertArrayEquals(new Throwable[] {cleanup}, retained.getSuppressed());
        AtomicSaveStoreTestSupport.assertUnchangedOldSlots(world);
    }

    @Test
    void selfIdenticalCleanupThrowableIsNotAddedAsItsOwnSuppressedFailure()
            throws Exception {
        PreparedWorld world = AtomicSaveStoreTestSupport.preparedWorld(
                tempDir.resolve("self-suppression"));
        RecordingSaveFileOperations files = AtomicSaveStoreTestSupport.operations();
        IllegalStateException same =
                new IllegalStateException("same injected failure instance");
        files.failBefore(FileOperation.FORCE_TEMP, same);
        files.failBefore(FileOperation.DELETE_TEMP, same);

        SaveWriteResult result = AtomicSaveStoreTestSupport.store(world.directory(), files)
                .save(replacement(), REPLACEMENT_MODIFIED);

        assertEquals(SaveWriteResult.Status.FAILED, result.status());
        Throwable retained = AtomicSaveStoreTestSupport.primaryFailure(result);
        assertSame(same, retained);
        assertEquals(0, retained.getSuppressed().length);
        AtomicSaveStoreTestSupport.assertUnchangedOldSlots(world);
    }

    @Test
    void cleanupFailureWithoutAnyKnownGoodSlotIsBlockingAndPublishesNoManifest()
            throws Exception {
        Path worldDir = Files.createDirectories(tempDir.resolve("uncertain-ownership"));
        RecordingSaveFileOperations files = AtomicSaveStoreTestSupport.operations();
        IOException primary = new IOException("injected force failure");
        IOException cleanup = new IOException("injected cleanup ownership failure");
        files.failBefore(FileOperation.FORCE_TEMP, primary);
        files.failBefore(FileOperation.DELETE_TEMP, cleanup);

        SaveWriteResult result = AtomicSaveStoreTestSupport.store(worldDir, files)
                .save(replacement(), REPLACEMENT_MODIFIED);

        AtomicSaveStoreTestSupport.assertFailurePublishesNoCommittedManifest(
                result, worldDir);
        assertEquals(SaveWriteResult.Status.BLOCKING_FAILURE, result.status());
        assertEquals("save-write.temp-cleanup-ownership-uncertain",
                result.diagnostics().get(0).code());
        String message = result.diagnostics().get(0).message().toLowerCase(Locale.ROOT);
        assertTrue(message.contains("cleanup"));
        assertTrue(message.contains("ownership"));
        assertFalse(message.contains("remediation"));
        assertSame(primary, AtomicSaveStoreTestSupport.primaryFailure(result));
        assertArrayEquals(new Throwable[] {cleanup}, primary.getSuppressed());
        AtomicSaveStoreTestSupport.assertNoCatalogValidSlot(worldDir);
        assertTrue(Files.exists(files.createdTemps().get(0)));
    }

    private static void createDirectoryLinkOrSkip(Path link, Path target)
            throws Exception {
        try {
            Files.createSymbolicLink(link, target.toAbsolutePath());
            return;
        } catch (UnsupportedOperationException | IOException unsupported) {
            if (!System.getProperty("os.name", "")
                    .toLowerCase(java.util.Locale.ROOT)
                    .contains("win")) {
                Assumptions.assumeTrue(false,
                        "directory symbolic links are unavailable on this filesystem");
                return;
            }
        } catch (SecurityException denied) {
            Assumptions.assumeTrue(false,
                    "directory symbolic links are denied by the test environment");
            return;
        }

        try {
            createJunction(link, target);
        } catch (IOException unavailable) {
            Assumptions.assumeTrue(false,
                    () -> "directory link fixture unavailable: "
                            + unavailable.getMessage());
        }
    }

    private boolean linkModeFor(Path target, String probeName) throws Exception {
        Path probe = tempDir.resolve(probeName);
        createDirectoryLinkOrSkip(probe, target);
        boolean symbolicLink = Files.isSymbolicLink(probe);
        Files.delete(probe);
        return symbolicLink;
    }

    private static void createDirectoryLink(
            Path link, Path target, boolean symbolicLink) throws IOException {
        if (symbolicLink) {
            Files.createSymbolicLink(link, target.toAbsolutePath());
        } else {
            createJunction(link, target);
        }
    }

    private static boolean hasGuardedMutationOverload(String methodName) {
        return java.util.Arrays.stream(SaveFileOperations.class.getMethods())
                .filter(method -> method.getName().equals(methodName))
                .map(java.lang.reflect.Method::getParameterTypes)
                .anyMatch(parameters -> parameters.length == 3
                        && parameters[0] == Path.class
                        && parameters[1] == Path.class
                        && parameters[2].isInterface());
    }

    private static void createJunction(Path link, Path target) throws IOException {
        Process junction = new ProcessBuilder(
                        "cmd.exe",
                        "/d",
                        "/c",
                        "mklink",
                        "/J",
                        link.toString(),
                        target.toAbsolutePath().toString())
                .redirectErrorStream(true)
                .start();
        byte[] output = junction.getInputStream().readAllBytes();
        int exitCode;
        try {
            exitCode = junction.waitFor();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while creating directory junction", interrupted);
        }
        if (exitCode != 0) {
            throw new IOException(
                    "Directory junction creation failed: "
                            + new String(output, StandardCharsets.UTF_8));
        }
    }

    private static JdkSaveFileOperations jdkFilesWithDirectoryForcer(
            DirectoryForceAction action) {
        for (Constructor<?> constructor : JdkSaveFileOperations.class
                .getDeclaredConstructors()) {
            Class<?>[] parameters = constructor.getParameterTypes();
            if (parameters.length != 1 || !parameters[0].isInterface()) {
                continue;
            }
            assertFalse(Modifier.isPublic(constructor.getModifiers()));
            Object proxy = Proxy.newProxyInstance(
                    parameters[0].getClassLoader(),
                    new Class<?>[] {parameters[0]},
                    (ignoredProxy, method, arguments) -> {
                        if (method.getDeclaringClass() == Object.class) {
                            return switch (method.getName()) {
                                case "toString" -> "InjectedDirectoryForcer";
                                case "hashCode" -> System.identityHashCode(ignoredProxy);
                                case "equals" -> ignoredProxy == arguments[0];
                                default -> throw new AssertionError(method.getName());
                            };
                        }
                        action.force((Path) arguments[0]);
                        return null;
                    });
            try {
                constructor.setAccessible(true);
                return (JdkSaveFileOperations) constructor.newInstance(proxy);
            } catch (InstantiationException | IllegalAccessException failure) {
                throw new AssertionError(
                        "The directory-force seam constructor is unusable", failure);
            } catch (InvocationTargetException failure) {
                throw new AssertionError(
                        "The directory-force seam constructor rejected its forcer",
                        failure.getCause());
            }
        }
        throw new AssertionError(
                "JdkSaveFileOperations requires an injectable package directory-force seam");
    }

    private static SaveGameSnapshot replacement() {
        return AtomicSaveStoreTestSupport.snapshot("Replacement", 909L, 90L);
    }

    private static void writeDifferentValidArchive(
            Path archive, String displayName, long seed, long fixedTick)
            throws IOException {
        new SaveArchiveWriter().write(
                archive,
                AtomicSaveStoreTestSupport.codec().encode(
                        AtomicSaveStoreTestSupport.snapshot(
                                displayName, seed, fixedTick),
                        Instant.parse("2026-08-10T15:20:00Z")));
    }

    private static SaveSnapshotCodec oversizedPlayerCodec() {
        SaveSectionCodec<PlayerSaveSnapshot> delegate = new PlayerSectionCodec();
        SaveSectionCodec<PlayerSaveSnapshot> oversized = new SaveSectionCodec<>() {
            @Override
            public SaveSectionId sectionId() {
                return delegate.sectionId();
            }

            @Override
            public int codecVersion() {
                return delegate.codecVersion();
            }

            @Override
            public boolean required() {
                return delegate.required();
            }

            @Override
            public byte[] encode(PlayerSaveSnapshot value) {
                return new byte[Math.toIntExact(SaveArchiveLimits.MAX_PLAYER_BYTES + 1L)];
            }

            @Override
            public PlayerSaveSnapshot decode(byte[] bytes) {
                return delegate.decode(bytes);
            }
        };
        return new SaveSnapshotCodec(
                new ChunkSectionCodec(),
                oversized,
                new InventorySectionCodec(),
                new WorldItemsSectionCodec());
    }

    private static void assertExpectedSlotLayout(SlotLayout layout, PreparedWorld world)
            throws IOException {
        switch (layout) {
            case UNCHANGED -> AtomicSaveStoreTestSupport.assertUnchangedOldSlots(world);
            case OLD_CURRENT_STILL_CURRENT ->
                    AtomicSaveStoreTestSupport.assertValidArchiveBytes(
                            world.current(), world.oldCurrentBytes());
            case OLD_CURRENT_ROTATED_TO_BACKUP -> {
                assertFalse(Files.exists(world.current()));
                AtomicSaveStoreTestSupport.assertValidArchiveBytes(
                        world.backup(), world.oldCurrentBytes());
            }
            case NEW_CURRENT_WITH_OLD_CURRENT_BACKUP -> {
                AtomicSaveStoreTestSupport.assertValidSnapshot(
                        world.current(), replacement());
                AtomicSaveStoreTestSupport.assertValidArchiveBytes(
                        world.backup(), world.oldCurrentBytes());
            }
        }
    }

    private static SaveSnapshotCodec faultingCodec(
            RequiredSection section, RuntimeException failure) {
        SaveSectionCodec<ChunkRepositorySnapshot> chunks = new ChunkSectionCodec();
        SaveSectionCodec<PlayerSaveSnapshot> player = new PlayerSectionCodec();
        SaveSectionCodec<InventorySaveSnapshot> inventory = new InventorySectionCodec();
        SaveSectionCodec<WorldItemsSaveSnapshot> worldItems =
                new WorldItemsSectionCodec();
        return new SaveSnapshotCodec(
                section == RequiredSection.CHUNKS ? failing(chunks, failure) : chunks,
                section == RequiredSection.PLAYER ? failing(player, failure) : player,
                section == RequiredSection.INVENTORY ? failing(inventory, failure) : inventory,
                section == RequiredSection.WORLD_ITEMS
                        ? failing(worldItems, failure)
                        : worldItems);
    }

    private static <T> SaveSectionCodec<T> failing(
            SaveSectionCodec<T> delegate, RuntimeException failure) {
        return new SaveSectionCodec<>() {
            @Override
            public SaveSectionId sectionId() {
                return delegate.sectionId();
            }

            @Override
            public int codecVersion() {
                return delegate.codecVersion();
            }

            @Override
            public boolean required() {
                return delegate.required();
            }

            @Override
            public byte[] encode(T value) {
                throw failure;
            }

            @Override
            public T decode(byte[] bytes) {
                return delegate.decode(bytes);
            }
        };
    }

    private enum RequiredSection {
        CHUNKS,
        PLAYER,
        INVENTORY,
        WORLD_ITEMS
    }

    private enum SlotLayout {
        UNCHANGED,
        OLD_CURRENT_STILL_CURRENT,
        OLD_CURRENT_ROTATED_TO_BACKUP,
        NEW_CURRENT_WITH_OLD_CURRENT_BACKUP
    }

    private enum StoreFault {
        CREATE_TEMP(
                "save-write.temp-create-failed",
                SlotLayout.UNCHANGED,
                files -> files.failBefore(
                        FileOperation.CREATE_TEMP,
                        new IOException("injected temp create failure"))),
        NON_REGULAR_TEMP(
                "save-write.unsafe-temp-path",
                SlotLayout.UNCHANGED,
                files -> files.runAfter(FileOperation.CREATE_TEMP, (temporary, ignored) -> {
                    Files.delete(temporary);
                    Files.createDirectory(temporary);
                })),
        TEMP_FORCE(
                "save-write.temp-force-failed",
                SlotLayout.UNCHANGED,
                files -> files.failBefore(
                        FileOperation.FORCE_TEMP,
                        new IOException("injected temp force failure"))),
        TEMP_REREAD_VALIDATION(
                "save-write.temp-validation-failed",
                SlotLayout.UNCHANGED,
                files -> files.runAfter(FileOperation.FORCE_TEMP, (temporary, ignored) ->
                        Files.write(
                                temporary,
                                "corrupt-temp".getBytes(StandardCharsets.UTF_8),
                                StandardOpenOption.WRITE,
                                StandardOpenOption.TRUNCATE_EXISTING))),
        BACKUP_ATOMIC_MOVE(
                "save-write.backup-move-failed",
                SlotLayout.UNCHANGED,
                files -> files.failBefore(
                        FileOperation.MOVE_ATOMIC_BACKUP,
                        new IOException("injected backup move failure"))),
        CURRENT_ATOMIC_MOVE(
                "save-write.current-move-failed",
                SlotLayout.OLD_CURRENT_STILL_CURRENT,
                files -> files.failBefore(
                        FileOperation.MOVE_ATOMIC_CURRENT,
                        new IOException("injected current move failure"))),
        FALLBACK_BACKUP_COPY(
                "save-write.backup-copy-failed",
                SlotLayout.UNCHANGED,
                files -> {
                    files.failBefore(
                            FileOperation.MOVE_ATOMIC_BACKUP,
                            AtomicSaveStoreTestSupport.unsupported("backup"));
                    files.failBefore(
                            FileOperation.COPY_BACKUP,
                            new IOException("injected fallback backup copy failure"));
                }),
        FALLBACK_BACKUP_FORCE(
                "save-write.backup-force-failed",
                SlotLayout.OLD_CURRENT_STILL_CURRENT,
                files -> {
                    files.failBefore(
                            FileOperation.MOVE_ATOMIC_BACKUP,
                            AtomicSaveStoreTestSupport.unsupported("backup"));
                    files.failBefore(
                            FileOperation.FORCE_BACKUP,
                            new IOException("injected fallback backup force failure"));
                }),
        FALLBACK_BACKUP_REREAD_VALIDATION(
                "save-write.backup-validation-failed",
                SlotLayout.OLD_CURRENT_STILL_CURRENT,
                files -> {
                    files.failBefore(
                            FileOperation.MOVE_ATOMIC_BACKUP,
                            AtomicSaveStoreTestSupport.unsupported("backup"));
                    files.runAfter(FileOperation.COPY_BACKUP, (source, destination) ->
                            Files.write(
                                    destination,
                                    "corrupt-backup".getBytes(StandardCharsets.UTF_8),
                                    StandardOpenOption.WRITE,
                                    StandardOpenOption.TRUNCATE_EXISTING));
                }),
        FALLBACK_CURRENT_REPLACE(
                "save-write.current-replace-failed",
                SlotLayout.OLD_CURRENT_STILL_CURRENT,
                files -> {
                    files.failBefore(
                            FileOperation.MOVE_ATOMIC_CURRENT,
                            AtomicSaveStoreTestSupport.unsupported("current"));
                    files.failBefore(
                            FileOperation.MOVE_REPLACE_CURRENT,
                            new IOException("injected fallback current replace failure"));
                }),
        DIRECTORY_FORCE(
                "save-write.directory-force-failed",
                SlotLayout.OLD_CURRENT_STILL_CURRENT,
                files -> files.failBefore(
                        FileOperation.FORCE_DIRECTORY,
                        new IOException("injected directory force failure"))),
        TEMP_CLEANUP(
                "save-write.temp-force-failed",
                SlotLayout.UNCHANGED,
                files -> {
                    files.failBefore(
                            FileOperation.FORCE_TEMP,
                            new IOException("injected primary force failure"));
                    files.failBefore(
                            FileOperation.DELETE_TEMP,
                            new IOException("injected temp cleanup failure"));
                });

        private final String diagnosticCode;
        private final SlotLayout layout;
        private final FaultConfiguration configuration;

        StoreFault(
                String diagnosticCode,
                SlotLayout layout,
                FaultConfiguration configuration) {
            this.diagnosticCode = diagnosticCode;
            this.layout = layout;
            this.configuration = configuration;
        }

        private void configure(RecordingSaveFileOperations files) throws IOException {
            configuration.configure(files);
        }
    }

    @FunctionalInterface
    private interface FaultConfiguration {
        void configure(RecordingSaveFileOperations files) throws IOException;
    }

    @FunctionalInterface
    private interface DirectoryForceAction {
        void force(Path directory) throws IOException;
    }
}

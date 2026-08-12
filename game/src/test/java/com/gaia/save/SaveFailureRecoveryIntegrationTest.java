package com.gaia.save;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
import com.gaia.save.format.SaveSectionCodec;
import com.gaia.save.format.SaveSectionId;
import com.gaia.save.snapshot.InventorySaveSnapshot;
import com.gaia.save.snapshot.PlayerSaveSnapshot;
import com.gaia.save.snapshot.SaveGameSnapshot;
import com.gaia.save.snapshot.WorldItemsSaveSnapshot;
import com.gaia.save.store.AtomicSaveStore;
import com.gaia.save.store.FileSaveCatalog;
import com.gaia.save.store.JdkSaveFileOperations;
import com.gaia.save.store.SaveDeleteResult;
import com.gaia.save.store.SaveFileOperations;
import com.gaia.save.store.SaveRecoveryResult;
import com.gaia.save.store.SaveRepository;
import com.gaia.save.store.SaveWriteResult;
import com.gaia.shell.save.SaveSummary;
import com.overlord.interaction.api.EntityRef;
import com.overlord.inventory.api.BodySlot;
import com.overlord.voxel.ChunkKey;
import com.overlord.voxel.ChunkRepositorySnapshot;
import com.overlord.voxel.ChunkSnapshot;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class SaveFailureRecoveryIntegrationTest {
    private static final Instant FIRST_MODIFIED =
            Instant.parse("2026-08-10T12:10:00Z");
    private static final Instant SECOND_MODIFIED =
            Instant.parse("2026-08-10T12:20:00Z");

    @TempDir
    Path tempDir;

    @Test
    void snapshotIdentityMismatchIsRejectedBeforeFilesystemMutationOnEveryAttempt()
            throws Exception {
        PreparedSave prepared = prepareKnownGood("snapshot-id-mismatch");
        SaveGameId wrongId = Gate14CTestSupport.id(901);
        SaveGameSnapshot wrongSnapshot = Gate14CTestSupport.snapshot(
                wrongId, "Wrong world identity", 901L, 901L);
        FaultingFileOperations files = new FaultingFileOperations(StoreFault.NONE);
        AtomicSaveStore store = Gate14CTestSupport.store(
                prepared.root(), prepared.id(), Gate14CTestSupport.codec(), files);

        SaveWriteResult first = store.save(wrongSnapshot, SECOND_MODIFIED);
        SaveWriteResult repeated = store.save(
                wrongSnapshot, SECOND_MODIFIED.plusSeconds(1));

        assertAll(
                () -> assertEquals(SaveWriteResult.Status.FAILED, first.status()),
                () -> assertEquals(SaveWriteResult.Status.FAILED, repeated.status()),
                () -> assertEquals(
                        "save-write.snapshot-identity-mismatch",
                        first.diagnostics().get(0).code()),
                () -> assertEquals(
                        "save-write.snapshot-identity-mismatch",
                        repeated.diagnostics().get(0).code()),
                () -> assertTrue(first.committedManifest().isEmpty()),
                () -> assertTrue(repeated.committedManifest().isEmpty()),
                () -> assertEquals(0, files.mutationCount()),
                () -> assertArrayEquals(
                        prepared.currentBytes(), Files.readAllBytes(prepared.current())),
                () -> assertArrayEquals(
                        prepared.backupBytes(), Files.readAllBytes(prepared.backup())));
        Gate14CTestSupport.assertClosedDiagnostics(first.diagnostics(), prepared.root());
        Gate14CTestSupport.assertClosedDiagnostics(repeated.diagnostics(), prepared.root());
    }

    @Test
    void validWrongIdCurrentCannotRotateOverExpectedIdBackup()
            throws Exception {
        PreparedSave prepared = prepareKnownGood("wrong-current-id");
        SaveGameId wrongId = Gate14CTestSupport.id(902);
        Gate14CTestSupport.writeArchive(
                prepared.current(),
                Gate14CTestSupport.snapshot(wrongId, "Wrong current", 902L, 902L),
                FIRST_MODIFIED);
        byte[] wrongCurrent = Files.readAllBytes(prepared.current());
        byte[] expectedBackup = Files.readAllBytes(prepared.backup());
        FaultingFileOperations files = new FaultingFileOperations(StoreFault.NONE);

        SaveWriteResult result = Gate14CTestSupport.store(
                        prepared.root(), prepared.id(), Gate14CTestSupport.codec(), files)
                .save(
                        Gate14CTestSupport.snapshot(
                                prepared.id(), "Expected replacement", 903L, 903L),
                        SECOND_MODIFIED);

        assertAll(
                () -> assertEquals(SaveWriteResult.Status.FAILED, result.status()),
                () -> assertEquals(
                        "save-write.current-validation-failed",
                        result.diagnostics().get(0).code()),
                () -> assertTrue(result.committedManifest().isEmpty()),
                () -> assertArrayEquals(wrongCurrent, Files.readAllBytes(prepared.current())),
                () -> assertArrayEquals(
                        expectedBackup, Files.readAllBytes(prepared.backup())));
        Gate14CTestSupport.assertClosedDiagnostics(result.diagnostics(), prepared.root());
    }

    @Test
    void wrongIdSlotArchivesNeverCountAsLastKnownGoodAfterCleanupFailure()
            throws Exception {
        PreparedSave prepared = prepareKnownGood("wrong-known-good-id");
        SaveGameId wrongId = Gate14CTestSupport.id(904);
        Gate14CTestSupport.writeArchive(
                prepared.current(),
                Gate14CTestSupport.snapshot(wrongId, "Wrong current", 904L, 904L),
                FIRST_MODIFIED);
        Gate14CTestSupport.writeArchive(
                prepared.backup(),
                Gate14CTestSupport.snapshot(wrongId, "Wrong backup", 905L, 905L),
                FIRST_MODIFIED.minusSeconds(1));
        byte[] wrongCurrent = Files.readAllBytes(prepared.current());
        byte[] wrongBackup = Files.readAllBytes(prepared.backup());
        FaultingFileOperations files = new FaultingFileOperations(
                StoreFault.FORCE_AND_CLEANUP);

        SaveWriteResult result = Gate14CTestSupport.store(
                        prepared.root(), prepared.id(), Gate14CTestSupport.codec(), files)
                .save(
                        Gate14CTestSupport.snapshot(
                                prepared.id(), "Expected snapshot", 906L, 906L),
                        SECOND_MODIFIED);

        assertAll(
                () -> assertEquals(
                        SaveWriteResult.Status.BLOCKING_FAILURE, result.status()),
                () -> assertEquals(
                        "save-write.temp-cleanup-ownership-uncertain",
                        result.diagnostics().get(0).code()),
                () -> assertTrue(result.committedManifest().isEmpty()),
                () -> assertArrayEquals(wrongCurrent, Files.readAllBytes(prepared.current())),
                () -> assertArrayEquals(wrongBackup, Files.readAllBytes(prepared.backup())));
        Gate14CTestSupport.assertClosedDiagnostics(result.diagnostics(), prepared.root());
    }

    @ParameterizedTest
    @EnumSource(ArchiveWriteFault.class)
    void partialRealArchiveWriteFailureClosesWriterAndPreservesExactSlots(
            ArchiveWriteFault fault) throws Exception {
        PreparedSave prepared = prepareKnownGood("writer-" + fault.name());
        SaveGameSnapshot replacement = Gate14CTestSupport.snapshot(
                prepared.id(), "Partial writer", 907L, 907L);
        Path baseline = tempDir.resolve("writer-baseline-" + fault.name() + ".glsave");
        Gate14CTestSupport.writeArchive(baseline, replacement, SECOND_MODIFIED);
        byte[] baselineBytes = Files.readAllBytes(baseline);
        int failureOffset = fault.failureOffset(baselineBytes);
        ArchiveOutputFailureState writerState = new ArchiveOutputFailureState();
        SaveArchiveWriter writer = faultingArchiveWriter(failureOffset, writerState);
        FaultingFileOperations files = new FaultingFileOperations(StoreFault.NONE);

        SaveWriteResult result = Gate14CTestSupport.store(
                        prepared.root(),
                        prepared.id(),
                        Gate14CTestSupport.codec(),
                        writer,
                        files)
                .save(replacement, SECOND_MODIFIED);

        assertAll(
                () -> assertEquals(SaveWriteResult.Status.FAILED, result.status()),
                () -> assertEquals(
                        "save-write.temp-write-failed",
                        result.diagnostics().get(0).code()),
                () -> assertTrue(result.committedManifest().isEmpty()),
                () -> assertTrue(writerState.closed(),
                        "the real writer must close its injected output after failure"),
                () -> assertEquals(failureOffset, writerState.writtenBytes()),
                () -> assertTrue(containsAscii(writerState.capturedBytes(), "manifest.json")),
                () -> assertEquals(
                        fault == ArchiveWriteFault.MID_CHUNKS,
                        containsAscii(writerState.capturedBytes(), "chunks.bin")),
                () -> assertFalse(containsAscii(writerState.capturedBytes(), "player.json")),
                () -> assertArrayEquals(
                        prepared.currentBytes(), Files.readAllBytes(prepared.current())),
                () -> assertArrayEquals(
                        prepared.backupBytes(), Files.readAllBytes(prepared.backup())),
                () -> assertTrue(files.createdTemps().stream().noneMatch(Files::exists)));
        Gate14CTestSupport.assertClosedDiagnostics(result.diagnostics(), prepared.root());
    }

    @ParameterizedTest
    @EnumSource(CodecFault.class)
    void requiredDomainCodecFailuresPreserveBothExactSlotsAndNeverReachFilesystem(
            CodecFault fault) throws Exception {
        PreparedSave prepared = prepareKnownGood("codec-" + fault.name());
        FaultingFileOperations files = new FaultingFileOperations(StoreFault.NONE);
        SaveSnapshotCodec failingCodec = Gate14CTestSupport.codecFailing(fault.sectionId());
        AtomicSaveStore store = Gate14CTestSupport.store(
                prepared.root(), prepared.id(), failingCodec, files);

        SaveWriteResult result = store.save(
                Gate14CTestSupport.snapshot(prepared.id(), "New snapshot", 30L, 30L),
                SECOND_MODIFIED);

        assertEquals(SaveWriteResult.Status.FAILED, result.status());
        assertEquals(0, files.mutationCount());
        assertArrayEquals(prepared.currentBytes(), Files.readAllBytes(prepared.current()));
        assertArrayEquals(prepared.backupBytes(), Files.readAllBytes(prepared.backup()));
        assertEquals(
                SaveSummary.Health.VALID,
                Gate14CTestSupport.catalog(prepared.root()).summaries().get(0).health());
        Gate14CTestSupport.assertClosedDiagnostics(result.diagnostics(), prepared.root());
    }

    @Test
    void malformedManifestInCompletedTempFailsValidationBeforeAnySlotMutation()
            throws Exception {
        PreparedSave prepared = prepareKnownGood("manifest");
        FaultingFileOperations files = new FaultingFileOperations(
                StoreFault.MALFORMED_TEMP_MANIFEST);

        SaveWriteResult result = Gate14CTestSupport.store(
                        prepared.root(), prepared.id(), Gate14CTestSupport.codec(), files)
                .save(
                        Gate14CTestSupport.snapshot(
                                prepared.id(), "Manifest failure", 31L, 31L),
                        SECOND_MODIFIED);

        assertEquals(SaveWriteResult.Status.FAILED, result.status());
        assertEquals("save-write.temp-validation-failed",
                result.diagnostics().get(0).code());
        assertArrayEquals(prepared.currentBytes(), Files.readAllBytes(prepared.current()));
        assertArrayEquals(prepared.backupBytes(), Files.readAllBytes(prepared.backup()));
        assertTrue(files.createdTemps().stream().noneMatch(Files::exists));
        Gate14CTestSupport.assertClosedDiagnostics(result.diagnostics(), prepared.root());
    }

    @ParameterizedTest
    @EnumSource(value = StoreFault.class, names = {
        "FORCE_TEMP",
        "MOVE_ATOMIC_CURRENT",
        "MOVE_REPLACE_CURRENT",
        "FORCE_DIRECTORY",
        "FORCE_AND_CLEANUP"
    })
    void filesystemFaultMatrixRetainsExactLastKnownGoodAndCatalogIgnoresTemps(
            StoreFault fault) throws Exception {
        PreparedSave prepared = prepareKnownGood("store-" + fault.name());
        FaultingFileOperations files = new FaultingFileOperations(fault);

        SaveWriteResult result = Gate14CTestSupport.store(
                        prepared.root(), prepared.id(), Gate14CTestSupport.codec(), files)
                .save(
                        Gate14CTestSupport.snapshot(
                                prepared.id(), "Faulted replacement", 40L, 40L),
                        SECOND_MODIFIED);

        assertNotEquals(SaveWriteResult.Status.SUCCESS, result.status());
        assertTrue(exactValidArchiveSurvives(prepared.currentBytes(), prepared),
                "the exact old current must remain in current or backup");
        assertEquals(
                SaveSummary.Health.VALID,
                Gate14CTestSupport.catalog(prepared.root()).summaries().get(0).health());
        Gate14CTestSupport.assertClosedDiagnostics(result.diagnostics(), prepared.root());
        if (fault == StoreFault.FORCE_AND_CLEANUP) {
            assertTrue(files.createdTemps().stream().anyMatch(Files::exists));
            assertEquals(SaveWriteResult.Status.FAILED, result.status(),
                    "a verified current keeps failed temp cleanup non-blocking");
        }
    }

    @Test
    void repeatedSaveRotatesExactPriorCurrentAndPublishesOnlyTheSecondSnapshot()
            throws Exception {
        Path root = Files.createDirectories(tempDir.resolve("repeat-root"));
        SaveGameId id = Gate14CTestSupport.id(51);
        FaultingFileOperations files = new FaultingFileOperations(StoreFault.NONE);
        AtomicSaveStore store = Gate14CTestSupport.store(
                root, id, Gate14CTestSupport.codec(), files);
        SaveGameSnapshot first = Gate14CTestSupport.snapshot(id, "First", 51L, 51L);
        SaveGameSnapshot second = Gate14CTestSupport.snapshot(id, "Second", 52L, 52L);

        SaveWriteResult firstResult = store.save(first, FIRST_MODIFIED);
        byte[] firstCurrent = Files.readAllBytes(
                root.resolve(id.value()).resolve("current.glsave"));
        SaveWriteResult secondResult = store.save(second, SECOND_MODIFIED);
        Path world = root.resolve(id.value());

        assertEquals(SaveWriteResult.Status.SUCCESS, firstResult.status());
        assertEquals(SaveWriteResult.Status.SUCCESS, secondResult.status());
        assertEquals(secondResult.committedManifest().orElseThrow().saveGameId(), id);
        assertEquals(second, Gate14CTestSupport.readValid(
                world.resolve("current.glsave")).snapshot().orElseThrow());
        assertArrayEquals(firstCurrent, Files.readAllBytes(world.resolve("backup.glsave")));
        assertEquals(first, Gate14CTestSupport.readValid(
                world.resolve("backup.glsave")).snapshot().orElseThrow());
        assertTrue(files.createdTemps().stream().noneMatch(Files::exists));
        assertEquals(
                SaveSummary.Health.VALID,
                Gate14CTestSupport.catalog(root).summaries().get(0).health());
    }

    @Test
    void crashLikeTempsNeverLoadAndExplicitRecoveryDeterministicallyPromotesBackup()
            throws Exception {
        Path root = Files.createDirectories(tempDir.resolve("crash-root"));
        SaveGameId id = Gate14CTestSupport.id(61);
        Path world = Files.createDirectories(root.resolve(id.value()));
        Path current = world.resolve("current.glsave");
        Path backup = world.resolve("backup.glsave");
        Files.write(current, new byte[] {0x13, 0x37});
        SaveGameSnapshot backupSnapshot =
                Gate14CTestSupport.snapshot(id, "Recovery backup", 61L, 61L);
        Gate14CTestSupport.writeArchive(backup, backupSnapshot, FIRST_MODIFIED);
        byte[] backupBytes = Files.readAllBytes(backup);
        Path stale = world.resolve("current.glsave.crash.tmp");
        Gate14CTestSupport.writeArchive(
                stale,
                Gate14CTestSupport.snapshot(id, "Uncommitted newer temp", 62L, 62L),
                SECOND_MODIFIED);

        SaveRepository repository = Gate14CTestSupport.repository(
                root, new FaultingFileOperations(StoreFault.NONE));
        List<SaveSummary> before = new FileSaveCatalog(repository).summaries();
        SaveRecoveryResult recovery = repository.recoverBackup(id);
        List<SaveSummary> after = new FileSaveCatalog(repository).summaries();

        assertEquals(1, before.size());
        assertEquals(SaveSummary.Health.RECOVERABLE_BACKUP, before.get(0).health());
        assertEquals(SaveRecoveryResult.Status.SUCCESS, recovery.status());
        assertArrayEquals(backupBytes, Files.readAllBytes(current));
        assertArrayEquals(backupBytes, Files.readAllBytes(backup));
        assertEquals(1, after.size());
        assertEquals(SaveSummary.Health.VALID, after.get(0).health());
        assertEquals(backupSnapshot, Gate14CTestSupport.readValid(current)
                .snapshot().orElseThrow());
        assertTrue(Files.exists(stale),
                "startup discovery and explicit recovery must not infer ownership of stale temps");
    }

    @Test
    void deleteMoveFailurePreservesRowWhileCleanupFailureRemovesOnlyToLocalTrash()
            throws Exception {
        PreparedSave moveFailure = prepareKnownGood("delete-move");
        FaultingFileOperations moveFiles = new FaultingFileOperations(
                StoreFault.DELETE_MOVE);
        SaveRepository moveRepository = Gate14CTestSupport.repository(
                moveFailure.root(), moveFiles);

        SaveDeleteResult failed = moveRepository.delete(moveFailure.id());

        assertEquals(SaveDeleteResult.Status.FAILURE, failed.status());
        assertArrayEquals(
                moveFailure.currentBytes(), Files.readAllBytes(moveFailure.current()));
        assertEquals(1, new FileSaveCatalog(moveRepository).summaries().size());
        Gate14CTestSupport.assertClosedDiagnostics(failed.diagnostics(), moveFailure.root());

        PreparedSave cleanupFailure = prepareKnownGood("delete-cleanup");
        FaultingFileOperations cleanupFiles = new FaultingFileOperations(
                StoreFault.DELETE_CLEANUP);
        SaveRepository cleanupRepository = Gate14CTestSupport.repository(
                cleanupFailure.root(), cleanupFiles);
        SaveDeleteResult warning = cleanupRepository.delete(cleanupFailure.id());

        assertEquals(SaveDeleteResult.Status.DELETED_WITH_CLEANUP_WARNING, warning.status());
        assertFalse(Files.exists(cleanupFailure.current().getParent()));
        assertTrue(new FileSaveCatalog(cleanupRepository).summaries().isEmpty());
        assertTrue(cleanupFiles.deletedPaths().stream().allMatch(path ->
                path.startsWith(cleanupFailure.root().resolve(".trash"))));
        Gate14CTestSupport.assertClosedDiagnostics(warning.diagnostics(), cleanupFailure.root());
    }

    private PreparedSave prepareKnownGood(String suffix) throws IOException {
        Path root = Files.createDirectories(tempDir.resolve("root-" + suffix));
        SaveGameId id = Gate14CTestSupport.id(Math.abs(suffix.hashCode()) + 100);
        Path world = Files.createDirectories(root.resolve(id.value()));
        Path current = world.resolve("current.glsave");
        Path backup = world.resolve("backup.glsave");
        Gate14CTestSupport.writeArchive(
                current,
                Gate14CTestSupport.snapshot(id, "Old current", 11L, 11L),
                FIRST_MODIFIED);
        Gate14CTestSupport.writeArchive(
                backup,
                Gate14CTestSupport.snapshot(id, "Old backup", 10L, 10L),
                Instant.parse("2026-08-10T12:05:00Z"));
        return new PreparedSave(
                root,
                id,
                current,
                backup,
                Files.readAllBytes(current),
                Files.readAllBytes(backup));
    }

    private static SaveArchiveWriter faultingArchiveWriter(
            int failureOffset, ArchiveOutputFailureState state) {
        Class<?> outputFactory = Arrays.stream(SaveArchiveWriter.class.getDeclaredClasses())
                .filter(type -> type.getSimpleName().equals("ArchiveOutputFactory"))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "SaveArchiveWriter requires a package-private ArchiveOutputFactory seam"));
        assertTrue(outputFactory.isInterface());
        Constructor<?> constructor;
        try {
            constructor = SaveArchiveWriter.class.getDeclaredConstructor(outputFactory);
        } catch (NoSuchMethodException failure) {
            throw new AssertionError(
                    "SaveArchiveWriter requires a package-private output-factory constructor",
                    failure);
        }
        assertFalse(Modifier.isPublic(constructor.getModifiers()));
        constructor.setAccessible(true);
        Object factory = Proxy.newProxyInstance(
                outputFactory.getClassLoader(),
                new Class<?>[] {outputFactory},
                (proxy, method, arguments) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        return switch (method.getName()) {
                            case "toString" -> "FaultingArchiveOutputFactory";
                            case "hashCode" -> System.identityHashCode(proxy);
                            case "equals" -> proxy == arguments[0];
                            default -> throw new AssertionError(method.getName());
                        };
                    }
                    if (!method.getName().equals("open")
                            || arguments == null
                            || arguments.length != 1
                            || !(arguments[0] instanceof Path archive)) {
                        throw new AssertionError(
                                "ArchiveOutputFactory must expose OutputStream open(Path)");
                    }
                    return new FailAfterOutputStream(
                            Files.newOutputStream(archive), failureOffset, state);
                });
        try {
            return (SaveArchiveWriter) constructor.newInstance(factory);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("The package-private writer seam is unusable", failure);
        }
    }

    private static boolean containsAscii(byte[] bytes, String value) {
        byte[] needle = value.getBytes(StandardCharsets.US_ASCII);
        return indexOf(bytes, needle) >= 0;
    }

    private static int indexOf(byte[] bytes, byte[] needle) {
        for (int offset = 0; offset <= bytes.length - needle.length; offset++) {
            boolean match = true;
            for (int index = 0; index < needle.length; index++) {
                if (bytes[offset + index] != needle[index]) {
                    match = false;
                    break;
                }
            }
            if (match) {
                return offset;
            }
        }
        return -1;
    }

    private static boolean exactValidArchiveSurvives(
            byte[] expected, PreparedSave prepared) throws IOException {
        for (Path candidate : List.of(prepared.current(), prepared.backup())) {
            if (Files.isRegularFile(candidate)
                    && Arrays.equals(expected, Files.readAllBytes(candidate))
                    && Gate14CTestSupport.reader().read(candidate).status()
                            == SaveArchiveReadResult.Status.VALID) {
                return true;
            }
        }
        return false;
    }
}

enum CodecFault {
    WORLD(SaveSectionId.CHUNKS),
    INVENTORY(SaveSectionId.INVENTORY),
    WORLD_ITEMS(SaveSectionId.WORLD_ITEMS);

    private final SaveSectionId sectionId;

    CodecFault(SaveSectionId sectionId) {
        this.sectionId = sectionId;
    }

    SaveSectionId sectionId() {
        return sectionId;
    }
}

enum ArchiveWriteFault {
    PARTIAL_MANIFEST,
    MID_CHUNKS;

    int failureOffset(byte[] completeArchive) {
        int manifest = indexOfAscii(completeArchive, "manifest.json");
        int chunks = indexOfAscii(completeArchive, "chunks.bin");
        int player = indexOfAscii(completeArchive, "player.json");
        assertTrue(manifest >= 0 && manifest < chunks && chunks < player,
                "baseline archive must contain canonical local entry names");
        return switch (this) {
            case PARTIAL_MANIFEST -> manifest + (chunks - manifest) / 2;
            case MID_CHUNKS -> chunks + (player - chunks) / 2;
        };
    }

    private static int indexOfAscii(byte[] bytes, String value) {
        byte[] needle = value.getBytes(StandardCharsets.US_ASCII);
        for (int offset = 0; offset <= bytes.length - needle.length; offset++) {
            boolean match = true;
            for (int index = 0; index < needle.length; index++) {
                if (bytes[offset + index] != needle[index]) {
                    match = false;
                    break;
                }
            }
            if (match) {
                return offset;
            }
        }
        return -1;
    }
}

final class ArchiveOutputFailureState {
    private final ByteArrayOutputStream captured = new ByteArrayOutputStream();
    private int writtenBytes;
    private boolean closed;

    void capture(byte[] bytes, int offset, int length) {
        captured.write(bytes, offset, length);
        writtenBytes += length;
    }

    void capture(int value) {
        captured.write(value);
        writtenBytes++;
    }

    byte[] capturedBytes() {
        return captured.toByteArray();
    }

    int writtenBytes() {
        return writtenBytes;
    }

    boolean closed() {
        return closed;
    }

    void markClosed() {
        closed = true;
    }
}

final class FailAfterOutputStream extends OutputStream {
    private final OutputStream delegate;
    private final int failureOffset;
    private final ArchiveOutputFailureState state;

    FailAfterOutputStream(
            OutputStream delegate,
            int failureOffset,
            ArchiveOutputFailureState state) {
        this.delegate = delegate;
        this.failureOffset = failureOffset;
        this.state = state;
    }

    @Override
    public void write(int value) throws IOException {
        requireCapacity();
        delegate.write(value);
        state.capture(value);
    }

    @Override
    public void write(byte[] bytes, int offset, int length) throws IOException {
        int remaining = failureOffset - state.writtenBytes();
        if (remaining <= 0) {
            throw injectedFailure();
        }
        int accepted = Math.min(length, remaining);
        delegate.write(bytes, offset, accepted);
        state.capture(bytes, offset, accepted);
        if (accepted < length) {
            throw injectedFailure();
        }
    }

    @Override
    public void flush() throws IOException {
        delegate.flush();
    }

    @Override
    public void close() throws IOException {
        state.markClosed();
        delegate.close();
    }

    private void requireCapacity() throws IOException {
        if (state.writtenBytes() >= failureOffset) {
            throw injectedFailure();
        }
    }

    private static IOException injectedFailure() {
        return new IOException("injected partial archive output failure");
    }
}

enum StoreFault {
    NONE,
    MALFORMED_TEMP_MANIFEST,
    FORCE_TEMP,
    MOVE_ATOMIC_CURRENT,
    MOVE_REPLACE_CURRENT,
    FORCE_DIRECTORY,
    FORCE_AND_CLEANUP,
    DELETE_MOVE,
    DELETE_CLEANUP
}

record PreparedSave(
        Path root,
        SaveGameId id,
        Path current,
        Path backup,
        byte[] currentBytes,
        byte[] backupBytes) {
    PreparedSave {
        currentBytes = currentBytes.clone();
        backupBytes = backupBytes.clone();
    }

    @Override
    public byte[] currentBytes() {
        return currentBytes.clone();
    }

    @Override
    public byte[] backupBytes() {
        return backupBytes.clone();
    }
}

final class FaultingFileOperations implements SaveFileOperations {
    private final SaveFileOperations delegate = new JdkSaveFileOperations();
    private final StoreFault fault;
    private final List<Path> createdTemps = new ArrayList<>();
    private final List<Path> deletedPaths = new ArrayList<>();
    private int mutationCount;

    FaultingFileOperations(StoreFault fault) {
        this.fault = fault;
    }

    @Override
    public Path createSiblingTemp(
            Path directory, String targetName, MutationGuard mutationGuard)
            throws IOException {
        mutationCount++;
        Path temporary = delegate.createSiblingTemp(directory, targetName, mutationGuard);
        createdTemps.add(temporary);
        return temporary;
    }

    @Override
    public void forceFile(Path file, MutationGuard mutationGuard) throws IOException {
        mutationCount++;
        if (isCurrentTemp(file) && fault == StoreFault.MALFORMED_TEMP_MANIFEST) {
            Gate14CTestSupport.replaceManifest(file, "{");
        }
        if (isCurrentTemp(file)
                && (fault == StoreFault.FORCE_TEMP
                        || fault == StoreFault.FORCE_AND_CLEANUP)) {
            throw new IOException("injected file force failure");
        }
        delegate.forceFile(file, mutationGuard);
    }

    @Override
    public void moveAtomicReplacing(
            Path source, Path destination, MutationGuard mutationGuard)
            throws IOException {
        mutationCount++;
        if (isCurrent(destination) && fault == StoreFault.MOVE_ATOMIC_CURRENT) {
            throw new IOException("injected atomic current move failure");
        }
        if (isCurrent(destination) && fault == StoreFault.MOVE_REPLACE_CURRENT) {
            throw new AtomicMoveNotSupportedException(
                    source.toString(), destination.toString(), "injected unsupported move");
        }
        if (isTrashDestination(destination) && fault == StoreFault.DELETE_MOVE) {
            throw new IOException("injected delete move failure");
        }
        delegate.moveAtomicReplacing(source, destination, mutationGuard);
    }

    @Override
    public void moveReplacing(
            Path source, Path destination, MutationGuard mutationGuard)
            throws IOException {
        mutationCount++;
        if (isCurrent(destination) && fault == StoreFault.MOVE_REPLACE_CURRENT) {
            throw new IOException("injected replacement current move failure");
        }
        if (isTrashDestination(destination) && fault == StoreFault.DELETE_MOVE) {
            throw new IOException("injected delete replacement failure");
        }
        delegate.moveReplacing(source, destination, mutationGuard);
    }

    @Override
    public void copyReplacing(
            Path source, Path destination, MutationGuard mutationGuard)
            throws IOException {
        mutationCount++;
        delegate.copyReplacing(source, destination, mutationGuard);
    }

    @Override
    public boolean deleteIfExists(Path path, MutationGuard mutationGuard)
            throws IOException {
        mutationCount++;
        deletedPaths.add(path.toAbsolutePath().normalize());
        if (fault == StoreFault.FORCE_AND_CLEANUP
                || fault == StoreFault.DELETE_CLEANUP) {
            throw new IOException("injected cleanup failure");
        }
        return delegate.deleteIfExists(path, mutationGuard);
    }

    @Override
    public void forceDirectoryBestEffort(
            Path directory, MutationGuard mutationGuard) throws IOException {
        mutationCount++;
        if (fault == StoreFault.FORCE_DIRECTORY
                && directory.getFileName().toString().matches(
                        "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
            throw new IOException("injected directory force failure");
        }
        delegate.forceDirectoryBestEffort(directory, mutationGuard);
    }

    int mutationCount() {
        return mutationCount;
    }

    List<Path> createdTemps() {
        return List.copyOf(createdTemps);
    }

    List<Path> deletedPaths() {
        return List.copyOf(deletedPaths);
    }

    private static boolean isCurrent(Path destination) {
        return destination.getFileName().toString().equals("current.glsave");
    }

    private static boolean isCurrentTemp(Path file) {
        return file.getFileName().toString().startsWith("current.glsave.");
    }

    private static boolean isTrashDestination(Path destination) {
        Path parent = destination.getParent();
        return parent != null && parent.getFileName().toString().equals(".trash");
    }
}

final class Gate14CTestSupport {
    private static final int WORLD_HEIGHT = 16;
    private static final int CHUNK_RADIUS = 2;
    private static final Instant CREATED = Instant.parse("2026-08-10T12:00:00Z");
    private static final EntityRef OWNER = new EntityRef(14);
    private static final SaveSnapshotCodec CODEC = new SaveSnapshotCodec(
            new ChunkSectionCodec(),
            new PlayerSectionCodec(),
            new InventorySectionCodec(),
            new WorldItemsSectionCodec());

    private Gate14CTestSupport() {}

    static SaveSnapshotCodec codec() {
        return CODEC;
    }

    static SaveSnapshotCodec codecFailing(SaveSectionId failedSection) {
        ChunkSectionCodec chunks = new ChunkSectionCodec();
        PlayerSectionCodec player = new PlayerSectionCodec();
        InventorySectionCodec inventory = new InventorySectionCodec();
        WorldItemsSectionCodec worldItems = new WorldItemsSectionCodec();
        return new SaveSnapshotCodec(
                wrapped(chunks, failedSection),
                wrapped(player, failedSection),
                wrapped(inventory, failedSection),
                wrapped(worldItems, failedSection));
    }

    private static <T> SaveSectionCodec<T> wrapped(
            SaveSectionCodec<T> delegate, SaveSectionId failedSection) {
        if (!delegate.sectionId().equals(failedSection)) {
            return delegate;
        }
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
                throw new IllegalStateException("injected section encoding failure");
            }

            @Override
            public T decode(byte[] bytes) {
                return delegate.decode(bytes);
            }
        };
    }

    static SaveGameId id(int suffix) {
        long normalized = Integer.toUnsignedLong(suffix);
        return SaveGameId.parse(String.format(
                "123e4567-e89b-12d3-a456-%012x", normalized));
    }

    static SaveGameSnapshot snapshot(
            SaveGameId id, String name, long seed, long fixedTick) {
        List<ChunkSnapshot> chunks = new ArrayList<>();
        long revision = 0;
        for (int x = -CHUNK_RADIUS; x <= CHUNK_RADIUS; x++) {
            for (int z = -CHUNK_RADIUS; z <= CHUNK_RADIUS; z++) {
                byte[] blocks = new byte[16 * WORLD_HEIGHT * 16];
                blocks[Math.floorMod(x * 31 + z * 17 + (int) seed, blocks.length)] = 1;
                chunks.add(ChunkSnapshot.of(
                        new ChunkKey(x, z), ++revision, WORLD_HEIGHT, blocks));
            }
        }
        return new SaveGameSnapshot(
                new SaveGameSnapshot.StaticMetadata(
                        SaveFormatVersion.CURRENT,
                        "0.2.0-alpha.1",
                        id,
                        name,
                        CREATED,
                        seed,
                        "v1",
                        "a".repeat(64),
                        CHUNK_RADIUS,
                        WORLD_HEIGHT,
                        Optional.of("Gate 14C integration fixture")),
                fixedTick,
                new ChunkRepositorySnapshot(WORLD_HEIGHT, revision, chunks),
                new PlayerSaveSnapshot(
                        OWNER,
                        1.0,
                        20.0,
                        -3.0,
                        0.0,
                        0.0,
                        0.0,
                        90.0,
                        -10.0,
                        GameMode.SURVIVAL,
                        false),
                new InventorySaveSnapshot(
                        OWNER, Map.of(), BodySlot.LEFT_HAND, false, 1L),
                new WorldItemsSaveSnapshot(fixedTick, List.of(), 0L, false));
    }

    static AtomicSaveStore store(
            Path root,
            SaveGameId id,
            SaveSnapshotCodec codec,
            SaveFileOperations files) {
        return store(root, id, codec, new SaveArchiveWriter(), files);
    }

    static AtomicSaveStore store(
            Path root,
            SaveGameId id,
            SaveSnapshotCodec codec,
            SaveArchiveWriter writer,
            SaveFileOperations files) {
        return new AtomicSaveStore(
                root,
                id,
                codec,
                writer,
                new SaveArchiveReader(codec),
                files);
    }

    static SaveArchiveReader reader() {
        return new SaveArchiveReader(CODEC);
    }

    static SaveArchiveReadResult readValid(Path archive) {
        SaveArchiveReadResult result = reader().read(archive);
        assertEquals(SaveArchiveReadResult.Status.VALID, result.status());
        return result;
    }

    static void writeArchive(
            Path archive, SaveGameSnapshot snapshot, Instant modified) throws IOException {
        new SaveArchiveWriter().write(archive, CODEC.encode(snapshot, modified));
    }

    static SaveRepository repository(Path root, SaveFileOperations files) {
        return SaveRepository.open(root, reader(), files);
    }

    static FileSaveCatalog catalog(Path root) {
        return new FileSaveCatalog(repository(root, new JdkSaveFileOperations()));
    }

    static void assertClosedDiagnostics(List<SaveDiagnostic> diagnostics, Path secretPath) {
        assertFalse(diagnostics.isEmpty());
        for (SaveDiagnostic diagnostic : diagnostics) {
            assertTrue(diagnostic.code().codePointCount(0, diagnostic.code().length()) <= 96);
            assertTrue(diagnostic.message().codePointCount(0, diagnostic.message().length())
                    <= SaveDiagnostic.MAX_MESSAGE_CODE_POINTS);
            assertFalse(diagnostic.message().contains(secretPath.toString()));
            assertFalse(diagnostic.message().contains("injected"));
        }
    }

    static void replaceManifest(Path archive, String manifest) throws IOException {
        List<ZipFixtureEntry> entries = readZipEntries(archive);
        List<ZipFixtureEntry> replaced = new ArrayList<>();
        for (ZipFixtureEntry entry : entries) {
            replaced.add(entry.name().equals("manifest.json")
                    ? new ZipFixtureEntry(
                            "manifest.json", manifest.getBytes(StandardCharsets.UTF_8))
                    : entry);
        }
        writeZip(archive, replaced);
    }

    static List<ZipFixtureEntry> readZipEntries(Path archive) throws IOException {
        List<ZipFixtureEntry> entries = new ArrayList<>();
        try (var input = new java.util.zip.ZipInputStream(Files.newInputStream(archive))) {
            for (var entry = input.getNextEntry(); entry != null; entry = input.getNextEntry()) {
                entries.add(new ZipFixtureEntry(entry.getName(), input.readAllBytes()));
            }
        }
        return entries;
    }

    static void writeZip(Path archive, List<ZipFixtureEntry> entries) throws IOException {
        try (var output = new java.util.zip.ZipOutputStream(Files.newOutputStream(archive))) {
            for (ZipFixtureEntry entry : entries) {
                output.putNextEntry(new java.util.zip.ZipEntry(entry.name()));
                output.write(entry.bytes());
                output.closeEntry();
            }
        }
    }
}

record ZipFixtureEntry(String name, byte[] bytes) {
    ZipFixtureEntry {
        bytes = bytes.clone();
    }

    @Override
    public byte[] bytes() {
        return bytes.clone();
    }
}

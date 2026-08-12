package com.gaia.save.store;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
import com.gaia.save.format.SaveGameManifest;
import com.gaia.save.snapshot.InventorySaveSnapshot;
import com.gaia.save.snapshot.PlayerSaveSnapshot;
import com.gaia.save.snapshot.SaveGameSnapshot;
import com.gaia.save.snapshot.WorldItemsSaveSnapshot;
import com.overlord.interaction.api.EntityRef;
import com.overlord.inventory.api.BodySlot;
import com.overlord.voxel.ChunkKey;
import com.overlord.voxel.ChunkRepositorySnapshot;
import com.overlord.voxel.ChunkSnapshot;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AtomicSaveStoreTest {
    private static final Instant FIRST_MODIFIED =
            Instant.parse("2026-08-10T13:00:00Z");
    private static final Instant SECOND_MODIFIED =
            Instant.parse("2026-08-10T13:05:00Z");

    @TempDir Path tempDir;

    @Test
    void firstSaveCommitsTheExactManifestWithoutCreatingABackup() throws Exception {
        Path worldDir = Files.createDirectories(tempDir.resolve("first-save"));
        RecordingSaveFileOperations files = AtomicSaveStoreTestSupport.operations();
        SaveGameSnapshot snapshot = AtomicSaveStoreTestSupport.snapshot(
                "First Save", 101L, 43L);
        SaveGameManifest expectedManifest =
                AtomicSaveStoreTestSupport.codec().encode(snapshot, FIRST_MODIFIED).manifest();

        SaveWriteResult result = AtomicSaveStoreTestSupport.store(worldDir, files)
                .save(snapshot, FIRST_MODIFIED);

        assertEquals(SaveWriteResult.Status.SUCCESS, result.status());
        assertEquals(Optional.of(expectedManifest), result.committedManifest());
        assertTrue(result.diagnostics().isEmpty());
        AtomicSaveStoreTestSupport.assertValidSnapshot(
                worldDir.resolve("current.glsave"), snapshot);
        assertFalse(Files.exists(worldDir.resolve("backup.glsave")));
        assertFalse(files.calls().contains(FileOperation.DELETE_TEMP));
        AtomicSaveStoreTestSupport.assertNoTaskTemps(worldDir);
    }

    @Test
    void repeatedSaveRotatesThePreviouslyCommittedBytesToBackup() throws Exception {
        Path worldDir = Files.createDirectories(tempDir.resolve("repeated-save"));
        RecordingSaveFileOperations files = AtomicSaveStoreTestSupport.operations();
        AtomicSaveStore store = AtomicSaveStoreTestSupport.store(worldDir, files);
        SaveGameSnapshot first = AtomicSaveStoreTestSupport.snapshot(
                "Repeated Save", 202L, 51L);
        SaveGameSnapshot second = AtomicSaveStoreTestSupport.snapshot(
                "Repeated Save", 202L, 52L);

        SaveWriteResult firstResult = store.save(first, FIRST_MODIFIED);
        assertEquals(SaveWriteResult.Status.SUCCESS, firstResult.status());
        byte[] firstCommittedBytes = Files.readAllBytes(
                worldDir.resolve("current.glsave"));
        files.clearCalls();

        SaveWriteResult secondResult = store.save(second, SECOND_MODIFIED);

        assertEquals(SaveWriteResult.Status.SUCCESS, secondResult.status());
        assertEquals(
                Optional.of(AtomicSaveStoreTestSupport.codec()
                        .encode(second, SECOND_MODIFIED)
                        .manifest()),
                secondResult.committedManifest());
        AtomicSaveStoreTestSupport.assertValidSnapshot(
                worldDir.resolve("current.glsave"), second);
        AtomicSaveStoreTestSupport.assertValidArchiveBytes(
                worldDir.resolve("backup.glsave"), firstCommittedBytes);
        assertTrue(files.indexOf(FileOperation.MOVE_ATOMIC_BACKUP)
                < files.indexOf(FileOperation.MOVE_ATOMIC_CURRENT));
        assertFalse(files.calls().contains(FileOperation.DELETE_TEMP));
        AtomicSaveStoreTestSupport.assertNoTaskTemps(worldDir);
    }

    @Test
    void bothUnsupportedAtomicMovesBuildAndForceBackupBeforeFallbackCurrentReplace()
            throws Exception {
        PreparedWorld world = AtomicSaveStoreTestSupport.preparedWorld(
                tempDir.resolve("both-unsupported"));
        RecordingSaveFileOperations files = AtomicSaveStoreTestSupport.operations();
        files.failBefore(
                FileOperation.MOVE_ATOMIC_BACKUP,
                AtomicSaveStoreTestSupport.unsupported("backup"));
        files.failBefore(
                FileOperation.MOVE_ATOMIC_CURRENT,
                AtomicSaveStoreTestSupport.unsupported("current"));
        SaveGameSnapshot replacement = AtomicSaveStoreTestSupport.snapshot(
                "Atomic Fallback", 303L, 61L);

        SaveWriteResult result = AtomicSaveStoreTestSupport.store(world.directory(), files)
                .save(replacement, SECOND_MODIFIED);

        assertEquals(SaveWriteResult.Status.SUCCESS, result.status());
        assertEquals(
                List.of(
                        FileOperation.CREATE_TEMP,
                        FileOperation.FORCE_TEMP,
                        FileOperation.CREATE_TEMP,
                        FileOperation.COPY_BACKUP,
                        FileOperation.FORCE_BACKUP,
                        FileOperation.MOVE_ATOMIC_BACKUP,
                        FileOperation.MOVE_REPLACE_BACKUP,
                        FileOperation.FORCE_DIRECTORY,
                        FileOperation.MOVE_ATOMIC_CURRENT,
                        FileOperation.MOVE_REPLACE_CURRENT,
                        FileOperation.FORCE_DIRECTORY),
                files.calls());
        AtomicSaveStoreTestSupport.assertValidArchiveBytes(
                world.backup(), world.oldCurrentBytes());
        AtomicSaveStoreTestSupport.assertValidSnapshot(world.current(), replacement);
        assertFalse(files.calls().contains(FileOperation.DELETE_TEMP));
        AtomicSaveStoreTestSupport.assertNoTaskTemps(world.directory());
    }

    @Test
    void unsupportedCurrentAtomicMoveOnFirstSaveUsesFallbackWithoutPublishingBackup()
            throws Exception {
        Path worldDir = Files.createDirectories(tempDir.resolve("first-fallback"));
        RecordingSaveFileOperations files = AtomicSaveStoreTestSupport.operations();
        files.failBefore(
                FileOperation.MOVE_ATOMIC_CURRENT,
                AtomicSaveStoreTestSupport.unsupported("current"));
        SaveGameSnapshot snapshot = AtomicSaveStoreTestSupport.snapshot(
                "First Fallback", 404L, 71L);

        SaveWriteResult result = AtomicSaveStoreTestSupport.store(worldDir, files)
                .save(snapshot, FIRST_MODIFIED);

        assertEquals(SaveWriteResult.Status.SUCCESS, result.status());
        assertEquals(
                List.of(
                        FileOperation.CREATE_TEMP,
                        FileOperation.FORCE_TEMP,
                        FileOperation.MOVE_ATOMIC_CURRENT,
                        FileOperation.MOVE_REPLACE_CURRENT,
                        FileOperation.FORCE_DIRECTORY),
                files.calls());
        AtomicSaveStoreTestSupport.assertValidSnapshot(
                worldDir.resolve("current.glsave"), snapshot);
        assertFalse(Files.exists(worldDir.resolve("backup.glsave")));
        AtomicSaveStoreTestSupport.assertNoTaskTemps(worldDir);
    }

    @Test
    void publicBoundaryDerivesDirectWorldDirectoryFromSaveRootAndId()
            throws Exception {
        Path saveRoot = Files.createDirectories(tempDir.resolve("safe-root"));
        SaveGameId saveGameId = AtomicSaveStoreTestSupport.saveGameId();
        RecordingSaveFileOperations files = AtomicSaveStoreTestSupport.operations();
        SaveGameSnapshot snapshot = AtomicSaveStoreTestSupport.snapshot(
                "Root Derived", 505L, 81L);

        SaveWriteResult result = AtomicSaveStoreTestSupport.publicStore(
                        saveRoot, saveGameId, files)
                .save(snapshot, FIRST_MODIFIED);

        assertEquals(SaveWriteResult.Status.SUCCESS, result.status());
        AtomicSaveStoreTestSupport.assertValidSnapshot(
                saveRoot.resolve(saveGameId.value()).resolve("current.glsave"), snapshot);
        assertFalse(Files.exists(saveRoot.resolve("current.glsave")));
    }

    @Test
    void arbitraryWorldDirectoryConstructorIsNotAPublicBoundary()
            throws Exception {
        Constructor<AtomicSaveStore> constructor = AtomicSaveStore.class.getDeclaredConstructor(
                Path.class,
                SaveSnapshotCodec.class,
                SaveArchiveWriter.class,
                SaveArchiveReader.class,
                SaveFileOperations.class);

        assertFalse(Modifier.isPublic(constructor.getModifiers()));
    }
}

final class AtomicSaveStoreTestSupport {
    private static final int WORLD_HEIGHT = 16;
    private static final int CHUNK_RADIUS = 4;
    private static final Instant CREATED = Instant.parse("2026-08-10T12:00:00Z");
    private static final Instant OLD_BACKUP_MODIFIED =
            Instant.parse("2026-08-10T12:10:00Z");
    private static final Instant OLD_CURRENT_MODIFIED =
            Instant.parse("2026-08-10T12:20:00Z");
    private static final EntityRef OWNER = new EntityRef(7);
    private static final SaveSnapshotCodec CODEC = new SaveSnapshotCodec(
            new ChunkSectionCodec(),
            new PlayerSectionCodec(),
            new InventorySectionCodec(),
            new WorldItemsSectionCodec());

    private AtomicSaveStoreTestSupport() {}

    static SaveSnapshotCodec codec() {
        return CODEC;
    }

    static SaveGameId saveGameId() {
        return SaveGameId.parse("123e4567-e89b-12d3-a456-426614174000");
    }

    static RecordingSaveFileOperations operations() {
        return new RecordingSaveFileOperations(new JdkSaveFileOperations());
    }

    static AtomicSaveStore store(Path worldDir, RecordingSaveFileOperations files) {
        return store(worldDir, files, CODEC);
    }

    static AtomicSaveStore store(
            Path worldDir,
            RecordingSaveFileOperations files,
            SaveSnapshotCodec codec) {
        return new AtomicSaveStore(
                worldDir,
                codec,
                new SaveArchiveWriter(),
                new SaveArchiveReader(codec),
                files);
    }

    static AtomicSaveStore publicStore(
            Path saveRoot,
            SaveGameId saveGameId,
            RecordingSaveFileOperations files) {
        try {
            Constructor<AtomicSaveStore> constructor = AtomicSaveStore.class.getConstructor(
                    Path.class,
                    SaveGameId.class,
                    SaveSnapshotCodec.class,
                    SaveArchiveWriter.class,
                    SaveArchiveReader.class,
                    SaveFileOperations.class);
            return constructor.newInstance(
                    saveRoot,
                    saveGameId,
                    CODEC,
                    new SaveArchiveWriter(),
                    new SaveArchiveReader(CODEC),
                    files);
        } catch (NoSuchMethodException failure) {
            throw new AssertionError(
                    "AtomicSaveStore requires a public saveRoot + SaveGameId boundary",
                    failure);
        } catch (InstantiationException | IllegalAccessException failure) {
            throw new AssertionError("The public save-store boundary is not usable", failure);
        } catch (InvocationTargetException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof RuntimeException runtimeFailure) {
                throw runtimeFailure;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new AssertionError("The public save-store boundary failed", cause);
        }
    }

    static PreparedWorld preparedWorld(Path worldDir) throws IOException {
        Files.createDirectories(worldDir);
        Path current = worldDir.resolve("current.glsave");
        Path backup = worldDir.resolve("backup.glsave");
        writeArchive(
                backup,
                snapshot("Old Backup", 11L, 39L),
                OLD_BACKUP_MODIFIED);
        writeArchive(
                current,
                snapshot("Old Current", 12L, 40L),
                OLD_CURRENT_MODIFIED);
        byte[] oldCurrentBytes = Files.readAllBytes(current);
        byte[] oldBackupBytes = Files.readAllBytes(backup);
        assertValidArchiveBytes(current, oldCurrentBytes);
        assertValidArchiveBytes(backup, oldBackupBytes);
        assertFalse(java.util.Arrays.equals(oldCurrentBytes, oldBackupBytes));
        return new PreparedWorld(
                worldDir, current, backup, oldCurrentBytes, oldBackupBytes);
    }

    static SaveGameSnapshot snapshot(String displayName, long seed, long fixedTick) {
        List<ChunkSnapshot> chunks = new ArrayList<>();
        long revision = 0;
        for (int x = -CHUNK_RADIUS; x <= CHUNK_RADIUS; x++) {
            for (int z = -CHUNK_RADIUS; z <= CHUNK_RADIUS; z++) {
                byte[] blocks = new byte[16 * WORLD_HEIGHT * 16];
                blocks[Math.floorMod(x * 31 + z * 17 + (int) seed, blocks.length)] =
                        (byte) (1 + Math.floorMod(x + z + (int) seed, 6));
                chunks.add(ChunkSnapshot.of(
                        new ChunkKey(x, z), ++revision, WORLD_HEIGHT, blocks));
            }
        }
        return new SaveGameSnapshot(
                new SaveGameSnapshot.StaticMetadata(
                        SaveFormatVersion.CURRENT,
                        "0.2.0-alpha.1",
                        SaveGameId.parse("123e4567-e89b-12d3-a456-426614174000"),
                        displayName,
                        CREATED,
                        seed,
                        "v1",
                        "b".repeat(64),
                        CHUNK_RADIUS,
                        WORLD_HEIGHT,
                        Optional.of("Atomic store fixture")),
                fixedTick,
                new ChunkRepositorySnapshot(WORLD_HEIGHT, revision, chunks),
                new PlayerSaveSnapshot(
                        OWNER,
                        1.25,
                        20.5,
                        -3.75,
                        0.125,
                        -0.25,
                        0.5,
                        90.0,
                        -12.5,
                        GameMode.SURVIVAL,
                        false),
                new InventorySaveSnapshot(
                        OWNER, Map.of(), BodySlot.LEFT_HAND, false, 3L),
                new WorldItemsSaveSnapshot(fixedTick, List.of(), 0L, false));
    }

    static void assertValidSnapshot(Path archive, SaveGameSnapshot expected) {
        SaveArchiveReadResult result = new SaveArchiveReader(CODEC).read(archive);
        assertEquals(SaveArchiveReadResult.Status.VALID, result.status(), archive.toString());
        assertEquals(expected, result.snapshot().orElseThrow());
    }

    static void assertValidArchiveBytes(Path archive, byte[] expected) throws IOException {
        assertTrue(Files.isRegularFile(archive), archive.toString());
        assertArrayEquals(expected, Files.readAllBytes(archive), archive.toString());
        assertEquals(
                SaveArchiveReadResult.Status.VALID,
                new SaveArchiveReader(CODEC).read(archive).status(),
                archive.toString());
    }

    static void assertOldCurrentSurvives(PreparedWorld world) throws IOException {
        boolean currentMatches = validArchiveEquals(
                world.current(), world.oldCurrentBytes());
        boolean backupMatches = validArchiveEquals(
                world.backup(), world.oldCurrentBytes());
        assertTrue(currentMatches || backupMatches,
                "OLD_CURRENT must survive byte-for-byte as a validated slot archive");
    }

    static void assertUnchangedOldSlots(PreparedWorld world) throws IOException {
        assertValidArchiveBytes(world.current(), world.oldCurrentBytes());
        assertValidArchiveBytes(world.backup(), world.oldBackupBytes());
    }

    static void assertNoCatalogValidSlot(Path worldDir) {
        assertFalse(validArchive(worldDir.resolve("current.glsave")));
        assertFalse(validArchive(worldDir.resolve("backup.glsave")));
    }

    static void assertNoTaskTemps(Path worldDir) throws IOException {
        try (Stream<Path> entries = Files.list(worldDir)) {
            assertTrue(entries
                    .map(path -> path.getFileName().toString())
                    .noneMatch(name -> name.endsWith(".tmp")));
        }
    }

    static void assertFailurePublishesNoCommittedManifest(
            SaveWriteResult result, Path worldDir) {
        assertNotEquals(SaveWriteResult.Status.SUCCESS, result.status());
        assertTrue(result.committedManifest().isEmpty());
        assertFalse(result.diagnostics().isEmpty());
        for (SaveDiagnostic diagnostic : result.diagnostics()) {
            assertTrue(diagnostic.code().codePointCount(0, diagnostic.code().length()) <= 96);
            assertTrue(diagnostic.message().codePointCount(0, diagnostic.message().length())
                    <= SaveDiagnostic.MAX_MESSAGE_CODE_POINTS);
            assertFalse(diagnostic.message().contains(worldDir.toString()));
        }
    }

    static Throwable primaryFailure(SaveWriteResult result) {
        return result.diagnostics().get(0).cause().orElseThrow();
    }

    static java.nio.file.AtomicMoveNotSupportedException unsupported(String target) {
        return new java.nio.file.AtomicMoveNotSupportedException(
                "source", target, "injected atomic move unsupported");
    }

    private static void writeArchive(
            Path archive, SaveGameSnapshot snapshot, Instant modified) throws IOException {
        new SaveArchiveWriter().write(archive, CODEC.encode(snapshot, modified));
    }

    private static boolean validArchiveEquals(Path archive, byte[] expected)
            throws IOException {
        return Files.isRegularFile(archive)
                && java.util.Arrays.equals(expected, Files.readAllBytes(archive))
                && new SaveArchiveReader(CODEC).read(archive).status()
                        == SaveArchiveReadResult.Status.VALID;
    }

    private static boolean validArchive(Path archive) {
        return Files.isRegularFile(archive)
                && new SaveArchiveReader(CODEC).read(archive).status()
                        == SaveArchiveReadResult.Status.VALID;
    }
}

record PreparedWorld(
        Path directory,
        Path current,
        Path backup,
        byte[] oldCurrentBytes,
        byte[] oldBackupBytes) {
    PreparedWorld {
        oldCurrentBytes = oldCurrentBytes.clone();
        oldBackupBytes = oldBackupBytes.clone();
    }

    @Override
    public byte[] oldCurrentBytes() {
        return oldCurrentBytes.clone();
    }

    @Override
    public byte[] oldBackupBytes() {
        return oldBackupBytes.clone();
    }
}

enum FileOperation {
    CREATE_TEMP,
    FORCE_TEMP,
    FORCE_BACKUP,
    MOVE_ATOMIC_BACKUP,
    MOVE_ATOMIC_CURRENT,
    MOVE_REPLACE_BACKUP,
    MOVE_REPLACE_CURRENT,
    COPY_BACKUP,
    DELETE_TEMP,
    FORCE_DIRECTORY
}

final class RecordingSaveFileOperations implements SaveFileOperations {
    private final SaveFileOperations delegate;
    private final List<FileOperation> calls = new ArrayList<>();
    private final Map<FileOperation, InjectedAction> before =
            new EnumMap<>(FileOperation.class);
    private final Map<FileOperation, InjectedAction> after =
            new EnumMap<>(FileOperation.class);
    private final List<Path> createdTemps = new ArrayList<>();
    private final Map<Path, String> tempTargets = new HashMap<>();
    private final List<Path> deletedPaths = new ArrayList<>();
    private final List<Path> forcedDirectories = new ArrayList<>();
    private Path returnedTemp;

    RecordingSaveFileOperations(SaveFileOperations delegate) {
        this.delegate = delegate;
    }

    public Path createSiblingTemp(
            Path directory,
            String targetName,
            MutationGuard mutationGuard) throws IOException {
        FileOperation operation = FileOperation.CREATE_TEMP;
        record(operation);
        run(before.get(operation), directory, null);
        Path temporary;
        if (returnedTemp == null) {
            temporary = delegate.createSiblingTemp(
                    directory, targetName, mutationGuard);
        } else {
            mutationGuard.validate();
            temporary = returnedTemp;
        }
        createdTemps.add(temporary);
        tempTargets.put(temporary.toAbsolutePath().normalize(), targetName);
        run(after.get(operation), temporary, null);
        return temporary;
    }

    public void forceFile(Path file, MutationGuard mutationGuard) throws IOException {
        FileOperation operation = "backup.glsave".equals(
                tempTargets.getOrDefault(
                        file.toAbsolutePath().normalize(),
                        file.getFileName().toString()))
                ? FileOperation.FORCE_BACKUP
                : FileOperation.FORCE_TEMP;
        record(operation);
        run(before.get(operation), file, null);
        delegate.forceFile(file, mutationGuard);
        run(after.get(operation), file, null);
    }

    public void moveAtomicReplacing(
            Path source,
            Path destination,
            MutationGuard mutationGuard) throws IOException {
        FileOperation operation = destination.getFileName().toString().equals("backup.glsave")
                ? FileOperation.MOVE_ATOMIC_BACKUP
                : FileOperation.MOVE_ATOMIC_CURRENT;
        record(operation);
        run(before.get(operation), source, destination);
        delegate.moveAtomicReplacing(source, destination, mutationGuard);
        run(after.get(operation), source, destination);
    }

    public void moveReplacing(
            Path source,
            Path destination,
            MutationGuard mutationGuard) throws IOException {
        FileOperation operation = destination.getFileName().toString().equals("backup.glsave")
                ? FileOperation.MOVE_REPLACE_BACKUP
                : FileOperation.MOVE_REPLACE_CURRENT;
        record(operation);
        run(before.get(operation), source, destination);
        delegate.moveReplacing(source, destination, mutationGuard);
        run(after.get(operation), source, destination);
    }

    public void copyReplacing(
            Path source,
            Path destination,
            MutationGuard mutationGuard) throws IOException {
        FileOperation operation = FileOperation.COPY_BACKUP;
        record(operation);
        run(before.get(operation), source, destination);
        delegate.copyReplacing(source, destination, mutationGuard);
        run(after.get(operation), source, destination);
    }

    public boolean deleteIfExists(Path path, MutationGuard mutationGuard)
            throws IOException {
        FileOperation operation = FileOperation.DELETE_TEMP;
        record(operation);
        deletedPaths.add(path.toAbsolutePath().normalize());
        run(before.get(operation), path, null);
        boolean deleted = delegate.deleteIfExists(path, mutationGuard);
        run(after.get(operation), path, null);
        return deleted;
    }

    public void forceDirectoryBestEffort(
            Path directory, MutationGuard mutationGuard) throws IOException {
        FileOperation operation = FileOperation.FORCE_DIRECTORY;
        record(operation);
        forcedDirectories.add(directory.toAbsolutePath().normalize());
        run(before.get(operation), directory, null);
        delegate.forceDirectoryBestEffort(directory, mutationGuard);
        run(after.get(operation), directory, null);
    }

    void failBefore(FileOperation operation, Throwable failure) {
        before.put(operation, (source, destination) -> throwFailure(failure));
    }

    void runBefore(FileOperation operation, InjectedAction action) {
        before.put(operation, action);
    }

    void failBeforeOccurrence(
            FileOperation operation, int targetOccurrence, Throwable failure) {
        if (targetOccurrence < 1) {
            throw new IllegalArgumentException("targetOccurrence must be positive");
        }
        int[] occurrence = {0};
        before.put(operation, (source, destination) -> {
            occurrence[0]++;
            if (occurrence[0] == targetOccurrence) {
                throwFailure(failure);
            }
        });
    }

    void runAfter(FileOperation operation, InjectedAction action) {
        after.put(operation, action);
    }

    void returnTemp(Path temporary) {
        returnedTemp = temporary;
    }

    List<FileOperation> calls() {
        return List.copyOf(calls);
    }

    int indexOf(FileOperation operation) {
        int index = calls.indexOf(operation);
        assertTrue(index >= 0, () -> operation + " absent from " + calls);
        return index;
    }

    List<Path> createdTemps() {
        return List.copyOf(createdTemps);
    }

    List<Path> deletedPaths() {
        return List.copyOf(deletedPaths);
    }

    List<Path> forcedDirectories() {
        return List.copyOf(forcedDirectories);
    }

    void clearCalls() {
        calls.clear();
        deletedPaths.clear();
        forcedDirectories.clear();
    }

    private void record(FileOperation operation) {
        calls.add(operation);
    }

    private static void run(
            InjectedAction action, Path source, Path destination) throws IOException {
        if (action != null) {
            action.run(source, destination);
        }
    }

    private static void throwFailure(Throwable failure) throws IOException {
        if (failure instanceof IOException ioFailure) {
            throw ioFailure;
        }
        if (failure instanceof RuntimeException runtimeFailure) {
            throw runtimeFailure;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        throw new AssertionError("Unsupported injected Throwable type", failure);
    }
}

@FunctionalInterface
interface InjectedAction {
    void run(Path source, Path destination) throws IOException;
}

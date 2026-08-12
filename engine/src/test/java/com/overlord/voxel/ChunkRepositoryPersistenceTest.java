package com.overlord.voxel;

import static com.overlord.voxel.ChunkRepositoryRestoreResult.Status.GENERATION_ACTIVE;
import static com.overlord.voxel.ChunkRepositoryRestoreResult.Status.INVALID_SNAPSHOT;
import static com.overlord.voxel.ChunkRepositoryRestoreResult.Status.RESTORED;
import static com.overlord.voxel.ChunkRepositoryRestoreResult.Status.TARGET_NOT_FRESH;
import static com.overlord.voxel.ChunkRepositoryRestoreResult.Status.TARGET_NOT_EMPTY;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.overlord.config.GameConfig;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.Test;

class ChunkRepositoryPersistenceTest {
    private static final int WORLD_HEIGHT = 32;
    private static final int BLOCK_COUNT =
            GameConfig.Chunk.SIZE
                    * WORLD_HEIGHT
                    * GameConfig.Chunk.SIZE;

    @Test
    void canonicalCaptureIsSortedImmutableAndOwnsChunkBytes() {
        ChunkRepository repository = repository();
        ChunkKey first = new ChunkKey(1, 4);
        ChunkKey second = new ChunkKey(-1, 3);
        ChunkKey third = new ChunkKey(1, -2);
        setOrigin(repository, first, (byte) 4);
        setOrigin(repository, second, (byte) 5);
        setOrigin(repository, third, (byte) 6);

        ChunkRepositorySnapshot saved = repository.canonicalSnapshot();

        assertEquals(WORLD_HEIGHT, saved.worldHeight());
        assertEquals(3L, saved.revisionHighWater());
        assertEquals(
                List.of(second, third, first),
                saved.chunks().stream().map(ChunkSnapshot::key).toList());
        assertEquals(
                Map.of(first, 1L, second, 2L, third, 3L),
                saved.chunks().stream()
                        .collect(
                                java.util.stream.Collectors.toMap(
                                        ChunkSnapshot::key,
                                        ChunkSnapshot::revision)));
        assertThrows(
                UnsupportedOperationException.class,
                () -> saved.chunks().add(chunk(8, 8, 4L, (byte) 1)));

        ChunkSnapshot captured = saved.chunks().get(0);
        byte[] firstCopy = captured.copyBlocks();
        byte[] secondCopy = captured.copyBlocks();
        assertNotSame(firstCopy, secondCopy);
        firstCopy[0] = 99;
        assertEquals(5, Byte.toUnsignedInt(captured.copyBlocks()[0]));
    }

    @Test
    void restorePublishesAllChunksAndAdvancesRevisionAboveSavedHighWater() {
        ChunkRepository target = repository();
        ChunkRepositorySnapshot saved =
                snapshot(
                        91L,
                        chunk(1, -2, 77L, (byte) 7),
                        chunk(-1, 3, 80L, (byte) 8));

        ChunkRepositoryRestoreResult result = target.restoreCanonical(saved);

        assertEquals(RESTORED, result.status());
        assertEquals(2, result.restoredChunkCount());
        assertEquals(
                List.of(new ChunkKey(-1, 3), new ChunkKey(1, -2)),
                sorted(target.keys()));
        assertEquals(77L, target.revision(new ChunkKey(1, -2)));
        assertEquals(80L, target.revision(new ChunkKey(-1, 3)));
        assertEquals(7, Byte.toUnsignedInt(target.getBlock(16, 0, -32)));
        assertEquals(8, Byte.toUnsignedInt(target.getBlock(-16, 0, 48)));
        assertEquals(ChunkState.DIRTY, target.state(new ChunkKey(1, -2)));
        assertEquals(ChunkState.DIRTY, target.state(new ChunkKey(-1, 3)));
        assertEquals(target.keys(), target.meshingCandidates());

        assertTrue(target.setBlock(0, 1, 0, (byte) 2));
        assertTrue(target.revision(new ChunkKey(0, 0)) > 91L);
    }

    @Test
    void invalidSnapshotVariantsRejectWithoutPublishingOrAdvancingRevision() {
        ChunkSnapshot malformed = chunk(4, 4, 1L, (byte) 4);
        corruptBlockLength(malformed);
        List<ChunkRepositorySnapshot> invalidSnapshots =
                List.of(
                        snapshot(
                                4L,
                                chunk(2, 2, 3L, (byte) 1),
                                chunk(2, 2, 4L, (byte) 2)),
                        snapshot(5L, chunk(3, 3, 6L, (byte) 3)),
                        snapshot(1L, chunk(3, 4, 0L, (byte) 3)),
                        new ChunkRepositorySnapshot(
                                WORLD_HEIGHT, -1L, List.of()),
                        snapshot(Long.MAX_VALUE, chunk(3, 5, 1L, (byte) 3)),
                        new ChunkRepositorySnapshot(
                                16,
                                1L,
                                List.of(chunk(3, 6, 1L, 16, (byte) 3))),
                        snapshot(1L, malformed));

        for (ChunkRepositorySnapshot invalid : invalidSnapshots) {
            ChunkRepository target = repository();

            ChunkRepositoryRestoreResult result =
                    target.restoreCanonical(invalid);

            assertEquals(INVALID_SNAPSHOT, result.status());
            assertEquals(0, result.restoredChunkCount());
            assertTrue(target.keys().isEmpty());
            assertTrue(target.setBlock(0, 0, 0, (byte) 1));
            assertEquals(1L, target.revision(new ChunkKey(0, 0)));
        }
    }

    @Test
    void nonemptyTargetRejectsWithoutChangingExistingCanonicalState() {
        ChunkRepository target = repository();
        setOrigin(target, new ChunkKey(5, 5), (byte) 12);

        ChunkRepositoryRestoreResult result =
                target.restoreCanonical(
                        snapshot(8L, chunk(0, 0, 8L, (byte) 3)));

        assertEquals(TARGET_NOT_EMPTY, result.status());
        assertEquals(0, result.restoredChunkCount());
        assertEquals(List.of(new ChunkKey(5, 5)), sorted(target.keys()));
        assertEquals(1L, target.revision(new ChunkKey(5, 5)));
        assertEquals(
                12,
                Byte.toUnsignedInt(
                        target.getBlock(
                                5 * GameConfig.Chunk.SIZE,
                                0,
                                5 * GameConfig.Chunk.SIZE)));
    }

    @Test
    void activeGenerationRejectsCaptureAndRestoreWithoutChangingTarget() {
        ChunkRepository target = repository();
        ChunkKey generating = new ChunkKey(7, -3);
        ChunkGenerationTicket ticket =
                target.beginGeneration(
                        generating, ChunkGenerationMode.INITIAL);

        assertThrows(IllegalStateException.class, target::canonicalSnapshot);
        ChunkRepositoryRestoreResult result =
                target.restoreCanonical(
                        snapshot(2L, chunk(0, 0, 2L, (byte) 1)));

        assertEquals(GENERATION_ACTIVE, result.status());
        assertTrue(target.keys().isEmpty());
        assertEquals(
                ChunkGenerationResult.Status.COMMITTED,
                target.commitGeneration(
                                ticket,
                                new ChunkGenerationData(
                                        generating,
                                        WORLD_HEIGHT,
                                        new byte[BLOCK_COUNT]))
                        .status());
        assertEquals(1L, target.revision(generating));
    }

    @Test
    void captureRejectsWhenRevisionChangesWhileEntriesAreCopied()
            throws InterruptedException {
        ChunkRepository repository = repository();
        ChunkKey blockedKey = new ChunkKey(-2, -2);
        ChunkKey changedKey = new ChunkKey(2, 2);
        setOrigin(repository, blockedKey, (byte) 1);
        setOrigin(repository, changedKey, (byte) 2);
        Object blockedEntry = capturedEntry(repository, blockedKey);
        AtomicReference<Throwable> captureFailure = new AtomicReference<>();
        Thread capture =
                new Thread(
                        () -> {
                            try {
                                repository.canonicalSnapshot();
                            } catch (Throwable failure) {
                                captureFailure.set(failure);
                            }
                        },
                        "canonical-chunk-capture");
        capture.setDaemon(true);

        synchronized (blockedEntry) {
            capture.start();
            awaitBlocked(capture);
            assertTrue(
                    repository.setBlock(
                            changedKey.worldOriginX(),
                            1,
                            changedKey.worldOriginZ(),
                            (byte) 9));
        }
        capture.join(TimeUnit.SECONDS.toMillis(5));

        assertTrue(captureFailure.get() instanceof IllegalStateException);
    }

    @Test
    void revisionExhaustionThrowsWithoutWrappingOrChangingChunk() {
        ChunkRepository target = repository();
        ChunkKey key = new ChunkKey(0, 0);
        assertEquals(
                RESTORED,
                target.restoreCanonical(
                                snapshot(
                                        Long.MAX_VALUE - 1,
                                        chunk(0, 0, 1L, (byte) 1)))
                        .status());
        assertTrue(target.setBlock(0, 0, 0, (byte) 2));
        assertEquals(Long.MAX_VALUE, target.revision(key));

        assertThrows(
                IllegalStateException.class,
                () -> target.setBlock(0, 0, 0, (byte) 3));
        assertEquals(Long.MAX_VALUE, target.revision(key));
        assertEquals(2, Byte.toUnsignedInt(target.getBlock(0, 0, 0)));
    }

    @Test
    void boundaryMutationRejectsBeforeChangingPrimaryOrLoadedNeighborWhenRevisionBudgetIsExhausted() {
        ChunkRepository target = repository();
        ChunkKey primary = new ChunkKey(0, 0);
        ChunkKey east = primary.east();
        assertEquals(
                RESTORED,
                target.restoreCanonical(
                                snapshot(
                                        Long.MAX_VALUE - 1,
                                        chunk(0, 0, 10L, (byte) 1),
                                        chunk(1, 0, 11L, (byte) 2)))
                        .status());
        ChunkRepositorySnapshot before = target.canonicalSnapshot();
        List<ChunkKey> keysBefore = sorted(target.keys());
        List<ChunkKey> candidatesBefore =
                sorted(target.meshingCandidates());

        IllegalStateException failure =
                assertThrows(
                        IllegalStateException.class,
                        () ->
                                target.setBlock(
                                        GameConfig.Chunk.SIZE - 1,
                                        1,
                                        1,
                                        (byte) 9));

        assertEquals("Chunk revision sequence is exhausted", failure.getMessage());
        assertEquals(keysBefore, sorted(target.keys()));
        assertEquals(candidatesBefore, sorted(target.meshingCandidates()));
        assertEquals(1, Byte.toUnsignedInt(target.getBlock(0, 0, 0)));
        assertEquals(0, Byte.toUnsignedInt(target.getBlock(15, 1, 1)));
        assertEquals(2, Byte.toUnsignedInt(target.getBlock(16, 0, 0)));
        assertEquals(10L, target.revision(primary));
        assertEquals(11L, target.revision(east));
        assertEquals(ChunkState.DIRTY, target.state(primary));
        assertEquals(ChunkState.DIRTY, target.state(east));
        assertCanonicalEquals(before, target.canonicalSnapshot());
    }

    @Test
    void exhaustedMutationOfAbsentChunkDoesNotPublishGhostEntry() {
        ChunkRepository target = repository();
        ChunkKey budget = new ChunkKey(4, 4);
        ChunkKey absent = new ChunkKey(-3, -3);
        assertEquals(
                RESTORED,
                target.restoreCanonical(
                                snapshot(
                                        Long.MAX_VALUE - 1,
                                        chunk(4, 4, 20L, (byte) 1)))
                        .status());
        assertTrue(
                target.setBlock(
                        budget.worldOriginX() + 1,
                        1,
                        budget.worldOriginZ() + 1,
                        (byte) 2));
        List<ChunkKey> keysBefore = sorted(target.keys());

        assertThrows(
                IllegalStateException.class,
                () ->
                        target.setBlock(
                                absent.worldOriginX(),
                                0,
                                absent.worldOriginZ(),
                                (byte) 3));

        assertFalse(target.contains(absent));
        assertEquals(keysBefore, sorted(target.keys()));
        assertFalse(target.meshingCandidates().contains(absent));
        ChunkGenerationTicket ticket =
                target.beginGeneration(
                        absent, ChunkGenerationMode.INITIAL);
        IllegalStateException generationFailure =
                new IllegalStateException("deliberate cancellation");
        assertEquals(
                ChunkGenerationResult.Status.FAILED,
                target.failGeneration(ticket, generationFailure).status());
        assertSame(
                generationFailure,
                target.generationFailure(absent).orElseThrow());
    }

    @Test
    void unloadExhaustionLeavesActiveGenerationAndEntryLifecycleUnchanged() {
        ChunkRepository target = repository();
        ChunkKey rebuilding = new ChunkKey(0, 0);
        ChunkKey budget = new ChunkKey(4, 4);
        assertEquals(
                RESTORED,
                target.restoreCanonical(
                                snapshot(
                                        Long.MAX_VALUE - 1,
                                        chunk(0, 0, 30L, (byte) 1),
                                        chunk(4, 4, 31L, (byte) 2)))
                        .status());
        long rebuildingRevision = target.revision(rebuilding);
        assertTrue(target.claimMeshing(rebuilding).isPresent());
        IllegalStateException meshingFailure =
                new IllegalStateException("keep this failure");
        target.markMeshingFailure(
                rebuilding, rebuildingRevision, meshingFailure);
        assertFalse(target.meshingCandidates().contains(rebuilding));
        assertTrue(
                target.setBlock(
                        budget.worldOriginX() + 1,
                        1,
                        budget.worldOriginZ() + 1,
                        (byte) 3));
        ChunkGenerationTicket ticket =
                target.beginGeneration(
                        rebuilding, ChunkGenerationMode.REBUILD);

        assertThrows(
                IllegalStateException.class,
                () -> target.beginUnload(rebuilding));

        assertEquals(
                ChunkGenerationStatus.GENERATING,
                target.generationStatus(rebuilding));
        assertEquals(ChunkState.DIRTY, target.state(rebuilding));
        assertEquals(rebuildingRevision, target.revision(rebuilding));
        assertEquals(1, Byte.toUnsignedInt(target.getBlock(0, 0, 0)));
        assertFalse(target.meshingCandidates().contains(rebuilding));
        IllegalStateException deliberateFailure =
                new IllegalStateException("generation remains controllable");
        assertEquals(
                ChunkGenerationResult.Status.FAILED,
                target.failGeneration(ticket, deliberateFailure).status());
        assertSame(
                deliberateFailure,
                target.generationFailure(rebuilding).orElseThrow());
        ChunkGenerationTicket replacement =
                target.beginGeneration(
                        rebuilding, ChunkGenerationMode.REBUILD);
        assertEquals(
                ChunkGenerationResult.Status.FAILED,
                target.failGeneration(
                                replacement,
                                new IllegalStateException("replacement failure"))
                        .status());
    }

    @Test
    void boundaryMutationWithExactRevisionBudgetDirtiesLoadedNeighborInOrder() {
        ChunkRepository target = repository();
        ChunkKey primary = new ChunkKey(0, 0);
        ChunkKey east = primary.east();
        assertEquals(
                RESTORED,
                target.restoreCanonical(
                                snapshot(
                                        Long.MAX_VALUE - 2,
                                        chunk(0, 0, 40L, (byte) 1),
                                        chunk(1, 0, 41L, (byte) 2)))
                        .status());

        ChunkMutationOutcome outcome =
                target.compareAndSetBlock(
                        GameConfig.Chunk.SIZE - 1,
                        1,
                        1,
                        (byte) 0,
                        (byte) 9);

        assertEquals(ChunkMutationOutcome.Status.APPLIED, outcome.status());
        assertEquals(
                List.of(primary, east),
                List.copyOf(outcome.dirtyRevisions().keySet()));
        assertEquals(Long.MAX_VALUE - 1, outcome.dirtyRevisions().get(primary));
        assertEquals(Long.MAX_VALUE, outcome.dirtyRevisions().get(east));
        assertEquals(Long.MAX_VALUE - 1, target.revision(primary));
        assertEquals(Long.MAX_VALUE, target.revision(east));
        assertEquals(ChunkState.DIRTY, target.state(primary));
        assertEquals(ChunkState.DIRTY, target.state(east));
        assertEquals(9, Byte.toUnsignedInt(target.getBlock(15, 1, 1)));
    }

    @Test
    void initialGenerationExhaustionDoesNotPublishChunkOrCommitAttempt() {
        ChunkRepository target = repository();
        ChunkKey generated = new ChunkKey(0, 0);
        ChunkKey east = generated.east();
        assertEquals(
                RESTORED,
                target.restoreCanonical(
                                snapshot(
                                        Long.MAX_VALUE - 1,
                                        chunk(1, 0, 50L, (byte) 2)))
                        .status());
        ChunkRepositorySnapshot before = target.canonicalSnapshot();
        ChunkGenerationTicket ticket =
                target.beginGeneration(
                        generated, ChunkGenerationMode.INITIAL);

        assertThrows(
                IllegalStateException.class,
                () ->
                        target.commitGeneration(
                                ticket,
                                generationData(generated, (byte) 7)));

        assertFalse(target.contains(generated));
        assertEquals(50L, target.revision(east));
        assertEquals(
                ChunkGenerationStatus.GENERATING,
                target.generationStatus(generated));
        assertEquals(
                ChunkGenerationResult.Status.FAILED,
                target.failGeneration(
                                ticket,
                                new IllegalStateException("controlled failure"))
                        .status());
        assertCanonicalEquals(before, target.canonicalSnapshot());
    }

    @Test
    void rebuildGenerationExhaustionDoesNotReplaceChunkOrCommitAttempt() {
        ChunkRepository target = repository();
        ChunkKey rebuilt = new ChunkKey(0, 0);
        ChunkKey east = rebuilt.east();
        assertEquals(
                RESTORED,
                target.restoreCanonical(
                                snapshot(
                                        Long.MAX_VALUE - 1,
                                        chunk(0, 0, 60L, (byte) 1),
                                        chunk(1, 0, 61L, (byte) 2)))
                        .status());
        ChunkRepositorySnapshot before = target.canonicalSnapshot();
        ChunkGenerationTicket ticket =
                target.beginGeneration(
                        rebuilt, ChunkGenerationMode.REBUILD);
        byte[] replacement = new byte[BLOCK_COUNT];
        replacement[0] = 1;
        replacement[GameConfig.Chunk.SIZE - 1] = 9;

        assertThrows(
                IllegalStateException.class,
                () ->
                        target.commitGeneration(
                                ticket,
                                new ChunkGenerationData(
                                        rebuilt,
                                        WORLD_HEIGHT,
                                        replacement)));

        assertEquals(
                ChunkGenerationStatus.GENERATING,
                target.generationStatus(rebuilt));
        assertEquals(60L, target.revision(rebuilt));
        assertEquals(61L, target.revision(east));
        assertEquals(0, Byte.toUnsignedInt(target.getBlock(15, 0, 0)));
        assertEquals(
                ChunkGenerationResult.Status.FAILED,
                target.failGeneration(
                                ticket,
                                new IllegalStateException("controlled failure"))
                        .status());
        assertCanonicalEquals(before, target.canonicalSnapshot());
    }

    @Test
    void legacyGenerationExhaustionRollsBackTentativeEntryPublication() {
        ChunkRepository target = repository();
        ChunkKey generated = new ChunkKey(0, 0);
        assertEquals(
                RESTORED,
                target.restoreCanonical(
                                snapshot(
                                        Long.MAX_VALUE - 1,
                                        chunk(1, 0, 70L, (byte) 2)))
                        .status());
        ChunkRepositorySnapshot before = target.canonicalSnapshot();
        AtomicBoolean generatorInvoked = new AtomicBoolean();

        assertThrows(
                IllegalStateException.class,
                () ->
                        target.generate(
                                generated,
                                chunk -> {
                                    generatorInvoked.set(true);
                                    chunk.setBlock(0, 0, 0, (byte) 7);
                                }));

        assertTrue(generatorInvoked.get());
        assertFalse(target.contains(generated));
        assertCanonicalEquals(before, target.canonicalSnapshot());
    }

    @Test
    void absentBoundaryMutationCannotLeaveAdmittedNeighborRenderedAgainstMissingPrimary()
            throws Exception {
        ChunkRepository target = repository();
        ChunkKey primary = new ChunkKey(0, 0);
        ChunkKey east = primary.east();
        assertEquals(
                RESTORED,
                target.restoreCanonical(
                                snapshot(
                                        100L,
                                        chunk(1, 0, 50L, (byte) 2)))
                        .status());

        assertAbsentPublicationInvalidatesInterveningNeighborMesh(
                target,
                primary,
                east,
                () ->
                        assertTrue(
                                target.setBlock(
                                        GameConfig.Chunk.SIZE - 1,
                                        1,
                                        1,
                                        (byte) 9)));
    }

    @Test
    void initialGenerationCannotLeaveAdmittedNeighborRenderedAgainstMissingPrimary()
            throws Exception {
        ChunkRepository target = repository();
        ChunkKey primary = new ChunkKey(0, 0);
        ChunkKey east = primary.east();
        assertEquals(
                RESTORED,
                target.restoreCanonical(
                                snapshot(
                                        100L,
                                        chunk(1, 0, 50L, (byte) 2)))
                        .status());
        ChunkGenerationTicket ticket =
                target.beginGeneration(
                        primary, ChunkGenerationMode.INITIAL);
        byte[] blocks = new byte[BLOCK_COUNT];
        blocks[canonicalIndex(15, 1, 1)] = 9;

        assertAbsentPublicationInvalidatesInterveningNeighborMesh(
                target,
                primary,
                east,
                () ->
                        assertEquals(
                                ChunkGenerationResult.Status.COMMITTED,
                                target.commitGeneration(
                                                ticket,
                                                new ChunkGenerationData(
                                                        primary,
                                                        WORLD_HEIGHT,
                                                        blocks))
                                        .status()));
    }

    @Test
    void concurrentAbsentBoundaryMutationsLockOverlappingNeighborsWithoutDeadlock()
            throws Exception {
        ChunkRepository target = repository();
        ChunkKey firstPrimary = new ChunkKey(0, 0);
        ChunkKey secondPrimary = new ChunkKey(-1, 0);
        ChunkKey north = new ChunkKey(0, -1);
        ChunkKey northWest = new ChunkKey(-1, -1);
        assertEquals(
                RESTORED,
                target.restoreCanonical(
                                snapshot(
                                        20L,
                                        chunk(0, -1, 10L, (byte) 1),
                                        chunk(-1, -1, 11L, (byte) 2)))
                        .status());
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Boolean> first =
                    executor.submit(
                            () -> {
                                awaitLatch(start);
                                return target.setBlock(0, 1, 0, (byte) 3);
                            });
            Future<Boolean> second =
                    executor.submit(
                            () -> {
                                awaitLatch(start);
                                return target.setBlock(-1, 1, 0, (byte) 4);
                            });

            start.countDown();

            assertTrue(first.get(5, TimeUnit.SECONDS));
            assertTrue(second.get(5, TimeUnit.SECONDS));
            assertEquals(
                    List.of(
                            northWest,
                            secondPrimary,
                            north,
                            firstPrimary),
                    sorted(target.keys()));
            assertTrue(target.meshingCandidates().contains(north));
            assertTrue(target.meshingCandidates().contains(northWest));
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void absentMutationPreparesCompleteOutcomeBeforePrimaryPublication() {
        AtomicReference<ChunkRepository> repositoryReference =
                new AtomicReference<>();
        AtomicReference<ChunkMutationOutcome> preparedOutcome =
                new AtomicReference<>();
        IllegalStateException preparationFailure =
                new IllegalStateException("stop after result preparation");
        ChunkRepository target =
                new ChunkRepository(
                        WORLD_HEIGHT,
                        new ChunkDirtyTracker(),
                        (key, outcome) -> {
                            assertFalse(
                                    repositoryReference.get().contains(key));
                            preparedOutcome.set(outcome);
                            throw preparationFailure;
                        });
        repositoryReference.set(target);
        ChunkKey primary = new ChunkKey(0, 0);
        ChunkKey east = primary.east();
        assertEquals(
                RESTORED,
                target.restoreCanonical(
                                snapshot(
                                        100L,
                                        chunk(1, 0, 50L, (byte) 2)))
                        .status());

        IllegalStateException actual =
                assertThrows(
                        IllegalStateException.class,
                        () ->
                                target.setBlock(
                                        GameConfig.Chunk.SIZE - 1,
                                        1,
                                        1,
                                        (byte) 9));

        assertSame(preparationFailure, actual);
        assertEquals(
                List.of(primary, east),
                List.copyOf(
                        preparedOutcome.get()
                                .dirtyRevisions()
                                .keySet()));
        assertEquals(
                101L,
                preparedOutcome.get().dirtyRevisions().get(primary));
        assertEquals(
                102L,
                preparedOutcome.get().dirtyRevisions().get(east));
        assertFalse(target.contains(primary));
        assertEquals(50L, target.revision(east));
        assertEquals(ChunkState.DIRTY, target.state(east));
        assertEquals(2, Byte.toUnsignedInt(target.getBlock(16, 0, 0)));
    }

    @Test
    void canonicalCaptureCannotPairReservedHighWaterWithPrePublicationKeySet()
            throws Exception {
        AtomicReference<ChunkRepository> repositoryReference =
                new AtomicReference<>();
        CountDownLatch revisionReserved = new CountDownLatch(1);
        CountDownLatch releasePublication = new CountDownLatch(1);
        ChunkKey primary = new ChunkKey(0, 0);
        ChunkRepository target = new ChunkRepository(
                WORLD_HEIGHT,
                new ChunkDirtyTracker(),
                (key, outcome) -> {
                    assertEquals(primary, key);
                    assertFalse(repositoryReference.get().contains(key));
                    assertEquals(
                            1L,
                            outcome.dirtyRevisions().get(primary));
                    revisionReserved.countDown();
                    awaitLatch(releasePublication);
                });
        repositoryReference.set(target);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        Future<Boolean> mutation = null;
        Future<ChunkRepositorySnapshot> capture = null;
        CountDownLatch captureStarted = new CountDownLatch(1);
        try {
            mutation = executor.submit(
                    () -> target.setBlock(1, 1, 1, (byte) 9));
            awaitLatch(revisionReserved);
            capture = executor.submit(
                    () -> {
                        captureStarted.countDown();
                        return target.canonicalSnapshot();
                    });
            awaitLatch(captureStarted);

            boolean captureBlocked = false;
            boolean captureFailedClosed = false;
            try {
                ChunkRepositorySnapshot premature =
                        capture.get(1, TimeUnit.SECONDS);
                fail(
                        "canonical capture returned before absent mutation publication: keys="
                                + premature.chunks().stream()
                                        .map(ChunkSnapshot::key)
                                        .toList()
                                + ", highWater="
                                + premature.revisionHighWater());
            } catch (TimeoutException blocked) {
                captureBlocked = true;
            } catch (ExecutionException failedClosed) {
                assertInstanceOf(
                        IllegalStateException.class,
                        failedClosed.getCause());
                captureFailedClosed = true;
            } finally {
                releasePublication.countDown();
            }

            assertTrue(mutation.get(5, TimeUnit.SECONDS));
            ChunkRepositorySnapshot completed = captureBlocked
                    ? capture.get(5, TimeUnit.SECONDS)
                    : target.canonicalSnapshot();
            assertTrue(captureBlocked || captureFailedClosed);
            assertAllCanonicalPublication(completed, primary);
        } finally {
            releasePublication.countDown();
            if (mutation != null) {
                mutation.cancel(true);
            }
            if (capture != null) {
                capture.cancel(true);
            }
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void initialGenerationLockBoundaryUsesPrimitiveLongSupplier()
            throws ReflectiveOperationException {
        Method boundary =
                ChunkRepository.class.getDeclaredMethod(
                        "withLockedCandidatesLong",
                        List.class,
                        LongSupplier.class);

        assertEquals(long.class, boundary.getReturnType());
        assertArrayEquals(
                new Class<?>[] {List.class, LongSupplier.class},
                boundary.getParameterTypes());
    }

    @Test
    void restoreRejectsPreviouslyMutatedAndUnloadedRepositoryAsNotFresh() {
        ChunkRepository target = repository();
        ChunkKey used = new ChunkKey(0, 0);
        assertTrue(target.setBlock(0, 0, 0, (byte) 1));
        assertTrue(target.setBlock(0, 0, 0, (byte) 2));
        assertTrue(target.setBlock(0, 0, 0, (byte) 3));
        assertTrue(target.beginUnload(used));
        assertTrue(target.completeUnload(used));
        ChunkRepositorySnapshot historicalEmpty =
                target.canonicalSnapshot();
        assertEquals(4L, historicalEmpty.revisionHighWater());
        assertTrue(historicalEmpty.chunks().isEmpty());
        ChunkRepositorySnapshot savedLowerHighWater =
                snapshot(2L, chunk(3, 3, 2L, (byte) 4));

        ChunkRepositoryRestoreResult result =
                target.restoreCanonical(savedLowerHighWater);

        assertEquals(TARGET_NOT_FRESH, result.status());
        assertEquals(0, result.restoredChunkCount());
        assertTrue(target.keys().isEmpty());
        assertCanonicalEquals(
                historicalEmpty, target.canonicalSnapshot());
    }

    @Test
    void restoreRejectsCompletedAndCancelledGenerationHistoryAsNotFresh() {
        ChunkRepositorySnapshot emptySaved = snapshot(0L);

        ChunkRepository completed = repository();
        ChunkKey completedKey = new ChunkKey(2, 2);
        ChunkGenerationTicket completedTicket =
                completed.beginGeneration(
                        completedKey, ChunkGenerationMode.INITIAL);
        assertEquals(
                ChunkGenerationResult.Status.COMMITTED,
                completed.commitGeneration(
                                completedTicket,
                                generationData(completedKey, (byte) 1))
                        .status());
        assertTrue(completed.beginUnload(completedKey));
        assertTrue(completed.completeUnload(completedKey));
        ChunkRepositorySnapshot completedBefore =
                completed.canonicalSnapshot();

        ChunkRepositoryRestoreResult completedResult =
                completed.restoreCanonical(emptySaved);

        assertEquals(TARGET_NOT_FRESH, completedResult.status());
        assertEquals(0, completedResult.restoredChunkCount());
        assertCanonicalEquals(
                completedBefore, completed.canonicalSnapshot());

        ChunkRepository cancelled = repository();
        ChunkKey cancelledKey = new ChunkKey(-2, -2);
        cancelled.beginGeneration(
                cancelledKey, ChunkGenerationMode.INITIAL);
        assertTrue(cancelled.beginUnload(cancelledKey));
        assertTrue(cancelled.completeUnload(cancelledKey));
        ChunkRepositorySnapshot cancelledBefore =
                cancelled.canonicalSnapshot();

        ChunkRepositoryRestoreResult cancelledResult =
                cancelled.restoreCanonical(emptySaved);

        assertEquals(TARGET_NOT_FRESH, cancelledResult.status());
        assertEquals(0, cancelledResult.restoredChunkCount());
        assertCanonicalEquals(
                cancelledBefore, cancelled.canonicalSnapshot());
    }

    @Test
    void restorePrebuildsDetachedMapAndExactResultBeforeSinglePublication() {
        AtomicReference<ChunkRepository> targetReference =
                new AtomicReference<>();
        AtomicReference<Object> preparedMap = new AtomicReference<>();
        AtomicReference<ChunkRepositoryRestoreResult> preparedResult =
                new AtomicReference<>();
        AtomicBoolean injectFailure = new AtomicBoolean(true);
        IllegalStateException sentinel =
                new IllegalStateException("Chunk restore publication probe");
        ChunkRepositorySnapshot saved =
                snapshot(
                        91L,
                        chunk(1, -2, 77L, (byte) 7),
                        chunk(-1, 3, 80L, (byte) 8));
        ChunkRepository target =
                new ChunkRepository(
                        WORLD_HEIGHT,
                        new ChunkDirtyTracker(),
                        (key, outcome) -> {},
                        (detached, validated, success) -> {
                            assertNotNull(detached);
                            assertPreparedRestoreMap(detached, saved);
                            assertSame(saved, validated);
                            assertTrue(
                                    targetReference.get().keys().isEmpty());
                            assertEquals(
                                    0L,
                                    revisionHighWater(
                                            targetReference.get()));
                            assertEquals(RESTORED, success.status());
                            assertEquals(
                                    2, success.restoredChunkCount());
                            preparedMap.set(detached);
                            preparedResult.set(success);
                            if (injectFailure.getAndSet(false)) {
                                throw sentinel;
                            }
                        });
        targetReference.set(target);

        assertSame(
                sentinel,
                assertThrows(
                        IllegalStateException.class,
                        () -> target.restoreCanonical(saved)));
        assertTrue(target.keys().isEmpty());
        assertEquals(0L, revisionHighWater(target));

        ChunkRepositoryRestoreResult restored =
                target.restoreCanonical(saved);

        assertSame(preparedResult.get(), restored);
        assertNotNull(preparedMap.get());
        assertEquals(
                List.of(new ChunkKey(-1, 3), new ChunkKey(1, -2)),
                sorted(target.keys()));
        assertEquals(91L, revisionHighWater(target));
    }

    private static ChunkRepository repository() {
        return new ChunkRepository(WORLD_HEIGHT, new ChunkDirtyTracker());
    }

    private static ChunkRepositorySnapshot snapshot(
            long revisionHighWater, ChunkSnapshot... chunks) {
        return new ChunkRepositorySnapshot(
                WORLD_HEIGHT, revisionHighWater, List.of(chunks));
    }

    private static ChunkSnapshot chunk(
            int x, int z, long revision, byte block) {
        return chunk(x, z, revision, WORLD_HEIGHT, block);
    }

    private static ChunkSnapshot chunk(
            int x,
            int z,
            long revision,
            int worldHeight,
            byte block) {
        byte[] blocks =
                new byte[
                        GameConfig.Chunk.SIZE
                                * worldHeight
                                * GameConfig.Chunk.SIZE];
        blocks[0] = block;
        return ChunkSnapshot.of(
                new ChunkKey(x, z), revision, worldHeight, blocks);
    }

    private static ChunkGenerationData generationData(
            ChunkKey key, byte block) {
        byte[] blocks = new byte[BLOCK_COUNT];
        blocks[0] = block;
        return new ChunkGenerationData(key, WORLD_HEIGHT, blocks);
    }

    private static int canonicalIndex(
            int localX, int y, int localZ) {
        return localX
                + y * GameConfig.Chunk.SIZE
                + localZ * GameConfig.Chunk.SIZE * WORLD_HEIGHT;
    }

    private static void assertAbsentPublicationInvalidatesInterveningNeighborMesh(
            ChunkRepository target,
            ChunkKey primary,
            ChunkKey neighbor,
            Runnable publication)
            throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(3);
        CountDownLatch binHeld = new CountDownLatch(1);
        CountDownLatch releaseBin = new CountDownLatch(1);
        CountDownLatch neighborMeshCompleted = new CountDownLatch(1);
        AtomicReference<Thread> neighborThread = new AtomicReference<>();
        AtomicReference<Long> interveningRevision = new AtomicReference<>();
        AtomicBoolean meshSawPrimary = new AtomicBoolean();
        try {
            Future<?> binHolder =
                    executor.submit(
                            () ->
                                    entriesMap(target)
                                            .compute(
                                                    primary,
                                                    (ignored, current) -> {
                                                        assertEquals(null, current);
                                                        binHeld.countDown();
                                                        awaitLatch(releaseBin);
                                                        return null;
                                                    }));
            assertTrue(binHeld.await(5, TimeUnit.SECONDS));
            Future<?> primaryPublication = executor.submit(publication);
            awaitRevisionHighWater(target, 102L);
            Future<?> neighborMesh =
                    executor.submit(
                            () -> {
                                neighborThread.set(Thread.currentThread());
                                try {
                                    assertTrue(
                                            target.setBlock(
                                                    neighbor.worldOriginX() + 1,
                                                    2,
                                                    neighbor.worldOriginZ() + 1,
                                                    (byte) 7));
                                    long revision = target.revision(neighbor);
                                    interveningRevision.set(revision);
                                    ChunkMeshInput input =
                                            target.claimMeshing(neighbor)
                                                    .orElseThrow();
                                    meshSawPrimary.set(
                                            Byte.toUnsignedInt(
                                                            input.getBlock(
                                                                    -1,
                                                                    1,
                                                                    1))
                                                    == 9);
                                    assertTrue(
                                            target.markReadyForUpload(
                                                    neighbor, revision));
                                    assertTrue(
                                            target.markRenderable(
                                                    neighbor, revision));
                                } finally {
                                    neighborMeshCompleted.countDown();
                                }
                            });
            awaitCompletedOrBlocked(
                    neighborThread, neighborMeshCompleted);

            releaseBin.countDown();
            binHolder.get(5, TimeUnit.SECONDS);
            primaryPublication.get(5, TimeUnit.SECONDS);
            neighborMesh.get(5, TimeUnit.SECONDS);

            assertTrue(target.contains(primary));
            if (!meshSawPrimary.get()) {
                assertEquals(ChunkState.DIRTY, target.state(neighbor));
                assertTrue(
                        target.revision(neighbor)
                                > interveningRevision.get());
                assertTrue(target.meshingCandidates().contains(neighbor));
            }
        } finally {
            releaseBin.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private static void awaitCompletedOrBlocked(
            AtomicReference<Thread> thread,
            CountDownLatch completed) {
        long deadline =
                System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (completed.getCount() != 0
                && (thread.get() == null
                        || thread.get().getState()
                                != Thread.State.BLOCKED)
                && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertTrue(
                completed.getCount() == 0
                        || (thread.get() != null
                        && thread.get().getState()
                                        == Thread.State.BLOCKED));
    }

    private static void assertAllCanonicalPublication(
            ChunkRepositorySnapshot snapshot,
            ChunkKey primary) {
        assertEquals(1L, snapshot.revisionHighWater());
        assertEquals(
                List.of(primary),
                snapshot.chunks().stream()
                        .map(ChunkSnapshot::key)
                        .toList());
        ChunkSnapshot published = snapshot.chunks().get(0);
        assertEquals(1L, published.revision());
        assertEquals(9, Byte.toUnsignedInt(published.getBlock(1, 1, 1)));
    }

    private static void awaitRevisionHighWater(
            ChunkRepository repository, long expected) {
        long deadline =
                System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (revisionHighWater(repository) < expected
                && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertEquals(expected, revisionHighWater(repository));
    }

    private static long revisionHighWater(ChunkRepository repository) {
        try {
            Field field =
                    ChunkRepository.class.getDeclaredField(
                            "revisionSequence");
            field.setAccessible(true);
            return ((java.util.concurrent.atomic.AtomicLong)
                            field.get(repository))
                    .get();
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    @SuppressWarnings("unchecked")
    private static ConcurrentHashMap<ChunkKey, Object> entriesMap(
            ChunkRepository repository) {
        try {
            Field field =
                    ChunkRepository.class.getDeclaredField("entries");
            field.setAccessible(true);
            return (ConcurrentHashMap<ChunkKey, Object>)
                    field.get(repository);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting for latch");
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError(failure);
        }
    }

    private static void setOrigin(
            ChunkRepository repository, ChunkKey key, byte block) {
        assertTrue(
                repository.setBlock(
                        key.worldOriginX(),
                        0,
                        key.worldOriginZ(),
                        block));
    }

    private static List<ChunkKey> sorted(Iterable<ChunkKey> keys) {
        List<ChunkKey> sorted = new ArrayList<>();
        keys.forEach(sorted::add);
        sorted.sort(
                Comparator.comparingInt(ChunkKey::x)
                        .thenComparingInt(ChunkKey::z));
        return List.copyOf(sorted);
    }

    private static void assertCanonicalEquals(
            ChunkRepositorySnapshot expected,
            ChunkRepositorySnapshot actual) {
        assertEquals(expected.worldHeight(), actual.worldHeight());
        assertEquals(
                expected.revisionHighWater(),
                actual.revisionHighWater());
        assertEquals(expected.chunks().size(), actual.chunks().size());
        for (int index = 0; index < expected.chunks().size(); index++) {
            ChunkSnapshot expectedChunk = expected.chunks().get(index);
            ChunkSnapshot actualChunk = actual.chunks().get(index);
            assertEquals(expectedChunk.key(), actualChunk.key());
            assertEquals(expectedChunk.revision(), actualChunk.revision());
            assertEquals(
                    expectedChunk.worldHeight(),
                    actualChunk.worldHeight());
            assertArrayEquals(
                    expectedChunk.copyBlocks(), actualChunk.copyBlocks());
        }
    }

    @SuppressWarnings("unchecked")
    private static void assertPreparedRestoreMap(
            Object detached, ChunkRepositorySnapshot saved) {
        assertTrue(detached instanceof Map<?, ?>);
        Map<ChunkKey, ?> entries = (Map<ChunkKey, ?>) detached;
        assertEquals(
                saved.chunks().stream()
                        .map(ChunkSnapshot::key)
                        .sorted(
                                Comparator.comparingInt(ChunkKey::x)
                                        .thenComparingInt(ChunkKey::z))
                        .toList(),
                sorted(entries.keySet()));
        try {
            for (ChunkSnapshot chunkSnapshot : saved.chunks()) {
                Object entry = entries.get(chunkSnapshot.key());
                assertNotNull(entry);
                Field chunkField =
                        entry.getClass().getDeclaredField("chunk");
                Field stateField =
                        entry.getClass().getDeclaredField("state");
                Field revisionField =
                        entry.getClass().getDeclaredField("revision");
                Field failureField =
                        entry.getClass().getDeclaredField("failure");
                chunkField.setAccessible(true);
                stateField.setAccessible(true);
                revisionField.setAccessible(true);
                failureField.setAccessible(true);

                Chunk detachedChunk = (Chunk) chunkField.get(entry);
                assertEquals(ChunkState.DIRTY, stateField.get(entry));
                assertEquals(
                        chunkSnapshot.revision(),
                        revisionField.getLong(entry));
                assertEquals(null, failureField.get(entry));
                assertEquals(
                        chunkSnapshot.getBlock(0, 0, 0),
                        detachedChunk.getBlock(0, 0, 0));
            }
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(
                    "cannot inspect prepared Chunk restore map",
                    failure);
        }
    }

    @SuppressWarnings("unchecked")
    private static Object capturedEntry(
            ChunkRepository repository, ChunkKey key) {
        try {
            Field entriesField =
                    ChunkRepository.class.getDeclaredField("entries");
            entriesField.setAccessible(true);
            Map<ChunkKey, ?> entries =
                    (Map<ChunkKey, ?>) entriesField.get(repository);
            return entries.get(key);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private static void corruptBlockLength(ChunkSnapshot snapshot) {
        try {
            Field blocks = ChunkSnapshot.class.getDeclaredField("blocks");
            blocks.setAccessible(true);
            blocks.set(snapshot, new byte[1]);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private static void awaitBlocked(Thread thread) {
        long deadline =
                System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (thread.getState() != Thread.State.BLOCKED
                && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertEquals(Thread.State.BLOCKED, thread.getState());
    }
}

package com.overlord.voxel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.overlord.config.GameConfig;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ChunkRepositoryGenerationTransactionTest {
    private static final int WORLD_HEIGHT = 32;

    @Test
    void generationQueriesAreInitiallyIdleAndNonAllocating() {
        ChunkRepository repository = repository();
        ChunkKey key = new ChunkKey(3, -2);

        assertEquals(
                ChunkGenerationStatus.IDLE,
                repository.generationStatus(key));
        assertEquals(Optional.empty(), repository.generationFailure(key));
        assertFalse(repository.contains(key));
    }

    @Test
    void initialTicketCapturesEmptyBaselineAndUniqueAttempt() {
        ChunkRepository repository = repository();
        ChunkKey key = new ChunkKey(-1, 2);

        ChunkGenerationTicket first =
                repository.beginGeneration(
                        key, ChunkGenerationMode.INITIAL);

        assertEquals(key, first.key());
        assertEquals(ChunkGenerationMode.INITIAL, first.mode());
        assertTrue(first.attemptId() > 0);
        assertEquals(0L, first.baseRevision());
        assertEquals(
                ChunkGenerationStatus.GENERATING,
                repository.generationStatus(key));

        repository.failGeneration(
                first, new IllegalStateException("first failed"));
        ChunkGenerationTicket second =
                repository.beginGeneration(
                        key, ChunkGenerationMode.INITIAL);
        assertTrue(second.attemptId() > first.attemptId());
    }

    @Test
    void initialCommitPublishesWholeChunkOnce() {
        ChunkRepository repository = repository();
        ChunkKey key = new ChunkKey(-1, 2);
        ChunkGenerationTicket ticket =
                repository.beginGeneration(
                        key, ChunkGenerationMode.INITIAL);
        assertEquals(
                ChunkGenerationStatus.GENERATING,
                repository.generationStatus(key));
        assertEquals(0, repository.getBlock(-14, 7, 35));

        ChunkGenerationResult result =
                repository.commitGeneration(
                        ticket, data(key, 2, 7, 3, (byte) 5));

        assertEquals(
                ChunkGenerationResult.Status.COMMITTED,
                result.status());
        assertEquals(key, result.key());
        assertEquals(Optional.empty(), result.failure());
        assertEquals(
                ChunkGenerationStatus.COMMITTED,
                repository.generationStatus(key));
        assertEquals(ChunkState.GENERATED, repository.state(key));
        assertEquals(5, repository.getBlock(-14, 7, 35));
        assertEquals(result.revision(), repository.revision(key));
        assertTrue(result.revision() > 0);

        ChunkGenerationResult repeated =
                repository.commitGeneration(
                        ticket, data(key, 2, 7, 3, (byte) 9));
        assertEquals(
                ChunkGenerationResult.Status.CONFLICT,
                repeated.status());
        assertEquals(5, repository.getBlock(-14, 7, 35));
    }

    @Test
    void failedInitialAttemptPublishesNoPartialChunk() {
        ChunkRepository repository = repository();
        ChunkKey key = new ChunkKey(0, 0);
        ChunkGenerationTicket ticket =
                repository.beginGeneration(
                        key, ChunkGenerationMode.INITIAL);
        IllegalStateException cause =
                new IllegalStateException("stage failed");

        ChunkGenerationResult result =
                repository.failGeneration(ticket, cause);

        assertEquals(
                ChunkGenerationResult.Status.FAILED,
                result.status());
        assertEquals(key, result.key());
        assertEquals(0L, result.revision());
        assertSame(cause, result.failure().orElseThrow());
        assertEquals(
                ChunkGenerationStatus.FAILED,
                repository.generationStatus(key));
        assertSame(
                cause,
                repository.generationFailure(key).orElseThrow());
        assertEquals(ChunkState.EMPTY, repository.state(key));
        assertFalse(repository.contains(key));
    }

    @Test
    void failGenerationPreservesRuntimeExceptionAndError() {
        ChunkRepository repository = repository();
        ChunkKey runtimeKey = new ChunkKey(0, 0);
        ChunkGenerationTicket runtimeTicket =
                repository.beginGeneration(
                        runtimeKey, ChunkGenerationMode.INITIAL);
        RuntimeException runtimeFailure =
                new IllegalArgumentException("runtime");

        ChunkGenerationResult runtimeResult =
                repository.failGeneration(
                        runtimeTicket, runtimeFailure);

        assertSame(runtimeFailure, runtimeResult.failure().orElseThrow());
        assertSame(
                runtimeFailure,
                repository
                        .generationFailure(runtimeKey)
                        .orElseThrow());

        ChunkKey errorKey = new ChunkKey(1, 0);
        ChunkGenerationTicket errorTicket =
                repository.beginGeneration(
                        errorKey, ChunkGenerationMode.INITIAL);
        Error errorFailure = new AssertionError("error");

        ChunkGenerationResult errorResult =
                repository.failGeneration(errorTicket, errorFailure);

        assertSame(errorFailure, errorResult.failure().orElseThrow());
        assertSame(
                errorFailure,
                repository.generationFailure(errorKey).orElseThrow());
    }

    @Test
    void laterBeginReplacesTerminalFailure() {
        ChunkRepository repository = repository();
        ChunkKey key = new ChunkKey(0, 0);
        ChunkGenerationTicket failedTicket =
                repository.beginGeneration(
                        key, ChunkGenerationMode.INITIAL);
        repository.failGeneration(
                failedTicket, new IllegalStateException("failed"));

        ChunkGenerationTicket retry =
                repository.beginGeneration(
                        key, ChunkGenerationMode.INITIAL);

        assertEquals(
                ChunkGenerationStatus.GENERATING,
                repository.generationStatus(key));
        assertEquals(Optional.empty(), repository.generationFailure(key));
        assertTrue(retry.attemptId() > failedTicket.attemptId());
    }

    @Test
    void laterInitialCommitInvalidatesAllLoadedHorizontalNeighbors() {
        ChunkRepository repository = repository();
        ChunkKey center = new ChunkKey(0, 0);
        commitInitial(
                repository,
                center.north(),
                filled(center.north(), (byte) 1));
        commitInitial(
                repository,
                center.south(),
                filled(center.south(), (byte) 1));
        commitInitial(
                repository,
                center.west(),
                filled(center.west(), (byte) 1));
        commitInitial(
                repository,
                center.east(),
                filled(center.east(), (byte) 1));
        long northRevision = repository.revision(center.north());
        long southRevision = repository.revision(center.south());
        long westRevision = repository.revision(center.west());
        long eastRevision = repository.revision(center.east());

        commitInitial(
                repository, center, filled(center, (byte) 1));

        assertDirtiedAfter(
                repository, center.north(), northRevision);
        assertDirtiedAfter(
                repository, center.south(), southRevision);
        assertDirtiedAfter(
                repository, center.west(), westRevision);
        assertDirtiedAfter(
                repository, center.east(), eastRevision);
        assertEquals(5, repository.keys().size());
    }

    @Test
    void duplicateBeginDoesNotReplaceLiveAttempt() {
        ChunkRepository repository = repository();
        ChunkKey key = new ChunkKey(0, 0);
        ChunkGenerationTicket ticket =
                repository.beginGeneration(
                        key, ChunkGenerationMode.INITIAL);

        assertThrows(
                IllegalStateException.class,
                () ->
                        repository.beginGeneration(
                                key, ChunkGenerationMode.INITIAL));

        assertEquals(
                ChunkGenerationResult.Status.COMMITTED,
                repository
                        .commitGeneration(ticket, filled(key, (byte) 3))
                        .status());
    }

    @Test
    void initialBeginRejectsAlreadyLoadedEntry() {
        ChunkRepository repository = repository();
        ChunkKey key = new ChunkKey(0, 0);
        repository.generate(key, chunk -> {});

        assertThrows(
                IllegalStateException.class,
                () ->
                        repository.beginGeneration(
                                key, ChunkGenerationMode.INITIAL));
    }

    @Test
    void mismatchedPayloadKeyConflictsAndTerminatesAttempt() {
        ChunkRepository repository = repository();
        ChunkKey key = new ChunkKey(0, 0);
        ChunkGenerationTicket ticket =
                repository.beginGeneration(
                        key, ChunkGenerationMode.INITIAL);

        ChunkGenerationResult mismatch =
                repository.commitGeneration(
                        ticket,
                        filled(new ChunkKey(1, 0), (byte) 3));

        assertEquals(
                ChunkGenerationResult.Status.CONFLICT,
                mismatch.status());
        assertFalse(repository.contains(key));
        assertEquals(
                ChunkGenerationResult.Status.CONFLICT,
                repository
                        .commitGeneration(ticket, filled(key, (byte) 3))
                        .status());
        assertTrue(
                repository
                                .beginGeneration(
                                        key,
                                        ChunkGenerationMode.INITIAL)
                                .attemptId()
                        > ticket.attemptId());
    }

    @Test
    void mismatchedWorldHeightConflictsAndTerminatesAttempt() {
        ChunkRepository repository = repository();
        ChunkKey key = new ChunkKey(0, 0);
        ChunkGenerationTicket ticket =
                repository.beginGeneration(
                        key, ChunkGenerationMode.INITIAL);

        ChunkGenerationResult result =
                repository.commitGeneration(
                        ticket,
                        new ChunkGenerationData(
                                key, 16, new byte[16 * 16 * 16]));

        assertEquals(
                ChunkGenerationResult.Status.CONFLICT,
                result.status());
        assertFalse(repository.contains(key));
        assertTrue(
                repository
                                .beginGeneration(
                                        key,
                                        ChunkGenerationMode.INITIAL)
                                .attemptId()
                        > ticket.attemptId());
    }

    @Test
    void fabricatedAttemptDoesNotStealLiveTicket() {
        ChunkRepository repository = repository();
        ChunkKey key = new ChunkKey(0, 0);
        ChunkGenerationTicket ticket =
                repository.beginGeneration(
                        key, ChunkGenerationMode.INITIAL);
        ChunkGenerationTicket fabricated =
                new ChunkGenerationTicket(
                        key,
                        ChunkGenerationMode.INITIAL,
                        0,
                        ticket.baseRevision());

        assertEquals(
                ChunkGenerationResult.Status.CONFLICT,
                repository
                        .commitGeneration(
                                fabricated, filled(key, (byte) 4))
                        .status());
        assertEquals(
                ChunkGenerationResult.Status.COMMITTED,
                repository
                        .commitGeneration(ticket, filled(key, (byte) 4))
                        .status());
    }

    @Test
    void reusedFailedTicketCannotChangeTerminalOutcome() {
        ChunkRepository repository = repository();
        ChunkKey key = new ChunkKey(0, 0);
        ChunkGenerationTicket ticket =
                repository.beginGeneration(
                        key, ChunkGenerationMode.INITIAL);
        RuntimeException firstFailure =
                new IllegalStateException("first");
        repository.failGeneration(ticket, firstFailure);

        ChunkGenerationResult repeated =
                repository.failGeneration(
                        ticket, new IllegalStateException("second"));

        assertEquals(
                ChunkGenerationResult.Status.CONFLICT,
                repeated.status());
        assertSame(
                firstFailure,
                repository.generationFailure(key).orElseThrow());
    }

    @Test
    void unloadingPendingInitialAttemptPreventsLateCommit() {
        ChunkRepository repository = repository();
        ChunkKey key = new ChunkKey(0, 0);
        ChunkGenerationTicket ticket =
                repository.beginGeneration(
                        key, ChunkGenerationMode.INITIAL);

        assertTrue(repository.beginUnload(key));
        assertEquals(
                ChunkGenerationStatus.IDLE,
                repository.generationStatus(key));
        assertThrows(
                IllegalStateException.class,
                () ->
                        repository.beginGeneration(
                                key, ChunkGenerationMode.INITIAL));
        assertTrue(repository.completeUnload(key));

        ChunkGenerationResult result =
                repository.commitGeneration(
                        ticket, filled(key, (byte) 3));

        assertEquals(
                ChunkGenerationResult.Status.CONFLICT,
                result.status());
        assertFalse(repository.contains(key));
        assertEquals(
                ChunkGenerationStatus.IDLE,
                repository.generationStatus(key));
    }

    @Test
    void unloadedAlternateIncarnationInvalidatesPendingInitialTicket() {
        ChunkRepository repository = repository();
        ChunkKey key = new ChunkKey(0, 0);
        ChunkGenerationTicket ticket =
                repository.beginGeneration(
                        key, ChunkGenerationMode.INITIAL);
        repository.generate(
                key,
                chunk -> chunk.setBlock(1, 4, 1, (byte) 7));

        assertTrue(repository.beginUnload(key));
        assertTrue(repository.completeUnload(key));

        ChunkGenerationResult result =
                repository.commitGeneration(
                        ticket, filled(key, (byte) 3));

        assertEquals(
                ChunkGenerationResult.Status.CONFLICT,
                result.status());
        assertFalse(repository.contains(key));
        assertEquals(0, repository.getBlock(1, 4, 1));
    }

    @Test
    void ticketOwnershipIsIsolatedBetweenRepositories() {
        ChunkRepository first = repository();
        ChunkRepository second = repository();
        ChunkKey key = new ChunkKey(0, 0);
        ChunkGenerationTicket firstTicket =
                first.beginGeneration(
                        key, ChunkGenerationMode.INITIAL);
        ChunkGenerationTicket secondTicket =
                second.beginGeneration(
                        key, ChunkGenerationMode.INITIAL);
        assertEquals(firstTicket, secondTicket);

        assertEquals(
                ChunkGenerationResult.Status.CONFLICT,
                second
                        .commitGeneration(
                                firstTicket, filled(key, (byte) 1))
                        .status());
        assertEquals(
                ChunkGenerationResult.Status.CONFLICT,
                first
                        .commitGeneration(
                                secondTicket, filled(key, (byte) 2))
                        .status());

        assertEquals(
                ChunkGenerationResult.Status.COMMITTED,
                first
                        .commitGeneration(
                                firstTicket, filled(key, (byte) 3))
                        .status());
        assertEquals(
                ChunkGenerationResult.Status.COMMITTED,
                second
                        .commitGeneration(
                                secondTicket, filled(key, (byte) 4))
                        .status());
        assertEquals(3, first.getBlock(1, 4, 1));
        assertEquals(4, second.getBlock(1, 4, 1));
    }

    @Test
    void completeUnloadClearsCommittedGenerationMetadata() {
        ChunkRepository repository = repository();
        ChunkKey key = new ChunkKey(0, 0);
        commitInitial(
                repository, key, filled(key, (byte) 1));

        assertEquals(
                ChunkGenerationStatus.COMMITTED,
                repository.generationStatus(key));
        assertEquals(1, generationAttemptCount(repository));
        assertTrue(repository.beginUnload(key));
        assertEquals(
                ChunkGenerationStatus.COMMITTED,
                repository.generationStatus(key));

        assertTrue(repository.completeUnload(key));

        assertEquals(
                ChunkGenerationStatus.IDLE,
                repository.generationStatus(key));
        assertEquals(0, generationAttemptCount(repository));
    }

    @Test
    void unloadingManyTransactionChunksRetainsNoAttemptTombstones() {
        ChunkRepository repository = repository();

        for (int index = 0; index < 100; index++) {
            ChunkKey key = new ChunkKey(index, -index);
            commitInitial(
                    repository, key, filled(key, (byte) 1));
            assertTrue(repository.beginUnload(key));
            assertTrue(repository.completeUnload(key));
        }

        assertTrue(repository.keys().isEmpty());
        assertEquals(0, generationAttemptCount(repository));
    }

    @Test
    void oldUnloadCleanupCannotClearNewIncarnationMetadata()
            throws InterruptedException {
        ChunkRepository repository = repository();
        ChunkKey key = new ChunkKey(0, 0);
        commitInitial(
                repository, key, filled(key, (byte) 1));
        assertTrue(repository.beginUnload(key));

        Object oldEntry = capturedEntry(repository, key);
        Object attemptsMonitor =
                generationAttemptsMonitor(repository);
        CountDownLatch entryHeld = new CountDownLatch(1);
        CountDownLatch releaseEntry = new CountDownLatch(1);
        AtomicReference<Throwable> asynchronousFailure =
                new AtomicReference<>();
        Thread entryHolder =
                new Thread(
                        () -> {
                            synchronized (oldEntry) {
                                entryHeld.countDown();
                                try {
                                    if (!releaseEntry.await(
                                            5, TimeUnit.SECONDS)) {
                                        throw new AssertionError(
                                                "Timed out waiting to release old entry");
                                    }
                                } catch (Throwable failure) {
                                    asynchronousFailure.compareAndSet(
                                            null, failure);
                                }
                            }
                        },
                        "old-entry-holder");
        entryHolder.setDaemon(true);
        entryHolder.start();
        assertTrue(
                entryHeld.await(5, TimeUnit.SECONDS),
                "Old entry monitor was not acquired");

        AtomicBoolean oldUnloadCompleted = new AtomicBoolean();
        Thread oldUnload =
                new Thread(
                        () -> {
                            try {
                                oldUnloadCompleted.set(
                                        repository.completeUnload(key));
                            } catch (Throwable failure) {
                                asynchronousFailure.compareAndSet(
                                        null, failure);
                            }
                        },
                        "old-unload-completion");
        oldUnload.setDaemon(true);
        oldUnload.start();
        awaitBlocked(
                oldUnload,
                "Old unload did not block on its entry monitor");

        try {
            synchronized (attemptsMonitor) {
                releaseEntry.countDown();
                awaitEntryRemoval(repository, key);

                ChunkGenerationTicket newTicket =
                        repository.beginGeneration(
                                key, ChunkGenerationMode.INITIAL);
                assertEquals(
                        ChunkGenerationResult.Status.COMMITTED,
                        repository
                                .commitGeneration(
                                        newTicket,
                                        filled(key, (byte) 2))
                                .status());
                assertTrue(repository.beginUnload(key));
                assertEquals(
                        ChunkState.UNLOADING,
                        repository.state(key));
            }
        } finally {
            releaseEntry.countDown();
        }

        entryHolder.join(TimeUnit.SECONDS.toMillis(5));
        oldUnload.join(TimeUnit.SECONDS.toMillis(5));
        assertFalse(entryHolder.isAlive(), "Entry holder did not finish");
        assertFalse(oldUnload.isAlive(), "Old unload did not finish");
        if (asynchronousFailure.get() != null) {
            throw new AssertionError(
                    "Asynchronous coordination failed",
                    asynchronousFailure.get());
        }

        assertTrue(oldUnloadCompleted.get());
        assertTrue(repository.contains(key));
        assertEquals(ChunkState.UNLOADING, repository.state(key));
        assertEquals(
                ChunkGenerationStatus.COMMITTED,
                repository.generationStatus(key));
        assertEquals(1, generationAttemptCount(repository));
        assertEquals(2, repository.getBlock(1, 4, 1));

        assertTrue(repository.completeUnload(key));
        assertEquals(
                ChunkGenerationStatus.IDLE,
                repository.generationStatus(key));
    }

    @Test
    void transactionApiRejectsNulls() {
        ChunkRepository repository = repository();
        ChunkKey key = new ChunkKey(0, 0);
        ChunkGenerationTicket ticket =
                repository.beginGeneration(
                        key, ChunkGenerationMode.INITIAL);
        ChunkGenerationData data = filled(key, (byte) 1);

        assertThrows(
                NullPointerException.class,
                () ->
                        repository.beginGeneration(
                                null, ChunkGenerationMode.INITIAL));
        assertThrows(
                NullPointerException.class,
                () -> repository.beginGeneration(key, null));
        assertThrows(
                NullPointerException.class,
                () -> repository.commitGeneration(null, data));
        assertThrows(
                NullPointerException.class,
                () -> repository.commitGeneration(ticket, null));
        assertThrows(
                NullPointerException.class,
                () -> repository.failGeneration(null, new Error()));
        assertThrows(
                NullPointerException.class,
                () -> repository.failGeneration(ticket, null));
        assertThrows(
                NullPointerException.class,
                () -> repository.generationStatus(null));
        assertThrows(
                NullPointerException.class,
                () -> repository.generationFailure(null));
    }

    private static ChunkRepository repository() {
        return new ChunkRepository(
                WORLD_HEIGHT, new ChunkDirtyTracker());
    }

    private static void commitInitial(
            ChunkRepository repository,
            ChunkKey key,
            ChunkGenerationData data) {
        ChunkGenerationTicket ticket =
                repository.beginGeneration(
                        key, ChunkGenerationMode.INITIAL);
        assertEquals(
                ChunkGenerationResult.Status.COMMITTED,
                repository.commitGeneration(ticket, data).status());
    }

    private static ChunkGenerationData data(
            ChunkKey key,
            int localX,
            int y,
            int localZ,
            byte blockId) {
        byte[] blocks = blocks();
        blocks[canonicalIndex(localX, y, localZ)] = blockId;
        return new ChunkGenerationData(key, WORLD_HEIGHT, blocks);
    }

    private static ChunkGenerationData filled(
            ChunkKey key, byte blockId) {
        byte[] blocks = blocks();
        java.util.Arrays.fill(blocks, blockId);
        return new ChunkGenerationData(key, WORLD_HEIGHT, blocks);
    }

    private static byte[] blocks() {
        return new byte[
                GameConfig.Chunk.SIZE
                        * WORLD_HEIGHT
                        * GameConfig.Chunk.SIZE];
    }

    private static int canonicalIndex(
            int localX, int y, int localZ) {
        return localX
                + y * GameConfig.Chunk.SIZE
                + localZ * GameConfig.Chunk.SIZE * WORLD_HEIGHT;
    }

    private static void assertDirtiedAfter(
            ChunkRepository repository,
            ChunkKey key,
            long previousRevision) {
        assertEquals(ChunkState.DIRTY, repository.state(key));
        assertTrue(repository.revision(key) > previousRevision);
    }

    @SuppressWarnings("unchecked")
    private static int generationAttemptCount(
            ChunkRepository repository) {
        try {
            Field attemptsField =
                    ChunkRepository.class.getDeclaredField(
                            "generationAttempts");
            attemptsField.setAccessible(true);
            Object attempts = attemptsField.get(repository);
            Field byKeyField =
                    attempts.getClass().getDeclaredField("byKey");
            byKeyField.setAccessible(true);
            Map<ChunkKey, ?> byKey =
                    (Map<ChunkKey, ?>) byKeyField.get(attempts);
            return byKey.size();
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
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

    private static Object generationAttemptsMonitor(
            ChunkRepository repository) {
        try {
            Field attemptsField =
                    ChunkRepository.class.getDeclaredField(
                            "generationAttempts");
            attemptsField.setAccessible(true);
            return attemptsField.get(repository);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private static void awaitEntryRemoval(
            ChunkRepository repository, ChunkKey key) {
        long deadline =
                System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (repository.contains(key)
                && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertFalse(
                repository.contains(key),
                "Old entry was not removed before cleanup");
    }

    private static void awaitBlocked(
            Thread thread, String failureMessage) {
        long deadline =
                System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (thread.getState() != Thread.State.BLOCKED
                && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertEquals(
                Thread.State.BLOCKED,
                thread.getState(),
                failureMessage);
    }
}

package com.overlord.voxel;

import static com.overlord.voxel.ChunkAvailability.AVAILABLE;
import static com.overlord.voxel.ChunkAvailability.FAILED;
import static com.overlord.voxel.ChunkAvailability.UNKNOWN;
import static com.overlord.voxel.ChunkStreamingPublication.Status.PUBLISHED;
import static com.overlord.voxel.ChunkStreamingPublication.Status.STALE;
import static com.overlord.voxel.ChunkStreamingTicket.SourcePreference.GENERATE;
import static com.overlord.voxel.ChunkStreamingTicket.SourcePreference.LOAD;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.overlord.config.GameConfig;
import com.overlord.voxel.ChunkStreamingTicket.BaseIdentity;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class ChunkRepositoryStreamingTest {
    private static final int WORLD_HEIGHT = 32;
    private static final int BLOCK_COUNT =
            GameConfig.Chunk.SIZE
                    * WORLD_HEIGHT
                    * GameConfig.Chunk.SIZE;

    @Test
    void duplicateSameEpochRequestsCoalesceWithoutChangingChosenSource() {
        ChunkRepository repository = repository();
        ChunkKey key = new ChunkKey(-3, 5);

        ChunkStreamingTicket first =
                repository.request(key, 41L, LOAD);
        ChunkStreamingTicket duplicate =
                repository.request(key, 41L, GENERATE);

        assertSame(first, duplicate);
        assertEquals(key, first.key());
        assertEquals(41L, first.epoch());
        assertEquals(LOAD, first.sourcePreference());
        assertEquals(0L, first.expectedRevision());
        assertEquals(ChunkState.LOADING, repository.state(key));
        assertEquals(UNKNOWN, repository.availability(key));
        assertFalse(repository.contains(key));
    }

    @Test
    void newerEpochReplacesTicketAndOlderEpochCannotTakeAuthorityBack() {
        ChunkRepository repository = repository();
        ChunkKey key = new ChunkKey(6, -4);
        ChunkStreamingTicket old =
                repository.request(key, 7L, LOAD);

        ChunkStreamingTicket replacement =
                repository.request(key, 8L, GENERATE);
        ChunkStreamingTicket lateOlderRequest =
                repository.request(key, 7L, LOAD);

        assertNotSame(old, replacement);
        assertSame(replacement, lateOlderRequest);
        assertEquals(8L, replacement.epoch());
        assertEquals(GENERATE, replacement.sourcePreference());
        assertEquals(ChunkState.GENERATING, repository.state(key));
        assertFalse(repository.cancel(old));
        assertTrue(repository.cancel(replacement));
    }

    @Test
    void loadAndGeneratePreferencesChooseDistinctWorkerSources() {
        ChunkRepository repository = repository();
        ChunkKey loaded = new ChunkKey(1, 2);
        ChunkKey generated = new ChunkKey(2, 2);

        ChunkStreamingTicket loadTicket =
                repository.request(loaded, 12L, LOAD);
        ChunkStreamingTicket generateTicket =
                repository.request(generated, 12L, GENERATE);

        assertEquals(LOAD, loadTicket.sourcePreference());
        assertEquals(ChunkState.LOADING, repository.state(loaded));
        assertEquals(GENERATE, generateTicket.sourcePreference());
        assertEquals(ChunkState.GENERATING, repository.state(generated));

        ChunkStreamingPublication wrongSource =
                repository.publish(
                        loadTicket,
                        filled(loaded, (byte) 4),
                        new BaseIdentity(GENERATE, 0L));

        assertEquals(STALE, wrongSource.status());
        assertFalse(repository.contains(loaded));
        assertSame(
                loadTicket,
                repository.request(loaded, 12L, LOAD));
        assertEquals(
                PUBLISHED,
                repository
                        .publish(
                                loadTicket,
                                filled(loaded, (byte) 5),
                                identityFor(loadTicket))
                        .status());
        assertEquals(
                PUBLISHED,
                repository
                        .publish(
                                generateTicket,
                                filled(generated, (byte) 6),
                                identityFor(generateTicket))
                        .status());
        assertEquals(AVAILABLE, repository.availability(loaded));
        assertEquals(AVAILABLE, repository.availability(generated));
    }

    @Test
    void cancelBeforeWorkerResultPreventsPublication() {
        ChunkRepository repository = repository();
        ChunkKey key = new ChunkKey(-7, 3);
        ChunkStreamingTicket ticket =
                repository.request(key, 15L, GENERATE);

        assertTrue(repository.cancel(ticket));
        ChunkStreamingPublication late =
                repository.publish(
                        ticket,
                        filled(key, (byte) 7),
                        identityFor(ticket));

        assertEquals(STALE, late.status());
        assertEquals(UNKNOWN, repository.availability(key));
        assertEquals(ChunkState.EMPTY, repository.state(key));
        assertFalse(repository.contains(key));
    }

    @Test
    void cancelAfterWorkerResultWasProducedPreventsPublication() {
        ChunkRepository repository = repository();
        ChunkKey key = new ChunkKey(9, -8);
        ChunkStreamingTicket ticket =
                repository.request(key, 19L, LOAD);
        ChunkGenerationData completedWorkerResult =
                filled(key, (byte) 8);
        BaseIdentity completedIdentity = identityFor(ticket);

        assertTrue(repository.cancel(ticket));
        ChunkStreamingPublication late =
                repository.publish(
                        ticket,
                        completedWorkerResult,
                        completedIdentity);

        assertEquals(STALE, late.status());
        assertEquals(UNKNOWN, repository.availability(key));
        assertFalse(repository.contains(key));
    }

    @Test
    void unloadWhileWorkRunsInvalidatesTicketBeforeCompletion() {
        ChunkRepository repository = repository();
        ChunkKey key = new ChunkKey(4, 4);
        ChunkStreamingTicket ticket =
                repository.request(key, 23L, GENERATE);

        assertTrue(repository.beginUnload(key));
        assertEquals(ChunkState.UNLOADING, repository.state(key));
        assertEquals(UNKNOWN, repository.availability(key));
        assertTrue(repository.completeUnload(ticket));

        assertEquals(
                STALE,
                repository
                        .publish(
                                ticket,
                                filled(key, (byte) 9),
                                identityFor(ticket))
                        .status());
        assertEquals(ChunkState.EMPTY, repository.state(key));
        assertFalse(repository.contains(key));
    }

    @Test
    void replacedTicketCannotPublishOverNewerRequest() {
        ChunkRepository repository = repository();
        ChunkKey key = new ChunkKey(-5, -5);
        ChunkStreamingTicket old =
                repository.request(key, 30L, LOAD);
        ChunkGenerationData oldResult = filled(key, (byte) 3);
        BaseIdentity oldIdentity = identityFor(old);
        ChunkStreamingTicket replacement =
                repository.request(key, 31L, GENERATE);

        ChunkStreamingPublication stale =
                repository.publish(old, oldResult, oldIdentity);

        assertEquals(STALE, stale.status());
        assertFalse(repository.contains(key));
        assertEquals(UNKNOWN, repository.availability(key));

        ChunkStreamingPublication published =
                repository.publish(
                        replacement,
                        filled(key, (byte) 11),
                        identityFor(replacement));

        assertEquals(PUBLISHED, published.status());
        assertEquals(published.revision(), repository.revision(key));
        assertEquals(11, blockAtOrigin(repository, key));
    }

    @Test
    void oldTicketAwareUnloadCompletionCannotRemoveReplacementIncarnation() {
        ChunkRepository repository = repository();
        ChunkKey key = new ChunkKey(11, 2);
        ChunkStreamingTicket first =
                repository.request(key, 50L, GENERATE);
        assertEquals(
                PUBLISHED,
                repository
                        .publish(
                                first,
                                filled(key, (byte) 2),
                                identityFor(first))
                        .status());
        assertTrue(repository.beginUnload(key));
        assertTrue(repository.completeUnload(first));

        ChunkStreamingTicket replacement =
                repository.request(key, 51L, LOAD);
        assertEquals(
                PUBLISHED,
                repository
                        .publish(
                                replacement,
                                filled(key, (byte) 12),
                                identityFor(replacement))
                        .status());

        assertFalse(repository.completeUnload(first));
        assertTrue(repository.contains(key));
        assertEquals(AVAILABLE, repository.availability(key));
        assertEquals(12, blockAtOrigin(repository, key));
    }

    @Test
    void absentLoadTicketUnloadCompletionNeverCapturesLaterGenerationIncarnation() {
        ChunkRepository repository = repository();
        ChunkKey key = new ChunkKey(-11, 7);
        ChunkStreamingTicket oldLoad =
                repository.request(key, 52L, LOAD);
        assertTrue(repository.beginUnload(key));

        ChunkGenerationTicket laterGeneration =
                repository.beginGeneration(
                        key, ChunkGenerationMode.INITIAL);
        assertEquals(
                ChunkGenerationResult.Status.COMMITTED,
                repository
                        .commitGeneration(
                                laterGeneration,
                                filled(key, (byte) 17))
                        .status());
        assertTrue(repository.beginUnload(key));
        long laterUnloadRevision = repository.revision(key);

        assertTrue(repository.completeUnload(oldLoad));

        assertTrue(repository.contains(key));
        assertEquals(ChunkState.UNLOADING, repository.state(key));
        assertEquals(laterUnloadRevision, repository.revision(key));
        assertEquals(
                ChunkGenerationStatus.COMMITTED,
                repository.generationStatus(key));
        assertEquals(17, blockAtOrigin(repository, key));
        assertTrue(repository.completeUnload(key));
    }

    @Test
    void blockedOldTicketCompletionCannotSampleLaterGenerationIncarnation()
            throws InterruptedException {
        ChunkRepository repository = repository();
        ChunkKey key = new ChunkKey(-12, 8);
        ChunkStreamingTicket oldLoad =
                repository.request(key, 53L, LOAD);
        assertTrue(repository.beginUnload(key));
        Object attemptsMonitor = generationAttemptsMonitor(repository);
        AtomicBoolean oldCompleted = new AtomicBoolean();
        AtomicReference<Throwable> asynchronousFailure =
                new AtomicReference<>();
        Thread oldCompletion =
                new Thread(
                        () -> {
                            try {
                                oldCompleted.set(
                                        repository.completeUnload(oldLoad));
                            } catch (Throwable failure) {
                                asynchronousFailure.set(failure);
                            }
                        },
                        "old-streaming-unload-completion");
        oldCompletion.setDaemon(true);

        long laterUnloadRevision;
        synchronized (attemptsMonitor) {
            oldCompletion.start();
            awaitBlocked(
                    oldCompletion,
                    "Old exact completion did not block on request authority");
            ChunkGenerationTicket laterGeneration =
                    repository.beginGeneration(
                            key, ChunkGenerationMode.INITIAL);
            assertEquals(
                    ChunkGenerationResult.Status.COMMITTED,
                    repository
                            .commitGeneration(
                                    laterGeneration,
                                    filled(key, (byte) 18))
                            .status());
            assertTrue(repository.beginUnload(key));
            laterUnloadRevision = repository.revision(key);
        }

        oldCompletion.join(TimeUnit.SECONDS.toMillis(5));
        assertFalse(oldCompletion.isAlive());
        if (asynchronousFailure.get() != null) {
            throw new AssertionError(
                    "Old exact unload completion failed",
                    asynchronousFailure.get());
        }
        assertTrue(oldCompleted.get());
        assertTrue(repository.contains(key));
        assertEquals(ChunkState.UNLOADING, repository.state(key));
        assertEquals(laterUnloadRevision, repository.revision(key));
        assertEquals(
                ChunkGenerationStatus.COMMITTED,
                repository.generationStatus(key));
        assertEquals(18, blockAtOrigin(repository, key));
        assertTrue(repository.completeUnload(key));
    }

    @Test
    void nullEntryKeyCleanupCannotClearReplacementPublishedAttempt()
            throws InterruptedException {
        ChunkRepository repository = repository();
        ChunkKey key = new ChunkKey(-13, 9);
        ChunkStreamingTicket oldLoad =
                repository.request(key, 54L, LOAD);
        assertTrue(repository.beginUnload(key));
        ChunkStreamingTicket replacement =
                repository.request(key, 55L, GENERATE);
        CountDownLatch nullEntryObserved = new CountDownLatch(1);
        CountDownLatch releaseCleanup = new CountDownLatch(1);
        installNullEntryBarrier(
                repository,
                key,
                "key-unload-cleanup",
                nullEntryObserved,
                releaseCleanup);
        AtomicBoolean cleanupCompleted = new AtomicBoolean();
        AtomicReference<Throwable> asynchronousFailure =
                new AtomicReference<>();
        Thread cleanup =
                new Thread(
                        () -> {
                            try {
                                cleanupCompleted.set(
                                        repository.completeUnload(key));
                            } catch (Throwable failure) {
                                asynchronousFailure.set(failure);
                            }
                        },
                        "key-unload-cleanup");
        cleanup.setDaemon(true);
        cleanup.start();
        assertTrue(
                nullEntryObserved.await(5, TimeUnit.SECONDS),
                "Key cleanup did not observe the absent entry");

        assertEquals(
                PUBLISHED,
                repository
                        .publish(
                                replacement,
                                filled(key, (byte) 24),
                                identityFor(replacement))
                        .status());
        releaseCleanup.countDown();
        cleanup.join(TimeUnit.SECONDS.toMillis(5));

        assertFalse(cleanup.isAlive());
        if (asynchronousFailure.get() != null) {
            throw new AssertionError(
                    "Key unload cleanup failed",
                    asynchronousFailure.get());
        }
        assertTrue(cleanupCompleted.get());
        assertTrue(repository.contains(key));
        assertEquals(24, blockAtOrigin(repository, key));
        assertEquals(
                ChunkGenerationStatus.COMMITTED,
                repository.generationStatus(key));
        assertSame(
                replacement,
                repository.request(key, 55L, GENERATE));
        assertFalse(repository.cancel(oldLoad));
    }

    @Test
    void newerEpochReplacesRequestWhileOldTicketIsUnloading() {
        ChunkRepository repository = repository();
        ChunkKey key = new ChunkKey(13, -9);
        ChunkStreamingTicket old =
                repository.request(key, 80L, LOAD);
        assertTrue(repository.beginUnload(key));

        ChunkStreamingTicket replacement =
                repository.request(key, 81L, GENERATE);

        assertNotSame(old, replacement);
        assertEquals(81L, replacement.epoch());
        assertEquals(GENERATE, replacement.sourcePreference());
        assertEquals(ChunkState.GENERATING, repository.state(key));
        assertFalse(repository.cancel(old));
        assertTrue(repository.completeUnload(old));
        assertEquals(ChunkState.GENERATING, repository.state(key));
        assertEquals(
                PUBLISHED,
                repository
                        .publish(
                                replacement,
                                filled(key, (byte) 19),
                                identityFor(replacement))
                        .status());
        assertEquals(19, blockAtOrigin(repository, key));
    }

    @Test
    void newerEpochQueuesReplacementWhilePublishedEntryFinishesUnload() {
        ChunkRepository repository = repository();
        ChunkKey key = new ChunkKey(15, -11);
        ChunkStreamingTicket old =
                repository.request(key, 82L, GENERATE);
        assertEquals(
                PUBLISHED,
                repository
                        .publish(
                                old,
                                filled(key, (byte) 21),
                                identityFor(old))
                        .status());
        assertTrue(repository.beginUnload(key));

        ChunkStreamingTicket replacement =
                repository.request(key, 83L, GENERATE);

        assertNotSame(old, replacement);
        assertEquals(0L, replacement.expectedRevision());
        assertEquals(
                STALE,
                repository
                        .publish(
                                old,
                                filled(key, (byte) 22),
                                identityFor(old))
                        .status());
        assertTrue(repository.completeUnload(old));
        assertEquals(ChunkState.GENERATING, repository.state(key));
        assertEquals(
                PUBLISHED,
                repository
                        .publish(
                                replacement,
                                filled(key, (byte) 23),
                                identityFor(replacement))
                        .status());
        assertEquals(23, blockAtOrigin(repository, key));
    }

    @Test
    void exhaustedRequestSequencePreservesOldTicketAndGenerationAuthority() {
        ChunkRepository repository = repository();
        ChunkKey key = new ChunkKey(14, -10);
        ChunkStreamingTicket old =
                repository.request(key, 90L, GENERATE);
        setStreamingRequestSequence(repository, Long.MAX_VALUE);

        IllegalStateException failure =
                assertThrows(
                        IllegalStateException.class,
                        () -> repository.request(key, 91L, LOAD));

        assertEquals(
                "Chunk streaming request sequence is exhausted",
                failure.getMessage());
        assertSame(old, repository.request(key, 90L, GENERATE));
        assertEquals(ChunkState.GENERATING, repository.state(key));
        assertEquals(
                ChunkGenerationStatus.GENERATING,
                repository.generationStatus(key));
        assertEquals(
                PUBLISHED,
                repository
                        .publish(
                                old,
                                filled(key, (byte) 20),
                                identityFor(old))
                        .status());
        assertEquals(20, blockAtOrigin(repository, key));
    }

    @Test
    void modifiedPublicKeyBoundariesRejectUnsafeCoordinates() {
        ChunkRepository repository = repository();
        ChunkKey unsafe =
                new ChunkKey(
                        ChunkCoordinatePolicy.MAX_SAFE_CHUNK_COORDINATE + 1,
                        0);

        assertThrows(
                IllegalArgumentException.class,
                () -> repository.state(unsafe));
        assertThrows(
                IllegalArgumentException.class,
                () -> repository.beginUnload(unsafe));
        assertThrows(
                IllegalArgumentException.class,
                () -> repository.completeUnload(unsafe));
    }

    @Test
    void availabilityIsClosedForAbsentPendingFailedAndPublishedChunks() {
        ChunkRepository repository = repository();
        ChunkKey absent = new ChunkKey(0, 0);
        ChunkKey pending = new ChunkKey(1, 0);
        ChunkKey failed = new ChunkKey(2, 0);
        ChunkKey published = new ChunkKey(3, 0);

        assertEquals(UNKNOWN, repository.availability(absent));
        assertEquals(0, blockAtOrigin(repository, absent));

        repository.request(pending, 60L, LOAD);
        assertEquals(UNKNOWN, repository.availability(pending));

        ChunkGenerationTicket failedTicket =
                repository.beginGeneration(
                        failed, ChunkGenerationMode.INITIAL);
        repository.failGeneration(
                failedTicket,
                new IllegalStateException("generation failed"));
        assertEquals(FAILED, repository.availability(failed));
        assertEquals(0, blockAtOrigin(repository, failed));

        ChunkStreamingTicket publishedTicket =
                repository.request(published, 60L, GENERATE);
        assertEquals(
                PUBLISHED,
                repository
                        .publish(
                                publishedTicket,
                                filled(published, (byte) 13),
                                identityFor(publishedTicket))
                        .status());
        assertEquals(AVAILABLE, repository.availability(published));
    }

    @Test
    void falseAbsentUnloadPreservesTerminalGenerationFailureExactly() {
        ChunkRepository repository = repository();
        ChunkKey key = new ChunkKey(-4, 6);
        ChunkGenerationTicket ticket =
                repository.beginGeneration(
                        key, ChunkGenerationMode.INITIAL);
        IllegalStateException cause =
                new IllegalStateException("preserve terminal failure");
        assertEquals(
                ChunkGenerationResult.Status.FAILED,
                repository.failGeneration(ticket, cause).status());
        assertEquals(
                ChunkGenerationStatus.FAILED,
                repository.generationStatus(key));
        assertSame(cause, repository.generationFailure(key).orElseThrow());
        assertEquals(FAILED, repository.availability(key));

        assertFalse(repository.beginUnload(key));

        assertEquals(
                ChunkGenerationStatus.FAILED,
                repository.generationStatus(key));
        assertSame(cause, repository.generationFailure(key).orElseThrow());
        assertEquals(FAILED, repository.availability(key));
        assertFalse(repository.contains(key));
    }

    @Test
    void canonicalRevisionConflictTerminatesDeadRequestBeforeRetry() {
        ChunkRepository repository = repository();
        ChunkKey key = new ChunkKey(-2, 4);
        repository.generate(
                key,
                chunk -> chunk.setBlock(1, 2, 1, (byte) 2));
        ChunkStreamingTicket stale =
                repository.request(key, 72L, GENERATE);
        assertTrue(
                repository.setBlock(
                        key.worldOriginX() + 1,
                        2,
                        key.worldOriginZ() + 1,
                        (byte) 3));

        assertEquals(
                STALE,
                repository
                        .publish(
                                stale,
                                filled(key, (byte) 4),
                                identityFor(stale))
                        .status());

        ChunkStreamingTicket retry =
                repository.request(key, 72L, GENERATE);
        assertNotSame(stale, retry);
        assertEquals(repository.revision(key), retry.expectedRevision());
    }

    @Test
    void streamingApiRejectsNullLifecycleInputs() {
        ChunkRepository repository = repository();
        ChunkKey key = new ChunkKey(0, 0);
        ChunkStreamingTicket ticket =
                repository.request(key, 1L, GENERATE);
        ChunkGenerationData data = filled(key, (byte) 1);

        assertThrows(
                NullPointerException.class,
                () -> repository.request(null, 1L, GENERATE));
        assertThrows(
                NullPointerException.class,
                () -> repository.request(key, 1L, null));
        assertThrows(
                NullPointerException.class,
                () -> repository.publish(null, data, identityFor(ticket)));
        assertThrows(
                NullPointerException.class,
                () -> repository.publish(ticket, null, identityFor(ticket)));
        assertThrows(
                NullPointerException.class,
                () -> repository.publish(ticket, data, null));
        assertThrows(
                NullPointerException.class,
                () -> repository.cancel(null));
        assertThrows(
                NullPointerException.class,
                () -> repository.availability(null));
        assertThrows(
                NullPointerException.class,
                () -> repository.completeUnload((ChunkStreamingTicket) null));
    }

    private static ChunkRepository repository() {
        return new ChunkRepository(
                WORLD_HEIGHT, new ChunkDirtyTracker());
    }

    private static BaseIdentity identityFor(
            ChunkStreamingTicket ticket) {
        return new BaseIdentity(
                ticket.sourcePreference(), ticket.expectedRevision());
    }

    private static ChunkGenerationData filled(
            ChunkKey key, byte blockId) {
        byte[] blocks = new byte[BLOCK_COUNT];
        java.util.Arrays.fill(blocks, blockId);
        return new ChunkGenerationData(key, WORLD_HEIGHT, blocks);
    }

    private static int blockAtOrigin(
            ChunkRepository repository, ChunkKey key) {
        return Byte.toUnsignedInt(
                repository.getBlock(
                        key.worldOriginX(),
                        0,
                        key.worldOriginZ()));
    }

    private static Object generationAttemptsMonitor(
            ChunkRepository repository) {
        try {
            Field field =
                    ChunkRepository.class.getDeclaredField(
                            "generationAttempts");
            field.setAccessible(true);
            return field.get(repository);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private static void setStreamingRequestSequence(
            ChunkRepository repository, long value) {
        try {
            Field field =
                    ChunkRepository.class.getDeclaredField(
                            "streamingRequestSequence");
            field.setAccessible(true);
            ((AtomicLong) field.get(repository)).set(value);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
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

    @SuppressWarnings("unchecked")
    private static void installNullEntryBarrier(
            ChunkRepository repository,
            ChunkKey key,
            String cleanupThreadName,
            CountDownLatch observed,
            CountDownLatch release) {
        try {
            Field field =
                    ChunkRepository.class.getDeclaredField("entries");
            field.setAccessible(true);
            Map<ChunkKey, Object> current =
                    (Map<ChunkKey, Object>) field.get(repository);
            NullEntryBarrierMap replacement =
                    new NullEntryBarrierMap(
                            key,
                            cleanupThreadName,
                            observed,
                            release);
            replacement.putAll(current);
            field.set(repository, replacement);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private static final class NullEntryBarrierMap
            extends ConcurrentHashMap<ChunkKey, Object> {
        private final ChunkKey blockedKey;
        private final String cleanupThreadName;
        private final CountDownLatch observed;
        private final CountDownLatch release;
        private final AtomicBoolean intercepted =
                new AtomicBoolean();

        private NullEntryBarrierMap(
                ChunkKey blockedKey,
                String cleanupThreadName,
                CountDownLatch observed,
                CountDownLatch release) {
            this.blockedKey = blockedKey;
            this.cleanupThreadName = cleanupThreadName;
            this.observed = observed;
            this.release = release;
        }

        @Override
        public Object get(Object key) {
            if (blockedKey.equals(key)
                    && cleanupThreadName.equals(
                            Thread.currentThread().getName())
                    && intercepted.compareAndSet(false, true)) {
                observed.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) {
                        throw new AssertionError(
                                "Timed out waiting to release key cleanup");
                    }
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(failure);
                }
                return null;
            }
            return super.get(key);
        }
    }
}

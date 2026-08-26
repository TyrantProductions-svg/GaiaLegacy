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
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ChunkRepositoryGenerationTransactionTest {
    private static final int WORLD_HEIGHT = 32;
    private static final ChunkKey KEY = new ChunkKey(0, 0);
    private static final ChunkKey NORTH = KEY.north();
    private static final ChunkKey SOUTH = KEY.south();
    private static final ChunkKey WEST = KEY.west();
    private static final ChunkKey EAST = KEY.east();
    private static final ChunkKey NORTH_WEST = KEY.northWest();
    private static final ChunkKey NORTH_EAST = KEY.northEast();
    private static final ChunkKey SOUTH_WEST = KEY.southWest();
    private static final ChunkKey SOUTH_EAST = KEY.southEast();

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
    void laterInitialCommitInvalidatesAllLoadedMeshingNeighbors() {
        ChunkRepository repository = repository();
        ChunkKey center = new ChunkKey(0, 0);
        Set<ChunkKey> neighbors = allMeshingNeighbors(center);
        for (ChunkKey neighbor : neighbors) {
            commitInitial(
                    repository, neighbor, filled(neighbor, (byte) 1));
        }
        Map<ChunkKey, Long> previousRevisions =
                neighbors.stream()
                        .collect(
                                java.util.stream.Collectors.toMap(
                                        key -> key,
                                        repository::revision));

        commitInitial(
                repository, center, filled(center, (byte) 1));

        for (ChunkKey neighbor : neighbors) {
            assertDirtiedAfter(
                    repository,
                    neighbor,
                    previousRevisions.get(neighbor));
        }
        assertEquals(9, repository.keys().size());
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
    void rebuildTicketCapturesStableRevisionWithoutChangingLifecycle() {
        ChunkRepository repository = renderableRepository(KEY);
        long revision = repository.revision(KEY);

        ChunkGenerationTicket ticket =
                repository.beginGeneration(
                        KEY, ChunkGenerationMode.REBUILD);

        assertEquals(KEY, ticket.key());
        assertEquals(ChunkGenerationMode.REBUILD, ticket.mode());
        assertEquals(revision, ticket.baseRevision());
        assertEquals(ChunkState.RENDERABLE, repository.state(KEY));
        assertEquals(
                ChunkGenerationStatus.GENERATING,
                repository.generationStatus(KEY));
    }

    @Test
    void rebuildFailurePreservesCommittedChunkAndLifecycle() {
        ChunkRepository repository = renderableRepository(KEY);
        long revision = repository.revision(KEY);
        byte oldBlock = repository.getBlock(1, 4, 1);
        ChunkGenerationTicket ticket =
                repository.beginGeneration(
                        KEY, ChunkGenerationMode.REBUILD);
        IllegalStateException failure =
                new IllegalStateException("provider failure");

        ChunkGenerationResult result =
                repository.failGeneration(ticket, failure);

        assertEquals(ChunkGenerationResult.Status.FAILED, result.status());
        assertSame(failure, result.failure().orElseThrow());
        assertEquals(revision, repository.revision(KEY));
        assertEquals(oldBlock, repository.getBlock(1, 4, 1));
        assertEquals(ChunkState.RENDERABLE, repository.state(KEY));
        assertEquals(
                ChunkGenerationStatus.FAILED,
                repository.generationStatus(KEY));
        assertSame(
                failure,
                repository.generationFailure(KEY).orElseThrow());
    }

    @Test
    void rebuildRejectsChangedBaseRevision() {
        ChunkRepository repository = generatedRepository(KEY, EAST);
        ChunkGenerationTicket ticket =
                repository.beginGeneration(
                        KEY, ChunkGenerationMode.REBUILD);
        assertTrue(repository.setBlock(1, 4, 1, (byte) 7));
        long changedRevision = repository.revision(KEY);

        ChunkGenerationResult result =
                repository.commitGeneration(
                        ticket, filled(KEY, (byte) 3));

        assertEquals(
                ChunkGenerationResult.Status.CONFLICT,
                result.status());
        assertEquals(changedRevision, repository.revision(KEY));
        assertEquals(7, repository.getBlock(1, 4, 1));
        assertEquals(ChunkState.DIRTY, repository.state(KEY));
        assertEquals(
                ChunkGenerationStatus.IDLE,
                repository.generationStatus(KEY));
    }

    @Test
    void changedEastEdgeDirtiesOnlyTargetAndLoadedEastNeighbor() {
        ChunkRepository repository =
                generatedRepository(KEY, EAST, NORTH);
        long eastRevision = repository.revision(EAST);
        long northRevision = repository.revision(NORTH);
        ChunkGenerationTicket ticket =
                repository.beginGeneration(
                        KEY, ChunkGenerationMode.REBUILD);

        ChunkGenerationResult result =
                repository.commitGeneration(
                        ticket, withEastEdgeChanged(KEY));

        assertEquals(
                ChunkGenerationResult.Status.COMMITTED,
                result.status());
        assertEquals(ChunkState.DIRTY, repository.state(KEY));
        assertDirtiedAfter(repository, EAST, eastRevision);
        assertEquals(northRevision, repository.revision(NORTH));
    }

    @Test
    void changedNorthEdgeNonCornerDirtiesOnlyLoadedNorthNeighbor() {
        ChunkRepository repository =
                generatedRepository(KEY, NORTH, WEST, NORTH_WEST);
        long northRevision = repository.revision(NORTH);
        long westRevision = repository.revision(WEST);
        long northWestRevision = repository.revision(NORTH_WEST);
        ChunkGenerationTicket ticket =
                repository.beginGeneration(
                        KEY, ChunkGenerationMode.REBUILD);

        ChunkGenerationResult result =
                repository.commitGeneration(
                        ticket, withNorthEdgeChanged(KEY));

        assertEquals(
                ChunkGenerationResult.Status.COMMITTED,
                result.status());
        assertDirtiedAfter(repository, NORTH, northRevision);
        assertEquals(westRevision, repository.revision(WEST));
        assertEquals(
                northWestRevision,
                repository.revision(NORTH_WEST));
    }

    @Test
    void changedNorthWestCornerColumnDirtiesCardinalsAndDiagonal() {
        ChunkRepository repository =
                generatedRepository(KEY, NORTH, WEST, NORTH_WEST, EAST);
        Map<ChunkKey, Long> expectedDirty =
                Map.of(
                        NORTH, repository.revision(NORTH),
                        WEST, repository.revision(WEST),
                        NORTH_WEST, repository.revision(NORTH_WEST));
        long eastRevision = repository.revision(EAST);
        ChunkGenerationTicket ticket =
                repository.beginGeneration(
                        KEY, ChunkGenerationMode.REBUILD);

        ChunkGenerationResult result =
                repository.commitGeneration(
                        ticket, withNorthWestCornerChanged(KEY));

        assertEquals(
                ChunkGenerationResult.Status.COMMITTED,
                result.status());
        for (Map.Entry<ChunkKey, Long> dirty : expectedDirty.entrySet()) {
            assertDirtiedAfter(
                    repository, dirty.getKey(), dirty.getValue());
        }
        assertEquals(eastRevision, repository.revision(EAST));
    }

    @Test
    void unchangedHorizontalEdgesDoNotDirtyLoadedNeighbors() {
        ChunkRepository repository =
                generatedRepository(KEY, NORTH, SOUTH, WEST, EAST);
        Map<ChunkKey, Long> neighborRevisions =
                Map.of(
                        NORTH, repository.revision(NORTH),
                        SOUTH, repository.revision(SOUTH),
                        WEST, repository.revision(WEST),
                        EAST, repository.revision(EAST));
        long targetRevision = repository.revision(KEY);
        ChunkGenerationTicket ticket =
                repository.beginGeneration(
                        KEY, ChunkGenerationMode.REBUILD);

        ChunkGenerationResult result =
                repository.commitGeneration(
                        ticket, withInteriorChanged(KEY));

        assertEquals(
                ChunkGenerationResult.Status.COMMITTED,
                result.status());
        assertTrue(result.revision() > targetRevision);
        assertEquals(ChunkState.DIRTY, repository.state(KEY));
        for (Map.Entry<ChunkKey, Long> neighbor :
                neighborRevisions.entrySet()) {
            assertEquals(
                    neighbor.getValue(),
                    repository.revision(neighbor.getKey()));
        }
    }

    @Test
    void allChangedHorizontalEdgesDirtyAllLoadedNeighbors() {
        ChunkRepository repository =
                generatedRepository(KEY, NORTH, SOUTH, WEST, EAST);
        Map<ChunkKey, Long> neighborRevisions =
                Map.of(
                        NORTH, repository.revision(NORTH),
                        SOUTH, repository.revision(SOUTH),
                        WEST, repository.revision(WEST),
                        EAST, repository.revision(EAST));
        ChunkGenerationTicket ticket =
                repository.beginGeneration(
                        KEY, ChunkGenerationMode.REBUILD);

        ChunkGenerationResult result =
                repository.commitGeneration(
                        ticket, withAllEdgesChanged(KEY));

        assertEquals(
                ChunkGenerationResult.Status.COMMITTED,
                result.status());
        for (Map.Entry<ChunkKey, Long> neighbor :
                neighborRevisions.entrySet()) {
            assertDirtiedAfter(
                    repository,
                    neighbor.getKey(),
                    neighbor.getValue());
        }
    }

    @Test
    void unchangedCornerColumnsDoNotDirtyLoadedDiagonals() {
        ChunkRepository repository =
                generatedRepository(
                        KEY,
                        NORTH_WEST,
                        NORTH_EAST,
                        SOUTH_WEST,
                        SOUTH_EAST);
        Map<ChunkKey, Long> diagonalRevisions =
                Map.of(
                        NORTH_WEST, repository.revision(NORTH_WEST),
                        NORTH_EAST, repository.revision(NORTH_EAST),
                        SOUTH_WEST, repository.revision(SOUTH_WEST),
                        SOUTH_EAST, repository.revision(SOUTH_EAST));
        ChunkGenerationTicket ticket =
                repository.beginGeneration(
                        KEY, ChunkGenerationMode.REBUILD);

        ChunkGenerationResult result =
                repository.commitGeneration(
                        ticket, withAllEdgesChanged(KEY));

        assertEquals(
                ChunkGenerationResult.Status.COMMITTED,
                result.status());
        for (Map.Entry<ChunkKey, Long> diagonal :
                diagonalRevisions.entrySet()) {
            assertEquals(
                    diagonal.getValue(),
                    repository.revision(diagonal.getKey()));
        }
    }

    @Test
    void changedEdgesDoNotAllocateMissingNeighbors() {
        ChunkRepository repository = generatedRepository(KEY);
        ChunkGenerationTicket ticket =
                repository.beginGeneration(
                        KEY, ChunkGenerationMode.REBUILD);

        ChunkGenerationResult result =
                repository.commitGeneration(
                        ticket, withAllEdgesChanged(KEY));

        assertEquals(
                ChunkGenerationResult.Status.COMMITTED,
                result.status());
        assertEquals(Set.of(KEY), repository.keys());
    }

    @Test
    void rebuildBeginRejectsUnloadingChunk() {
        ChunkRepository repository = generatedRepository(KEY);
        assertTrue(repository.beginUnload(KEY));
        long unloadingRevision = repository.revision(KEY);

        assertThrows(
                IllegalStateException.class,
                () ->
                        repository.beginGeneration(
                                KEY, ChunkGenerationMode.REBUILD));

        assertEquals(ChunkState.UNLOADING, repository.state(KEY));
        assertEquals(unloadingRevision, repository.revision(KEY));
    }

    @Test
    void pendingRebuildConflictsWhenUnloadBegins() {
        ChunkRepository repository = generatedRepository(KEY);
        byte oldBlock = repository.getBlock(1, 4, 1);
        ChunkGenerationTicket ticket =
                repository.beginGeneration(
                        KEY, ChunkGenerationMode.REBUILD);
        assertTrue(repository.beginUnload(KEY));
        long unloadingRevision = repository.revision(KEY);

        ChunkGenerationResult result =
                repository.commitGeneration(
                        ticket, filled(KEY, (byte) 3));

        assertEquals(
                ChunkGenerationResult.Status.CONFLICT,
                result.status());
        assertEquals(ChunkState.UNLOADING, repository.state(KEY));
        assertEquals(unloadingRevision, repository.revision(KEY));
        assertEquals(oldBlock, repository.getBlock(1, 4, 1));
        assertTrue(repository.completeUnload(KEY));
        assertEquals(
                ChunkGenerationStatus.IDLE,
                repository.generationStatus(KEY));
    }

    @Test
    void rebuildDuplicateTerminalCallsPreserveFirstOutcome() {
        ChunkRepository repository = generatedRepository(KEY);
        ChunkGenerationTicket committedTicket =
                repository.beginGeneration(
                        KEY, ChunkGenerationMode.REBUILD);
        ChunkGenerationResult committed =
                repository.commitGeneration(
                        committedTicket, filled(KEY, (byte) 3));
        long committedRevision = repository.revision(KEY);

        assertEquals(
                ChunkGenerationResult.Status.CONFLICT,
                repository
                        .commitGeneration(
                                committedTicket,
                                filled(KEY, (byte) 4))
                        .status());
        assertEquals(
                ChunkGenerationResult.Status.CONFLICT,
                repository
                        .failGeneration(
                                committedTicket,
                                new IllegalStateException("late failure"))
                        .status());
        assertEquals(committed.revision(), committedRevision);
        assertEquals(3, repository.getBlock(1, 4, 1));
        assertEquals(
                ChunkGenerationStatus.COMMITTED,
                repository.generationStatus(KEY));

        ChunkGenerationTicket failedTicket =
                repository.beginGeneration(
                        KEY, ChunkGenerationMode.REBUILD);
        IllegalStateException firstFailure =
                new IllegalStateException("first failure");
        repository.failGeneration(failedTicket, firstFailure);

        assertEquals(
                ChunkGenerationResult.Status.CONFLICT,
                repository
                        .failGeneration(
                                failedTicket,
                                new IllegalStateException("second failure"))
                        .status());
        assertEquals(
                ChunkGenerationResult.Status.CONFLICT,
                repository
                        .commitGeneration(
                                failedTicket,
                                filled(KEY, (byte) 5))
                        .status());
        assertEquals(committedRevision, repository.revision(KEY));
        assertEquals(3, repository.getBlock(1, 4, 1));
        assertSame(
                firstFailure,
                repository.generationFailure(KEY).orElseThrow());
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

    @Test
    void streamingGenerateRequestReusesGenerationAttemptAuthority() {
        ChunkRepository repository = repository();
        ChunkKey key = new ChunkKey(-8, 6);

        ChunkStreamingTicket streaming =
                repository.request(
                        key,
                        71L,
                        ChunkStreamingTicket.SourcePreference.GENERATE);

        assertEquals(
                ChunkGenerationStatus.GENERATING,
                repository.generationStatus(key));
        assertThrows(
                IllegalStateException.class,
                () ->
                        repository.beginGeneration(
                                key, ChunkGenerationMode.INITIAL));

        ChunkStreamingPublication publication =
                repository.publish(
                        streaming,
                        filled(key, (byte) 14),
                        new ChunkStreamingTicket.BaseIdentity(
                                ChunkStreamingTicket.SourcePreference.GENERATE,
                                0L));

        assertEquals(
                ChunkStreamingPublication.Status.PUBLISHED,
                publication.status());
        assertEquals(
                ChunkGenerationStatus.COMMITTED,
                repository.generationStatus(key));
        assertEquals(ChunkState.GENERATED, repository.state(key));
        assertEquals(14, repository.getBlock(-127, 4, 97));
    }

    private static ChunkRepository repository() {
        return new ChunkRepository(
                WORLD_HEIGHT, new ChunkDirtyTracker());
    }

    private static ChunkRepository generatedRepository(
            ChunkKey... keys) {
        ChunkRepository repository = repository();
        for (ChunkKey key : keys) {
            commitInitial(
                    repository, key, filled(key, (byte) 1));
        }
        return repository;
    }

    private static ChunkRepository renderableRepository(ChunkKey key) {
        ChunkRepository repository = generatedRepository(key);
        ChunkMeshInput input =
                repository.claimMeshing(key).orElseThrow();
        long revision = input.center().revision();
        assertTrue(repository.markReadyForUpload(key, revision));
        assertTrue(repository.markRenderable(key, revision));
        return repository;
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

    private static ChunkGenerationData withInteriorChanged(
            ChunkKey key) {
        byte[] blocks = blocks();
        java.util.Arrays.fill(blocks, (byte) 1);
        blocks[canonicalIndex(1, 4, 1)] = 2;
        return new ChunkGenerationData(key, WORLD_HEIGHT, blocks);
    }

    private static ChunkGenerationData withEastEdgeChanged(
            ChunkKey key) {
        byte[] blocks = blocks();
        java.util.Arrays.fill(blocks, (byte) 1);
        blocks[
                        canonicalIndex(
                                GameConfig.Chunk.SIZE - 1,
                                4,
                                1)] =
                2;
        return new ChunkGenerationData(key, WORLD_HEIGHT, blocks);
    }

    private static ChunkGenerationData withNorthEdgeChanged(
            ChunkKey key) {
        byte[] blocks = blocks();
        java.util.Arrays.fill(blocks, (byte) 1);
        blocks[canonicalIndex(3, 4, 0)] = 2;
        return new ChunkGenerationData(key, WORLD_HEIGHT, blocks);
    }

    private static ChunkGenerationData withNorthWestCornerChanged(
            ChunkKey key) {
        byte[] blocks = blocks();
        java.util.Arrays.fill(blocks, (byte) 1);
        blocks[canonicalIndex(0, 4, 0)] = 2;
        return new ChunkGenerationData(key, WORLD_HEIGHT, blocks);
    }

    private static ChunkGenerationData withAllEdgesChanged(
            ChunkKey key) {
        byte[] blocks = blocks();
        java.util.Arrays.fill(blocks, (byte) 1);
        blocks[canonicalIndex(0, 1, 1)] = 2;
        blocks[
                        canonicalIndex(
                                GameConfig.Chunk.SIZE - 1,
                                2,
                                2)] =
                2;
        blocks[canonicalIndex(3, 3, 0)] = 2;
        blocks[
                        canonicalIndex(
                                4,
                                4,
                                GameConfig.Chunk.SIZE - 1)] =
                2;
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

    private static Set<ChunkKey> allMeshingNeighbors(ChunkKey center) {
        return Set.of(
                center.north(),
                center.northEast(),
                center.east(),
                center.southEast(),
                center.south(),
                center.southWest(),
                center.west(),
                center.northWest());
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

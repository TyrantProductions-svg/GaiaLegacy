package com.overlord.voxel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.overlord.config.GameConfig;
import java.lang.reflect.Field;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ChunkRepositoryTest {
    @Test
    void eastEdgeChangeDirtiesOnlyTargetAndEastNeighbor() {
        ChunkRepository repository = generatedPairEastWest();
        ChunkKey center = new ChunkKey(0, 0);
        ChunkKey east = center.east();
        long centerRevision = repository.revision(center);
        long eastRevision = repository.revision(east);

        repository.setBlock(
                GameConfig.Chunk.SIZE - 1, 4, 2, (byte) 1);

        assertEquals(ChunkState.DIRTY, repository.state(center));
        assertEquals(ChunkState.DIRTY, repository.state(east));
        assertEquals(centerRevision + 1, repository.revision(center));
        assertEquals(eastRevision + 1, repository.revision(east));
        assertEquals(Set.of(center, east), repository.keys());
    }

    @Test
    void westEdgeChangeDirtiesOnlyTargetAndWestNeighbor() {
        ChunkRepository repository =
                generatedPair(new ChunkKey(0, 0), new ChunkKey(-1, 0));
        ChunkKey center = new ChunkKey(0, 0);
        ChunkKey west = center.west();
        long centerRevision = repository.revision(center);
        long westRevision = repository.revision(west);

        repository.setBlock(0, 4, 2, (byte) 1);

        assertEquals(ChunkState.DIRTY, repository.state(center));
        assertEquals(ChunkState.DIRTY, repository.state(west));
        assertEquals(centerRevision + 1, repository.revision(center));
        assertEquals(westRevision + 1, repository.revision(west));
        assertEquals(Set.of(center, west), repository.keys());
    }

    @Test
    void northEdgeChangeDirtiesOnlyTargetAndNorthNeighbor() {
        ChunkRepository repository =
                generatedPair(new ChunkKey(0, 0), new ChunkKey(0, -1));
        ChunkKey center = new ChunkKey(0, 0);
        ChunkKey north = center.north();
        long centerRevision = repository.revision(center);
        long northRevision = repository.revision(north);

        repository.setBlock(2, 4, 0, (byte) 1);

        assertEquals(ChunkState.DIRTY, repository.state(center));
        assertEquals(ChunkState.DIRTY, repository.state(north));
        assertEquals(centerRevision + 1, repository.revision(center));
        assertEquals(northRevision + 1, repository.revision(north));
        assertEquals(Set.of(center, north), repository.keys());
    }

    @Test
    void southEdgeChangeDirtiesOnlyTargetAndSouthNeighbor() {
        ChunkRepository repository =
                generatedPair(new ChunkKey(0, 0), new ChunkKey(0, 1));
        ChunkKey center = new ChunkKey(0, 0);
        ChunkKey south = center.south();
        long centerRevision = repository.revision(center);
        long southRevision = repository.revision(south);

        repository.setBlock(
                2, 4, GameConfig.Chunk.SIZE - 1, (byte) 1);

        assertEquals(ChunkState.DIRTY, repository.state(center));
        assertEquals(ChunkState.DIRTY, repository.state(south));
        assertEquals(centerRevision + 1, repository.revision(center));
        assertEquals(southRevision + 1, repository.revision(south));
        assertEquals(Set.of(center, south), repository.keys());
    }

    @Test
    void cornerChangesDirtyTwoOrthogonalNeighborsWithoutDiagonals() {
        assertCornerDirtiesOnly(
                0,
                0,
                new ChunkKey(0, 0).west(),
                new ChunkKey(0, 0).north());
        assertCornerDirtiesOnly(
                GameConfig.Chunk.SIZE - 1,
                0,
                new ChunkKey(0, 0).east(),
                new ChunkKey(0, 0).north());
        assertCornerDirtiesOnly(
                0,
                GameConfig.Chunk.SIZE - 1,
                new ChunkKey(0, 0).west(),
                new ChunkKey(0, 0).south());
        assertCornerDirtiesOnly(
                GameConfig.Chunk.SIZE - 1,
                GameConfig.Chunk.SIZE - 1,
                new ChunkKey(0, 0).east(),
                new ChunkKey(0, 0).south());
    }

    @Test
    void interiorChangeDoesNotDirtyNeighbor() {
        ChunkRepository repository = generatedPairEastWest();
        ChunkKey east = new ChunkKey(1, 0);
        long eastRevision = repository.revision(east);
        ChunkState eastState = repository.state(east);

        repository.setBlock(2, 4, 2, (byte) 1);

        assertEquals(eastRevision, repository.revision(east));
        assertEquals(eastState, repository.state(east));
    }

    @Test
    void successfulGenerationDirtiesEachPresentCardinalNeighborOnce() {
        ChunkRepository repository = new ChunkRepository();
        ChunkKey center = new ChunkKey(0, 0);
        Set<ChunkKey> neighbors =
                Set.of(
                        center.north(),
                        center.south(),
                        center.west(),
                        center.east());
        for (ChunkKey neighbor : neighbors) {
            repository.generate(
                    neighbor,
                    chunk -> chunk.setBlock(1, 1, 1, (byte) 1));
        }

        repository.generate(
                center,
                chunk -> chunk.setBlock(1, 1, 1, (byte) 1));

        assertEquals(ChunkState.GENERATED, repository.state(center));
        assertEquals(1L, repository.revision(center));
        for (ChunkKey neighbor : neighbors) {
            assertEquals(ChunkState.DIRTY, repository.state(neighbor));
            assertEquals(2L, repository.revision(neighbor));
        }
    }

    @Test
    void boundaryChangeDoesNotCreateMissingNeighbors() {
        ChunkRepository repository = new ChunkRepository();
        ChunkKey center = new ChunkKey(0, 0);
        repository.generate(
                center, chunk -> chunk.setBlock(1, 1, 1, (byte) 1));
        long centerRevision = repository.revision(center);

        repository.setBlock(
                GameConfig.Chunk.SIZE - 1,
                4,
                GameConfig.Chunk.SIZE - 1,
                (byte) 1);

        assertEquals(Set.of(center), repository.keys());
        assertEquals(centerRevision + 1, repository.revision(center));
    }

    @Test
    @SuppressWarnings("deprecation")
    void boundaryPropagationLeavesCompatibilityCreatedNeighborEmpty() {
        ChunkRepository repository = new ChunkRepository();
        World world = new World(repository);
        ChunkKey center = new ChunkKey(0, 0);
        ChunkKey east = center.east();
        Chunk compatibilityChunk = world.getChunk(east.x(), east.z());

        repository.generate(
                center, chunk -> chunk.setBlock(1, 1, 1, (byte) 1));
        assertEquals(ChunkState.EMPTY, repository.state(east));
        assertEquals(0L, repository.revision(east));
        long centerRevision = repository.revision(center);

        assertTrue(
                repository.setBlock(
                        GameConfig.Chunk.SIZE - 1,
                        4,
                        2,
                        (byte) 1));

        assertEquals(ChunkState.DIRTY, repository.state(center));
        assertEquals(centerRevision + 1, repository.revision(center));
        assertEquals(ChunkState.EMPTY, repository.state(east));
        assertEquals(0L, repository.revision(east));
        assertSame(
                compatibilityChunk,
                world.getChunk(east.x(), east.z()));
    }

    @Test
    void unchangedBoundaryWriteDoesNotDirtyTargetOrNeighbor() {
        ChunkRepository repository = generatedPairEastWest();
        ChunkKey center = new ChunkKey(0, 0);
        ChunkKey east = center.east();
        int worldX = GameConfig.Chunk.SIZE - 1;
        repository.setBlock(worldX, 4, 2, (byte) 1);
        long centerRevision = repository.revision(center);
        long eastRevision = repository.revision(east);
        ChunkState centerState = repository.state(center);
        ChunkState eastState = repository.state(east);

        assertFalse(repository.setBlock(worldX, 4, 2, (byte) 1));

        assertEquals(centerRevision, repository.revision(center));
        assertEquals(eastRevision, repository.revision(east));
        assertEquals(centerState, repository.state(center));
        assertEquals(eastState, repository.state(east));
    }

    @Test
    void nonMultipleNegativeWorldCornerUsesFloorBasedDirtyPropagation() {
        int worldX = -1;
        int worldZ = -GameConfig.Chunk.SIZE - 1;
        ChunkKey target = new ChunkKey(-1, -2);
        ChunkKey east = new ChunkKey(0, -2);
        ChunkKey south = new ChunkKey(-1, -1);
        ChunkKey diagonal = new ChunkKey(0, -1);
        ChunkRepository repository =
                generatedChunks(target, east, south, diagonal);
        long targetRevision = repository.revision(target);
        long eastRevision = repository.revision(east);
        long southRevision = repository.revision(south);
        long diagonalRevision = repository.revision(diagonal);

        repository.setBlock(worldX, 4, worldZ, (byte) 1);

        assertEquals(targetRevision + 1, repository.revision(target));
        assertEquals(eastRevision + 1, repository.revision(east));
        assertEquals(southRevision + 1, repository.revision(south));
        assertEquals(diagonalRevision, repository.revision(diagonal));
        assertEquals(
                1,
                Byte.toUnsignedInt(
                        repository.getBlock(worldX, 4, worldZ)));
    }

    @Test
    void generationTransitionsOnceAndSnapshotOwnsBytes() {
        ChunkRepository repository = new ChunkRepository();
        ChunkKey key = new ChunkKey(0, 0);

        repository.generate(
                key, chunk -> chunk.setBlock(1, 18, 3, (byte) 7));

        assertEquals(ChunkState.GENERATED, repository.state(key));
        assertEquals(1L, repository.revision(key));
        ChunkSnapshot snapshot = repository.snapshot(key).orElseThrow();

        repository.setBlock(1, 18, 3, (byte) 9);

        assertEquals(7, Byte.toUnsignedInt(snapshot.getBlock(1, 18, 3)));
        assertEquals(9, Byte.toUnsignedInt(repository.getBlock(1, 18, 3)));
        assertEquals(ChunkState.DIRTY, repository.state(key));
        assertEquals(2L, repository.revision(key));
    }

    @Test
    void missingReadsAndAirWritesDoNotCreateEntries() {
        ChunkRepository repository = new ChunkRepository();

        assertEquals(0, repository.getBlock(20, 5, -2));
        assertFalse(repository.setBlock(20, 5, -2, (byte) 0));
        assertTrue(repository.keys().isEmpty());
    }

    @Test
    void nonAirMutationExplicitlyCreatesDirtyEntry() {
        ChunkRepository repository = new ChunkRepository();

        assertTrue(repository.setBlock(20, 5, -2, (byte) 3));

        ChunkKey key = ChunkKey.fromWorld(20, -2);
        assertEquals(ChunkState.DIRTY, repository.state(key));
        assertEquals(1L, repository.revision(key));
        assertEquals(3, Byte.toUnsignedInt(repository.getBlock(20, 5, -2)));
    }

    @Test
    void outOfRangeYDoesNotCreateOrMutateEntries() {
        ChunkRepository repository = new ChunkRepository(32, new ChunkDirtyTracker());

        assertEquals(0, repository.getBlock(1, -1, 1));
        assertEquals(0, repository.getBlock(1, 32, 1));
        assertFalse(repository.setBlock(1, -1, 1, (byte) 4));
        assertFalse(repository.setBlock(1, 32, 1, (byte) 4));
        assertTrue(repository.keys().isEmpty());
    }

    @Test
    void duplicateGenerationIsRejectedWithoutChangingEntry() {
        ChunkRepository repository = new ChunkRepository();
        ChunkKey key = new ChunkKey(2, -3);
        repository.generate(
                key, chunk -> chunk.setBlock(4, 5, 6, (byte) 8));

        IllegalStateException failure =
                assertThrows(
                        IllegalStateException.class,
                        () -> repository.generate(key, chunk -> {}));

        assertTrue(failure.getMessage().contains(key.toString()));
        assertTrue(failure.getMessage().contains(ChunkState.GENERATED.toString()));
        assertTrue(failure.getMessage().contains(ChunkState.GENERATING.toString()));
        assertEquals(ChunkState.GENERATED, repository.state(key));
        assertEquals(1L, repository.revision(key));
        assertEquals(
                8,
                Byte.toUnsignedInt(
                        repository.getBlock(
                                key.worldOriginX() + 4,
                                5,
                                key.worldOriginZ() + 6)));
    }

    @Test
    void generatorFailureRemovesEntryAndRethrowsFailure() {
        ChunkRepository repository = new ChunkRepository();
        ChunkKey key = new ChunkKey(1, 1);
        IllegalArgumentException expected =
                new IllegalArgumentException("generation failed");

        IllegalArgumentException actual =
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                repository.generate(
                                        key,
                                        chunk -> {
                                            chunk.setBlock(1, 1, 1, (byte) 5);
                                            throw expected;
                                        }));

        assertSame(expected, actual);
        assertFalse(repository.contains(key));
        assertEquals(ChunkState.EMPTY, repository.state(key));
        assertEquals(0L, repository.revision(key));
        assertTrue(repository.snapshot(key).isEmpty());
    }

    @Test
    void failedGenerationDoesNotDirtyExistingCardinalNeighbor() {
        ChunkRepository repository = new ChunkRepository();
        ChunkKey target = new ChunkKey(0, 0);
        ChunkKey east = target.east();
        repository.generate(
                east, chunk -> chunk.setBlock(1, 1, 1, (byte) 1));
        long eastRevision = repository.revision(east);
        ChunkState eastState = repository.state(east);
        IllegalStateException expected =
                new IllegalStateException("generation failed");

        IllegalStateException actual =
                assertThrows(
                        IllegalStateException.class,
                        () ->
                                repository.generate(
                                        target,
                                        chunk -> {
                                            chunk.setBlock(1, 1, 1, (byte) 2);
                                            throw expected;
                                        }));

        assertSame(expected, actual);
        assertFalse(repository.contains(target));
        assertEquals(ChunkState.EMPTY, repository.state(target));
        assertEquals(0L, repository.revision(target));
        assertEquals(eastState, repository.state(east));
        assertEquals(eastRevision, repository.revision(east));
    }

    @Test
    void mutationRetriesWhenGenerationFailureRemovesCapturedEntry()
            throws Exception {
        ChunkRepository repository = new ChunkRepository();
        ChunkKey key = new ChunkKey(0, 0);
        CountDownLatch generationStarted = new CountDownLatch(1);
        CountDownLatch failGeneration = new CountDownLatch(1);
        CountDownLatch mutationStarted = new CountDownLatch(1);
        AtomicReference<Thread> mutationThread = new AtomicReference<>();
        IllegalStateException generationFailure =
                new IllegalStateException("generation failed");
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<?> generation =
                    executor.submit(
                            () ->
                                    repository.generate(
                                            key,
                                            chunk -> {
                                                generationStarted.countDown();
                                                try {
                                                    if (!failGeneration.await(
                                                            5,
                                                            TimeUnit.SECONDS)) {
                                                        throw new AssertionError(
                                                                "generation release timed out");
                                                    }
                                                } catch (InterruptedException failure) {
                                                    Thread.currentThread().interrupt();
                                                    throw new AssertionError(failure);
                                                }
                                                throw generationFailure;
                                            }));
            assertTrue(
                    generationStarted.await(5, TimeUnit.SECONDS),
                    "generation did not acquire the entry monitor");

            Future<Boolean> mutation =
                    executor.submit(
                            () -> {
                                mutationThread.set(Thread.currentThread());
                                mutationStarted.countDown();
                                return repository.setBlock(
                                        1, 2, 3, (byte) 7);
                            });
            assertTrue(
                    mutationStarted.await(5, TimeUnit.SECONDS),
                    "mutation task did not start");

            long blockedDeadline =
                    System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (mutationThread.get().getState() != Thread.State.BLOCKED
                    && System.nanoTime() < blockedDeadline) {
                Thread.onSpinWait();
            }
            assertEquals(
                    Thread.State.BLOCKED,
                    mutationThread.get().getState(),
                    "mutation did not block on the generation entry monitor");

            failGeneration.countDown();

            ExecutionException actualFailure =
                    assertThrows(
                            ExecutionException.class,
                            () -> generation.get(5, TimeUnit.SECONDS));
            assertSame(generationFailure, actualFailure.getCause());
            assertTrue(mutation.get(5, TimeUnit.SECONDS));
            assertEquals(ChunkState.DIRTY, repository.state(key));
            assertEquals(1L, repository.revision(key));
            assertEquals(7, Byte.toUnsignedInt(repository.getBlock(1, 2, 3)));
        } finally {
            failGeneration.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void boundaryPropagationDoesNotMutateRemovedNeighborEntry()
            throws Exception {
        ChunkRepository repository = new ChunkRepository();
        ChunkKey target = new ChunkKey(0, 0);
        ChunkKey east = target.east();
        repository.generate(
                target, chunk -> chunk.setBlock(1, 1, 1, (byte) 1));
        long targetRevision = repository.revision(target);
        CountDownLatch generationStarted = new CountDownLatch(1);
        CountDownLatch failGeneration = new CountDownLatch(1);
        CountDownLatch mutationStarted = new CountDownLatch(1);
        AtomicReference<Thread> mutationThread = new AtomicReference<>();
        IllegalStateException generationFailure =
                new IllegalStateException("generation failed");
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<?> generation =
                    executor.submit(
                            () ->
                                    repository.generate(
                                            east,
                                            chunk -> {
                                                generationStarted.countDown();
                                                try {
                                                    if (!failGeneration.await(
                                                            5,
                                                            TimeUnit.SECONDS)) {
                                                        throw new AssertionError(
                                                                "generation release timed out");
                                                    }
                                                } catch (InterruptedException failure) {
                                                    Thread.currentThread().interrupt();
                                                    throw new AssertionError(failure);
                                                }
                                                throw generationFailure;
                                            }));
            assertTrue(
                    generationStarted.await(5, TimeUnit.SECONDS),
                    "neighbor generation did not acquire its entry monitor");
            Object capturedNeighborEntry =
                    capturedEntry(repository, east);
            assertTrue(
                    capturedNeighborEntry != null,
                    "neighbor entry was not present during generation");

            Future<Boolean> mutation =
                    executor.submit(
                            () -> {
                                mutationThread.set(Thread.currentThread());
                                mutationStarted.countDown();
                                return repository.setBlock(
                                        GameConfig.Chunk.SIZE - 1,
                                        2,
                                        3,
                                        (byte) 7);
                            });
            assertTrue(
                    mutationStarted.await(5, TimeUnit.SECONDS),
                    "boundary mutation task did not start");

            long blockedDeadline =
                    System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (mutationThread.get().getState() != Thread.State.BLOCKED
                    && System.nanoTime() < blockedDeadline) {
                Thread.onSpinWait();
            }
            assertEquals(
                    Thread.State.BLOCKED,
                    mutationThread.get().getState(),
                    "boundary mutation did not block on the neighbor entry monitor");

            failGeneration.countDown();

            ExecutionException actualFailure =
                    assertThrows(
                            ExecutionException.class,
                            () -> generation.get(5, TimeUnit.SECONDS));
            assertSame(generationFailure, actualFailure.getCause());
            assertTrue(mutation.get(5, TimeUnit.SECONDS));
            assertEquals(ChunkState.DIRTY, repository.state(target));
            assertEquals(
                    targetRevision + 1,
                    repository.revision(target));
            assertEquals(
                    7,
                    Byte.toUnsignedInt(
                            repository.getBlock(
                                    GameConfig.Chunk.SIZE - 1,
                                    2,
                                    3)));
            assertFalse(repository.contains(east));
            assertEquals(ChunkState.EMPTY, repository.state(east));
            assertEquals(0L, repository.revision(east));
            assertTrue(repository.snapshot(east).isEmpty());
            assertEquals(Set.of(target), repository.keys());
            assertRemovedEntryWasNotDirtied(
                    capturedNeighborEntry, generationFailure);
        } finally {
            failGeneration.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void keysAreImmutableAndDetachedFromLaterRepositoryChanges() {
        ChunkRepository repository = new ChunkRepository();
        ChunkKey first = new ChunkKey(0, 0);
        ChunkKey second = new ChunkKey(1, 0);
        repository.setBlock(1, 1, 1, (byte) 1);
        Set<ChunkKey> keys = repository.keys();

        assertThrows(UnsupportedOperationException.class, () -> keys.add(second));

        repository.setBlock(17, 1, 1, (byte) 1);
        assertEquals(Set.of(first), keys);
        assertEquals(Set.of(first, second), repository.keys());
    }

    @Test
    void generatedChunksAreNotRenderableBeforeUploadLifecycleCompletes() {
        ChunkRepository repository = new ChunkRepository();
        ChunkKey key = new ChunkKey(0, 0);

        assertFalse(repository.isRenderable(key));
        repository.generate(key, chunk -> {});
        assertFalse(repository.isRenderable(key));
    }

    private static ChunkRepository generatedPairEastWest() {
        return generatedPair(new ChunkKey(0, 0), new ChunkKey(1, 0));
    }

    private static ChunkRepository generatedPair(
            ChunkKey first, ChunkKey second) {
        return generatedChunks(first, second);
    }

    private static ChunkRepository generatedChunks(ChunkKey... keys) {
        ChunkRepository repository = new ChunkRepository();
        for (ChunkKey key : keys) {
            repository.generate(
                    key,
                    chunk -> chunk.setBlock(1, 1, 1, (byte) 1));
        }
        return repository;
    }

    private static void assertCornerDirtiesOnly(
            int localX,
            int localZ,
            ChunkKey firstNeighbor,
            ChunkKey secondNeighbor) {
        ChunkKey center = new ChunkKey(0, 0);
        ChunkKey diagonal =
                new ChunkKey(
                        firstNeighbor.x() + secondNeighbor.x(),
                        firstNeighbor.z() + secondNeighbor.z());
        ChunkRepository repository =
                generatedChunks(
                        center,
                        firstNeighbor,
                        secondNeighbor,
                        diagonal);
        long centerRevision = repository.revision(center);
        long firstRevision = repository.revision(firstNeighbor);
        long secondRevision = repository.revision(secondNeighbor);
        long diagonalRevision = repository.revision(diagonal);

        repository.setBlock(localX, 4, localZ, (byte) 1);

        assertEquals(centerRevision + 1, repository.revision(center));
        assertEquals(firstRevision + 1, repository.revision(firstNeighbor));
        assertEquals(secondRevision + 1, repository.revision(secondNeighbor));
        assertEquals(diagonalRevision, repository.revision(diagonal));
    }

    @SuppressWarnings("unchecked")
    private static Object capturedEntry(
            ChunkRepository repository, ChunkKey key) {
        try {
            Field entriesField =
                    ChunkRepository.class.getDeclaredField("entries");
            entriesField.setAccessible(true);
            ConcurrentHashMap<ChunkKey, ?> entries =
                    (ConcurrentHashMap<ChunkKey, ?>)
                            entriesField.get(repository);
            return entries.get(key);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private static void assertRemovedEntryWasNotDirtied(
            Object entry, Throwable generationFailure) {
        try {
            Field revisionField =
                    entry.getClass().getDeclaredField("revision");
            Field failureField =
                    entry.getClass().getDeclaredField("failure");
            revisionField.setAccessible(true);
            failureField.setAccessible(true);

            assertEquals(0L, revisionField.getLong(entry));
            assertSame(generationFailure, failureField.get(entry));
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }
}

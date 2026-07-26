package com.overlord.voxel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.overlord.config.GameConfig;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
    void dirtyChunkRevisionRequiresKeyAndPositiveRevision() {
        ChunkKey key = new ChunkKey(2, -3);

        assertEquals(key, new DirtyChunkRevision(key, 1).key());
        assertThrows(
                NullPointerException.class,
                () -> new DirtyChunkRevision(null, 1));
        assertThrows(
                IllegalArgumentException.class,
                () -> new DirtyChunkRevision(key, 0));
        assertThrows(
                IllegalArgumentException.class,
                () -> new DirtyChunkRevision(key, -1));
    }

    @Test
    void mutationOutcomeDefensivelyCopiesImmutableOrderedCollections() {
        ChunkKey target = new ChunkKey(1, 2);
        ChunkKey east = target.east();
        List<DirtyChunkRevision> source = new ArrayList<>();
        source.add(new DirtyChunkRevision(target, 7));
        source.add(new DirtyChunkRevision(east, 8));
        ChunkMutationOutcome outcome =
                new ChunkMutationOutcome(
                        ChunkMutationOutcome.Status.APPLIED,
                        (byte) 3,
                        source);

        source.clear();

        assertEquals(
                List.of(
                        new DirtyChunkRevision(target, 7),
                        new DirtyChunkRevision(east, 8)),
                outcome.dirtiedChunks());
        assertEquals(
                List.of(target, east),
                List.copyOf(outcome.dirtyRevisions().keySet()));
        assertEquals(Map.of(target, 7L, east, 8L), outcome.dirtyRevisions());
        assertEquals(List.of(target, east), List.copyOf(outcome.dirtyChunks()));
        assertThrows(
                UnsupportedOperationException.class,
                () ->
                        outcome.dirtiedChunks()
                                .add(new DirtyChunkRevision(target, 9)));
        assertThrows(
                UnsupportedOperationException.class,
                () -> outcome.dirtyRevisions().put(target, 9L));
        assertThrows(
                UnsupportedOperationException.class,
                () -> outcome.dirtyChunks().remove(target));
        assertNotSame(outcome.dirtyRevisions(), outcome.dirtyRevisions());
        assertNotSame(outcome.dirtyChunks(), outcome.dirtyChunks());
    }

    @Test
    void mutationOutcomeRejectsDuplicateKeysAndInvalidStatusContents() {
        ChunkKey key = new ChunkKey(0, 0);
        DirtyChunkRevision dirty = new DirtyChunkRevision(key, 1);

        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new ChunkMutationOutcome(
                                ChunkMutationOutcome.Status.APPLIED,
                                (byte) 0,
                                List.of(dirty, new DirtyChunkRevision(key, 2))));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new ChunkMutationOutcome(
                                ChunkMutationOutcome.Status.APPLIED,
                                (byte) 0,
                                List.of()));
        for (ChunkMutationOutcome.Status status :
                List.of(
                        ChunkMutationOutcome.Status.NO_CHANGE,
                        ChunkMutationOutcome.Status.CONFLICT,
                        ChunkMutationOutcome.Status.OUT_OF_BOUNDS)) {
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            new ChunkMutationOutcome(
                                    status, (byte) 0, List.of(dirty)));
        }
    }

    @Test
    void compareAndSetInteriorReturnsExactTargetRevisionOnly() {
        ChunkRepository repository = new ChunkRepository();
        ChunkKey target = new ChunkKey(0, 0);
        repository.generate(target, chunk -> {});
        long previousRevision = repository.revision(target);

        ChunkMutationOutcome outcome =
                repository.compareAndSetBlock(
                        2, 4, 3, (byte) 0, (byte) 7);

        assertEquals(ChunkMutationOutcome.Status.APPLIED, outcome.status());
        assertEquals(0, Byte.toUnsignedInt(outcome.observedBlock()));
        assertEquals(List.of(target), List.copyOf(outcome.dirtyChunks()));
        assertEquals(previousRevision + 1, outcome.dirtyRevisions().get(target));
        assertEquals(repository.revision(target), outcome.dirtyRevisions().get(target));
        assertEquals(ChunkState.DIRTY, repository.state(target));
        assertEquals(7, Byte.toUnsignedInt(repository.getBlock(2, 4, 3)));
    }

    @Test
    void compareAndSetLoadedCardinalEdgesReturnExactOrderedRevisions() {
        assertEdgeOutcome(
                GameConfig.Chunk.SIZE - 1,
                2,
                new ChunkKey(0, 0).east());
        assertEdgeOutcome(0, 2, new ChunkKey(0, 0).west());
        assertEdgeOutcome(2, 0, new ChunkKey(0, 0).north());
        assertEdgeOutcome(
                2,
                GameConfig.Chunk.SIZE - 1,
                new ChunkKey(0, 0).south());
    }

    @Test
    void compareAndSetCornerReturnsOrderedCardinalAndDiagonalRevisions() {
        ChunkKey target = new ChunkKey(0, 0);
        ChunkKey east = target.east();
        ChunkKey south = target.south();
        ChunkKey diagonal = new ChunkKey(1, 1);
        ChunkRepository repository =
                generatedChunks(target, east, south, diagonal);
        Map<ChunkKey, Long> previousRevisions =
                Map.of(
                        target, repository.revision(target),
                        east, repository.revision(east),
                        south, repository.revision(south),
                        diagonal, repository.revision(diagonal));

        ChunkMutationOutcome outcome =
                repository.compareAndSetBlock(
                        GameConfig.Chunk.SIZE - 1,
                        4,
                        GameConfig.Chunk.SIZE - 1,
                        (byte) 0,
                        (byte) 6);

        assertEquals(
                List.of(target, east, south, diagonal),
                List.copyOf(outcome.dirtyChunks()));
        assertEquals(
                repository.revision(target),
                outcome.dirtyRevisions().get(target));
        assertEquals(
                repository.revision(east),
                outcome.dirtyRevisions().get(east));
        assertEquals(
                repository.revision(south),
                outcome.dirtyRevisions().get(south));
        assertEquals(
                repository.revision(diagonal),
                outcome.dirtyRevisions().get(diagonal));
        assertEquals(4, Set.copyOf(outcome.dirtyRevisions().values()).size());
        for (Map.Entry<ChunkKey, Long> previous :
                previousRevisions.entrySet()) {
            assertTrue(
                    outcome.dirtyRevisions().get(previous.getKey())
                            > previous.getValue());
        }
    }

    @Test
    void compareAndSetCornerOmitsMissingDiagonalWithoutAllocatingRevision() {
        ChunkKey target = new ChunkKey(0, 0);
        ChunkKey east = target.east();
        ChunkKey south = target.south();
        ChunkKey diagonal = target.southEast();
        ChunkRepository repository = generatedChunks(target, east, south);

        ChunkMutationOutcome outcome =
                repository.compareAndSetBlock(
                        GameConfig.Chunk.SIZE - 1,
                        4,
                        GameConfig.Chunk.SIZE - 1,
                        (byte) 0,
                        (byte) 6);

        assertEquals(
                List.of(target, east, south),
                List.copyOf(outcome.dirtyChunks()));
        assertFalse(outcome.dirtyRevisions().containsKey(diagonal));
        assertEquals(0L, repository.revision(diagonal));
        assertFalse(repository.contains(diagonal));
    }

    @Test
    void compareAndSetMissingEdgeNeighborsRemainCompletelyAbsent() {
        ChunkRepository repository = new ChunkRepository();
        ChunkKey target = new ChunkKey(0, 0);
        ChunkKey east = target.east();
        ChunkKey south = target.south();
        repository.generate(target, chunk -> {});

        ChunkMutationOutcome outcome =
                repository.compareAndSetBlock(
                        GameConfig.Chunk.SIZE - 1,
                        4,
                        GameConfig.Chunk.SIZE - 1,
                        (byte) 0,
                        (byte) 4);

        assertEquals(Set.of(target), repository.keys());
        assertEquals(List.of(target), List.copyOf(outcome.dirtyChunks()));
        assertFalse(outcome.dirtyRevisions().containsKey(east));
        assertFalse(outcome.dirtyRevisions().containsKey(south));
        assertEquals(0L, repository.revision(east));
        assertEquals(0L, repository.revision(south));
        assertTrue(repository.snapshot(east).isEmpty());
        assertTrue(repository.snapshot(south).isEmpty());
    }

    @Test
    void compareAndSetConflictReportsActualWithoutRevisionChange() {
        ChunkRepository repository = new ChunkRepository();
        ChunkKey key = new ChunkKey(0, 0);
        repository.generate(
                key, chunk -> chunk.setBlock(2, 4, 3, (byte) 5));
        long revision = repository.revision(key);

        ChunkMutationOutcome outcome =
                repository.compareAndSetBlock(
                        2, 4, 3, (byte) 4, (byte) 7);

        assertEquals(ChunkMutationOutcome.Status.CONFLICT, outcome.status());
        assertEquals(5, Byte.toUnsignedInt(outcome.observedBlock()));
        assertTrue(outcome.dirtiedChunks().isEmpty());
        assertEquals(revision, repository.revision(key));
        assertEquals(5, Byte.toUnsignedInt(repository.getBlock(2, 4, 3)));
    }

    @Test
    void compareAndSetNoChangeAndOutOfBoundsDoNotIssueRevisions() {
        ChunkRepository repository =
                new ChunkRepository(32, new ChunkDirtyTracker());
        ChunkKey key = new ChunkKey(0, 0);
        repository.generate(
                key, chunk -> chunk.setBlock(2, 4, 3, (byte) 5));
        long revision = repository.revision(key);

        ChunkMutationOutcome noChange =
                repository.compareAndSetBlock(
                        2, 4, 3, (byte) 5, (byte) 5);
        ChunkMutationOutcome below =
                repository.compareAndSetBlock(
                        2, -1, 3, (byte) 0, (byte) 7);
        ChunkMutationOutcome above =
                repository.compareAndSetBlock(
                        2, 32, 3, (byte) 0, (byte) 7);

        assertEquals(ChunkMutationOutcome.Status.NO_CHANGE, noChange.status());
        assertEquals(5, Byte.toUnsignedInt(noChange.observedBlock()));
        assertTrue(noChange.dirtiedChunks().isEmpty());
        for (ChunkMutationOutcome outcome : List.of(below, above)) {
            assertEquals(
                    ChunkMutationOutcome.Status.OUT_OF_BOUNDS,
                    outcome.status());
            assertEquals(0, Byte.toUnsignedInt(outcome.observedBlock()));
            assertTrue(outcome.dirtiedChunks().isEmpty());
        }
        assertEquals(revision, repository.revision(key));
    }

    @Test
    void compareAndSetAbsentChunkSupportsAirCreationAndConflictWithoutAllocation() {
        ChunkRepository repository = new ChunkRepository();
        ChunkKey conflictKey = ChunkKey.fromWorld(34, -3);

        ChunkMutationOutcome conflict =
                repository.compareAndSetBlock(
                        34, 5, -3, (byte) 2, (byte) 7);

        assertEquals(ChunkMutationOutcome.Status.CONFLICT, conflict.status());
        assertEquals(0, Byte.toUnsignedInt(conflict.observedBlock()));
        assertTrue(conflict.dirtiedChunks().isEmpty());
        assertFalse(repository.contains(conflictKey));

        ChunkMutationOutcome noChange =
                repository.compareAndSetBlock(
                        34, 5, -3, (byte) 0, (byte) 0);

        assertEquals(ChunkMutationOutcome.Status.NO_CHANGE, noChange.status());
        assertFalse(repository.contains(conflictKey));

        ChunkMutationOutcome applied =
                repository.compareAndSetBlock(
                        34, 5, -3, (byte) 0, (byte) 7);

        assertEquals(ChunkMutationOutcome.Status.APPLIED, applied.status());
        assertEquals(List.of(conflictKey), List.copyOf(applied.dirtyChunks()));
        assertEquals(
                repository.revision(conflictKey),
                applied.dirtyRevisions().get(conflictKey));
        assertEquals(7, Byte.toUnsignedInt(repository.getBlock(34, 5, -3)));
    }

    @Test
    void compareAndSetNegativeWorldCornerUsesFloorBoundaryMapping() {
        int worldX = -1;
        int worldZ = -GameConfig.Chunk.SIZE - 1;
        ChunkKey target = new ChunkKey(-1, -2);
        ChunkKey east = target.east();
        ChunkKey south = target.south();
        ChunkRepository repository = generatedChunks(target, east, south);

        ChunkMutationOutcome outcome =
                repository.compareAndSetBlock(
                        worldX,
                        4,
                        worldZ,
                        (byte) 0,
                        (byte) 3);

        assertEquals(
                List.of(target, east, south),
                List.copyOf(outcome.dirtyChunks()));
        assertEquals(3, Byte.toUnsignedInt(repository.getBlock(worldX, 4, worldZ)));
    }

    @Test
    void compareAndSetInvalidatesPreviouslyClaimedReadyRevision() {
        ChunkRepository repository = new ChunkRepository();
        ChunkKey key = new ChunkKey(0, 0);
        repository.generate(key, chunk -> {});
        long claimedRevision =
                repository.claimMeshing(key).orElseThrow().center().revision();
        assertTrue(repository.markReadyForUpload(key, claimedRevision));

        ChunkMutationOutcome outcome =
                repository.compareAndSetBlock(
                        1, 1, 1, (byte) 0, (byte) 1);

        assertEquals(ChunkMutationOutcome.Status.APPLIED, outcome.status());
        assertEquals(ChunkState.DIRTY, repository.state(key));
        assertFalse(repository.isReadyForUpload(key, claimedRevision));
        assertFalse(repository.markRenderable(key, claimedRevision));
        assertTrue(outcome.dirtyRevisions().get(key) > claimedRevision);
    }

    @Test
    void compareAndSetUnloadingTargetConflictsWithoutRevisionChange() {
        ChunkRepository repository = new ChunkRepository();
        ChunkKey key = new ChunkKey(0, 0);
        repository.generate(
                key, chunk -> chunk.setBlock(1, 1, 1, (byte) 2));
        assertTrue(repository.beginUnload(key));
        long revision = repository.revision(key);

        ChunkMutationOutcome outcome =
                repository.compareAndSetBlock(
                        1, 1, 1, (byte) 2, (byte) 3);

        assertEquals(ChunkMutationOutcome.Status.CONFLICT, outcome.status());
        assertEquals(2, Byte.toUnsignedInt(outcome.observedBlock()));
        assertTrue(outcome.dirtiedChunks().isEmpty());
        assertEquals(revision, repository.revision(key));
        assertEquals(2, Byte.toUnsignedInt(repository.getBlock(1, 1, 1)));
        assertEquals(ChunkState.UNLOADING, repository.state(key));
    }

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
        assertTrue(repository.revision(center) > centerRevision);
        assertTrue(repository.revision(east) > eastRevision);
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
        assertTrue(repository.revision(center) > centerRevision);
        assertTrue(repository.revision(west) > westRevision);
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
        assertTrue(repository.revision(center) > centerRevision);
        assertTrue(repository.revision(north) > northRevision);
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
        assertTrue(repository.revision(center) > centerRevision);
        assertTrue(repository.revision(south) > southRevision);
        assertEquals(Set.of(center, south), repository.keys());
    }

    @Test
    void cornerChangesDirtyTwoCardinalsAndOneDiagonal() {
        assertCornerDirtiesLoadedNeighbors(
                0,
                0,
                new ChunkKey(0, 0).west(),
                new ChunkKey(0, 0).north());
        assertCornerDirtiesLoadedNeighbors(
                GameConfig.Chunk.SIZE - 1,
                0,
                new ChunkKey(0, 0).east(),
                new ChunkKey(0, 0).north());
        assertCornerDirtiesLoadedNeighbors(
                0,
                GameConfig.Chunk.SIZE - 1,
                new ChunkKey(0, 0).west(),
                new ChunkKey(0, 0).south());
        assertCornerDirtiesLoadedNeighbors(
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
    void successfulGenerationDirtiesEachPresentMeshingNeighborOnce() {
        ChunkRepository repository = new ChunkRepository();
        ChunkKey center = new ChunkKey(0, 0);
        Set<ChunkKey> neighbors = allMeshingNeighbors(center);
        for (ChunkKey neighbor : neighbors) {
            repository.generate(
                    neighbor,
                    chunk -> chunk.setBlock(1, 1, 1, (byte) 1));
        }
        var neighborRevisions =
                neighbors.stream()
                        .collect(
                                java.util.stream.Collectors.toMap(
                                        key -> key,
                                        repository::revision));

        repository.generate(
                center,
                chunk -> chunk.setBlock(1, 1, 1, (byte) 1));

        assertEquals(ChunkState.GENERATED, repository.state(center));
        assertTrue(
                repository.revision(center)
                        > neighborRevisions.values().stream()
                                .mapToLong(Long::longValue)
                                .max()
                                .orElseThrow());
        for (ChunkKey neighbor : neighbors) {
            assertEquals(ChunkState.DIRTY, repository.state(neighbor));
            assertTrue(
                    repository.revision(neighbor)
                            > neighborRevisions.get(neighbor));
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
    void boundaryPropagationDoesNotCreateMissingNeighborSnapshot() {
        ChunkRepository repository = new ChunkRepository();
        ChunkKey center = new ChunkKey(0, 0);
        ChunkKey east = center.east();

        repository.generate(
                center, chunk -> chunk.setBlock(1, 1, 1, (byte) 1));
        assertTrue(repository.snapshot(east).isEmpty());
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
        assertTrue(repository.snapshot(east).isEmpty());
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

        assertTrue(repository.revision(target) > targetRevision);
        assertTrue(repository.revision(east) > eastRevision);
        assertTrue(repository.revision(south) > southRevision);
        assertTrue(repository.revision(diagonal) > diagonalRevision);
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

    @Test
    void claimMeshingAtomicallyCapturesImmutableThreeByThreeSnapshots() {
        ChunkRepository repository = new ChunkRepository();
        ChunkKey center = new ChunkKey(0, 0);
        generateMarker(repository, center, (byte) 1);
        generateMarker(repository, center.north(), (byte) 2);
        generateMarker(repository, center.north().east(), (byte) 3);
        generateMarker(repository, center.east(), (byte) 4);
        generateMarker(repository, center.south().east(), (byte) 5);
        generateMarker(repository, center.south(), (byte) 6);
        generateMarker(repository, center.south().west(), (byte) 7);
        generateMarker(repository, center.west(), (byte) 8);
        generateMarker(repository, center.north().west(), (byte) 9);

        Optional<ChunkMeshInput> claimed = repository.claimMeshing(center);

        ChunkMeshInput input = claimed.orElseThrow();
        assertEquals(ChunkState.MESHING, repository.state(center));
        assertEquals(1, Byte.toUnsignedInt(input.center().getBlock(1, 2, 3)));
        assertEquals(2, Byte.toUnsignedInt(input.north().getBlock(1, 2, 3)));
        assertEquals(3, Byte.toUnsignedInt(input.northEast().getBlock(1, 2, 3)));
        assertEquals(4, Byte.toUnsignedInt(input.east().getBlock(1, 2, 3)));
        assertEquals(5, Byte.toUnsignedInt(input.southEast().getBlock(1, 2, 3)));
        assertEquals(6, Byte.toUnsignedInt(input.south().getBlock(1, 2, 3)));
        assertEquals(7, Byte.toUnsignedInt(input.southWest().getBlock(1, 2, 3)));
        assertEquals(8, Byte.toUnsignedInt(input.west().getBlock(1, 2, 3)));
        assertEquals(9, Byte.toUnsignedInt(input.northWest().getBlock(1, 2, 3)));
        assertFalse(
                Arrays.stream(ChunkMeshInput.class.getDeclaredFields())
                        .map(Field::getType)
                        .anyMatch(
                                type ->
                                        type == World.class
                                                || type == Chunk.class
                                                || type == ChunkRepository.class));
        assertTrue(repository.claimMeshing(center).isEmpty());
    }

    @Test
    void staleClaimRecheckDoesNotDemoteNewerMeshingClaim()
            throws Exception {
        ChunkRepository repository = new ChunkRepository();
        ChunkKey center = new ChunkKey(0, 0);
        ChunkKey east = center.east();
        repository.generate(
                center, chunk -> chunk.setBlock(1, 1, 1, (byte) 1));
        repository.generate(
                east, chunk -> chunk.setBlock(1, 1, 1, (byte) 1));
        Object centerEntry = capturedEntry(repository, center);
        Object eastEntry = capturedEntry(repository, east);
        CountDownLatch oldClaimStarted = new CountDownLatch(1);
        CountDownLatch newerClaimOwnsTarget = new CountDownLatch(1);
        AtomicReference<Thread> oldClaimThread = new AtomicReference<>();
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<Optional<ChunkMeshInput>> oldClaim;
            Future<Optional<ChunkMeshInput>> newerClaim;
            synchronized (eastEntry) {
                oldClaim =
                        executor.submit(
                                () -> {
                                    oldClaimThread.set(
                                            Thread.currentThread());
                                    oldClaimStarted.countDown();
                                    return repository.claimMeshing(center);
                                });
                assertTrue(
                        oldClaimStarted.await(5, TimeUnit.SECONDS),
                        "old claim task did not start");
                awaitBlocked(
                        oldClaimThread.get(),
                        "old claim did not block while capturing east");

                newerClaim =
                        executor.submit(
                                () -> {
                                    synchronized (centerEntry) {
                                        newerClaimOwnsTarget.countDown();
                                        repository.setBlock(
                                                center.worldOriginX() + 1,
                                                1,
                                                center.worldOriginZ() + 1,
                                                (byte) 2);
                                        return repository.claimMeshing(
                                                center);
                                    }
                                });
                assertTrue(
                        newerClaimOwnsTarget.await(
                                5, TimeUnit.SECONDS),
                        "newer claim did not acquire the target");
            }

            ChunkMeshInput newerInput =
                    newerClaim.get(5, TimeUnit.SECONDS).orElseThrow();
            assertTrue(
                    oldClaim.get(5, TimeUnit.SECONDS).isEmpty(),
                    "old claim unexpectedly survived revision change");
            assertEquals(
                    newerInput.center().revision(),
                    repository.revision(center));
            assertEquals(ChunkState.MESHING, repository.state(center));
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void staleReadyResultLeavesMutatedTargetDirty() {
        ChunkRepository repository = new ChunkRepository();
        ChunkKey key = new ChunkKey(0, 0);
        repository.generate(key, chunk -> {});
        long claimedRevision =
                repository.claimMeshing(key).orElseThrow().center().revision();

        repository.setBlock(1, 1, 1, (byte) 1);

        assertFalse(repository.markReadyForUpload(key, claimedRevision));
        assertEquals(ChunkState.DIRTY, repository.state(key));
    }

    @Test
    void diagonalMutationInvalidatesClaimedTargetRevision() {
        ChunkKey target = new ChunkKey(0, 0);
        ChunkKey diagonal = target.southEast();
        ChunkRepository repository = generatedChunks(target, diagonal);
        long claimedRevision =
                repository
                        .claimMeshing(target)
                        .orElseThrow()
                        .center()
                        .revision();

        assertTrue(
                repository.setBlock(
                        diagonal.worldOriginX(),
                        1,
                        diagonal.worldOriginZ(),
                        (byte) 2));

        assertTrue(repository.revision(target) > claimedRevision);
        assertFalse(repository.markReadyForUpload(target, claimedRevision));
        assertEquals(ChunkState.DIRTY, repository.state(target));
    }

    @Test
    void diagonalUnloadInvalidatesClaimedTargetRevision() {
        ChunkKey target = new ChunkKey(0, 0);
        ChunkKey diagonal = target.southEast();
        ChunkRepository repository = generatedChunks(target, diagonal);
        long claimedRevision =
                repository
                        .claimMeshing(target)
                        .orElseThrow()
                        .center()
                        .revision();

        assertTrue(repository.beginUnload(diagonal));

        assertTrue(repository.revision(target) > claimedRevision);
        assertFalse(repository.markReadyForUpload(target, claimedRevision));
        assertEquals(ChunkState.DIRTY, repository.state(target));
    }

    @Test
    void currentReadyResultTransitionsClaimToReadyForUpload() {
        ChunkRepository repository = new ChunkRepository();
        ChunkKey key = new ChunkKey(0, 0);
        repository.generate(key, chunk -> {});
        long claimedRevision =
                repository.claimMeshing(key).orElseThrow().center().revision();

        assertTrue(repository.markReadyForUpload(key, claimedRevision));

        assertEquals(ChunkState.READY_FOR_UPLOAD, repository.state(key));
    }

    @Test
    void readyForUploadQueryRequiresCurrentStateAndRevision() {
        ChunkRepository repository = new ChunkRepository();
        ChunkKey key = new ChunkKey(0, 0);
        repository.generate(key, chunk -> {});
        long revision =
                repository.claimMeshing(key).orElseThrow().center().revision();

        assertFalse(repository.isReadyForUpload(key, revision));
        assertTrue(repository.markReadyForUpload(key, revision));
        assertTrue(repository.isReadyForUpload(key, revision));
        assertFalse(repository.isReadyForUpload(key, revision + 1));

        repository.setBlock(1, 1, 1, (byte) 1);

        assertFalse(repository.isReadyForUpload(key, revision));
    }

    @Test
    void onlyCurrentReadyRevisionCanBecomeRenderable() {
        ChunkRepository repository = new ChunkRepository();
        ChunkKey key = new ChunkKey(0, 0);
        repository.generate(key, chunk -> {});
        long revision =
                repository.claimMeshing(key).orElseThrow().center().revision();
        assertTrue(repository.markReadyForUpload(key, revision));

        assertTrue(repository.markRenderable(key, revision));
        assertEquals(ChunkState.RENDERABLE, repository.state(key));
        assertFalse(repository.markRenderable(key, revision));
    }

    @Test
    void staleReadyRevisionCannotBecomeRenderable() {
        ChunkRepository repository = new ChunkRepository();
        ChunkKey key = new ChunkKey(0, 0);
        repository.generate(key, chunk -> {});
        long revision =
                repository.claimMeshing(key).orElseThrow().center().revision();
        assertTrue(repository.markReadyForUpload(key, revision));
        repository.setBlock(1, 1, 1, (byte) 1);

        assertFalse(repository.markRenderable(key, revision));
        assertEquals(ChunkState.DIRTY, repository.state(key));
    }

    @Test
    void beginUnloadInvalidatesWorkAndDirtiesMeshingNeighborsOnce() {
        ChunkKey center = new ChunkKey(0, 0);
        Set<ChunkKey> neighbors = allMeshingNeighbors(center);
        List<ChunkKey> loaded = new ArrayList<>();
        loaded.add(center);
        loaded.addAll(neighbors);
        ChunkRepository repository =
                generatedChunks(loaded.toArray(ChunkKey[]::new));
        long centerRevision = repository.revision(center);
        long claimedRevision =
                repository
                        .claimMeshing(center)
                        .orElseThrow()
                        .center()
                        .revision();
        var neighborRevisions =
                neighbors.stream()
                        .collect(
                                java.util.stream.Collectors.toMap(
                                        key -> key,
                                        repository::revision));

        assertTrue(repository.beginUnload(center));
        assertFalse(repository.beginUnload(center));

        assertEquals(ChunkState.UNLOADING, repository.state(center));
        assertTrue(repository.revision(center) > centerRevision);
        assertFalse(
                repository.markReadyForUpload(
                        center, claimedRevision));
        for (ChunkKey neighbor : neighbors) {
            assertTrue(
                    repository.revision(neighbor)
                            > neighborRevisions.get(neighbor));
            assertEquals(ChunkState.DIRTY, repository.state(neighbor));
        }
    }

    @Test
    void completeUnloadOnlyRemovesEntryAfterBeginUnload() {
        ChunkRepository repository = new ChunkRepository();
        ChunkKey key = new ChunkKey(0, 0);
        repository.generate(key, chunk -> {});

        assertFalse(repository.completeUnload(key));
        assertTrue(repository.contains(key));
        assertTrue(repository.beginUnload(key));
        assertTrue(repository.completeUnload(key));
        assertFalse(repository.contains(key));
        assertFalse(repository.completeUnload(key));
    }

    @Test
    void regeneratedEntryUsesRevisionNewerThanUnloadedIncarnation() {
        ChunkRepository repository = new ChunkRepository();
        ChunkKey key = new ChunkKey(0, 0);
        repository.generate(key, chunk -> {});
        long unloadedRevision = repository.revision(key);
        assertTrue(repository.beginUnload(key));
        assertTrue(repository.completeUnload(key));

        repository.generate(key, chunk -> {});

        assertTrue(repository.revision(key) > unloadedRevision);
    }

    @Test
    void revisionsAreGloballyUniqueAcrossEntriesAndReloads() {
        ChunkRepository repository = new ChunkRepository();
        ChunkKey first = new ChunkKey(0, 0);
        ChunkKey second = new ChunkKey(2, 0);
        repository.generate(first, chunk -> {});
        repository.generate(second, chunk -> {});
        long firstRevision = repository.revision(first);
        long secondRevision = repository.revision(second);

        assertTrue(secondRevision > firstRevision);
        assertTrue(repository.beginUnload(first));
        long unloadingRevision = repository.revision(first);
        assertTrue(unloadingRevision > secondRevision);
        assertTrue(repository.completeUnload(first));

        repository.generate(first, chunk -> {});

        assertTrue(repository.revision(first) > unloadingRevision);
    }

    @Test
    void unloadingManyUniqueKeysRetainsNoPerKeyTombstoneMap() {
        ChunkRepository repository = new ChunkRepository();
        for (int index = 0; index < 1_000; index++) {
            ChunkKey key = new ChunkKey(index, -index);
            repository.generate(key, chunk -> {});
            assertTrue(repository.beginUnload(key));
            assertTrue(repository.completeUnload(key));
        }

        assertTrue(repository.keys().isEmpty());
        Set<String> mapFields =
                Arrays.stream(ChunkRepository.class.getDeclaredFields())
                        .filter(
                                field ->
                                        Map.class.isAssignableFrom(
                                                field.getType()))
                        .map(Field::getName)
                        .collect(java.util.stream.Collectors.toSet());
        assertEquals(Set.of("entries"), mapFields);
    }

    @Test
    void unloadedIncarnationCannotCompleteAgainstReloadedEntry() {
        ChunkRepository repository = new ChunkRepository();
        ChunkKey key = new ChunkKey(0, 0);
        repository.generate(key, chunk -> {});
        long oldRevision =
                repository.claimMeshing(key).orElseThrow().center().revision();
        assertTrue(repository.beginUnload(key));
        assertTrue(repository.completeUnload(key));

        repository.generate(key, chunk -> {});
        long newRevision =
                repository.claimMeshing(key).orElseThrow().center().revision();

        assertTrue(newRevision > oldRevision);
        assertFalse(repository.markReadyForUpload(key, oldRevision));
        assertTrue(repository.markReadyForUpload(key, newRevision));
    }

    @Test
    void unloadingEntryRejectsFurtherBlockMutation() {
        ChunkRepository repository = new ChunkRepository();
        ChunkKey key = new ChunkKey(0, 0);
        repository.generate(
                key, chunk -> chunk.setBlock(1, 1, 1, (byte) 1));
        assertTrue(repository.beginUnload(key));

        assertFalse(repository.setBlock(1, 1, 1, (byte) 2));
        assertEquals(ChunkState.UNLOADING, repository.state(key));
        assertEquals(1, Byte.toUnsignedInt(repository.getBlock(1, 1, 1)));
    }

    @Test
    void claimAfterBeginUnloadCapturesUnloadingNeighborAsEmpty() {
        ChunkKey center = new ChunkKey(0, 0);
        ChunkKey east = center.east();
        ChunkRepository repository = new ChunkRepository();
        repository.generate(
                center, chunk -> chunk.setBlock(1, 1, 1, (byte) 1));
        repository.generate(
                east, chunk -> chunk.setBlock(0, 1, 1, (byte) 2));

        assertTrue(repository.beginUnload(east));
        ChunkMeshInput input =
                repository.claimMeshing(center).orElseThrow();

        assertEquals(0L, input.east().revision());
        assertEquals(
                0,
                Byte.toUnsignedInt(input.east().getBlock(0, 1, 1)));
    }

    @Test
    void failedClaimRequiresExplicitRetryBeforeItCanBeClaimedAgain() {
        ChunkRepository repository = new ChunkRepository();
        ChunkKey key = new ChunkKey(0, 0);
        repository.generate(key, chunk -> {});
        long claimedRevision =
                repository.claimMeshing(key).orElseThrow().center().revision();
        IllegalStateException failure =
                new IllegalStateException("meshing failed");

        repository.markMeshingFailure(key, claimedRevision, failure);

        assertEquals(ChunkState.DIRTY, repository.state(key));
        assertFalse(repository.meshingCandidates().contains(key));
        assertTrue(repository.claimMeshing(key).isEmpty());

        repository.retry(key);

        assertTrue(repository.meshingCandidates().contains(key));
        assertTrue(repository.claimMeshing(key).isPresent());
    }

    @Test
    void laterMutationClearsMeshingFailureAndMakesEntryEligible() {
        ChunkRepository repository = new ChunkRepository();
        ChunkKey key = new ChunkKey(0, 0);
        repository.generate(key, chunk -> {});
        long claimedRevision =
                repository.claimMeshing(key).orElseThrow().center().revision();
        repository.markMeshingFailure(
                key,
                claimedRevision,
                new IllegalStateException("meshing failed"));

        repository.setBlock(1, 1, 1, (byte) 1);

        assertTrue(repository.meshingCandidates().contains(key));
    }

    private static ChunkRepository generatedPairEastWest() {
        return generatedPair(new ChunkKey(0, 0), new ChunkKey(1, 0));
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

    private static void generateMarker(
            ChunkRepository repository, ChunkKey key, byte marker) {
        repository.generate(
                key, chunk -> chunk.setBlock(1, 2, 3, marker));
    }

    private static void assertEdgeOutcome(
            int localX, int localZ, ChunkKey neighbor) {
        ChunkKey target = new ChunkKey(0, 0);
        ChunkRepository repository = generatedChunks(target, neighbor);
        long targetRevision = repository.revision(target);
        long neighborRevision = repository.revision(neighbor);

        ChunkMutationOutcome outcome =
                repository.compareAndSetBlock(
                        localX,
                        4,
                        localZ,
                        (byte) 0,
                        (byte) 9);

        assertEquals(ChunkMutationOutcome.Status.APPLIED, outcome.status());
        assertEquals(
                List.of(target, neighbor),
                List.copyOf(outcome.dirtyChunks()));
        assertTrue(outcome.dirtyRevisions().get(target) > targetRevision);
        assertTrue(outcome.dirtyRevisions().get(neighbor) > neighborRevision);
        assertEquals(
                repository.revision(target),
                outcome.dirtyRevisions().get(target));
        assertEquals(
                repository.revision(neighbor),
                outcome.dirtyRevisions().get(neighbor));
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

    private static void assertCornerDirtiesLoadedNeighbors(
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

        assertTrue(repository.revision(center) > centerRevision);
        assertTrue(repository.revision(firstNeighbor) > firstRevision);
        assertTrue(repository.revision(secondNeighbor) > secondRevision);
        assertTrue(repository.revision(diagonal) > diagonalRevision);
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

    private static void awaitBlocked(
            Thread thread, String failureMessage) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
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

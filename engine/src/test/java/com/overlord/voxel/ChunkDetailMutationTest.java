package com.overlord.voxel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.overlord.config.GameConfig;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ChunkDetailMutationTest {
    private static final int WORLD_HEIGHT = 32;
    private static final int X = 4;
    private static final int Y = 7;
    private static final int Z = 6;

    @Test
    void fullToDetailPublishesOneCanonicalRevision() {
        ChunkRepository repository = repositoryWithFull(X, Y, Z, (byte) 7);
        ChunkKey key = ChunkKey.fromWorld(X, Z);
        long before = repository.revision(key);

        ChunkDetailMutationOutcome result =
                repository.mutateDetail(
                        new ChunkDetailMutation.ConvertFullToDetail(
                                X, Y, Z, before, (byte) 7));

        assertEquals(ChunkDetailMutationOutcome.Status.APPLIED, result.status());
        assertEquals(before, result.observedChunkRevision());
        assertEquals(before + 1, result.resultingChunkRevision());
        assertEquals(new FullCellState((byte) 7), result.oldState().orElseThrow());
        DetailCellState detail =
                assertInstanceOf(
                        DetailCellState.class,
                        result.newState().orElseThrow());
        assertEquals(-1L, detail.occupancyMask());
        assertEquals(Set.of(key), result.dirtyChunks());
        assertEquals(ChunkState.DIRTY, repository.state(key));
        assertTrue(repository.voxelModified(key));
        assertEquals(
                detail,
                repository.observeCell(X, Y, Z)
                        .observation()
                        .orElseThrow()
                        .state());
    }

    @Test
    void firstPlacementIntoFullAirCreatesOneOccupiedDetail() {
        ChunkRepository repository = repositoryWithFull(X, Y, Z, (byte) 0);
        ChunkKey key = ChunkKey.fromWorld(X, Z);
        long before = repository.revision(key);
        LocalSubVoxelPosition position =
                new LocalSubVoxelPosition(3, 2, 1);

        ChunkDetailMutationOutcome result =
                repository.mutateDetail(
                        new ChunkDetailMutation.SetSubVoxel(
                                X,
                                Y,
                                Z,
                                before,
                                new FullCellState((byte) 0),
                                position,
                                (byte) 9));

        DetailCellState detail =
                assertInstanceOf(
                        DetailCellState.class,
                        result.newState().orElseThrow());
        assertEquals(1L << position.index(), detail.occupancyMask());
        assertEquals(9, Byte.toUnsignedInt(detail.blockId(position)));
        assertEquals(before + 1, repository.revision(key));
    }

    @Test
    void clearingFinalOccupiedSubvoxelAtomicallyReturnsToFullAir() {
        ChunkRepository repository = repositoryWithFull(X, Y, Z, (byte) 0);
        LocalSubVoxelPosition position =
                new LocalSubVoxelPosition(1, 0, 3);
        long initialRevision = repository.revision(new ChunkKey(0, 0));
        DetailCellState oneCell = oneOccupied(position, (byte) 5);
        ChunkDetailMutationOutcome placed =
                repository.mutateDetail(
                        new ChunkDetailMutation.SetSubVoxel(
                                X,
                                Y,
                                Z,
                                initialRevision,
                                new FullCellState((byte) 0),
                                position,
                                (byte) 5));

        ChunkDetailMutationOutcome cleared =
                repository.mutateDetail(
                        new ChunkDetailMutation.SetSubVoxel(
                                X,
                                Y,
                                Z,
                                placed.resultingChunkRevision(),
                                oneCell,
                                position,
                                (byte) 0));

        assertEquals(ChunkDetailMutationOutcome.Status.APPLIED, cleared.status());
        assertEquals(oneCell, cleared.oldState().orElseThrow());
        assertEquals(new FullCellState((byte) 0), cleared.newState().orElseThrow());
        assertEquals(
                new FullCellState((byte) 0),
                repository.observeCell(X, Y, Z)
                        .observation()
                        .orElseThrow()
                        .state());
        assertEquals(0, repository.snapshot(new ChunkKey(0, 0)).orElseThrow().details().entryCount());
    }

    @Test
    void staleRevisionAndExpectedStateRejectWithoutRevisionOrDirtyChanges() {
        ChunkRepository repository = repositoryWithFull(X, Y, Z, (byte) 7);
        ChunkKey key = new ChunkKey(0, 0);
        ChunkKey west = key.west();
        repository.generate(west, chunk -> {});
        long targetBefore = repository.revision(key);
        long westBefore = repository.revision(west);

        ChunkDetailMutationOutcome staleRevision =
                repository.mutateDetail(
                        new ChunkDetailMutation.ConvertFullToDetail(
                                X, Y, Z, targetBefore - 1, (byte) 7));
        ChunkDetailMutationOutcome staleState =
                repository.mutateDetail(
                        new ChunkDetailMutation.SetSubVoxel(
                                X,
                                Y,
                                Z,
                                targetBefore,
                                new FullCellState((byte) 3),
                                new LocalSubVoxelPosition(0, 0, 0),
                                (byte) 8));

        assertEquals(
                ChunkDetailMutationOutcome.Status.STALE_CHUNK_REVISION,
                staleRevision.status());
        assertEquals(
                ChunkDetailMutationOutcome.Status.EXPECTED_STATE_CONFLICT,
                staleState.status());
        assertTrue(staleRevision.dirtiedChunks().isEmpty());
        assertTrue(staleState.dirtiedChunks().isEmpty());
        assertEquals(targetBefore, repository.revision(key));
        assertEquals(westBefore, repository.revision(west));
        assertEquals(new FullCellState((byte) 7), repository.observeCell(X, Y, Z).observation().orElseThrow().state());
    }

    @Test
    void boundaryEditUsesExistingFaceAndDiagonalInvalidation() {
        assertBoundaryDirtyKeys(0, 8, List.of(new ChunkKey(0, 0), new ChunkKey(-1, 0)));
        assertBoundaryDirtyKeys(15, 8, List.of(new ChunkKey(0, 0), new ChunkKey(1, 0)));
        assertBoundaryDirtyKeys(8, 0, List.of(new ChunkKey(0, 0), new ChunkKey(0, -1)));
        assertBoundaryDirtyKeys(8, 15, List.of(new ChunkKey(0, 0), new ChunkKey(0, 1)));
        assertBoundaryDirtyKeys(
                0,
                0,
                List.of(
                        new ChunkKey(0, 0),
                        new ChunkKey(-1, 0),
                        new ChunkKey(0, -1),
                        new ChunkKey(-1, -1)));
    }

    @Test
    void activeUnloadIsInvalidatedButFinalizedUnloadRejectsMutation() {
        ChunkRepository repository = repositoryWithFull(X, Y, Z, (byte) 7);
        ChunkKey key = new ChunkKey(0, 0);
        long before = repository.revision(key);
        ChunkUnloadPreparation active = repository.prepareStreamingUnload(key);

        ChunkDetailMutationOutcome applied =
                repository.mutateDetail(
                        new ChunkDetailMutation.ConvertFullToDetail(
                                X, Y, Z, before, (byte) 7));

        assertEquals(ChunkDetailMutationOutcome.Status.APPLIED, applied.status());
        assertEquals(
                ChunkUnloadResult.Status.STALE,
                repository.validateStreamingUnload(active.ticket().orElseThrow()).status());

        ChunkUnloadPreparation finalized = repository.prepareStreamingUnload(key);
        assertEquals(
                ChunkUnloadResult.Status.VALID,
                repository.validateStreamingUnload(finalized.ticket().orElseThrow()).status());
        long finalizedRevision = repository.revision(key);
        DetailCellState current =
                assertInstanceOf(
                        DetailCellState.class,
                        repository.observeCell(X, Y, Z).observation().orElseThrow().state());

        ChunkDetailMutationOutcome rejected =
                repository.mutateDetail(
                        new ChunkDetailMutation.SetSubVoxel(
                                X,
                                Y,
                                Z,
                                finalizedRevision,
                                current,
                                new LocalSubVoxelPosition(0, 0, 0),
                                (byte) 8));

        assertEquals(ChunkDetailMutationOutcome.Status.UNLOAD_FINALIZED, rejected.status());
        assertEquals(finalizedRevision, repository.revision(key));
        assertTrue(rejected.dirtiedChunks().isEmpty());
        assertEquals(
                ChunkUnloadResult.Status.CANCELED,
                repository.cancelStreamingUnload(finalized.ticket().orElseThrow()).status());
    }

    @Test
    void explicitCompactionRequiresUniformFullyOccupiedDetail() {
        ChunkRepository repository = repositoryWithFull(X, Y, Z, (byte) 7);
        long before = repository.revision(new ChunkKey(0, 0));
        ChunkDetailMutationOutcome converted =
                repository.mutateDetail(
                        new ChunkDetailMutation.ConvertFullToDetail(
                                X, Y, Z, before, (byte) 7));
        DetailCellState uniform = DetailCellState.uniform((byte) 7);

        ChunkDetailMutationOutcome compacted =
                repository.mutateDetail(
                        new ChunkDetailMutation.CompactDetailToFull(
                                X,
                                Y,
                                Z,
                                converted.resultingChunkRevision(),
                                uniform,
                                (byte) 7));

        assertEquals(ChunkDetailMutationOutcome.Status.APPLIED, compacted.status());
        assertEquals(new FullCellState((byte) 7), compacted.newState().orElseThrow());

        long fullRevision = compacted.resultingChunkRevision();
        ChunkDetailMutationOutcome convertedAgain =
                repository.mutateDetail(
                        new ChunkDetailMutation.ConvertFullToDetail(
                                X, Y, Z, fullRevision, (byte) 7));
        DetailCellState changed = withBlock(uniform, 3, (byte) 8);
        ChunkDetailMutationOutcome changedResult =
                repository.mutateDetail(
                        new ChunkDetailMutation.SetSubVoxel(
                                X,
                                Y,
                                Z,
                                convertedAgain.resultingChunkRevision(),
                                uniform,
                                LocalSubVoxelPosition.fromIndex(3),
                                (byte) 8));
        long beforeReject = changedResult.resultingChunkRevision();

        ChunkDetailMutationOutcome rejected =
                repository.mutateDetail(
                        new ChunkDetailMutation.CompactDetailToFull(
                                X, Y, Z, beforeReject, changed, (byte) 7));

        assertEquals(ChunkDetailMutationOutcome.Status.INVALID_COMPACTION, rejected.status());
        assertEquals(beforeReject, repository.revision(new ChunkKey(0, 0)));
    }

    @Test
    void unavailableFailedAndCapacityOutcomesFailWithoutPublication() {
        ChunkRepository repository = repository();
        ChunkDetailMutationOutcome unknown =
                repository.mutateDetail(
                        new ChunkDetailMutation.SetSubVoxel(
                                X,
                                Y,
                                Z,
                                1L,
                                new FullCellState((byte) 0),
                                new LocalSubVoxelPosition(0, 0, 0),
                                (byte) 4));
        ChunkDetailMutationOutcome outOfBounds =
                repository.mutateDetail(
                        new ChunkDetailMutation.SetSubVoxel(
                                X,
                                -1,
                                Z,
                                1L,
                                new FullCellState((byte) 0),
                                new LocalSubVoxelPosition(0, 0, 0),
                                (byte) 4));

        assertEquals(ChunkDetailMutationOutcome.Status.UNKNOWN_CHUNK, unknown.status());
        assertEquals(ChunkDetailMutationOutcome.Status.OUT_OF_BOUNDS, outOfBounds.status());

        ChunkKey failedKey = new ChunkKey(0, 0);
        repository.generate(failedKey, chunk -> chunk.setBlock(X, Y, Z, (byte) 7));
        long failedRevision = repository.revision(failedKey);
        repository.claimMeshing(failedKey).orElseThrow();
        repository.markMeshingFailure(
                failedKey,
                failedRevision,
                new IllegalStateException("fixture"));
        ChunkDetailMutationOutcome failed =
                repository.mutateDetail(
                        new ChunkDetailMutation.ConvertFullToDetail(
                                X, Y, Z, failedRevision, (byte) 7));
        assertEquals(ChunkDetailMutationOutcome.Status.FAILED_CHUNK, failed.status());

        ChunkRepository capped = repository();
        capped.generate(
                failedKey,
                chunk -> {
                    for (int parent = 0; parent < Chunk.MAX_DETAIL_PARENTS_PER_CHUNK; parent++) {
                        replaceAtParentIndex(chunk, parent, oneOccupied(LocalSubVoxelPosition.fromIndex(0), (byte) 2));
                    }
                });
        long capRevision = capped.revision(failedKey);
        int nextParent = Chunk.MAX_DETAIL_PARENTS_PER_CHUNK;
        int[] coordinate = coordinateForParentIndex(nextParent);
        ChunkDetailMutationOutcome capacity =
                capped.mutateDetail(
                        new ChunkDetailMutation.SetSubVoxel(
                                coordinate[0],
                                coordinate[1],
                                coordinate[2],
                                capRevision,
                                new FullCellState((byte) 0),
                                new LocalSubVoxelPosition(0, 0, 0),
                                (byte) 3));
        assertEquals(ChunkDetailMutationOutcome.Status.CAPACITY_EXCEEDED, capacity.status());
        assertEquals(capRevision, capped.revision(failedKey));
    }

    @Test
    void legacyFullMutationAgainstDetailRejectsWithoutReadingBackingAir() {
        ChunkRepository repository = repositoryWithFull(X, Y, Z, (byte) 7);
        ChunkKey key = new ChunkKey(0, 0);
        long before = repository.revision(key);
        repository.mutateDetail(
                new ChunkDetailMutation.ConvertFullToDetail(
                        X, Y, Z, before, (byte) 7));
        long detailRevision = repository.revision(key);

        ChunkMutationOutcome outcome =
                repository.compareAndSetBlock(
                        X, Y, Z, (byte) 0, (byte) 4);

        assertEquals(ChunkMutationOutcome.Status.CONFLICT, outcome.status());
        assertEquals(detailRevision, repository.revision(key));
        assertTrue(outcome.dirtiedChunks().isEmpty());
    }

    private static void assertBoundaryDirtyKeys(
            int localX, int localZ, List<ChunkKey> expected) {
        ChunkRepository repository = repository();
        ChunkKey target = new ChunkKey(0, 0);
        for (ChunkKey key : expected) {
            repository.generate(key, chunk -> {});
        }
        long before = repository.revision(target);
        int worldX = target.worldOriginX() + localX;
        int worldZ = target.worldOriginZ() + localZ;

        ChunkDetailMutationOutcome result =
                repository.mutateDetail(
                        new ChunkDetailMutation.SetSubVoxel(
                                worldX,
                                Y,
                                worldZ,
                                before,
                                new FullCellState((byte) 0),
                                new LocalSubVoxelPosition(0, 0, 0),
                                (byte) 6));

        assertEquals(ChunkDetailMutationOutcome.Status.APPLIED, result.status());
        assertEquals(expected, List.copyOf(result.dirtyChunks()));
        assertEquals(expected, result.dirtiedChunks().stream().map(DirtyChunkRevision::key).toList());
    }

    private static ChunkRepository repositoryWithFull(
            int worldX, int y, int worldZ, byte id) {
        ChunkRepository repository = repository();
        ChunkKey key = ChunkKey.fromWorld(worldX, worldZ);
        repository.generate(
                key,
                chunk ->
                        chunk.setBlock(
                                ChunkKey.localCoordinate(worldX),
                                y,
                                ChunkKey.localCoordinate(worldZ),
                                id));
        return repository;
    }

    private static ChunkRepository repository() {
        return new ChunkRepository(WORLD_HEIGHT, new ChunkDirtyTracker());
    }

    private static DetailCellState oneOccupied(
            LocalSubVoxelPosition position, byte id) {
        byte[] ids = new byte[DetailCellState.CELL_COUNT];
        ids[position.index()] = id;
        return new DetailCellState(1L << position.index(), ids);
    }

    private static DetailCellState withBlock(
            DetailCellState state, int index, byte id) {
        byte[] ids = state.copyBlockIds();
        ids[index] = id;
        return new DetailCellState(state.occupancyMask(), ids);
    }

    private static void replaceAtParentIndex(
            Chunk chunk, int parentIndex, DetailCellState state) {
        int[] coordinate = coordinateForParentIndex(parentIndex);
        chunk.replaceCanonicalCell(coordinate[0], coordinate[1], coordinate[2], state);
    }

    private static int[] coordinateForParentIndex(int parentIndex) {
        int localZ = parentIndex / (GameConfig.Chunk.SIZE * WORLD_HEIGHT);
        int remainder = parentIndex % (GameConfig.Chunk.SIZE * WORLD_HEIGHT);
        int y = remainder / GameConfig.Chunk.SIZE;
        int localX = remainder % GameConfig.Chunk.SIZE;
        return new int[] {localX, y, localZ};
    }
}

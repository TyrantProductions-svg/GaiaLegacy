package com.overlord.voxel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TypedParentObservationTest {
    private static final int WORLD_HEIGHT = 32;

    @Test
    void observesFullStateAndRevisionFromOneResidentEntry() {
        ChunkRepository repository = repository();
        ChunkKey key = new ChunkKey(2, -3);
        repository.generate(
                key, chunk -> chunk.setBlock(4, 7, 6, (byte) 9));

        ParentCellObservationResult result =
                repository.observeCell(
                        key.worldOriginX() + 4,
                        7,
                        key.worldOriginZ() + 6);

        assertEquals(ChunkAvailability.AVAILABLE, result.status());
        ParentCellObservation observation = result.observation().orElseThrow();
        assertEquals(key, observation.chunkKey());
        assertEquals(4, observation.localX());
        assertEquals(7, observation.y());
        assertEquals(6, observation.localZ());
        assertEquals(repository.revision(key), observation.chunkRevision());
        assertEquals(
                new FullCellState((byte) 9), observation.state());
        assertEquals(key.worldOriginX() + 4, observation.worldX());
        assertEquals(key.worldOriginZ() + 6, observation.worldZ());
        assertTrue(result.unavailableKey().isEmpty());
    }

    @Test
    void observesDetailWithoutAllowingByteFallback() {
        ChunkRepository repository = repository();
        ChunkKey key = new ChunkKey(0, 0);
        repository.generate(
                key,
                chunk ->
                        chunk.replaceCanonicalCell(
                                2,
                                3,
                                4,
                                oneOccupiedCell((byte) 7)));
        World world = new World(repository);

        ParentCellObservation observation =
                world.observeCell(2, 3, 4).observation().orElseThrow();

        assertInstanceOf(DetailCellState.class, observation.state());
        assertEquals(repository.revision(key), observation.chunkRevision());
        assertThrows(
                IllegalStateException.class,
                () -> repository.getBlock(2, 3, 4));
        assertThrows(
                IllegalStateException.class,
                () -> world.getBlock(2, 3, 4));
    }

    @Test
    void missingChunkIsUnknownAndNeverImplicitAir() {
        ChunkRepository repository = repository();
        ChunkKey key = new ChunkKey(-7, 11);

        ParentCellObservationResult result =
                repository.observeCell(
                        key.worldOriginX(), 4, key.worldOriginZ());

        assertEquals(ChunkAvailability.UNKNOWN, result.status());
        assertTrue(result.observation().isEmpty());
        assertEquals(key, result.unavailableKey().orElseThrow());
    }

    @Test
    void failedResidentChunkIsReportedAsFailed() {
        ChunkRepository repository = repository();
        ChunkKey key = new ChunkKey(3, 4);
        repository.generate(
                key, chunk -> chunk.setBlock(1, 2, 3, (byte) 8));
        long revision = repository.revision(key);
        repository.claimMeshing(key).orElseThrow();
        repository.markMeshingFailure(
                key, revision, new IllegalStateException("fixture failure"));

        ParentCellObservationResult result =
                repository.observeCell(
                        key.worldOriginX() + 1,
                        2,
                        key.worldOriginZ() + 3);

        assertEquals(ChunkAvailability.FAILED, result.status());
        assertTrue(result.observation().isEmpty());
        assertEquals(key, result.unavailableKey().orElseThrow());
    }

    @Test
    void negativeAndMaximumCanonicalCoordinatesKeepExactIdentity() {
        ChunkRepository repository = repository();
        ChunkKey negative = ChunkKey.fromWorld(-1, -1);
        repository.generate(
                negative,
                chunk -> chunk.setBlock(15, 5, 15, (byte) 4));
        int largeCoordinate = 2_147_483_631;
        ChunkKey maximum =
                ChunkKey.fromWorld(largeCoordinate, largeCoordinate);
        repository.generate(
                maximum,
                chunk -> chunk.setBlock(15, 6, 15, (byte) 5));

        ParentCellObservation negativeObservation =
                repository.observeCell(-1, 5, -1)
                        .observation()
                        .orElseThrow();
        ParentCellObservation maximumObservation =
                repository.observeCell(
                                largeCoordinate,
                                6,
                                largeCoordinate)
                        .observation()
                        .orElseThrow();

        assertEquals(-1, negativeObservation.worldX());
        assertEquals(-1, negativeObservation.worldZ());
        assertEquals(15, negativeObservation.localX());
        assertEquals(15, negativeObservation.localZ());
        assertEquals(largeCoordinate, maximumObservation.worldX());
        assertEquals(largeCoordinate, maximumObservation.worldZ());
        assertEquals(15, maximumObservation.localX());
        assertEquals(15, maximumObservation.localZ());
    }

    @Test
    void verticalOutOfBoundsIsAvailableWithoutInventingParentState() {
        ChunkRepository repository = repository();

        ParentCellObservationResult below =
                repository.observeCell(0, -1, 0);
        ParentCellObservationResult above =
                repository.observeCell(0, WORLD_HEIGHT, 0);

        assertEquals(ChunkAvailability.AVAILABLE, below.status());
        assertTrue(below.observation().isEmpty());
        assertTrue(below.unavailableKey().isEmpty());
        assertEquals(ChunkAvailability.AVAILABLE, above.status());
        assertTrue(above.observation().isEmpty());
        assertTrue(above.unavailableKey().isEmpty());
    }

    private static ChunkRepository repository() {
        return new ChunkRepository(WORLD_HEIGHT, new ChunkDirtyTracker());
    }

    private static DetailCellState oneOccupiedCell(byte blockId) {
        byte[] ids = new byte[64];
        ids[0] = blockId;
        return new DetailCellState(1L, ids);
    }
}

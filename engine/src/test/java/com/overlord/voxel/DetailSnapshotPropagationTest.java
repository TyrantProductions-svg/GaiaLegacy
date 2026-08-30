package com.overlord.voxel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import com.overlord.config.GameConfig;
import org.junit.jupiter.api.Test;

class DetailSnapshotPropagationTest {
    private static final int WORLD_HEIGHT = 32;

    @Test
    void repositorySnapshotAndCanonicalCaptureCarryDetail() {
        ChunkRepository repository = repositoryWithDetail();
        ChunkKey key = new ChunkKey(2, -3);

        ChunkSnapshot direct = repository.snapshot(key).orElseThrow();
        ChunkSnapshot canonical =
                repository.canonicalSnapshot().chunks().get(0);

        assertDetail(direct, (byte) 7);
        assertDetail(canonical, (byte) 7);
        assertEquals(direct, canonical);
    }

    @Test
    void streamingUnloadCaptureCarriesDetail() {
        ChunkRepository repository = repositoryWithDetail();

        ChunkUnloadPreparation preparation =
                repository.prepareStreamingUnload(new ChunkKey(2, -3));

        assertEquals(
                ChunkUnloadPreparation.Status.PREPARED,
                preparation.status());
        assertDetail(preparation.capture().orElseThrow(), (byte) 7);
        assertEquals(
                ChunkUnloadResult.Status.CANCELED,
                repository.cancelStreamingUnload(
                                preparation.ticket().orElseThrow())
                        .status());
    }

    @Test
    void canonicalRestoreReconstructsDetailWithoutEmptySentinelState() {
        ChunkRepository source = repositoryWithDetail();
        ChunkRepository target = repository();

        ChunkRepositoryRestoreResult restored =
                target.restoreCanonical(source.canonicalSnapshot());

        assertEquals(
                ChunkRepositoryRestoreResult.Status.RESTORED,
                restored.status());
        assertEquals(1, restored.restoredChunkCount());
        assertInstanceOf(
                DetailCellState.class,
                target.observeCell(
                                new ChunkKey(2, -3).worldOriginX() + 2,
                                3,
                                new ChunkKey(2, -3).worldOriginZ() + 4)
                        .observation()
                        .orElseThrow()
                        .state());
    }

    @Test
    void detachedGenerationPublicationReconstructsDetail() {
        ChunkRepository repository = repository();
        ChunkKey key = new ChunkKey(-4, 5);
        ChunkGenerationTicket ticket =
                repository.beginGeneration(
                        key, ChunkGenerationMode.INITIAL);
        int parentIndex = canonicalIndex(2, 3, 4);
        ChunkGenerationData data =
                new ChunkGenerationData(
                        key,
                        WORLD_HEIGHT,
                        new byte[canonicalBlockCount()],
                        detailAt(parentIndex, (byte) 9));

        ChunkGenerationResult result =
                repository.commitGeneration(ticket, data);

        assertEquals(ChunkGenerationResult.Status.COMMITTED, result.status());
        assertInstanceOf(
                DetailCellState.class,
                repository.observeCell(
                                key.worldOriginX() + 2,
                                3,
                                key.worldOriginZ() + 4)
                        .observation()
                        .orElseThrow()
                        .state());
    }

    private static ChunkRepository repositoryWithDetail() {
        ChunkRepository repository = repository();
        repository.generate(
                new ChunkKey(2, -3),
                chunk ->
                        chunk.replaceCanonicalCell(
                                2,
                                3,
                                4,
                                oneOccupiedCell((byte) 7)));
        return repository;
    }

    private static ChunkRepository repository() {
        return new ChunkRepository(WORLD_HEIGHT, new ChunkDirtyTracker());
    }

    private static void assertDetail(
            ChunkSnapshot snapshot, byte expectedBlockId) {
        DetailCellState detail =
                assertInstanceOf(
                        DetailCellState.class,
                        snapshot.cellState(2, 3, 4));
        assertEquals(
                Byte.toUnsignedInt(expectedBlockId),
                Byte.toUnsignedInt(
                        detail.blockId(new LocalSubVoxelPosition(0, 0, 0))));
    }

    private static DetailCellState oneOccupiedCell(byte blockId) {
        byte[] ids = new byte[64];
        ids[0] = blockId;
        return new DetailCellState(1L, ids);
    }

    private static DetailChunkSnapshot detailAt(
            int parentIndex, byte blockId) {
        byte[] ids = new byte[64];
        ids[0] = blockId;
        return DetailChunkSnapshot.of(
                new int[] {parentIndex}, new long[] {1L}, ids);
    }

    private static int canonicalIndex(int localX, int y, int localZ) {
        return localX
                + y * GameConfig.Chunk.SIZE
                + localZ * GameConfig.Chunk.SIZE * WORLD_HEIGHT;
    }

    private static int canonicalBlockCount() {
        return GameConfig.Chunk.SIZE
                * WORLD_HEIGHT
                * GameConfig.Chunk.SIZE;
    }
}

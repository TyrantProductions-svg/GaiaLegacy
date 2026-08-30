package com.overlord.voxel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.overlord.config.GameConfig;
import org.junit.jupiter.api.Test;

class ChunkMeshGeometryBoundsTest {
    private static final int CHUNK_SIZE = GameConfig.Chunk.SIZE;
    private static final int WORLD_HEIGHT = 4;

    @Test
    void checkedFaceletArithmeticDerivesVerticesFloatsAndBytes() {
        long facelets = Math.multiplyExact(1_024L, 64L * 6L);

        ChunkMeshGeometryBounds.OutputBound bound =
                ChunkMeshGeometryBounds.fromFaceletLimit(facelets);

        assertEquals(393_216L, bound.faceletLimit());
        assertEquals(2_359_296L, bound.vertexLimit());
        assertEquals(23_592_960L, bound.floatLimit());
        assertEquals(94_371_840L, bound.byteLimit());
    }

    @Test
    void rejectsNegativeAndOverflowedOutputLimits() {
        assertThrows(
                IllegalArgumentException.class,
                () -> ChunkMeshGeometryBounds.fromFaceletLimit(-1));
        assertThrows(
                ArithmeticException.class,
                () -> ChunkMeshGeometryBounds.fromFaceletLimit(Long.MAX_VALUE));
    }

    @Test
    void exactInputBoundCountsOneFullCubeOrOneDetailCellAsSixFacelets() {
        byte[] fullBlocks = new byte[CHUNK_SIZE * WORLD_HEIGHT * CHUNK_SIZE];
        fullBlocks[parentIndex(1, 1, 1)] = 1;
        ChunkSnapshot full = ChunkSnapshot.of(
                new ChunkKey(0, 0), 1, WORLD_HEIGHT, fullBlocks);
        byte[] ids = new byte[64];
        ids[0] = 1;
        ChunkSnapshot detail = ChunkSnapshot.of(
                new ChunkKey(0, 0),
                1,
                WORLD_HEIGHT,
                new byte[fullBlocks.length],
                DetailChunkSnapshot.of(
                        new int[] {parentIndex(1, 1, 1)},
                        new long[] {1L},
                        ids));

        assertEquals(
                6L,
                ChunkMeshGeometryBounds.forInput(input(full)).faceletLimit());
        assertEquals(
                6L,
                ChunkMeshGeometryBounds.forInput(input(detail)).faceletLimit());
    }

    private static ChunkMeshInput input(ChunkSnapshot center) {
        return new ChunkMeshInput(
                center, null, null, null, null, null, null, null, null);
    }

    private static int parentIndex(int x, int y, int z) {
        return x + y * CHUNK_SIZE + z * CHUNK_SIZE * WORLD_HEIGHT;
    }
}

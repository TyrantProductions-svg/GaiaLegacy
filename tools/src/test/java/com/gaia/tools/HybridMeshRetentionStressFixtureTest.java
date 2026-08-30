package com.gaia.tools;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.overlord.config.GameConfig;
import com.overlord.voxel.BlockRenderResolver;
import com.overlord.voxel.ChunkKey;
import com.overlord.voxel.ChunkMeshBuilder;
import com.overlord.voxel.ChunkMeshData;
import com.overlord.voxel.ChunkMeshInput;
import com.overlord.voxel.ChunkSnapshot;
import com.overlord.voxel.DetailCellState;
import com.overlord.voxel.DetailChunkSnapshot;
import org.junit.jupiter.api.Test;

class HybridMeshRetentionStressFixtureTest {
    @Test
    void productionEquivalentResolverIsStableWithoutChangingGeometry() {
        BlockRenderResolver cached =
                HybridMeshRetentionStressFixture.fixtureResolver(true);
        BlockRenderResolver reconstructing =
                HybridMeshRetentionStressFixture.fixtureResolver(false);

        assertSame(cached.resolve(1), cached.resolve(1));
        assertSame(cached.resolve(2), cached.resolve(2));
        assertNotSame(reconstructing.resolve(1), reconstructing.resolve(1));
        assertNotSame(reconstructing.resolve(2), reconstructing.resolve(2));

        ChunkMeshInput input = mixedDetailInput();
        ChunkMeshData cachedMesh = new ChunkMeshBuilder(cached).build(input);
        ChunkMeshData reconstructedMesh =
                new ChunkMeshBuilder(reconstructing).build(input);

        assertArrayEquals(cachedMesh.vertices(), reconstructedMesh.vertices());
        assertArrayEquals(
                cachedMesh.canonicalHash(),
                reconstructedMesh.canonicalHash());
    }

    private static ChunkMeshInput mixedDetailInput() {
        int worldHeight = 4;
        byte[] detailIds = new byte[DetailCellState.CELL_COUNT];
        detailIds[0] = 1;
        detailIds[1] = 2;
        ChunkSnapshot center = ChunkSnapshot.of(
                new ChunkKey(0, 0),
                1L,
                worldHeight,
                new byte[GameConfig.Chunk.SIZE
                        * worldHeight
                        * GameConfig.Chunk.SIZE],
                DetailChunkSnapshot.of(
                        new int[] {0},
                        new long[] {3L},
                        detailIds));
        return new ChunkMeshInput(
                center, null, null, null, null, null, null, null, null);
    }
}

package com.gaia.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gaia.debug.DetailFixturePattern;
import com.gaia.save.streaming.DetailBlocksCodec;
import com.overlord.voxel.Chunk;
import com.overlord.voxel.ChunkMeshBuilder;
import java.util.List;
import org.junit.jupiter.api.Test;

class DetailVoxelPerformanceFixtureTest {
    @Test
    void productionMeasurementsCoverRequiredCanonicalCounts() {
        for (int count : new int[] {1, 64, 256, Chunk.MAX_DETAIL_PARENTS_PER_CHUNK}) {
            DetailVoxelPerformanceFixture.Measurement measurement =
                    DetailVoxelPerformanceFixture.measure(count, DetailFixturePattern.CHECKERBOARD);

            assertEquals(count, measurement.detailParents());
            assertEquals(count * 32L, measurement.occupiedSubVoxels());
            assertTrue(measurement.snapshotEstimateBytes() <= 76_032L);
            assertTrue(measurement.codecBytes() <= DetailBlocksCodec.MAX_V1_ENCODED_BYTES);
            assertTrue(measurement.collisionBoxes() <= count * 64L);
            if (count <= 64) {
                assertEquals("SUCCESS", measurement.meshStatus());
                assertEquals(measurement.facelets() * 6L, measurement.vertices());
                assertEquals(measurement.vertices() * 40L, measurement.meshOutputBytes());
                assertEquals(64, measurement.meshHash().length());
            } else {
                assertEquals("MESH_OUTPUT_LIMIT_EXCEEDED", measurement.meshStatus());
                assertTrue(measurement.meshOutputBytes()
                        <= ChunkMeshBuilder.MAX_HYBRID_MESH_BYTES);
                assertTrue(measurement.requiredMeshOutputBytes()
                        > ChunkMeshBuilder.MAX_HYBRID_MESH_BYTES);
                assertEquals("NONE", measurement.meshHash());
            }
        }
    }

    @Test
    void legalUniformAndMixedMaterialCapFixturesRemainRenderable() {
        for (DetailFixturePattern pattern : List.of(
                DetailFixturePattern.UNIFORM_FULL,
                DetailFixturePattern.MIXED_MATERIAL)) {
            DetailVoxelPerformanceFixture.Measurement measurement =
                    DetailVoxelPerformanceFixture.measure(
                            Chunk.MAX_DETAIL_PARENTS_PER_CHUNK, pattern);
            assertEquals("SUCCESS", measurement.meshStatus());
            assertTrue(measurement.meshOutputBytes()
                    <= ChunkMeshBuilder.MAX_HYBRID_MESH_BYTES);
        }
    }

    @Test
    void fullOnlyBaselineUsesTheEmptyDetailSnapshotAndNoExtension() {
        DetailVoxelPerformanceFixture.Measurement baseline =
                DetailVoxelPerformanceFixture.measureFullOnly();

        assertEquals(0, baseline.detailParents());
        assertEquals(0, baseline.snapshotEstimateBytes());
        assertEquals(0, baseline.codecBytes());
        assertEquals(0, baseline.collisionBoxes());
    }

    @Test
    void repeatedCanonicalInputHasIdenticalCountsAndHash() {
        DetailVoxelPerformanceFixture.Measurement first =
                DetailVoxelPerformanceFixture.measure(64, DetailFixturePattern.ASYMMETRIC);
        DetailVoxelPerformanceFixture.Measurement second =
                DetailVoxelPerformanceFixture.measure(64, DetailFixturePattern.ASYMMETRIC);

        assertEquals(first.facelets(), second.facelets());
        assertEquals(first.vertices(), second.vertices());
        assertEquals(first.meshOutputBytes(), second.meshOutputBytes());
        assertEquals(first.codecBytes(), second.codecBytes());
        assertEquals(first.meshHash(), second.meshHash());
    }
}

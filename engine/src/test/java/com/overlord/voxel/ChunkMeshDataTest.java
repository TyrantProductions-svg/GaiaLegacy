package com.overlord.voxel;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.overlord.renderer.AxisAlignedBounds;
import org.junit.jupiter.api.Test;

class ChunkMeshDataTest {
    @Test
    void copiesVerticesOnConstructionAndAccess() {
        float[] source = {
            1, 2, 3, 0, 0,
            4, 5, 6, 1, 1
        };
        ChunkMeshData data =
                new ChunkMeshData(
                        new ChunkKey(2, 3), 7, source);

        source[0] = 99;
        float[] firstRead = data.vertices();
        firstRead[1] = 99;

        assertArrayEquals(
                new float[] {
                    1, 2, 3, 0, 0,
                    4, 5, 6, 1, 1
                },
                data.vertices());
    }

    @Test
    void computesVertexCountAndLocalBounds() {
        ChunkMeshData data =
                new ChunkMeshData(
                        new ChunkKey(0, 0),
                        1,
                        new float[] {
                            4, 7, 2, 0, 0,
                            1, 3, 8, 1, 1,
                            2, 5, 6, 0.5f, 0.5f
                        });

        assertEquals(3, data.vertexCount());
        assertEquals(
                new AxisAlignedBounds(1, 3, 2, 4, 7, 8),
                data.localBounds().orElseThrow());
        assertFalse(data.isEmpty());
    }

    @Test
    void emptyVerticesHaveNoBounds() {
        ChunkMeshData data =
                new ChunkMeshData(
                        new ChunkKey(0, 0),
                        1,
                        new float[0]);

        assertEquals(0, data.vertexCount());
        assertTrue(data.localBounds().isEmpty());
        assertTrue(data.isEmpty());
    }

    @Test
    void rejectsVertexArrayWithoutFiveFloatLayout() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new ChunkMeshData(
                        new ChunkKey(0, 0),
                        1,
                        new float[] {1, 2, 3, 4}));
    }

    @Test
    void rejectsNullKeyAndVertices() {
        assertThrows(
                NullPointerException.class,
                () -> new ChunkMeshData(
                        null, 1, new float[0]));
        assertThrows(
                NullPointerException.class,
                () -> new ChunkMeshData(
                        new ChunkKey(0, 0), 1, null));
    }
}

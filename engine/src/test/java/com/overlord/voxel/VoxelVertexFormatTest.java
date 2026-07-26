package com.overlord.voxel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

class VoxelVertexFormatTest {
    @Test
    void exposesTheTenFloatChunkVertexContract() {
        List<VoxelVertexAttribute> attributes =
                VoxelVertexFormat.attributes();

        assertEquals(10, VoxelVertexFormat.FLOATS_PER_VERTEX);
        assertEquals(
                List.of(
                        new VoxelVertexAttribute(0, 3, 0),
                        new VoxelVertexAttribute(1, 2, 3),
                        new VoxelVertexAttribute(2, 3, 5),
                        new VoxelVertexAttribute(3, 1, 8),
                        new VoxelVertexAttribute(4, 1, 9)),
                attributes);
        assertEquals(40, VoxelVertexFormat.STRIDE_BYTES);
        assertEquals(0, attributes.get(0).byteOffset());
        assertEquals(12, attributes.get(1).byteOffset());
        assertEquals(20, attributes.get(2).byteOffset());
        assertEquals(32, attributes.get(3).byteOffset());
        assertEquals(36, attributes.get(4).byteOffset());
        assertEquals(15, VoxelVertexFormat.DEFAULT_LIGHT_LEVEL);
        assertThrows(
                UnsupportedOperationException.class,
                () -> attributes.add(new VoxelVertexAttribute(5, 1, 10)));
    }

    @Test
    void assignsStableExplicitIdsToAllFaces() {
        assertEquals(0, VoxelVertexFormat.faceId(BlockFace.NORTH));
        assertEquals(1, VoxelVertexFormat.faceId(BlockFace.SOUTH));
        assertEquals(2, VoxelVertexFormat.faceId(BlockFace.UP));
        assertEquals(3, VoxelVertexFormat.faceId(BlockFace.DOWN));
        assertEquals(4, VoxelVertexFormat.faceId(BlockFace.WEST));
        assertEquals(5, VoxelVertexFormat.faceId(BlockFace.EAST));
    }

    @Test
    void encodesFaceAndLightAndRejectsOutOfRangeLight() {
        assertEquals(
                47.0f,
                VoxelVertexFormat.encodeFaceLight(
                        BlockFace.UP, 15));
        assertThrows(
                IllegalArgumentException.class,
                () -> VoxelVertexFormat.encodeFaceLight(
                        BlockFace.NORTH, -1));
        assertThrows(
                IllegalArgumentException.class,
                () -> VoxelVertexFormat.encodeFaceLight(
                        BlockFace.NORTH, 16));
    }
}

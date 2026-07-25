package com.overlord.voxel;

public record VoxelVertexAttribute(
        int location,
        int componentCount,
        int floatOffset) {
    public int byteOffset() {
        return floatOffset * Float.BYTES;
    }
}

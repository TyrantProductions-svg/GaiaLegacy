package com.overlord.voxel;

@FunctionalInterface
public interface ChunkMesher {
    ChunkMeshData build(ChunkMeshInput input);
}

package com.overlord.renderer;

import com.overlord.voxel.ChunkMeshData;

public interface ChunkRenderBackend {
    ChunkRenderObject upload(ChunkMeshData data);

    void release(ChunkRenderObject object);
}

package com.overlord.renderer;

public interface ChunkGpuMesh {
    int vertexCount();

    void draw();

    void cleanup();
}

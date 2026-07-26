package com.overlord.renderer;

interface FullscreenTriangleBackend {
    int createVertexArray();

    void bindVertexArray(int vertexArrayId);

    void drawTriangles(int firstVertex, int vertexCount);

    void deleteVertexArray(int vertexArrayId);
}

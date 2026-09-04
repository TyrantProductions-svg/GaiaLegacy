package com.gaia.tools.viewer;

/** OpenGL calls used by the viewer renderer, isolated for deterministic tests. */
interface InspectorGlApi {
    int createLineVertexArray();
    int createLineBuffer();
    void configureLineBuffer(int vao, int vbo);
    void beginFrame(int width, int height);
    void wireframe(boolean enabled);
    void bindTextureSampler(int texture, int sampler);
    void drawIndexed(int vao, int count);
    void uploadAndDrawLines(int vao, int vbo, float[] positions);
    void endFrame();
    void deleteLineBuffer(int handle);
    void deleteLineVertexArray(int handle);
}

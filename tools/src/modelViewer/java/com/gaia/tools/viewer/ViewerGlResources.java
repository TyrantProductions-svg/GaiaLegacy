package com.gaia.tools.viewer;

/** Narrow owner-thread GPU boundary used by the viewer upload transaction. */
interface ViewerGlResources {
    void assertOwner(String operation);

    void assertNoError(String operation);

    int createVertexArray();

    int createBuffer();

    int createTexture();

    int createSampler();

    void uploadMesh(int vao, int vbo, int ebo, float[] vertices, int[] indices,
            int strideBytes, int positionOffsetBytes, int normalOffsetBytes, int uvOffsetBytes);

    void uploadSrgbTexture(int texture, int width, int height, byte[] rgba);

    void generateMipmaps(int texture);

    void configureSampler(int sampler, int magFilter, int minFilter, int wrapS, int wrapT);

    void deleteVertexArray(int handle);

    void deleteBuffer(int handle);

    void deleteTexture(int handle);

    void deleteSampler(int handle);
}

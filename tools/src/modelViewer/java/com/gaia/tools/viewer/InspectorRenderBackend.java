package com.gaia.tools.viewer;

import org.joml.Matrix4fc;

/** Testable draw boundary for the one model-inspector renderer. */
interface InspectorRenderBackend extends AutoCloseable {
    void begin(Matrix4fc projection, int framebufferWidth, int framebufferHeight);

    void wireframe(boolean enabled);

    void triangles(InspectorGpuModel.PrimitiveGpu primitive, Matrix4fc modelView,
            float[] baseColor, Integer texture, Integer sampler);

    void lines(ViewerGeometry.Lines batch, Matrix4fc modelView);

    void end();

    @Override default void close() { }
}

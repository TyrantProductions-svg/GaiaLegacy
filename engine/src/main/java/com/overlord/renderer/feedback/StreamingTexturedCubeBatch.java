package com.overlord.renderer.feedback;

public interface StreamingTexturedCubeBatch extends AutoCloseable {
    void upload(ParticleRenderBatch particles);

    void draw();

    void cleanup();

    @Override
    default void close() {
        cleanup();
    }
}

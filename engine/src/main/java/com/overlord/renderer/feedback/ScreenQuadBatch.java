package com.overlord.renderer.feedback;

import java.util.List;

public interface ScreenQuadBatch extends AutoCloseable {
    void upload(List<ScreenQuad> quads);

    void draw();

    void cleanup();

    @Override
    default void close() {
        cleanup();
    }
}

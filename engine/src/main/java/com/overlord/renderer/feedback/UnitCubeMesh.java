package com.overlord.renderer.feedback;

public interface UnitCubeMesh extends AutoCloseable {
    void draw();

    void cleanup();

    @Override
    default void close() {
        cleanup();
    }
}

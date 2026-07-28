package com.overlord.renderer.state;

public interface RenderStateBackend {
    RenderStateSnapshot capture();

    void apply(RenderStateSpec state);

    void restore(RenderStateSnapshot snapshot);

    void clearColorAndDepth();

    default void setViewport(Viewport viewport) {
        throw new UnsupportedOperationException(
                "viewport operation is not supported");
    }
}

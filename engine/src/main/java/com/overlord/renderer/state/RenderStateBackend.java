package com.overlord.renderer.state;

public interface RenderStateBackend {
    RenderStateSnapshot capture();

    void apply(RenderStateSpec state);

    void restore(RenderStateSnapshot snapshot);

    void clearColorAndDepth();

    default void clearDepth() {
        throw new UnsupportedOperationException(
                "depth-only clear operation is not supported");
    }

    default void setViewport(Viewport viewport) {
        throw new UnsupportedOperationException(
                "viewport operation is not supported");
    }

    default void setScissor(ScissorBox scissorBox) {
        throw new UnsupportedOperationException(
                "scissor operation is not supported");
    }
}

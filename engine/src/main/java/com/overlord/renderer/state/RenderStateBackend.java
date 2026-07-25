package com.overlord.renderer.state;

public interface RenderStateBackend {
    RenderStateSnapshot capture();

    void apply(RenderStateSpec state);

    void restore(RenderStateSnapshot snapshot);

    void clearColorAndDepth();
}

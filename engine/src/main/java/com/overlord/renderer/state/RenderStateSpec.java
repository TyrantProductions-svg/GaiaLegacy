package com.overlord.renderer.state;

public record RenderStateSpec(
        boolean depthTest,
        boolean depthWrite,
        BlendMode blendMode,
        boolean cullFace) {}

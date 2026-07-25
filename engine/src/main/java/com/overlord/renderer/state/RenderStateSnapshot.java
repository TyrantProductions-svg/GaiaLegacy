package com.overlord.renderer.state;

public record RenderStateSnapshot(
        boolean depthTest,
        boolean depthWrite,
        boolean blend,
        int blendSourceRgb,
        int blendDestinationRgb,
        int blendSourceAlpha,
        int blendDestinationAlpha,
        int blendEquationRgb,
        int blendEquationAlpha,
        boolean cullFace,
        int currentProgram,
        int activeTexture,
        int texture2dUnit0) {}

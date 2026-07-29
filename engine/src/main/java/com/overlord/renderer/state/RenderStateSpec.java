package com.overlord.renderer.state;

public record RenderStateSpec(
        boolean depthTest,
        DepthFunction depthFunction,
        boolean depthWrite,
        BlendMode blendMode,
        boolean cullFace,
        boolean polygonOffsetFill,
        float polygonOffsetFactor,
        float polygonOffsetUnits,
        boolean scissorTest) {
    public RenderStateSpec(
            boolean depthTest,
            DepthFunction depthFunction,
            boolean depthWrite,
            BlendMode blendMode,
            boolean cullFace,
            boolean polygonOffsetFill,
            float polygonOffsetFactor,
            float polygonOffsetUnits) {
        this(
                depthTest,
                depthFunction,
                depthWrite,
                blendMode,
                cullFace,
                polygonOffsetFill,
                polygonOffsetFactor,
                polygonOffsetUnits,
                false);
    }

    public RenderStateSpec(
            boolean depthTest,
            boolean depthWrite,
            BlendMode blendMode,
            boolean cullFace) {
        this(
                depthTest,
                DepthFunction.LESS,
                depthWrite,
                blendMode,
                cullFace,
                false,
                0.0f,
                0.0f);
    }
}

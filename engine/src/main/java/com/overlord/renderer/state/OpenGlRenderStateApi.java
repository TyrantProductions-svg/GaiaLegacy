package com.overlord.renderer.state;

/** Narrow OpenGL command/query boundary used by the render-state backend. */
interface OpenGlRenderStateApi {
    boolean glIsEnabled(int capability);

    int glGetInteger(int parameter);

    boolean glGetBoolean(int parameter);

    float glGetFloat(int parameter);

    void glGetIntegerv(int parameter, int[] values);

    void glActiveTexture(int texture);

    void glBindBuffer(int target, int buffer);

    void glBindTexture(int target, int texture);

    void glBindVertexArray(int vertexArray);

    void glBlendEquationSeparate(int rgb, int alpha);

    void glBlendFuncSeparate(int sourceRgb, int destinationRgb, int sourceAlpha, int destinationAlpha);

    void glClear(int mask);

    void glDepthFunc(int function);

    void glDepthMask(boolean write);

    void glDisable(int capability);

    void glEnable(int capability);

    void glPolygonOffset(float factor, float units);

    void glUseProgram(int program);

    void glViewport(int x, int y, int width, int height);
}

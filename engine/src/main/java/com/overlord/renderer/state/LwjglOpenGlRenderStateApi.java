package com.overlord.renderer.state;

import org.lwjgl.opengl.GL30C;

/** Production bridge to LWJGL. Thread ownership is enforced by its backend caller. */
final class LwjglOpenGlRenderStateApi implements OpenGlRenderStateApi {
    @Override
    public boolean glIsEnabled(int capability) {
        return GL30C.glIsEnabled(capability);
    }

    @Override
    public int glGetInteger(int parameter) {
        return GL30C.glGetInteger(parameter);
    }

    @Override
    public boolean glGetBoolean(int parameter) {
        return GL30C.glGetBoolean(parameter);
    }

    @Override
    public float glGetFloat(int parameter) {
        return GL30C.glGetFloat(parameter);
    }

    @Override
    public void glGetIntegerv(int parameter, int[] values) {
        GL30C.glGetIntegerv(parameter, values);
    }

    @Override
    public void glActiveTexture(int texture) {
        GL30C.glActiveTexture(texture);
    }

    @Override
    public void glBindBuffer(int target, int buffer) {
        GL30C.glBindBuffer(target, buffer);
    }

    @Override
    public void glBindTexture(int target, int texture) {
        GL30C.glBindTexture(target, texture);
    }

    @Override
    public void glBindVertexArray(int vertexArray) {
        GL30C.glBindVertexArray(vertexArray);
    }

    @Override
    public void glBlendEquationSeparate(int rgb, int alpha) {
        GL30C.glBlendEquationSeparate(rgb, alpha);
    }

    @Override
    public void glBlendFuncSeparate(
            int sourceRgb,
            int destinationRgb,
            int sourceAlpha,
            int destinationAlpha) {
        GL30C.glBlendFuncSeparate(
                sourceRgb, destinationRgb, sourceAlpha, destinationAlpha);
    }

    @Override
    public void glClear(int mask) {
        GL30C.glClear(mask);
    }

    @Override
    public void glDepthFunc(int function) {
        GL30C.glDepthFunc(function);
    }

    @Override
    public void glDepthMask(boolean write) {
        GL30C.glDepthMask(write);
    }

    @Override
    public void glDisable(int capability) {
        GL30C.glDisable(capability);
    }

    @Override
    public void glEnable(int capability) {
        GL30C.glEnable(capability);
    }

    @Override
    public void glPolygonOffset(float factor, float units) {
        GL30C.glPolygonOffset(factor, units);
    }

    @Override
    public void glUseProgram(int program) {
        GL30C.glUseProgram(program);
    }

    @Override
    public void glViewport(int x, int y, int width, int height) {
        GL30C.glViewport(x, y, width, height);
    }
}

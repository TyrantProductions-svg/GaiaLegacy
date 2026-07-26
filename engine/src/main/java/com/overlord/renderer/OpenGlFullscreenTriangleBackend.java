package com.overlord.renderer;

import static org.lwjgl.opengl.GL11C.GL_TRIANGLES;
import static org.lwjgl.opengl.GL11C.glDrawArrays;
import static org.lwjgl.opengl.GL30C.glBindVertexArray;
import static org.lwjgl.opengl.GL30C.glDeleteVertexArrays;
import static org.lwjgl.opengl.GL30C.glGenVertexArrays;

final class OpenGlFullscreenTriangleBackend
        implements FullscreenTriangleBackend {
    @Override
    public int createVertexArray() {
        return glGenVertexArrays();
    }

    @Override
    public void bindVertexArray(int vertexArrayId) {
        glBindVertexArray(vertexArrayId);
    }

    @Override
    public void drawTriangles(int firstVertex, int vertexCount) {
        glDrawArrays(GL_TRIANGLES, firstVertex, vertexCount);
    }

    @Override
    public void deleteVertexArray(int vertexArrayId) {
        glDeleteVertexArrays(vertexArrayId);
    }
}

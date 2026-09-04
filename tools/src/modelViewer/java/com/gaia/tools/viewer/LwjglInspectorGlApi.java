package com.gaia.tools.viewer;

import static org.lwjgl.opengl.GL11C.GL_BACK;
import static org.lwjgl.opengl.GL11C.GL_BLEND;
import static org.lwjgl.opengl.GL11C.GL_CCW;
import static org.lwjgl.opengl.GL11C.GL_COLOR_BUFFER_BIT;
import static org.lwjgl.opengl.GL11C.GL_CULL_FACE;
import static org.lwjgl.opengl.GL11C.GL_DEPTH_BUFFER_BIT;
import static org.lwjgl.opengl.GL11C.GL_DEPTH_TEST;
import static org.lwjgl.opengl.GL11C.GL_FILL;
import static org.lwjgl.opengl.GL11C.GL_FLOAT;
import static org.lwjgl.opengl.GL11C.GL_FRONT_AND_BACK;
import static org.lwjgl.opengl.GL11C.GL_LINE;
import static org.lwjgl.opengl.GL11C.GL_LINES;
import static org.lwjgl.opengl.GL11C.GL_TEXTURE_2D;
import static org.lwjgl.opengl.GL11C.GL_TRIANGLES;
import static org.lwjgl.opengl.GL11C.GL_UNSIGNED_INT;
import static org.lwjgl.opengl.GL11C.glBindTexture;
import static org.lwjgl.opengl.GL11C.glClear;
import static org.lwjgl.opengl.GL11C.glClearColor;
import static org.lwjgl.opengl.GL11C.glCullFace;
import static org.lwjgl.opengl.GL11C.glDisable;
import static org.lwjgl.opengl.GL11C.glDrawArrays;
import static org.lwjgl.opengl.GL11C.glDrawElements;
import static org.lwjgl.opengl.GL11C.glEnable;
import static org.lwjgl.opengl.GL11C.glFrontFace;
import static org.lwjgl.opengl.GL11C.glPolygonMode;
import static org.lwjgl.opengl.GL11C.glViewport;
import static org.lwjgl.opengl.GL13C.GL_TEXTURE0;
import static org.lwjgl.opengl.GL13C.glActiveTexture;
import static org.lwjgl.opengl.GL15C.GL_ARRAY_BUFFER;
import static org.lwjgl.opengl.GL15C.GL_DYNAMIC_DRAW;
import static org.lwjgl.opengl.GL15C.glBindBuffer;
import static org.lwjgl.opengl.GL15C.glBufferData;
import static org.lwjgl.opengl.GL15C.glDeleteBuffers;
import static org.lwjgl.opengl.GL15C.glGenBuffers;
import static org.lwjgl.opengl.GL20C.glEnableVertexAttribArray;
import static org.lwjgl.opengl.GL20C.glVertexAttribPointer;
import static org.lwjgl.opengl.GL30C.GL_FRAMEBUFFER_SRGB;
import static org.lwjgl.opengl.GL30C.glBindVertexArray;
import static org.lwjgl.opengl.GL30C.glDeleteVertexArrays;
import static org.lwjgl.opengl.GL30C.glGenVertexArrays;
import static org.lwjgl.opengl.GL33C.glBindSampler;

import java.nio.FloatBuffer;
import org.lwjgl.system.MemoryStack;

/** Direct OpenGL 4.1 calls kept behind the viewer-only render boundary. */
final class LwjglInspectorGlApi implements InspectorGlApi {
    @Override public int createLineVertexArray() { return glGenVertexArrays(); }
    @Override public int createLineBuffer() { return glGenBuffers(); }
    @Override public void configureLineBuffer(int vao, int vbo) {
        glBindVertexArray(vao);
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(0, 3, GL_FLOAT, false, 3 * Float.BYTES, 0L);
        glBindVertexArray(0);
        glBindBuffer(GL_ARRAY_BUFFER, 0);
    }
    @Override public void beginFrame(int width, int height) {
        glViewport(0, 0, width, height);
        glDisable(GL_BLEND);
        glDisable(GL_FRAMEBUFFER_SRGB);
        glEnable(GL_DEPTH_TEST);
        glEnable(GL_CULL_FACE);
        glCullFace(GL_BACK);
        glFrontFace(GL_CCW);
        glClearColor(0.035f, 0.045f, 0.065f, 1.0f);
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
    }
    @Override public void wireframe(boolean enabled) {
        glPolygonMode(GL_FRONT_AND_BACK, enabled ? GL_LINE : GL_FILL);
    }
    @Override public void bindTextureSampler(int texture, int sampler) {
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, texture);
        glBindSampler(0, sampler);
    }
    @Override public void drawIndexed(int vao, int count) {
        glBindVertexArray(vao);
        glDrawElements(GL_TRIANGLES, count, GL_UNSIGNED_INT, 0L);
    }
    @Override public void uploadAndDrawLines(int vao, int vbo, float[] positions) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            FloatBuffer data = stack.mallocFloat(positions.length).put(positions).flip();
            glBindVertexArray(vao);
            glBindBuffer(GL_ARRAY_BUFFER, vbo);
            glBufferData(GL_ARRAY_BUFFER, data, GL_DYNAMIC_DRAW);
            glDrawArrays(GL_LINES, 0, positions.length / 3);
        }
    }
    @Override public void endFrame() {
        glPolygonMode(GL_FRONT_AND_BACK, GL_FILL);
        glBindVertexArray(0);
        glBindBuffer(GL_ARRAY_BUFFER, 0);
        glBindSampler(0, 0);
        glBindTexture(GL_TEXTURE_2D, 0);
    }
    @Override public void deleteLineBuffer(int handle) { glDeleteBuffers(handle); }
    @Override public void deleteLineVertexArray(int handle) { glDeleteVertexArrays(handle); }
}

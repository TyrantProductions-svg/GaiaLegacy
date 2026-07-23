package com.overlord.renderer;

import static org.lwjgl.opengl.GL30C.*;

import com.overlord.core.thread.MainThreadGuard;
import java.nio.FloatBuffer;
import java.util.Objects;
import org.lwjgl.BufferUtils;

public class Mesh {
    private final MainThreadGuard mainThreadGuard;
    private int vaoId;
    private int vboId;
    private final int vertexCount;

    public Mesh(MainThreadGuard mainThreadGuard, float[] vertices) {
        this.mainThreadGuard = Objects.requireNonNull(mainThreadGuard, "mainThreadGuard");
        this.mainThreadGuard.assertMainThread("mesh GPU upload");
        this.vertexCount = vertices.length / 5;

        try {
            vaoId = glGenVertexArrays();
            glBindVertexArray(vaoId);

            vboId = glGenBuffers();
            glBindBuffer(GL_ARRAY_BUFFER, vboId);

            FloatBuffer vertexBuffer = BufferUtils.createFloatBuffer(vertices.length);
            vertexBuffer.put(vertices).flip();
            glBufferData(GL_ARRAY_BUFFER, vertexBuffer, GL_STATIC_DRAW);

            glVertexAttribPointer(0, 3, GL_FLOAT, false, 5 * Float.BYTES, 0);
            glEnableVertexAttribArray(0);

            glVertexAttribPointer(
                    1, 2, GL_FLOAT, false, 5 * Float.BYTES, 3 * Float.BYTES);
            glEnableVertexAttribArray(1);

            glBindBuffer(GL_ARRAY_BUFFER, 0);
            glBindVertexArray(0);
        } catch (RuntimeException | Error failure) {
            if (vboId != 0) {
                glDeleteBuffers(vboId);
                vboId = 0;
            }
            if (vaoId != 0) {
                glDeleteVertexArrays(vaoId);
                vaoId = 0;
            }
            throw failure;
        }
    }

    public void draw() {
        mainThreadGuard.assertMainThread("mesh draw");
        glBindVertexArray(vaoId);
        glDrawArrays(GL_TRIANGLES, 0, vertexCount);
        glBindVertexArray(0);
    }

    public void cleanup() {
        mainThreadGuard.assertMainThread("mesh cleanup");
        if (vboId != 0) {
            glDeleteBuffers(vboId);
            vboId = 0;
        }
        if (vaoId != 0) {
            glDeleteVertexArrays(vaoId);
            vaoId = 0;
        }
    }
}

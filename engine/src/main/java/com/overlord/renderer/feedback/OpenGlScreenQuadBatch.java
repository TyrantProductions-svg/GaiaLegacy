package com.overlord.renderer.feedback;

import static org.lwjgl.opengl.GL11C.GL_FLOAT;
import static org.lwjgl.opengl.GL11C.GL_TRIANGLES;
import static org.lwjgl.opengl.GL11C.glDrawArrays;
import static org.lwjgl.opengl.GL15C.GL_ARRAY_BUFFER;
import static org.lwjgl.opengl.GL15C.GL_DYNAMIC_DRAW;
import static org.lwjgl.opengl.GL15C.glBindBuffer;
import static org.lwjgl.opengl.GL15C.glBufferData;
import static org.lwjgl.opengl.GL15C.glDeleteBuffers;
import static org.lwjgl.opengl.GL15C.glGenBuffers;
import static org.lwjgl.opengl.GL20C.glEnableVertexAttribArray;
import static org.lwjgl.opengl.GL20C.glVertexAttribPointer;
import static org.lwjgl.opengl.GL30C.glBindVertexArray;
import static org.lwjgl.opengl.GL30C.glDeleteVertexArrays;
import static org.lwjgl.opengl.GL30C.glGenVertexArrays;

import com.overlord.core.thread.MainThreadGuard;
import java.util.List;
import java.util.Objects;

public final class OpenGlScreenQuadBatch implements ScreenQuadBatch {
    private static final int FLOATS_PER_VERTEX = 2;
    private static final int VERTICES_PER_QUAD = 6;

    private final MainThreadGuard mainThreadGuard;
    private final ScreenQuadBatchBackend backend;
    private int vertexArray;
    private int vertexBuffer;
    private int vertexCount;

    public OpenGlScreenQuadBatch(MainThreadGuard mainThreadGuard) {
        this(mainThreadGuard, new OpenGlScreenQuadBatchBackend());
    }

    OpenGlScreenQuadBatch(
            MainThreadGuard mainThreadGuard, ScreenQuadBatchBackend backend) {
        this.mainThreadGuard = Objects.requireNonNull(mainThreadGuard, "mainThreadGuard");
        this.backend = Objects.requireNonNull(backend, "backend");
        this.mainThreadGuard.assertMainThread("screen quad batch creation");

        int createdVertexArray = 0;
        int createdVertexBuffer = 0;
        try {
            createdVertexArray = backend.createVertexArray();
            createdVertexBuffer = backend.createBuffer();
            backend.bindVertexArray(createdVertexArray);
            backend.bindArrayBuffer(createdVertexBuffer);
            backend.configurePositionAttribute();
            backend.bindArrayBuffer(0);
            backend.bindVertexArray(0);
        } catch (RuntimeException | Error failure) {
            cleanupCreated(createdVertexBuffer, createdVertexArray, failure);
            throw failure;
        }
        vertexArray = createdVertexArray;
        vertexBuffer = createdVertexBuffer;
    }

    @Override
    public void upload(List<ScreenQuad> quads) {
        mainThreadGuard.assertMainThread("screen quad batch upload");
        ensureOpen();
        List<ScreenQuad> snapshot = List.copyOf(Objects.requireNonNull(quads, "quads"));
        float[] vertices = new float[snapshot.size() * VERTICES_PER_QUAD * FLOATS_PER_VERTEX];
        int offset = 0;
        for (ScreenQuad quad : snapshot) {
            offset = append(Objects.requireNonNull(quad, "quad"), vertices, offset);
        }
        backend.bindVertexArray(vertexArray);
        backend.bindArrayBuffer(vertexBuffer);
        backend.upload(vertices);
        vertexCount = snapshot.size() * VERTICES_PER_QUAD;
    }

    @Override
    public void draw() {
        mainThreadGuard.assertMainThread("screen quad batch draw");
        ensureOpen();
        backend.bindVertexArray(vertexArray);
        backend.drawTriangles(vertexCount);
    }

    @Override
    public void cleanup() {
        mainThreadGuard.assertMainThread("screen quad batch cleanup");
        if (vertexArray == 0 && vertexBuffer == 0) {
            return;
        }
        int bufferToDelete = vertexBuffer;
        int vertexArrayToDelete = vertexArray;
        vertexBuffer = 0;
        vertexArray = 0;
        vertexCount = 0;

        Throwable failure = null;
        try {
            backend.deleteBuffer(bufferToDelete);
        } catch (RuntimeException | Error cleanupFailure) {
            failure = cleanupFailure;
        }
        try {
            backend.deleteVertexArray(vertexArrayToDelete);
        } catch (RuntimeException | Error cleanupFailure) {
            failure = appendFailure(failure, cleanupFailure);
        }
        if (failure != null) {
            rethrow(failure);
        }
    }

    private static int append(ScreenQuad quad, float[] output, int offset) {
        offset = vertex(output, offset, quad.xMin(), quad.yMin());
        offset = vertex(output, offset, quad.xMax(), quad.yMin());
        offset = vertex(output, offset, quad.xMax(), quad.yMax());
        offset = vertex(output, offset, quad.xMin(), quad.yMin());
        offset = vertex(output, offset, quad.xMax(), quad.yMax());
        return vertex(output, offset, quad.xMin(), quad.yMax());
    }

    private static int vertex(float[] output, int offset, float x, float y) {
        output[offset++] = x;
        output[offset++] = y;
        return offset;
    }

    private void cleanupCreated(int buffer, int array, Throwable primary) {
        if (buffer != 0) {
            try {
                backend.deleteBuffer(buffer);
            } catch (RuntimeException | Error cleanupFailure) {
                appendFailure(primary, cleanupFailure);
            }
        }
        if (array != 0) {
            try {
                backend.deleteVertexArray(array);
            } catch (RuntimeException | Error cleanupFailure) {
                appendFailure(primary, cleanupFailure);
            }
        }
    }

    private void ensureOpen() {
        if (vertexArray == 0 || vertexBuffer == 0) {
            throw new IllegalStateException("screen quad batch has been cleaned up");
        }
    }

    private static Throwable appendFailure(Throwable primary, Throwable next) {
        if (primary == null) {
            return next;
        }
        if (primary != next) {
            primary.addSuppressed(next);
        }
        return primary;
    }

    private static void rethrow(Throwable failure) {
        if (failure instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
        throw (Error) failure;
    }
}

interface ScreenQuadBatchBackend {
    int createVertexArray();

    int createBuffer();

    void bindVertexArray(int vertexArray);

    void bindArrayBuffer(int buffer);

    void configurePositionAttribute();

    void upload(float[] vertices);

    void drawTriangles(int vertexCount);

    void deleteBuffer(int buffer);

    void deleteVertexArray(int vertexArray);
}

final class OpenGlScreenQuadBatchBackend implements ScreenQuadBatchBackend {
    @Override
    public int createVertexArray() {
        return glGenVertexArrays();
    }

    @Override
    public int createBuffer() {
        return glGenBuffers();
    }

    @Override
    public void bindVertexArray(int vertexArray) {
        glBindVertexArray(vertexArray);
    }

    @Override
    public void bindArrayBuffer(int buffer) {
        glBindBuffer(GL_ARRAY_BUFFER, buffer);
    }

    @Override
    public void configurePositionAttribute() {
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(0, 2, GL_FLOAT, false, 2 * Float.BYTES, 0L);
    }

    @Override
    public void upload(float[] vertices) {
        glBufferData(GL_ARRAY_BUFFER, vertices, GL_DYNAMIC_DRAW);
    }

    @Override
    public void drawTriangles(int vertexCount) {
        glDrawArrays(GL_TRIANGLES, 0, vertexCount);
    }

    @Override
    public void deleteBuffer(int buffer) {
        glDeleteBuffers(buffer);
    }

    @Override
    public void deleteVertexArray(int vertexArray) {
        glDeleteVertexArrays(vertexArray);
    }
}

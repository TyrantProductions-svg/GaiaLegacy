package com.overlord.renderer.feedback;

import static org.lwjgl.opengl.GL11C.GL_FLOAT;
import static org.lwjgl.opengl.GL11C.GL_TRIANGLES;
import static org.lwjgl.opengl.GL11C.glDrawArrays;
import static org.lwjgl.opengl.GL15C.GL_ARRAY_BUFFER;
import static org.lwjgl.opengl.GL15C.GL_STATIC_DRAW;
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
import java.util.Objects;

public final class OpenGlUnitCubeMesh implements UnitCubeMesh {
    private static final int FLOATS_PER_VERTEX = 5;
    private static final int VERTEX_COUNT = 36;
    private static final float[] VERTICES = createVertices();

    private final MainThreadGuard mainThreadGuard;
    private final UnitCubeMeshBackend backend;
    private int vertexArray;
    private int vertexBuffer;

    public OpenGlUnitCubeMesh(MainThreadGuard mainThreadGuard) {
        this(mainThreadGuard, new OpenGlUnitCubeMeshBackend());
    }

    OpenGlUnitCubeMesh(MainThreadGuard mainThreadGuard, UnitCubeMeshBackend backend) {
        this.mainThreadGuard = Objects.requireNonNull(mainThreadGuard, "mainThreadGuard");
        this.backend = Objects.requireNonNull(backend, "backend");
        this.mainThreadGuard.assertMainThread("unit cube mesh creation");

        int createdVertexArray = 0;
        int createdVertexBuffer = 0;
        try {
            createdVertexArray = backend.createVertexArray();
            createdVertexBuffer = backend.createBuffer();
            backend.bindVertexArray(createdVertexArray);
            backend.bindArrayBuffer(createdVertexBuffer);
            backend.upload(VERTICES);
            backend.configureAttributes();
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
    public void draw() {
        mainThreadGuard.assertMainThread("unit cube mesh draw");
        ensureOpen();
        backend.bindVertexArray(vertexArray);
        backend.drawTriangles(VERTEX_COUNT);
    }

    @Override
    public void cleanup() {
        mainThreadGuard.assertMainThread("unit cube mesh cleanup");
        if (vertexArray == 0 && vertexBuffer == 0) {
            return;
        }
        int bufferToDelete = vertexBuffer;
        int vertexArrayToDelete = vertexArray;
        vertexBuffer = 0;
        vertexArray = 0;

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
            throw new IllegalStateException("unit cube mesh has been cleaned up");
        }
    }

    private static float[] createVertices() {
        float[] output = new float[VERTEX_COUNT * FLOATS_PER_VERTEX];
        int offset = 0;
        offset = quad(output, offset, 0, 0, 1, 1, 0, 1, 1, 1, 1, 0, 1, 1);
        offset = quad(output, offset, 1, 0, 0, 0, 0, 0, 0, 1, 0, 1, 1, 0);
        offset = quad(output, offset, 0, 1, 1, 1, 1, 1, 1, 1, 0, 0, 1, 0);
        offset = quad(output, offset, 0, 0, 0, 1, 0, 0, 1, 0, 1, 0, 0, 1);
        offset = quad(output, offset, 1, 0, 1, 1, 0, 0, 1, 1, 0, 1, 1, 1);
        quad(output, offset, 0, 0, 0, 0, 0, 1, 0, 1, 1, 0, 1, 1);
        return output;
    }

    private static int quad(
            float[] output,
            int offset,
            float ax,
            float ay,
            float az,
            float bx,
            float by,
            float bz,
            float cx,
            float cy,
            float cz,
            float dx,
            float dy,
            float dz) {
        offset = vertex(output, offset, ax, ay, az, 0, 0);
        offset = vertex(output, offset, bx, by, bz, 1, 0);
        offset = vertex(output, offset, cx, cy, cz, 1, 1);
        offset = vertex(output, offset, ax, ay, az, 0, 0);
        offset = vertex(output, offset, cx, cy, cz, 1, 1);
        return vertex(output, offset, dx, dy, dz, 0, 1);
    }

    private static int vertex(
            float[] output, int offset, float x, float y, float z, float u, float v) {
        output[offset++] = x;
        output[offset++] = y;
        output[offset++] = z;
        output[offset++] = u;
        output[offset++] = v;
        return offset;
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

interface UnitCubeMeshBackend {
    int createVertexArray();

    int createBuffer();

    void bindVertexArray(int vertexArray);

    void bindArrayBuffer(int buffer);

    void configureAttributes();

    void upload(float[] vertices);

    void drawTriangles(int vertexCount);

    void deleteBuffer(int buffer);

    void deleteVertexArray(int vertexArray);
}

final class OpenGlUnitCubeMeshBackend implements UnitCubeMeshBackend {
    private static final int FLOATS_PER_VERTEX = 5;

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
    public void configureAttributes() {
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(0, 3, GL_FLOAT, false, FLOATS_PER_VERTEX * Float.BYTES, 0L);
        glEnableVertexAttribArray(1);
        glVertexAttribPointer(
                1,
                2,
                GL_FLOAT,
                false,
                FLOATS_PER_VERTEX * Float.BYTES,
                3L * Float.BYTES);
    }

    @Override
    public void upload(float[] vertices) {
        glBufferData(GL_ARRAY_BUFFER, vertices, GL_STATIC_DRAW);
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

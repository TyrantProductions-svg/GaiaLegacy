package com.overlord.renderer.feedback;

import static org.lwjgl.opengl.GL11C.GL_FLOAT;
import static org.lwjgl.opengl.GL11C.GL_TRIANGLES;
import static org.lwjgl.opengl.GL11C.glDrawArrays;
import static org.lwjgl.opengl.GL15C.GL_ARRAY_BUFFER;
import static org.lwjgl.opengl.GL15C.GL_STREAM_DRAW;
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
import com.overlord.renderer.texture.TextureRegion;
import java.util.Objects;

public final class OpenGlStreamingTexturedCubeBatch implements StreamingTexturedCubeBatch {
    private static final int FLOATS_PER_VERTEX = 5;
    private static final int VERTICES_PER_CUBE = 36;

    private final MainThreadGuard mainThreadGuard;
    private final StreamingTexturedCubeBatchBackend backend;
    private int vertexArray;
    private int vertexBuffer;
    private int vertexCount;

    public OpenGlStreamingTexturedCubeBatch(MainThreadGuard mainThreadGuard) {
        this(mainThreadGuard, new OpenGlStreamingTexturedCubeBatchBackend());
    }

    OpenGlStreamingTexturedCubeBatch(
            MainThreadGuard mainThreadGuard, StreamingTexturedCubeBatchBackend backend) {
        this.mainThreadGuard = Objects.requireNonNull(mainThreadGuard, "mainThreadGuard");
        this.backend = Objects.requireNonNull(backend, "backend");
        this.mainThreadGuard.assertMainThread("particle streaming batch creation");

        int createdVertexArray = 0;
        int createdVertexBuffer = 0;
        try {
            createdVertexArray = backend.createVertexArray();
            createdVertexBuffer = backend.createBuffer();
            backend.bindVertexArray(createdVertexArray);
            backend.bindArrayBuffer(createdVertexBuffer);
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
    public void upload(ParticleRenderBatch particles) {
        mainThreadGuard.assertMainThread("particle streaming batch upload");
        ensureOpen();
        ParticleRenderBatch snapshot = Objects.requireNonNull(particles, "particles");
        if (snapshot.particles().isEmpty()) {
            vertexCount = 0;
            return;
        }
        float[] vertices = expand(snapshot);
        backend.bindVertexArray(vertexArray);
        backend.bindArrayBuffer(vertexBuffer);
        backend.upload(vertices, GL_STREAM_DRAW);
        vertexCount = snapshot.particles().size() * VERTICES_PER_CUBE;
    }

    @Override
    public void draw() {
        mainThreadGuard.assertMainThread("particle streaming batch draw");
        ensureOpen();
        if (vertexCount == 0) {
            return;
        }
        backend.bindVertexArray(vertexArray);
        backend.drawTriangles(vertexCount);
    }

    @Override
    public void cleanup() {
        mainThreadGuard.assertMainThread("particle streaming batch cleanup");
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

    private static float[] expand(ParticleRenderBatch particles) {
        float[] output =
                new float[particles.particles().size() * VERTICES_PER_CUBE * FLOATS_PER_VERTEX];
        int offset = 0;
        for (ParticleVisual particle : particles.particles()) {
            if (!Float.isFinite(particle.x())
                    || !Float.isFinite(particle.y())
                    || !Float.isFinite(particle.z())
                    || !Float.isFinite(particle.size())
                    || particle.size() <= 0.0f) {
                throw new IllegalArgumentException("particle visual geometry must be finite and positive");
            }
            float h = particle.size() * 0.5f;
            float x0 = particle.x() - h;
            float x1 = particle.x() + h;
            float y0 = particle.y() - h;
            float y1 = particle.y() + h;
            float z0 = particle.z() - h;
            float z1 = particle.z() + h;
            TextureRegion region = particle.region();
            offset = quad(output, offset, x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1, region);
            offset = quad(output, offset, x1, y0, z0, x0, y0, z0, x0, y1, z0, x1, y1, z0, region);
            offset = quad(output, offset, x0, y1, z1, x1, y1, z1, x1, y1, z0, x0, y1, z0, region);
            offset = quad(output, offset, x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1, region);
            offset = quad(output, offset, x1, y0, z1, x1, y0, z0, x1, y1, z0, x1, y1, z1, region);
            offset = quad(output, offset, x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0, region);
        }
        return output;
    }

    private static int quad(
            float[] output,
            int offset,
            float ax, float ay, float az,
            float bx, float by, float bz,
            float cx, float cy, float cz,
            float dx, float dy, float dz,
            TextureRegion region) {
        offset = vertex(output, offset, ax, ay, az, region.uMin(), region.vMin());
        offset = vertex(output, offset, bx, by, bz, region.uMax(), region.vMin());
        offset = vertex(output, offset, cx, cy, cz, region.uMax(), region.vMax());
        offset = vertex(output, offset, ax, ay, az, region.uMin(), region.vMin());
        offset = vertex(output, offset, cx, cy, cz, region.uMax(), region.vMax());
        return vertex(output, offset, dx, dy, dz, region.uMin(), region.vMax());
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
            throw new IllegalStateException("particle streaming batch has been cleaned up");
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

interface StreamingTexturedCubeBatchBackend {
    int createVertexArray();

    int createBuffer();

    void bindVertexArray(int vertexArray);

    void bindArrayBuffer(int buffer);

    void configureAttributes();

    void upload(float[] vertices, int usage);

    void drawTriangles(int vertexCount);

    void deleteBuffer(int buffer);

    void deleteVertexArray(int vertexArray);
}

final class OpenGlStreamingTexturedCubeBatchBackend implements StreamingTexturedCubeBatchBackend {
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
        int stride = FLOATS_PER_VERTEX * Float.BYTES;
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(0, 3, GL_FLOAT, false, stride, 0L);
        glEnableVertexAttribArray(1);
        glVertexAttribPointer(1, 2, GL_FLOAT, false, stride, 3L * Float.BYTES);
    }

    @Override
    public void upload(float[] vertices, int usage) {
        glBufferData(GL_ARRAY_BUFFER, vertices, usage);
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

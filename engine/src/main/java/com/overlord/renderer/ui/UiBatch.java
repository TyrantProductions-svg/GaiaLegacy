package com.overlord.renderer.ui;

import com.overlord.core.thread.MainThreadGuard;
import java.util.List;
import java.util.Objects;

public final class UiBatch implements AutoCloseable {
    private static final int FLOATS_PER_VERTEX = 8;
    private static final int VERTICES_PER_QUAD = 4;
    private static final int INDICES_PER_QUAD = 6;

    private final UiGpuBackend backend;
    private final MainThreadGuard guard;
    private int vertexArray;
    private int vertexBuffer;
    private int elementBuffer;
    private int indexCount;

    private UiBatch(
            UiGpuBackend backend,
            MainThreadGuard guard,
            int vertexArray,
            int vertexBuffer,
            int elementBuffer) {
        this.backend = backend;
        this.guard = guard;
        this.vertexArray = vertexArray;
        this.vertexBuffer = vertexBuffer;
        this.elementBuffer = elementBuffer;
    }

    static UiBatch create(UiGpuBackend backend, MainThreadGuard guard) {
        Objects.requireNonNull(backend, "backend");
        Objects.requireNonNull(guard, "guard");
        guard.assertMainThread("UI batch creation");
        int vertexArray = 0;
        int vertexBuffer = 0;
        int elementBuffer = 0;
        try {
            vertexArray = backend.createVertexArray();
            vertexBuffer = backend.createBuffer();
            elementBuffer = backend.createBuffer();
            backend.configureBatch(vertexArray, vertexBuffer, elementBuffer);
            return new UiBatch(backend, guard, vertexArray, vertexBuffer, elementBuffer);
        } catch (RuntimeException | Error failure) {
            Throwable primary = failure;
            primary = deleteBuffer(backend, elementBuffer, primary);
            primary = deleteBuffer(backend, vertexBuffer, primary);
            primary = deleteVertexArray(backend, vertexArray, primary);
            rethrow(primary);
            throw new AssertionError("unreachable");
        }
    }

    void upload(List<UiDrawCommand> commands) {
        guard.assertMainThread("UI batch upload");
        ensureOpen();
        List<UiDrawCommand> snapshot = List.copyOf(Objects.requireNonNull(commands, "commands"));
        float[] vertices =
                new float[snapshot.size() * VERTICES_PER_QUAD * FLOATS_PER_VERTEX];
        int[] indices = new int[snapshot.size() * INDICES_PER_QUAD];
        int vertexOffset = 0;
        int indexOffset = 0;
        int baseVertex = 0;
        for (UiDrawCommand command : snapshot) {
            UiDrawCommand checked = Objects.requireNonNull(command, "command");
            vertexOffset = appendQuad(checked, vertices, vertexOffset);
            indices[indexOffset++] = baseVertex;
            indices[indexOffset++] = baseVertex + 1;
            indices[indexOffset++] = baseVertex + 2;
            indices[indexOffset++] = baseVertex;
            indices[indexOffset++] = baseVertex + 2;
            indices[indexOffset++] = baseVertex + 3;
            baseVertex += VERTICES_PER_QUAD;
        }
        backend.uploadBatch(
                vertexArray, vertexBuffer, elementBuffer, vertices, indices);
        indexCount = indices.length;
    }

    void draw() {
        guard.assertMainThread("UI batch draw");
        ensureOpen();
        backend.drawBatch(vertexArray, indexCount);
    }

    @Override
    public void close() {
        guard.assertMainThread("UI batch cleanup");
        if (vertexArray == 0 && vertexBuffer == 0 && elementBuffer == 0) {
            return;
        }
        int deletingElementBuffer = elementBuffer;
        int deletingVertexBuffer = vertexBuffer;
        int deletingVertexArray = vertexArray;
        elementBuffer = 0;
        vertexBuffer = 0;
        vertexArray = 0;
        indexCount = 0;

        Throwable failure = null;
        failure = deleteBuffer(backend, deletingElementBuffer, failure);
        failure = deleteBuffer(backend, deletingVertexBuffer, failure);
        failure = deleteVertexArray(backend, deletingVertexArray, failure);
        if (failure != null) {
            rethrow(failure);
        }
    }

    private static int appendQuad(UiDrawCommand command, float[] output, int offset) {
        UiRect bounds = command.framebufferBounds();
        UiUvRect uv = command.uv();
        UiColor tint = command.tint();
        offset = appendVertex(output, offset, bounds.left(), bounds.top(), uv.left(), uv.top(), tint);
        offset = appendVertex(output, offset, bounds.right(), bounds.top(), uv.right(), uv.top(), tint);
        offset = appendVertex(output, offset, bounds.right(), bounds.bottom(), uv.right(), uv.bottom(), tint);
        return appendVertex(output, offset, bounds.left(), bounds.bottom(), uv.left(), uv.bottom(), tint);
    }

    private static int appendVertex(
            float[] output,
            int offset,
            double x,
            double y,
            float u,
            float v,
            UiColor tint) {
        output[offset++] = (float) x;
        output[offset++] = (float) y;
        output[offset++] = u;
        output[offset++] = v;
        output[offset++] = tint.red();
        output[offset++] = tint.green();
        output[offset++] = tint.blue();
        output[offset++] = tint.alpha();
        return offset;
    }

    private void ensureOpen() {
        if (vertexArray == 0 || vertexBuffer == 0 || elementBuffer == 0) {
            throw new IllegalStateException("UI batch has been cleaned up");
        }
    }

    private static Throwable deleteBuffer(
            UiGpuBackend backend, int buffer, Throwable failure) {
        if (buffer == 0) {
            return failure;
        }
        try {
            backend.deleteBuffer(buffer);
        } catch (RuntimeException | Error cleanupFailure) {
            return appendFailure(failure, cleanupFailure);
        }
        return failure;
    }

    private static Throwable deleteVertexArray(
            UiGpuBackend backend, int vertexArray, Throwable failure) {
        if (vertexArray == 0) {
            return failure;
        }
        try {
            backend.deleteVertexArray(vertexArray);
        } catch (RuntimeException | Error cleanupFailure) {
            return appendFailure(failure, cleanupFailure);
        }
        return failure;
    }

    static Throwable appendFailure(Throwable failure, Throwable next) {
        if (failure == null) {
            return next;
        }
        if (failure != next) {
            failure.addSuppressed(next);
        }
        return failure;
    }

    static void rethrow(Throwable failure) {
        if (failure instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
        throw (Error) failure;
    }
}

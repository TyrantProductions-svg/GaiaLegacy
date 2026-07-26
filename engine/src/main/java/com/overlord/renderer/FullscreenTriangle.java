package com.overlord.renderer;

import com.overlord.core.thread.MainThreadGuard;
import java.util.Objects;

public final class FullscreenTriangle implements AutoCloseable {
    private final MainThreadGuard mainThreadGuard;
    private final FullscreenTriangleBackend backend;
    private int vertexArrayId;

    public FullscreenTriangle(MainThreadGuard mainThreadGuard) {
        this(
                mainThreadGuard,
                new OpenGlFullscreenTriangleBackend());
    }

    FullscreenTriangle(
            MainThreadGuard mainThreadGuard,
            FullscreenTriangleBackend backend) {
        this.mainThreadGuard =
                Objects.requireNonNull(
                        mainThreadGuard, "mainThreadGuard");
        this.backend = Objects.requireNonNull(backend, "backend");
        this.mainThreadGuard.assertMainThread(
                "fullscreen triangle creation");

        int createdVertexArray = backend.createVertexArray();
        try {
            backend.bindVertexArray(createdVertexArray);
            backend.bindVertexArray(0);
        } catch (RuntimeException | Error failure) {
            try {
                backend.deleteVertexArray(createdVertexArray);
            } catch (RuntimeException | Error cleanupFailure) {
                if (cleanupFailure != failure) {
                    failure.addSuppressed(cleanupFailure);
                }
            }
            throw failure;
        }
        vertexArrayId = createdVertexArray;
    }

    public void draw() {
        mainThreadGuard.assertMainThread(
                "fullscreen triangle draw");
        ensureOpen();
        backend.bindVertexArray(vertexArrayId);
        Throwable failure = null;
        try {
            backend.drawTriangles(0, 3);
        } catch (RuntimeException | Error drawFailure) {
            failure = drawFailure;
        }
        try {
            backend.bindVertexArray(0);
        } catch (RuntimeException | Error unbindFailure) {
            if (failure == null) {
                failure = unbindFailure;
            } else if (unbindFailure != failure) {
                failure.addSuppressed(unbindFailure);
            }
        }
        if (failure != null) {
            rethrow(failure);
        }
    }

    public void cleanup() {
        mainThreadGuard.assertMainThread(
                "fullscreen triangle cleanup");
        if (vertexArrayId != 0) {
            int vertexArrayToDelete = vertexArrayId;
            vertexArrayId = 0;
            backend.deleteVertexArray(vertexArrayToDelete);
        }
    }

    @Override
    public void close() {
        cleanup();
    }

    private void ensureOpen() {
        if (vertexArrayId == 0) {
            throw new IllegalStateException(
                    "fullscreen triangle has been cleaned up");
        }
    }

    private static void rethrow(Throwable failure) {
        if (failure instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
        throw (Error) failure;
    }
}

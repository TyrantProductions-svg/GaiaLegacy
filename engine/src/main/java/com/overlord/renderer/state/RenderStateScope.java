package com.overlord.renderer.state;

import java.util.Objects;

public final class RenderStateScope implements AutoCloseable {
    private final RenderStateBackend backend;
    private final RenderStateSnapshot incoming;
    private boolean closed;

    private RenderStateScope(
            RenderStateBackend backend,
            RenderStateSnapshot incoming) {
        this.backend = backend;
        this.incoming = incoming;
    }

    public static RenderStateScope open(
            RenderStateBackend backend,
            RenderStateSpec requested) {
        Objects.requireNonNull(backend, "backend");
        Objects.requireNonNull(requested, "requested");
        RenderStateSnapshot incoming = backend.capture();
        try {
            backend.apply(requested);
        } catch (RuntimeException | Error failure) {
            try {
                backend.restore(incoming);
            } catch (RuntimeException | Error restoreFailure) {
                if (restoreFailure != failure) {
                    failure.addSuppressed(restoreFailure);
                }
            }
            throw failure;
        }
        return new RenderStateScope(backend, incoming);
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        backend.restore(incoming);
    }
}

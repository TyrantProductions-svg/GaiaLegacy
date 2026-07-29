package com.overlord.renderer.ui;

import com.overlord.core.thread.MainThreadGuard;
import java.util.Objects;

public final class UiTexture implements AutoCloseable {
    private final UiGpuBackend backend;
    private final MainThreadGuard guard;
    private int texture;

    private UiTexture(UiGpuBackend backend, MainThreadGuard guard, int texture) {
        this.backend = backend;
        this.guard = guard;
        this.texture = texture;
    }

    static UiTexture create(
            UiTextureData textureData,
            UiGpuBackend backend,
            MainThreadGuard guard) {
        Objects.requireNonNull(textureData, "textureData");
        Objects.requireNonNull(backend, "backend");
        Objects.requireNonNull(guard, "guard");
        guard.assertMainThread("UI texture creation");
        return new UiTexture(backend, guard, backend.createTexture(textureData));
    }

    void bindUnitZero() {
        guard.assertMainThread("UI texture bind");
        ensureOpen();
        backend.bindTextureUnitZero(texture);
    }

    @Override
    public void close() {
        guard.assertMainThread("UI texture cleanup");
        if (texture == 0) {
            return;
        }
        int deleting = texture;
        texture = 0;
        backend.deleteTexture(deleting);
    }

    private void ensureOpen() {
        if (texture == 0) {
            throw new IllegalStateException("UI texture has been cleaned up");
        }
    }
}

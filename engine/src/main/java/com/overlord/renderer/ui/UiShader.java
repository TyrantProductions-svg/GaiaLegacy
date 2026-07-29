package com.overlord.renderer.ui;

import com.overlord.core.thread.MainThreadGuard;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

public final class UiShader implements AutoCloseable {
    private static final String VERTEX_RESOURCE =
            "assets/overlord/shaders/ui/ui.vert";
    private static final String FRAGMENT_RESOURCE =
            "assets/overlord/shaders/ui/ui.frag";

    private final UiGpuBackend backend;
    private final MainThreadGuard guard;
    private int program;

    private UiShader(UiGpuBackend backend, MainThreadGuard guard, int program) {
        this.backend = backend;
        this.guard = guard;
        this.program = program;
    }

    static UiShader create(UiGpuBackend backend, MainThreadGuard guard) {
        Objects.requireNonNull(backend, "backend");
        Objects.requireNonNull(guard, "guard");
        guard.assertMainThread("UI shader creation");
        Sources sources = loadSources();
        try {
            return new UiShader(
                    backend,
                    guard,
                    backend.createProgram(
                            sources.vertexSource(),
                            sources.fragmentSource()));
        } catch (RuntimeException | Error failure) {
            throw new IllegalStateException(
                    "Failed to create UI shader program from resources "
                            + VERTEX_RESOURCE
                            + " and "
                            + FRAGMENT_RESOURCE,
                    failure);
        }
    }

    static Sources loadSources() {
        return loadSources(UiShader.class.getClassLoader());
    }

    static Sources loadSources(ClassLoader classLoader) {
        Objects.requireNonNull(classLoader, "classLoader");
        return new Sources(
                readResource(VERTEX_RESOURCE, classLoader),
                readResource(FRAGMENT_RESOURCE, classLoader));
    }

    void bind(int framebufferWidth, int framebufferHeight) {
        guard.assertMainThread("UI shader use");
        ensureOpen();
        backend.useProgram(program);
        backend.setFramebufferSize(program, framebufferWidth, framebufferHeight);
        backend.setTextureSampler(program, 0);
    }

    void setTextureSamplingEnabled(boolean enabled) {
        guard.assertMainThread("UI shader texture mode upload");
        ensureOpen();
        backend.setTextureSamplingEnabled(program, enabled);
    }

    @Override
    public void close() {
        guard.assertMainThread("UI shader cleanup");
        if (program == 0) {
            return;
        }
        int deleting = program;
        program = 0;
        backend.deleteProgram(deleting);
    }

    private void ensureOpen() {
        if (program == 0) {
            throw new IllegalStateException("UI shader has been cleaned up");
        }
    }

    private static String readResource(String path, ClassLoader classLoader) {
        try (InputStream input = classLoader.getResourceAsStream(path)) {
            if (input == null) {
                throw new IllegalStateException("Missing required UI shader resource: " + path);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to read UI shader resource: " + path, exception);
        }
    }

    record Sources(String vertexSource, String fragmentSource) {
        Sources {
            Objects.requireNonNull(vertexSource, "vertexSource");
            Objects.requireNonNull(fragmentSource, "fragmentSource");
        }
    }
}

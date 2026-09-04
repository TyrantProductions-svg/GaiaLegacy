package com.gaia.tools.viewer;

import com.overlord.core.thread.MainThreadGuard;
import java.util.Objects;
import org.joml.Matrix4fc;

/** Main-thread diagnostic rendering backend for the independent viewer JVM. */
final class OpenGlInspectorRenderBackend implements InspectorRenderBackend {
    private final MainThreadGuard guard;
    private final ViewerShader shader;
    private final InspectorGlApi gl;
    private final int lineVao;
    private final int lineVbo;
    private boolean closed;

    OpenGlInspectorRenderBackend(MainThreadGuard guard) {
        this(guard, new ViewerShaderProgram(guard), new LwjglInspectorGlApi());
    }

    OpenGlInspectorRenderBackend(MainThreadGuard guard, ViewerShader shader, InspectorGlApi gl) {
        this.guard = Objects.requireNonNull(guard, "guard");
        this.shader = Objects.requireNonNull(shader, "shader");
        this.gl = Objects.requireNonNull(gl, "OpenGL API");
        guard.assertMainThread("model viewer renderer creation");
        int vao = 0;
        int vbo = 0;
        try {
            vao = gl.createLineVertexArray();
            vbo = gl.createLineBuffer();
            gl.configureLineBuffer(vao, vbo);
        } catch (RuntimeException | Error failure) {
            Throwable result = failure;
            if (vbo != 0) {
                try { gl.deleteLineBuffer(vbo); }
                catch (RuntimeException | Error caught) { result.addSuppressed(caught); }
            }
            if (vao != 0) {
                try { gl.deleteLineVertexArray(vao); }
                catch (RuntimeException | Error caught) { result.addSuppressed(caught); }
            }
            try { shader.close(); }
            catch (RuntimeException | Error caught) { result.addSuppressed(caught); }
            if (result instanceof RuntimeException runtime) throw runtime;
            throw (Error)result;
        }
        lineVao = vao;
        lineVbo = vbo;
    }

    @Override public void begin(Matrix4fc projection, int framebufferWidth, int framebufferHeight) {
        owner("begin model viewer frame");
        shader.use();
        shader.projection(projection);
        gl.beginFrame(Math.max(1, framebufferWidth), Math.max(1, framebufferHeight));
    }

    @Override public void wireframe(boolean enabled) {
        owner("set model viewer wireframe");
        gl.wireframe(enabled);
    }

    @Override
    public void triangles(InspectorGpuModel.PrimitiveGpu primitive, Matrix4fc modelView,
            float[] baseColor, Integer texture, Integer sampler) {
        owner("draw model viewer primitive");
        shader.modelView(modelView);
        shader.baseColor(baseColor);
        shader.unlit(false);
        boolean hasTexture = texture != null && sampler != null;
        shader.textured(hasTexture);
        gl.bindTextureSampler(hasTexture ? texture : 0, hasTexture ? sampler : 0);
        gl.drawIndexed(primitive.vao(), primitive.indexCount());
    }

    @Override public void lines(ViewerGeometry.Lines batch, Matrix4fc modelView) {
        owner("draw model viewer diagnostic lines");
        shader.modelView(modelView);
        shader.lineColor(batch.color());
        shader.textured(false);
        shader.unlit(true);
        gl.bindTextureSampler(0, 0);
        gl.uploadAndDrawLines(lineVao, lineVbo, batch.positions());
    }

    @Override public void end() {
        owner("end model viewer frame");
        gl.endFrame();
    }

    @Override public void close() {
        guard.assertMainThread("close model viewer renderer");
        if (closed) return;
        closed = true;
        Throwable failure = null;
        try { gl.deleteLineBuffer(lineVbo); } catch (RuntimeException | Error caught) { failure = caught; }
        try { gl.deleteLineVertexArray(lineVao); } catch (RuntimeException | Error caught) {
            if (failure == null) failure = caught; else failure.addSuppressed(caught);
        }
        try { shader.close(); } catch (RuntimeException | Error caught) {
            if (failure == null) failure = caught; else failure.addSuppressed(caught);
        }
        if (failure instanceof RuntimeException runtime) throw runtime;
        if (failure instanceof Error error) throw error;
    }

    private void owner(String operation) {
        guard.assertMainThread(operation);
        if (closed) throw new IllegalStateException("model viewer renderer is closed");
    }
}

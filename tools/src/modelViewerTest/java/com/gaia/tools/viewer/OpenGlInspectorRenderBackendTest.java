package com.gaia.tools.viewer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.overlord.core.thread.MainThreadGuard;
import java.util.ArrayList;
import java.util.List;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

class OpenGlInspectorRenderBackendTest {
    @Test
    void backendAppliesDiagnosticStateBindsValidatedTextureAndRestoresWireframe() {
        FakeShader shader = new FakeShader();
        FakeGl gl = new FakeGl();
        OpenGlInspectorRenderBackend backend = new OpenGlInspectorRenderBackend(
                MainThreadGuard.captureCurrentThread(), shader, gl);
        var primitive = new InspectorGpuModel.PrimitiveGpu(8, 9, 10, 12, 0);

        backend.begin(new Matrix4f(), 640, 480);
        backend.wireframe(true);
        backend.triangles(primitive, new Matrix4f(), new float[]{0.2f, 0.3f, 0.4f}, 17, 19);
        backend.wireframe(false);
        backend.lines(new ViewerGeometry.Lines(new float[]{0,0,0,1,0,0},
                new double[]{1,0,0,0, 0,1,0,0, 0,0,1,0, 0,0,0,1},
                new float[]{1,0,0}), new Matrix4f());
        backend.end();
        backend.close();
        backend.close();

        assertEquals(List.of("begin:640x480", "wire:true", "bind:17:19", "draw:8:12",
                "wire:false", "bind:0:0", "lines:2", "end", "delete-buffer:2",
                "delete-vao:1"), gl.events);
        assertEquals(1, shader.closeCount);
        assertTrue(shader.sawTextured);
        assertTrue(shader.sawUnlit);
        assertFalse(gl.framebufferSrgbEnabled);
    }

    @Test
    void constructorFailureAttemptsEveryAcquiredCleanupInReverseOrder() {
        FakeShader shader = new FakeShader();
        FakeGl gl = new FakeGl();
        gl.failConfigure = true;
        gl.failDeleteBuffer = true;

        RuntimeException failure = org.junit.jupiter.api.Assertions.assertThrows(
                RuntimeException.class, () -> new OpenGlInspectorRenderBackend(
                        MainThreadGuard.captureCurrentThread(), shader, gl));

        assertEquals("configure", failure.getMessage());
        assertEquals(List.of("delete-buffer:2", "delete-vao:1"), gl.events);
        assertEquals(1, shader.closeCount);
        assertEquals(1, failure.getSuppressed().length);
    }

    private static final class FakeShader implements ViewerShader {
        boolean sawTextured;
        boolean sawUnlit;
        int closeCount;
        @Override public void use() { }
        @Override public void projection(org.joml.Matrix4fc value) { }
        @Override public void modelView(org.joml.Matrix4fc value) { }
        @Override public void baseColor(float[] value) { }
        @Override public void lineColor(float[] value) { }
        @Override public void textured(boolean value) { sawTextured |= value; }
        @Override public void unlit(boolean value) { sawUnlit |= value; }
        @Override public void close() { closeCount++; }
    }

    private static final class FakeGl implements InspectorGlApi {
        int next = 1;
        boolean framebufferSrgbEnabled;
        boolean failConfigure;
        boolean failDeleteBuffer;
        final List<String> events = new ArrayList<>();
        @Override public int createLineVertexArray() { return next++; }
        @Override public int createLineBuffer() { return next++; }
        @Override public void configureLineBuffer(int vao, int vbo) {
            if (failConfigure) throw new IllegalStateException("configure");
        }
        @Override public void beginFrame(int width, int height) { events.add("begin:"+width+"x"+height); }
        @Override public void wireframe(boolean enabled) { events.add("wire:"+enabled); }
        @Override public void bindTextureSampler(int texture, int sampler) { events.add("bind:"+texture+":"+sampler); }
        @Override public void drawIndexed(int vao, int count) { events.add("draw:"+vao+":"+count); }
        @Override public void uploadAndDrawLines(int vao, int vbo, float[] positions) { events.add("lines:"+(positions.length/3)); }
        @Override public void endFrame() { events.add("end"); }
        @Override public void deleteLineBuffer(int handle) {
            events.add("delete-buffer:"+handle);
            if (failDeleteBuffer) throw new IllegalStateException("delete buffer");
        }
        @Override public void deleteLineVertexArray(int handle) { events.add("delete-vao:"+handle); }
    }
}

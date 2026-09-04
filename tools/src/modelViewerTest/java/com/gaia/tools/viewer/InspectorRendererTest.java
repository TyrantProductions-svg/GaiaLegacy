package com.gaia.tools.viewer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.overlord.core.thread.MainThreadGuard;
import java.util.ArrayList;
import java.util.List;
import org.joml.Matrix4fc;
import org.junit.jupiter.api.Test;

class InspectorRendererTest {
    @Test
    void rendererPreservesInstancesSharesImageTextureAndScopesWireframeToModel() throws Exception {
        ViewerCpuModel cpu = ViewerCpuModel.from(ViewerFixtures.snapshot(true, true));
        MinimalGpuResources resources = new MinimalGpuResources();
        InspectorGpuModel gpu = InspectorGpuModel.upload(cpu, resources);
        FakeRenderBackend backend = new FakeRenderBackend();
        InspectorRenderer renderer = new InspectorRenderer(backend);
        OrbitCamera camera = new OrbitCamera(cpu.bounds(), 1280, 720);

        renderer.render(gpu, camera,
                new InspectorRenderer.Visibility(true, true, true, true), 1280, 720);

        assertEquals(2, backend.triangles.size());
        assertEquals(4, backend.triangles.get(0).texture);
        assertEquals(4, backend.triangles.get(1).texture);
        assertEquals(List.of(true, false), backend.wireframe);
        assertEquals(5, backend.lines);
        assertTrue(backend.triangles.get(0).modelView.m30()
                != backend.triangles.get(1).modelView.m30()
                || backend.triangles.get(0).modelView.m31()
                != backend.triangles.get(1).modelView.m31());
        assertTrue(backend.ended);
        gpu.close();
    }

    @Test
    void disabledHelpersProduceNoLineDrawsAndViewportIsClamped() throws Exception {
        ViewerCpuModel cpu = ViewerCpuModel.from(ViewerFixtures.snapshot(false, false));
        InspectorGpuModel gpu = InspectorGpuModel.upload(cpu, new MinimalGpuResources());
        FakeRenderBackend backend = new FakeRenderBackend();

        new InspectorRenderer(backend).render(gpu, new OrbitCamera(cpu.bounds(), 1, 1),
                new InspectorRenderer.Visibility(false, false, false, false), 0, -2);

        assertEquals(0, backend.lines);
        assertEquals(1, backend.width);
        assertEquals(1, backend.height);
        assertFalse(backend.wireframe.contains(true));
        gpu.close();
    }

    private static final class FakeRenderBackend implements InspectorRenderBackend {
        int width;
        int height;
        int lines;
        boolean ended;
        final List<Boolean> wireframe = new ArrayList<>();
        final List<TriangleCall> triangles = new ArrayList<>();

        @Override public void begin(Matrix4fc projection, int width, int height) {
            this.width = width; this.height = height;
        }
        @Override public void wireframe(boolean enabled) { wireframe.add(enabled); }
        @Override public void triangles(InspectorGpuModel.PrimitiveGpu primitive,
                Matrix4fc modelView, float[] baseColor, Integer texture, Integer sampler) {
            triangles.add(new TriangleCall(modelView, texture == null ? 0 : texture));
        }
        @Override public void lines(ViewerGeometry.Lines batch, Matrix4fc modelView) { lines++; }
        @Override public void end() { ended = true; }
    }

    private record TriangleCall(Matrix4fc modelView, int texture) { }

    private static final class MinimalGpuResources implements ViewerGlResources {
        private final MainThreadGuard guard = MainThreadGuard.captureCurrentThread();
        private int next = 1;
        @Override public void assertOwner(String operation) { guard.assertMainThread(operation); }
        @Override public void assertNoError(String operation) { guard.assertMainThread(operation); }
        @Override public int createVertexArray() { return next++; }
        @Override public int createBuffer() { return next++; }
        @Override public int createTexture() { return next++; }
        @Override public int createSampler() { return next++; }
        @Override public void uploadMesh(int a,int b,int c,float[] d,int[] e,int f,int g,int h,int i) { }
        @Override public void uploadSrgbTexture(int a,int b,int c,byte[] d) { }
        @Override public void generateMipmaps(int texture) { }
        @Override public void configureSampler(int a,int b,int c,int d,int e) { }
        @Override public void deleteVertexArray(int handle) { }
        @Override public void deleteBuffer(int handle) { }
        @Override public void deleteTexture(int handle) { }
        @Override public void deleteSampler(int handle) { }
    }
}

package com.overlord.renderer.pass;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.overlord.assets.ResourceLocation;
import com.overlord.renderer.AxisAlignedBounds;
import com.overlord.renderer.ChunkGpuMesh;
import com.overlord.renderer.ChunkRenderObject;
import com.overlord.renderer.TextureBinding;
import com.overlord.renderer.metrics.RenderMetricsRecorder;
import com.overlord.renderer.material.Material;
import com.overlord.renderer.material.MaterialDefinition;
import com.overlord.renderer.material.RenderType;
import com.overlord.renderer.queue.RenderQueue;
import com.overlord.renderer.shader.ShaderBinding;
import com.overlord.renderer.state.BlendMode;
import com.overlord.renderer.state.RenderStateBackend;
import com.overlord.renderer.state.RenderStateSnapshot;
import com.overlord.renderer.state.RenderStateSpec;
import com.overlord.renderer.visual.GammaPath;
import com.overlord.renderer.visual.LinearColor;
import com.overlord.renderer.visual.RenderVisualSettings;
import com.overlord.voxel.ChunkKey;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.junit.jupiter.api.Test;

class WorldRenderPassTest {
    private static final RenderStateSpec OPAQUE =
            new RenderStateSpec(true, true, BlendMode.DISABLED, false);
    private static final RenderStateSpec TRANSPARENT =
            new RenderStateSpec(true, false, BlendMode.ALPHA, false);

    @Test
    void drawsOpaqueBeforeTransparentWithTheRequiredBindingsUniformsAndStates() {
        List<String> calls = new ArrayList<>();
        FakeRenderStateBackend backend = new FakeRenderStateBackend(calls);
        RenderQueue queue = new RenderQueue();
        FakeMesh opaqueMesh = new FakeMesh("opaque", calls, null);
        FakeMesh transparentMesh = new FakeMesh("transparent", calls, null);
        FakeShader opaqueShader = new FakeShader("opaque", calls);
        FakeShader transparentShader = new FakeShader("transparent", calls);
        queue.submit(object(0, opaqueMesh), material(RenderType.OPAQUE, "opaque", opaqueShader, calls));
        queue.submit(object(1, transparentMesh), material(RenderType.TRANSPARENT, "transparent", transparentShader, calls));
        RenderVisualSettings visualSettings = visualSettings();
        RenderContext context = new RenderContext(
                new Matrix4f().translation(1, 2, 3),
                new Matrix4f().translation(4, 5, 6),
                visualSettings);

        new WorldRenderPass(backend).render(context, queue);

        assertEquals(
                List.of(
                        "capture", "apply:" + OPAQUE,
                        "opaque.shader.use", "opaque.texture.0",
                        "opaque.uniform.projection", "opaque.uniform.view", "opaque.uniform.model",
                        "opaque.uniform.textureAtlas=0",
                        "opaque.uniform.sunDirection",
                        "opaque.uniform.ambientStrength=0.23",
                        "opaque.uniform.directionalStrength=0.61",
                        "opaque.uniform.fogColor",
                        "opaque.uniform.fogStart=12.5",
                        "opaque.uniform.fogEnd=44.5",
                        "opaque.draw", "restore",
                        "capture", "apply:" + TRANSPARENT,
                        "transparent.shader.use", "transparent.texture.0",
                        "transparent.uniform.projection", "transparent.uniform.view", "transparent.uniform.model",
                        "transparent.uniform.textureAtlas=0",
                        "transparent.uniform.sunDirection",
                        "transparent.uniform.ambientStrength=0.23",
                        "transparent.uniform.directionalStrength=0.61",
                        "transparent.uniform.fogColor",
                        "transparent.uniform.fogStart=12.5",
                        "transparent.uniform.fogEnd=44.5",
                        "transparent.draw", "restore"),
                calls);
        assertEquals(1.0f, opaqueShader.matrix("projection").m30());
        assertEquals(4.0f, opaqueShader.matrix("view").m30());
        assertEquals(0.0f, opaqueShader.matrix("model").m30());
        assertEquals(16.0f, transparentShader.matrix("model").m30());
        for (FakeShader shader : List.of(opaqueShader, transparentShader)) {
            assertEquals(visualSettings.sunDirection(), shader.vector("sunDirection"));
            assertEquals(visualSettings.ambientStrength(), shader.scalar("ambientStrength"));
            assertEquals(
                    visualSettings.directionalStrength(),
                    shader.scalar("directionalStrength"));
            assertEquals(
                    new Vector3f(0.12f, 0.34f, 0.56f),
                    shader.vector("fogColor"));
            assertEquals(visualSettings.fogStart(), shader.scalar("fogStart"));
            assertEquals(visualSettings.fogEnd(), shader.scalar("fogEnd"));
        }
        assertEquals(1, Collections.frequency(calls, "opaque.draw"));
        assertEquals(1, Collections.frequency(calls, "transparent.draw"));
    }

    @Test
    void restoresIncomingStateWhenDrawingFails() {
        List<String> calls = new ArrayList<>();
        FakeRenderStateBackend backend = new FakeRenderStateBackend(calls);
        IllegalStateException expected = new IllegalStateException("draw failed");
        RenderQueue queue = new RenderQueue();
        queue.submit(
                object(0, new FakeMesh("opaque", calls, expected)),
                material(RenderType.OPAQUE, "opaque", new FakeShader("opaque", calls), calls));

        IllegalStateException escaped = assertThrows(
                IllegalStateException.class,
                () -> new WorldRenderPass(backend).render(
                        new RenderContext(new Matrix4f(), new Matrix4f()), queue));

        assertSame(expected, escaped);
        assertEquals("restore", calls.get(calls.size() - 1));
    }

    private static ChunkRenderObject object(int x, ChunkGpuMesh mesh) {
        return new ChunkRenderObject(
                new ChunkKey(x, 0), 1, mesh, new AxisAlignedBounds(0, 0, 0, 1, 1, 1));
    }

    @Test
    void recordsOnlySuccessfulMeshDraws() {
        List<Long> triangles = new ArrayList<>();
        RenderQueue queue = new RenderQueue();
        queue.submit(
                object(0, new FakeMesh("ok", new ArrayList<>(), null)),
                material(RenderType.OPAQUE, "ok", new FakeShader("ok", new ArrayList<>()), new ArrayList<>()));
        new WorldRenderPass(new FakeRenderStateBackend(new ArrayList<>())).render(
                new RenderContext(new Matrix4f(), new Matrix4f(), visualSettings(), triangles::add), queue);
        assertEquals(List.of(1L), triangles);
    }

    private static RenderVisualSettings visualSettings() {
        return new RenderVisualSettings(
                new Vector3f(2.0f, 3.0f, 4.0f),
                0.23f,
                0.61f,
                new LinearColor(0.01f, 0.02f, 0.03f),
                new LinearColor(0.04f, 0.05f, 0.06f),
                new LinearColor(0.12f, 0.34f, 0.56f),
                12.5f,
                44.5f,
                GammaPath.SHADER_SRGB_DECODE_ENCODE);
    }

    private static Material material(
            RenderType type, String name, ShaderBinding shader, List<String> calls) {
        return new Material(
                new MaterialDefinition(
                        ResourceLocation.parse("test:" + name), ResourceLocation.parse("test:atlas"),
                        type, 0.5f, ResourceLocation.parse("test:missing")),
                shader,
                textureUnit -> calls.add(name + ".texture." + textureUnit));
    }

    private static final class FakeMesh implements ChunkGpuMesh {
        private final String name;
        private final List<String> calls;
        private final RuntimeException failure;
        private FakeMesh(String name, List<String> calls, RuntimeException failure) {
            this.name = name; this.calls = calls; this.failure = failure;
        }
        @Override public int vertexCount() { return 3; }
        @Override public void draw() {
            calls.add(name + ".draw");
            if (failure != null) throw failure;
        }
        @Override public void cleanup() {}
    }

    private static final class FakeShader implements ShaderBinding {
        private final String name;
        private final List<String> calls;
        private final Map<String, Matrix4f> matrices = new HashMap<>();
        private final Map<String, Float> scalars = new HashMap<>();
        private final Map<String, Vector3f> vectors = new HashMap<>();
        private FakeShader(String name, List<String> calls) { this.name = name; this.calls = calls; }
        Matrix4f matrix(String uniform) { return matrices.get(uniform); }
        float scalar(String uniform) { return scalars.get(uniform); }
        Vector3f vector(String uniform) { return vectors.get(uniform); }
        @Override public int programId() { return 0; }
        @Override public void use() { calls.add(name + ".shader.use"); }
        @Override public void setMatrix4(String uniform, Matrix4fc value) {
            matrices.put(uniform, new Matrix4f(value));
            calls.add(name + ".uniform." + uniform);
        }
        @Override public void setInt(String uniform, int value) {
            calls.add(name + ".uniform." + uniform + "=" + value);
        }
        @Override public void setFloat(String uniform, float value) {
            scalars.put(uniform, value);
            calls.add(name + ".uniform." + uniform + "=" + value);
        }
        @Override public void setVector3(String uniform, Vector3fc value) {
            vectors.put(uniform, new Vector3f(value));
            calls.add(name + ".uniform." + uniform);
        }
    }

    private static final class FakeRenderStateBackend implements RenderStateBackend {
        private final List<String> calls;
        private FakeRenderStateBackend(List<String> calls) { this.calls = calls; }
        @Override public RenderStateSnapshot capture() {
            calls.add("capture");
            return new RenderStateSnapshot(false, false, false, 0, 0, 0, 0, 0, 0, false, 0, 0, 0);
        }
        @Override public void apply(RenderStateSpec state) { calls.add("apply:" + state); }
        @Override public void restore(RenderStateSnapshot snapshot) { calls.add("restore"); }
        @Override public void clearColorAndDepth() { calls.add("clear"); }
    }
}

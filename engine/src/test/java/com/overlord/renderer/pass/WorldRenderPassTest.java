package com.overlord.renderer.pass;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.overlord.assets.ResourceLocation;
import com.overlord.renderer.AxisAlignedBounds;
import com.overlord.renderer.ChunkGpuMesh;
import com.overlord.renderer.ChunkRenderObject;
import com.overlord.renderer.TextureBinding;
import com.overlord.renderer.material.Material;
import com.overlord.renderer.material.MaterialDefinition;
import com.overlord.renderer.material.RenderType;
import com.overlord.renderer.queue.RenderQueue;
import com.overlord.renderer.shader.ShaderBinding;
import com.overlord.renderer.state.BlendMode;
import com.overlord.renderer.state.RenderStateBackend;
import com.overlord.renderer.state.RenderStateSnapshot;
import com.overlord.renderer.state.RenderStateSpec;
import com.overlord.voxel.ChunkKey;
import java.util.ArrayList;
import java.util.List;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
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
        RenderContext context = new RenderContext(
                new Matrix4f().translation(1, 2, 3), new Matrix4f().translation(4, 5, 6));

        new WorldRenderPass(backend).render(context, queue);

        assertEquals(
                List.of(
                        "capture", "apply:" + OPAQUE,
                        "opaque.shader.use", "opaque.texture.0",
                        "opaque.uniform.projection", "opaque.uniform.view", "opaque.uniform.model",
                        "opaque.uniform.textureAtlas=0", "opaque.draw", "restore",
                        "capture", "apply:" + TRANSPARENT,
                        "transparent.shader.use", "transparent.texture.0",
                        "transparent.uniform.projection", "transparent.uniform.view", "transparent.uniform.model",
                        "transparent.uniform.textureAtlas=0", "transparent.draw", "restore"),
                calls);
        assertEquals(1.0f, opaqueShader.matrix("projection").m30());
        assertEquals(4.0f, opaqueShader.matrix("view").m30());
        assertEquals(0.0f, opaqueShader.matrix("model").m30());
        assertEquals(16.0f, transparentShader.matrix("model").m30());
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
        private final java.util.Map<String, Matrix4f> matrices = new java.util.HashMap<>();
        private FakeShader(String name, List<String> calls) { this.name = name; this.calls = calls; }
        Matrix4f matrix(String uniform) { return matrices.get(uniform); }
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
            calls.add(name + ".uniform." + uniform + "=" + value);
        }
        @Override public void setVector3(String uniform, Vector3fc value) {
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

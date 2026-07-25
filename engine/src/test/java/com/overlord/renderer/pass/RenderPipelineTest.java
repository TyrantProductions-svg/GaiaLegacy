package com.overlord.renderer.pass;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.overlord.assets.ResourceLocation;
import com.overlord.renderer.AxisAlignedBounds;
import com.overlord.renderer.ChunkGpuMesh;
import com.overlord.renderer.ChunkRenderObject;
import com.overlord.renderer.material.Material;
import com.overlord.renderer.material.MaterialDefinition;
import com.overlord.renderer.material.RenderType;
import com.overlord.renderer.queue.RenderQueue;
import com.overlord.renderer.shader.ShaderBinding;
import com.overlord.voxel.ChunkKey;
import java.util.ArrayList;
import java.util.List;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3fc;
import org.junit.jupiter.api.Test;

class RenderPipelineTest {
    @Test
    void rendersPassesInConfiguredOrderAndClearsQueue() {
        List<String> calls = new ArrayList<>();
        RenderPipeline pipeline = new RenderPipeline(List.of(
                pass("sky", calls), pass("world", calls), pass("debug", calls)));
        RenderQueue queue = queueWithItem();
        assertFalse(queue.isEmpty());

        pipeline.render(context(), queue);

        assertEquals(List.of("sky", "world", "debug"), calls);
        assertEquals(List.of("sky", "world", "debug"), pipeline.passIds());
        assertTrue(queue.isEmpty());
    }

    @Test
    void clearsQueueAndStopsAtTheSamePassFailure() {
        List<String> calls = new ArrayList<>();
        IllegalStateException expected = new IllegalStateException("world failed");
        RenderPipeline pipeline = new RenderPipeline(List.of(
                pass("sky", calls), failingPass("world", calls, expected), pass("debug", calls)));
        RenderQueue queue = queueWithItem();
        assertFalse(queue.isEmpty());

        IllegalStateException escaped = assertThrows(
                IllegalStateException.class, () -> pipeline.render(context(), queue));

        assertSame(expected, escaped);
        assertEquals(List.of("sky", "world"), calls);
        assertTrue(queue.isEmpty());
    }

    @Test
    void rejectsDuplicatePassIds() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new RenderPipeline(List.of(pass("world", new ArrayList<>()),
                        pass("world", new ArrayList<>()))));
    }

    @Test
    void renderContextCopiesInputsAndReturnedMatrices() {
        Matrix4f projection = new Matrix4f().translation(1, 2, 3);
        Matrix4f view = new Matrix4f().translation(4, 5, 6);
        RenderContext context = new RenderContext(projection, view);
        projection.translate(10, 0, 0);
        view.translate(20, 0, 0);

        assertEquals(1.0f, context.projection().m30());
        assertEquals(4.0f, context.view().m30());
        context.projection().translate(30, 0, 0);
        context.view().translate(40, 0, 0);
        assertEquals(1.0f, context.projection().m30());
        assertEquals(4.0f, context.view().m30());
    }

    private static RenderContext context() {
        return new RenderContext(new Matrix4f(), new Matrix4f());
    }

    private static RenderQueue queueWithItem() {
        RenderQueue queue = new RenderQueue();
        queue.submit(
                new ChunkRenderObject(
                        new ChunkKey(0, 0),
                        1,
                        new FakeMesh(),
                        new AxisAlignedBounds(0, 0, 0, 1, 1, 1)),
                new Material(
                        new MaterialDefinition(
                                ResourceLocation.parse("test:queued"),
                                ResourceLocation.parse("test:atlas"),
                                RenderType.OPAQUE,
                                0.5f,
                                ResourceLocation.parse("test:missing")),
                        new FakeShader(),
                        textureUnit -> {}));
        return queue;
    }

    private static RenderPass pass(String id, List<String> calls) {
        return new RenderPass() {
            @Override public String id() { return id; }
            @Override public void render(RenderContext context, RenderQueue queue) { calls.add(id); }
        };
    }

    private static RenderPass failingPass(String id, List<String> calls, RuntimeException failure) {
        return new RenderPass() {
            @Override public String id() { return id; }
            @Override public void render(RenderContext context, RenderQueue queue) {
                calls.add(id);
                throw failure;
            }
        };
    }

    private static final class FakeMesh implements ChunkGpuMesh {
        @Override public int vertexCount() { return 3; }
        @Override public void draw() {}
        @Override public void cleanup() {}
    }

    private static final class FakeShader implements ShaderBinding {
        @Override public int programId() { return 0; }
        @Override public void use() {}
        @Override public void setMatrix4(String uniform, Matrix4fc value) {}
        @Override public void setInt(String uniform, int value) {}
        @Override public void setFloat(String uniform, float value) {}
        @Override public void setVector3(String uniform, Vector3fc value) {}
    }
}

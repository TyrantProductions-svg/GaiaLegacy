package com.overlord.renderer.queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.overlord.assets.ResourceLocation;
import com.overlord.renderer.AxisAlignedBounds;
import com.overlord.renderer.ChunkGpuMesh;
import com.overlord.renderer.ChunkRenderObject;
import com.overlord.renderer.TextureBinding;
import com.overlord.renderer.material.Material;
import com.overlord.renderer.material.MaterialDefinition;
import com.overlord.renderer.material.RenderType;
import com.overlord.renderer.shader.ShaderBinding;
import com.overlord.voxel.ChunkKey;
import java.util.List;
import org.joml.Matrix4fc;
import org.joml.Vector3fc;
import org.junit.jupiter.api.Test;

class RenderQueueTest {
    @Test
    void routesOpaqueAndCutoutInSubmissionOrderAndTransparentSeparately() {
        RenderQueue queue = new RenderQueue();
        Material opaque = material(RenderType.OPAQUE, "opaque");
        Material cutout = material(RenderType.CUTOUT, "cutout");
        Material transparent = material(RenderType.TRANSPARENT, "transparent");

        queue.submit(object(0), opaque);
        queue.submit(object(1), cutout);
        queue.submit(object(2), transparent);

        assertEquals(
                List.of("opaque", "cutout"),
                queue.opaqueItems().stream()
                        .map(item -> item.material().definition().id().path())
                        .toList());
        assertEquals(
                List.of("transparent"),
                queue.transparentItems().stream()
                        .map(item -> item.material().definition().id().path())
                        .toList());
    }

    @Test
    void returnsImmutableSnapshotsAndClearEmptiesBothQueues() {
        RenderQueue queue = new RenderQueue();
        queue.submit(object(0), material(RenderType.OPAQUE, "opaque"));
        queue.submit(object(1), material(RenderType.TRANSPARENT, "transparent"));

        List<RenderItem> opaqueItems = queue.opaqueItems();
        List<RenderItem> transparentItems = queue.transparentItems();

        assertThrows(UnsupportedOperationException.class, opaqueItems::clear);
        assertThrows(UnsupportedOperationException.class, transparentItems::clear);
        queue.clear();

        assertTrue(queue.isEmpty());
        assertTrue(queue.opaqueItems().isEmpty());
        assertTrue(queue.transparentItems().isEmpty());
        assertFalse(opaqueItems.isEmpty());
        assertFalse(transparentItems.isEmpty());
    }

    private static ChunkRenderObject object(int x) {
        return new ChunkRenderObject(
                new ChunkKey(x, 0),
                1,
                new FakeMesh(),
                new AxisAlignedBounds(0, 0, 0, 1, 1, 1));
    }

    private static Material material(RenderType renderType, String path) {
        return new Material(
                new MaterialDefinition(
                        ResourceLocation.parse("test:" + path),
                        ResourceLocation.parse("test:atlas"),
                        renderType,
                        0.5f,
                        ResourceLocation.parse("test:missing")),
                new FakeShader(),
                textureUnit -> {});
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

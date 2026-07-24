package com.overlord.renderer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.overlord.voxel.ChunkKey;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

class ChunkRenderObjectTest {
    @Test
    void computesDefensiveWorldTransformAndBounds() {
        FakeGpuMesh mesh = new FakeGpuMesh(36);
        ChunkRenderObject object =
                new ChunkRenderObject(
                        new ChunkKey(2, -1),
                        8,
                        mesh,
                        new AxisAlignedBounds(0, 1, 0, 16, 5, 16));

        assertEquals(
                new AxisAlignedBounds(32, 1, -16, 48, 5, 0),
                object.worldBounds());
        assertEquals(32.0f, object.modelMatrix().m30());
        assertEquals(-16.0f, object.modelMatrix().m32());
        Matrix4f changed = object.modelMatrix().translate(100, 0, 0);
        assertEquals(132.0f, changed.m30());
        assertEquals(32.0f, object.modelMatrix().m30());
        assertSame(mesh, object.mesh());
        assertEquals(new ChunkKey(2, -1), object.key());
        assertEquals(8, object.revision());
        assertEquals(0, mesh.drawCalls);
        assertEquals(0, mesh.cleanupCalls);
    }

    @Test
    void rejectsNullRenderObjectValues() {
        FakeGpuMesh mesh = new FakeGpuMesh(1);
        AxisAlignedBounds bounds = new AxisAlignedBounds(0, 0, 0, 1, 1, 1);

        assertThrows(
                NullPointerException.class,
                () -> new ChunkRenderObject(null, 0, mesh, bounds));
        assertThrows(
                NullPointerException.class,
                () -> new ChunkRenderObject(new ChunkKey(0, 0), 0, null, bounds));
        assertThrows(
                NullPointerException.class,
                () -> new ChunkRenderObject(new ChunkKey(0, 0), 0, mesh, null));
    }

    @Test
    void rejectsNegativeRevisions() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new ChunkRenderObject(
                        new ChunkKey(0, 0),
                        -1,
                        new FakeGpuMesh(1),
                        new AxisAlignedBounds(0, 0, 0, 1, 1, 1)));
    }

    @Test
    void rejectsGpuMeshesWithoutPositiveVertexCounts() {
        AxisAlignedBounds bounds = new AxisAlignedBounds(0, 0, 0, 1, 1, 1);

        assertThrows(
                IllegalArgumentException.class,
                () -> new ChunkRenderObject(
                        new ChunkKey(0, 0), 0, new FakeGpuMesh(0), bounds));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ChunkRenderObject(
                        new ChunkKey(0, 0), 0, new FakeGpuMesh(-1), bounds));
    }

    private static final class FakeGpuMesh implements ChunkGpuMesh {
        private final int vertexCount;
        private int drawCalls;
        private int cleanupCalls;

        private FakeGpuMesh(int vertexCount) {
            this.vertexCount = vertexCount;
        }

        @Override
        public int vertexCount() {
            return vertexCount;
        }

        @Override
        public void draw() {
            drawCalls++;
        }

        @Override
        public void cleanup() {
            cleanupCalls++;
        }
    }
}

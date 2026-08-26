package com.overlord.renderer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.overlord.voxel.ChunkKey;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

class RenderOriginTest {
    @Test
    void originRelativeReplacementForPositiveLargeKeyPreservesCanonicalMeshAndRevision() {
        FakeGpuMesh mesh = new FakeGpuMesh(36);
        ChunkRenderObject object =
                new ChunkRenderObject(
                        new ChunkKey(100_000_001, 100_000_000),
                        42,
                        mesh,
                        new AxisAlignedBounds(0, 0, 0, 16, 8, 16));
        Matrix4f originalModel = object.modelMatrix();
        AxisAlignedBounds originalBounds = object.worldBounds();

        ChunkRenderObject rebased =
                object.forOrigin(new RenderOrigin(new ChunkKey(100_000_000, 100_000_000)));

        assertEquals(new ChunkKey(100_000_001, 100_000_000), object.key());
        assertEquals(42, object.revision());
        assertSame(mesh, object.mesh());
        assertEquals(originalModel, object.modelMatrix());
        assertEquals(originalBounds, object.worldBounds());
        assertEquals(new ChunkKey(100_000_001, 100_000_000), rebased.key());
        assertEquals(42, rebased.revision());
        assertSame(mesh, rebased.mesh());
        assertEquals(16.0f, rebased.modelMatrix().m30());
        assertEquals(0.0f, rebased.modelMatrix().m32());
        assertEquals(new AxisAlignedBounds(16, 0, 0, 32, 8, 16), rebased.worldBounds());
        assertFinite(rebased);
    }

    @Test
    void originRelativeReplacementForNegativeLargeKeyKeepsBoundsSmallAndFinite() {
        FakeGpuMesh mesh = new FakeGpuMesh(6);
        ChunkRenderObject object =
                new ChunkRenderObject(
                        new ChunkKey(-100_000_001, -100_000_002),
                        9,
                        mesh,
                        new AxisAlignedBounds(0, -2, 0, 16, 6, 16));
        Matrix4f originalModel = object.modelMatrix();
        AxisAlignedBounds originalBounds = object.worldBounds();

        ChunkRenderObject rebased =
                object.forOrigin(new RenderOrigin(new ChunkKey(-100_000_000, -100_000_000)));

        assertEquals(new ChunkKey(-100_000_001, -100_000_002), object.key());
        assertEquals(9, object.revision());
        assertSame(mesh, object.mesh());
        assertEquals(originalModel, object.modelMatrix());
        assertEquals(originalBounds, object.worldBounds());
        assertSame(mesh, rebased.mesh());
        assertEquals(new ChunkKey(-100_000_001, -100_000_002), rebased.key());
        assertEquals(9, rebased.revision());
        assertEquals(-16.0f, rebased.modelMatrix().m30());
        assertEquals(-32.0f, rebased.modelMatrix().m32());
        assertEquals(new AxisAlignedBounds(-16, -2, -32, 0, 6, -16), rebased.worldBounds());
        assertFinite(rebased);
    }

    private static void assertFinite(ChunkRenderObject object) {
        AxisAlignedBounds bounds = object.worldBounds();
        assertFalse(Float.isInfinite(object.modelMatrix().m30()));
        assertFalse(Float.isInfinite(object.modelMatrix().m32()));
        assertFalse(Float.isNaN(bounds.minX()));
        assertFalse(Float.isNaN(bounds.maxZ()));
    }

    private static final class FakeGpuMesh implements ChunkGpuMesh {
        private final int vertexCount;

        private FakeGpuMesh(int vertexCount) {
            this.vertexCount = vertexCount;
        }

        @Override
        public int vertexCount() {
            return vertexCount;
        }

        @Override
        public void draw() {}

        @Override
        public void cleanup() {}
    }
}

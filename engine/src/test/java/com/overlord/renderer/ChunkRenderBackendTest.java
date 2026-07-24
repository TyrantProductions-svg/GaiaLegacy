package com.overlord.renderer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.overlord.core.thread.MainThreadGuard;
import com.overlord.voxel.ChunkKey;
import com.overlord.voxel.ChunkMeshData;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class ChunkRenderBackendTest {
    @Test
    void rendererImplementsChunkRenderBackend() {
        assertTrue(ChunkRenderBackend.class.isAssignableFrom(Renderer.class));
    }

    @Test
    void uploadRejectsNullAndEmptyDataWithoutOpenGl() {
        Renderer renderer =
                new Renderer(
                        MainThreadGuard.captureCurrentThread(),
                        RenderAssets.missing());

        assertThrows(NullPointerException.class, () -> renderer.upload(null));
        assertThrows(
                IllegalArgumentException.class,
                () -> renderer.upload(
                        new ChunkMeshData(
                                new ChunkKey(0, 0), 1, new float[0])));
    }

    @Test
    void uploadRejectsWorkerBeforeOpenGl() throws InterruptedException {
        Renderer renderer =
                new Renderer(
                        MainThreadGuard.captureCurrentThread(),
                        RenderAssets.missing());

        assertWorkerRejected(() -> renderer.upload(nonEmptyData()));
    }

    @Test
    void releaseRejectsWorkerBeforeOpenGl() throws InterruptedException {
        Renderer renderer =
                new Renderer(
                        MainThreadGuard.captureCurrentThread(),
                        RenderAssets.missing());
        FakeChunkGpuMesh mesh = new FakeChunkGpuMesh(1);
        ChunkRenderObject object =
                new ChunkRenderObject(
                        new ChunkKey(2, -1),
                        7,
                        mesh,
                        new AxisAlignedBounds(0, 0, 0, 1, 1, 1));

        assertWorkerRejected(() -> renderer.release(object));
        assertEquals(0, mesh.cleanupCalls);
    }

    @Test
    void renderChunksRejectsWorkerBeforeOpenGl() throws InterruptedException {
        Renderer renderer =
                new Renderer(
                        MainThreadGuard.captureCurrentThread(),
                        RenderAssets.missing());

        assertWorkerRejected(() -> renderer.renderChunks(List.of()));
    }

    @Test
    void releaseCleansExactlyTheObjectMeshOnMainThread() {
        Renderer renderer =
                new Renderer(
                        MainThreadGuard.captureCurrentThread(),
                        RenderAssets.missing());
        FakeChunkGpuMesh releasedMesh = new FakeChunkGpuMesh(1);
        FakeChunkGpuMesh untouchedMesh = new FakeChunkGpuMesh(1);
        ChunkRenderObject object =
                new ChunkRenderObject(
                        new ChunkKey(0, 0),
                        4,
                        releasedMesh,
                        new AxisAlignedBounds(0, 0, 0, 1, 1, 1));

        renderer.release(object);

        assertEquals(1, releasedMesh.cleanupCalls);
        assertEquals(0, untouchedMesh.cleanupCalls);
    }

    private static ChunkMeshData nonEmptyData() {
        return new ChunkMeshData(
                new ChunkKey(3, -2),
                9,
                new float[] {
                    0.0f, 0.0f, 0.0f, 0.0f, 0.0f,
                    1.0f, 0.0f, 0.0f, 1.0f, 0.0f,
                    0.0f, 1.0f, 0.0f, 0.0f, 1.0f
                });
    }

    private static void assertWorkerRejected(Runnable operation)
            throws InterruptedException {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            Future<?> call = worker.submit(operation);
            ExecutionException failure =
                    assertThrows(
                            ExecutionException.class,
                            () -> call.get(5, TimeUnit.SECONDS));

            assertInstanceOf(IllegalStateException.class, failure.getCause());
        } finally {
            worker.shutdownNow();
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private static final class FakeChunkGpuMesh implements ChunkGpuMesh {
        private final int vertexCount;
        private int cleanupCalls;

        private FakeChunkGpuMesh(int vertexCount) {
            this.vertexCount = vertexCount;
        }

        @Override
        public int vertexCount() {
            return vertexCount;
        }

        @Override
        public void draw() {}

        @Override
        public void cleanup() {
            cleanupCalls++;
        }
    }
}

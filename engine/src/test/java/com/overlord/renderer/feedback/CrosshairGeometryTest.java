package com.overlord.renderer.feedback;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.overlord.core.thread.MainThreadGuard;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

class CrosshairGeometryTest {
    @Test
    void createsExactSixteenPixelCrosshairAtEvenFramebufferCenter() {
        assertEquals(
                List.of(
                        new ScreenQuad(504.0f, 383.0f, 510.0f, 385.0f),
                        new ScreenQuad(514.0f, 383.0f, 520.0f, 385.0f),
                        new ScreenQuad(511.0f, 376.0f, 513.0f, 382.0f),
                        new ScreenQuad(511.0f, 386.0f, 513.0f, 392.0f)),
                CrosshairGeometry.quads(1024, 768));
    }

    @Test
    void preservesHalfPixelCenterForOddFramebufferDimensions() {
        assertEquals(
                List.of(
                        new ScreenQuad(492.5f, 349.5f, 498.5f, 351.5f),
                        new ScreenQuad(502.5f, 349.5f, 508.5f, 351.5f),
                        new ScreenQuad(499.5f, 342.5f, 501.5f, 348.5f),
                        new ScreenQuad(499.5f, 352.5f, 501.5f, 358.5f)),
                CrosshairGeometry.quads(1001, 701));
    }

    @Test
    void screenQuadBatchExpandsQuadsDrawsOnceAndCleansUpInReverseOrder() {
        RecordingScreenQuadBackend backend = new RecordingScreenQuadBackend();
        OpenGlScreenQuadBatch batch =
                new OpenGlScreenQuadBatch(MainThreadGuard.captureCurrentThread(), backend);
        ScreenQuad quad = new ScreenQuad(1.0f, 2.0f, 4.0f, 6.0f);

        batch.upload(List.of(quad));
        batch.draw();
        batch.cleanup();
        batch.cleanup();

        assertEquals(
                List.of(
                        1.0f, 2.0f,
                        4.0f, 2.0f,
                        4.0f, 6.0f,
                        1.0f, 2.0f,
                        4.0f, 6.0f,
                        1.0f, 6.0f),
                backend.uploadedValues);
        assertEquals(List.of(6), backend.drawVertexCounts);
        assertEquals(List.of("buffer:41", "vao:31"), backend.deletions);
    }

    @Test
    void screenQuadBatchGuardsAllGpuOperations() throws Exception {
        MainThreadGuard guard = MainThreadGuard.captureCurrentThread();
        RecordingScreenQuadBackend backend = new RecordingScreenQuadBackend();
        OpenGlScreenQuadBatch batch = new OpenGlScreenQuadBatch(guard, backend);
        int callsAfterCreation = backend.calls;
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            ExecutionException uploadFailure =
                    assertThrows(
                            ExecutionException.class,
                            () ->
                                    worker.submit(
                                                    () ->
                                                            batch.upload(
                                                                    List.of(
                                                                            new ScreenQuad(
                                                                                    0.0f,
                                                                                    0.0f,
                                                                                    1.0f,
                                                                                    1.0f))))
                                            .get());
            assertInstanceOf(IllegalStateException.class, uploadFailure.getCause());
            ExecutionException drawFailure =
                    assertThrows(
                            ExecutionException.class,
                            () -> worker.submit(batch::draw).get());
            assertInstanceOf(IllegalStateException.class, drawFailure.getCause());
            ExecutionException cleanupFailure =
                    assertThrows(
                            ExecutionException.class,
                            () -> worker.submit(batch::cleanup).get());
            assertInstanceOf(IllegalStateException.class, cleanupFailure.getCause());
            assertEquals(callsAfterCreation, backend.calls);
        } finally {
            worker.shutdownNow();
            batch.cleanup();
        }
    }

    private static final class RecordingScreenQuadBackend
            implements ScreenQuadBatchBackend {
        private int calls;
        private final List<Float> uploadedValues = new ArrayList<>();
        private final List<Integer> drawVertexCounts = new ArrayList<>();
        private final List<String> deletions = new ArrayList<>();

        @Override
        public int createVertexArray() {
            calls++;
            return 31;
        }

        @Override
        public int createBuffer() {
            calls++;
            return 41;
        }

        @Override
        public void bindVertexArray(int vertexArray) {
            calls++;
        }

        @Override
        public void bindArrayBuffer(int buffer) {
            calls++;
        }

        @Override
        public void configurePositionAttribute() {
            calls++;
        }

        @Override
        public void upload(float[] vertices) {
            calls++;
            for (float vertex : vertices) {
                uploadedValues.add(vertex);
            }
        }

        @Override
        public void drawTriangles(int vertexCount) {
            calls++;
            drawVertexCounts.add(vertexCount);
        }

        @Override
        public void deleteBuffer(int buffer) {
            calls++;
            deletions.add("buffer:" + buffer);
        }

        @Override
        public void deleteVertexArray(int vertexArray) {
            calls++;
            deletions.add("vao:" + vertexArray);
        }
    }
}

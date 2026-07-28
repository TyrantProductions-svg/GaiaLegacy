package com.overlord.renderer.feedback;

import static org.lwjgl.opengl.GL15C.GL_STREAM_DRAW;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.overlord.assets.ResourceLocation;
import com.overlord.core.thread.MainThreadGuard;
import com.overlord.renderer.particle.ParticleCategory;
import com.overlord.renderer.texture.TextureRegion;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class OpenGlFeedbackResourceStructureTest {
    private static final TextureRegion REGION =
            new TextureRegion(new ResourceLocation("gaia", "stone"), 16, 0, 16, 16, 128, 16);

    @Test
    void expandsWholeImmutableBatchIntoOneStreamUploadAndOneDraw() {
        RecordingBackend backend = new RecordingBackend();
        OpenGlStreamingTexturedCubeBatch batch =
                new OpenGlStreamingTexturedCubeBatch(
                        MainThreadGuard.captureCurrentThread(), backend);

        batch.upload(new ParticleRenderBatch(List.of(particle(1L, 1, 2, 3), particle(2L, -4, 5, -6))));
        batch.draw();

        assertEquals(1, backend.uploads.size());
        assertEquals(List.of(GL_STREAM_DRAW), backend.uploadUsages);
        assertEquals(2 * 36 * 5, backend.uploads.get(0).length);
        assertEquals(List.of(72), backend.drawVertexCounts);
        assertEquals(1.0f - 0.1f, backend.uploads.get(0)[0]);
        assertEquals(2.0f - 0.1f, backend.uploads.get(0)[1]);
        assertEquals(3.0f + 0.1f, backend.uploads.get(0)[2]);
        assertEquals(REGION.uMin(), backend.uploads.get(0)[3]);
        assertEquals(REGION.vMin(), backend.uploads.get(0)[4]);
    }

    @Test
    void emptyUploadPerformsNoGpuUploadAndDrawUsesZeroBackendCalls() {
        RecordingBackend backend = new RecordingBackend();
        OpenGlStreamingTexturedCubeBatch batch =
                new OpenGlStreamingTexturedCubeBatch(
                        MainThreadGuard.captureCurrentThread(), backend);
        int callsAfterConstruction = backend.totalCalls;

        batch.upload(new ParticleRenderBatch(List.of()));
        batch.draw();

        assertEquals(callsAfterConstruction, backend.totalCalls);
        assertTrue(backend.uploads.isEmpty());
        assertTrue(backend.drawVertexCounts.isEmpty());
    }

    @ParameterizedTest(name = "failure at {0}")
    @MethodSource("constructionFailures")
    void partialConstructionRollsBackExactlyOnceInReverseOrder(
            String step, List<String> expectedDeletions) {
        RecordingBackend backend = new RecordingBackend();
        IllegalStateException failure = new IllegalStateException(step + " failed");
        backend.failureStep = step;
        backend.stepFailure = failure;

        IllegalStateException escaped =
                assertThrows(
                        IllegalStateException.class,
                        () ->
                                new OpenGlStreamingTexturedCubeBatch(
                                        MainThreadGuard.captureCurrentThread(), backend));

        assertSame(failure, escaped);
        assertEquals(expectedDeletions, backend.deletions);
    }

    @Test
    void constructionCleanupFailuresAreSuppressedOntoPrimaryFailure() {
        RecordingBackend backend = new RecordingBackend();
        IllegalStateException primary = new IllegalStateException("configure failed");
        IllegalStateException bufferFailure = new IllegalStateException("buffer cleanup failed");
        IllegalStateException arrayFailure = new IllegalStateException("array cleanup failed");
        backend.failureStep = "configure";
        backend.stepFailure = primary;
        backend.deleteBufferFailure = bufferFailure;
        backend.deleteVertexArrayFailure = arrayFailure;

        IllegalStateException escaped =
                assertThrows(
                        IllegalStateException.class,
                        () ->
                                new OpenGlStreamingTexturedCubeBatch(
                                        MainThreadGuard.captureCurrentThread(), backend));

        assertSame(primary, escaped);
        assertEquals(List.of(bufferFailure, arrayFailure), List.of(escaped.getSuppressed()));
    }

    @Test
    void normalCleanupIsReverseOrderAndIdempotent() {
        RecordingBackend backend = new RecordingBackend();
        OpenGlStreamingTexturedCubeBatch batch =
                new OpenGlStreamingTexturedCubeBatch(
                        MainThreadGuard.captureCurrentThread(), backend);

        batch.cleanup();
        batch.cleanup();

        assertEquals(List.of("buffer:202", "vertex-array:101"), backend.deletions);
    }

    @Test
    void workerThreadCreationFailsBeforeBackendAccess() throws Exception {
        RecordingBackend backend = new RecordingBackend();
        MainThreadGuard guard = MainThreadGuard.captureCurrentThread();
        AtomicReference<Throwable> constructionFailure = new AtomicReference<>();

        Thread worker =
                new Thread(
                        () ->
                                capture(
                                        constructionFailure,
                                        () ->
                                                new OpenGlStreamingTexturedCubeBatch(
                                                        guard, backend)),
                        "particle-construction-test-worker");
        worker.start();
        worker.join();

        assertTrue(constructionFailure.get() instanceof IllegalStateException);
        assertEquals(0, backend.totalCalls);
    }

    @Test
    void workerThreadUploadDrawAndCleanupFailBeforeBackendAccess() throws Exception {
        RecordingBackend backend = new RecordingBackend();
        OpenGlStreamingTexturedCubeBatch batch =
                new OpenGlStreamingTexturedCubeBatch(
                        MainThreadGuard.captureCurrentThread(), backend);
        int callsAfterConstruction = backend.totalCalls;
        AtomicReference<Throwable> uploadFailure = new AtomicReference<>();
        AtomicReference<Throwable> drawFailure = new AtomicReference<>();
        AtomicReference<Throwable> cleanupFailure = new AtomicReference<>();

        Thread worker =
                new Thread(
                        () -> {
                            capture(
                                    uploadFailure,
                                    () -> batch.upload(new ParticleRenderBatch(List.of(particle(1L, 0, 0, 0)))));
                            capture(drawFailure, batch::draw);
                            capture(cleanupFailure, batch::cleanup);
                        },
                        "particle-test-worker");
        worker.start();
        worker.join();

        assertTrue(uploadFailure.get() instanceof IllegalStateException);
        assertTrue(drawFailure.get() instanceof IllegalStateException);
        assertTrue(cleanupFailure.get() instanceof IllegalStateException);
        assertEquals(callsAfterConstruction, backend.totalCalls);
        batch.cleanup();
    }

    @Test
    void particleShadersUseExactGlsl410AndNoComputeOrStorageFacilities() throws Exception {
        String vertex = readResource("assets/overlord/shaders/feedback/particle.vert");
        String fragment = readResource("assets/overlord/shaders/feedback/particle.frag");

        assertTrue(vertex.startsWith("#version 410 core"));
        assertTrue(fragment.startsWith("#version 410 core"));
        assertTrue(vertex.contains("layout (location = 0) in vec3 aPosition"));
        assertTrue(vertex.contains("layout (location = 1) in vec2 aUv"));
        assertTrue(fragment.contains("uniform sampler2D blockAtlas"));
        for (String source : List.of(vertex, fragment)) {
            assertFalse(source.contains("#version 420"));
            assertFalse(source.contains("#version 430"));
            assertFalse(source.contains("compute"));
            assertFalse(source.contains("buffer "));
            assertFalse(source.contains("imageStore"));
        }
    }

    private static ParticleVisual particle(long sequence, float x, float y, float z) {
        return new ParticleVisual(
                x,
                y,
                z,
                0.2f,
                REGION,
                ParticleCategory.BREAK_COMMITTED,
                sequence);
    }

    private static void capture(AtomicReference<Throwable> target, Runnable action) {
        try {
            action.run();
        } catch (Throwable failure) {
            target.set(failure);
        }
    }

    private static String readResource(String path) throws Exception {
        try (InputStream input =
                OpenGlFeedbackResourceStructureTest.class
                        .getClassLoader()
                        .getResourceAsStream(path)) {
            assertNotNull(input, path);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static Stream<Arguments> constructionFailures() {
        List<String> both = List.of("buffer:202", "vertex-array:101");
        return Stream.of(
                Arguments.of("create-vertex-array", List.of()),
                Arguments.of("create-buffer", List.of("vertex-array:101")),
                Arguments.of("bind-vertex-array", both),
                Arguments.of("bind-array-buffer", both),
                Arguments.of("configure", both),
                Arguments.of("unbind-array-buffer", both),
                Arguments.of("unbind-vertex-array", both));
    }

    private static final class RecordingBackend implements StreamingTexturedCubeBatchBackend {
        private int totalCalls;
        private final List<float[]> uploads = new ArrayList<>();
        private final List<Integer> uploadUsages = new ArrayList<>();
        private final List<Integer> drawVertexCounts = new ArrayList<>();
        private final List<String> deletions = new ArrayList<>();
        private String failureStep;
        private RuntimeException stepFailure;
        private RuntimeException deleteBufferFailure;
        private RuntimeException deleteVertexArrayFailure;

        @Override
        public int createVertexArray() {
            totalCalls++;
            fail("create-vertex-array");
            return 101;
        }

        @Override
        public int createBuffer() {
            totalCalls++;
            fail("create-buffer");
            return 202;
        }

        @Override
        public void bindVertexArray(int vertexArray) {
            totalCalls++;
            fail(vertexArray == 0 ? "unbind-vertex-array" : "bind-vertex-array");
        }

        @Override
        public void bindArrayBuffer(int buffer) {
            totalCalls++;
            fail(buffer == 0 ? "unbind-array-buffer" : "bind-array-buffer");
        }

        @Override
        public void configureAttributes() {
            totalCalls++;
            fail("configure");
        }

        @Override
        public void upload(float[] vertices, int usage) {
            totalCalls++;
            uploads.add(vertices.clone());
            uploadUsages.add(usage);
            fail("upload");
        }

        @Override
        public void drawTriangles(int vertexCount) {
            totalCalls++;
            drawVertexCounts.add(vertexCount);
            fail("draw");
        }

        @Override
        public void deleteBuffer(int buffer) {
            totalCalls++;
            deletions.add("buffer:" + buffer);
            if (deleteBufferFailure != null) {
                throw deleteBufferFailure;
            }
        }

        @Override
        public void deleteVertexArray(int vertexArray) {
            totalCalls++;
            deletions.add("vertex-array:" + vertexArray);
            if (deleteVertexArrayFailure != null) {
                throw deleteVertexArrayFailure;
            }
        }

        private void fail(String step) {
            if (step.equals(failureStep)) {
                throw stepFailure;
            }
        }
    }
}

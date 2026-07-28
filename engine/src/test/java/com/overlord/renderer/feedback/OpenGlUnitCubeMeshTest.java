package com.overlord.renderer.feedback;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.overlord.core.thread.MainThreadGuard;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class OpenGlUnitCubeMeshTest {
    @Test
    void createsOneSharedCubeDrawsTwelveTrianglesAndCleansExactlyOnce() {
        RecordingBackend backend = new RecordingBackend();
        OpenGlUnitCubeMesh mesh =
                new OpenGlUnitCubeMesh(MainThreadGuard.captureCurrentThread(), backend);

        mesh.draw();
        mesh.cleanup();
        mesh.cleanup();

        assertEquals(1, backend.createVertexArrayCalls);
        assertEquals(1, backend.createBufferCalls);
        assertEquals(36 * 5, backend.uploadedVertices.length);
        assertEquals(1, backend.configureCalls);
        assertEquals(List.of(36), backend.drawCounts);
        assertEquals(List.of("buffer:202", "vertex-array:101"), backend.deletions);
    }

    @ParameterizedTest(name = "failure at {0}")
    @MethodSource("constructionFailureSteps")
    void everyConstructionFailureReleasesCreatedResourcesInReverseOrder(
            String failureStep, List<String> expectedDeletions) {
        RecordingBackend backend = new RecordingBackend();
        IllegalStateException failure = new IllegalStateException(failureStep + " failed");
        backend.failureStep = failureStep;
        backend.stepFailure = failure;

        IllegalStateException escaped =
                assertThrows(
                        IllegalStateException.class,
                        () ->
                                new OpenGlUnitCubeMesh(
                                        MainThreadGuard.captureCurrentThread(), backend));

        assertSame(failure, escaped);
        assertEquals(expectedDeletions, backend.deletions);
    }

    @Test
    void cleanupFailuresAreSuppressedOntoPrimaryConstructionFailure() {
        RecordingBackend backend = new RecordingBackend();
        IllegalStateException primary = new IllegalStateException("configure failed");
        IllegalStateException bufferCleanup = new IllegalStateException("buffer cleanup failed");
        IllegalStateException arrayCleanup = new IllegalStateException("array cleanup failed");
        backend.failureStep = "configure";
        backend.stepFailure = primary;
        backend.deleteBufferFailure = bufferCleanup;
        backend.deleteVertexArrayFailure = arrayCleanup;

        IllegalStateException escaped =
                assertThrows(
                        IllegalStateException.class,
                        () ->
                                new OpenGlUnitCubeMesh(
                                        MainThreadGuard.captureCurrentThread(), backend));

        assertSame(primary, escaped);
        assertEquals(List.of(bufferCleanup, arrayCleanup), List.of(escaped.getSuppressed()));
        assertEquals(List.of("buffer:202", "vertex-array:101"), backend.deletions);
    }

    @Test
    void workerThreadDrawAndCleanupFailBeforeBackendAccess() throws Exception {
        RecordingBackend backend = new RecordingBackend();
        OpenGlUnitCubeMesh mesh =
                new OpenGlUnitCubeMesh(MainThreadGuard.captureCurrentThread(), backend);
        int callsAfterConstruction = backend.totalCalls;

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            ExecutionException drawFailure =
                    assertThrows(ExecutionException.class, () -> executor.submit(mesh::draw).get());
            ExecutionException cleanupFailure =
                    assertThrows(
                            ExecutionException.class,
                            () -> executor.submit(mesh::cleanup).get());
            assertTrue(drawFailure.getCause() instanceof IllegalStateException);
            assertTrue(cleanupFailure.getCause() instanceof IllegalStateException);
        } finally {
            executor.shutdownNow();
        }

        assertEquals(callsAfterConstruction, backend.totalCalls);
        mesh.cleanup();
    }

    private static final class RecordingBackend implements UnitCubeMeshBackend {
        private int createVertexArrayCalls;
        private int createBufferCalls;
        private int configureCalls;
        private int totalCalls;
        private float[] uploadedVertices = new float[0];
        private final List<Integer> drawCounts = new ArrayList<>();
        private final List<String> deletions = new ArrayList<>();
        private String failureStep;
        private RuntimeException stepFailure;
        private RuntimeException deleteBufferFailure;
        private RuntimeException deleteVertexArrayFailure;

        @Override
        public int createVertexArray() {
            totalCalls++;
            createVertexArrayCalls++;
            fail("create-vertex-array");
            return 101;
        }

        @Override
        public int createBuffer() {
            totalCalls++;
            createBufferCalls++;
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
            configureCalls++;
            fail("configure");
        }

        @Override
        public void upload(float[] vertices) {
            totalCalls++;
            uploadedVertices = vertices.clone();
            fail("upload");
        }

        @Override
        public void drawTriangles(int vertexCount) {
            totalCalls++;
            drawCounts.add(vertexCount);
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

    private static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments>
            constructionFailureSteps() {
        List<String> both = List.of("buffer:202", "vertex-array:101");
        return java.util.stream.Stream.of(
                org.junit.jupiter.params.provider.Arguments.of(
                        "create-vertex-array", List.of()),
                org.junit.jupiter.params.provider.Arguments.of(
                        "create-buffer", List.of("vertex-array:101")),
                org.junit.jupiter.params.provider.Arguments.of("bind-vertex-array", both),
                org.junit.jupiter.params.provider.Arguments.of("bind-array-buffer", both),
                org.junit.jupiter.params.provider.Arguments.of("upload", both),
                org.junit.jupiter.params.provider.Arguments.of("configure", both),
                org.junit.jupiter.params.provider.Arguments.of("unbind-array-buffer", both),
                org.junit.jupiter.params.provider.Arguments.of("unbind-vertex-array", both));
    }
}

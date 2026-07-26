package com.overlord.renderer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.overlord.core.thread.MainThreadGuard;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

class FullscreenTriangleTest {
    @Test
    void backendExposesExactlyTheFourEmptyVaoOperations()
            throws NoSuchMethodException {
        assertEquals(
                4,
                FullscreenTriangleBackend.class
                        .getDeclaredMethods()
                        .length);
        assertEquals(
                int.class,
                FullscreenTriangleBackend.class
                        .getDeclaredMethod("createVertexArray")
                        .getReturnType());
        assertEquals(
                void.class,
                FullscreenTriangleBackend.class
                        .getDeclaredMethod("bindVertexArray", int.class)
                        .getReturnType());
        assertEquals(
                void.class,
                FullscreenTriangleBackend.class
                        .getDeclaredMethod(
                                "drawTriangles", int.class, int.class)
                        .getReturnType());
        assertEquals(
                void.class,
                FullscreenTriangleBackend.class
                        .getDeclaredMethod("deleteVertexArray", int.class)
                        .getReturnType());
    }

    @Test
    void createsOneEmptyVaoDrawsExactlyOneTriangleAndDeletesOnce() {
        RecordingBackend backend = new RecordingBackend();
        FullscreenTriangle triangle =
                new FullscreenTriangle(
                        MainThreadGuard.captureCurrentThread(), backend);

        assertEquals(1, backend.createCalls);
        assertEquals(List.of(73, 0), backend.boundVertexArrays);

        backend.boundVertexArrays.clear();
        triangle.draw();
        triangle.cleanup();
        triangle.cleanup();

        assertEquals(List.of(73, 0), backend.boundVertexArrays);
        assertEquals(List.of(new Draw(0, 3)), backend.draws);
        assertEquals(List.of(73), backend.deletedVertexArrays);
    }

    @Test
    void guardsConstructionDrawAndDeletionBeforeBackendCalls()
            throws Exception {
        MainThreadGuard guard = MainThreadGuard.captureCurrentThread();
        RecordingBackend constructionBackend = new RecordingBackend();

        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            ExecutionException constructionFailure =
                    assertThrows(
                            ExecutionException.class,
                            () ->
                                    worker.submit(
                                                    () ->
                                                            new FullscreenTriangle(
                                                                    guard,
                                                                    constructionBackend))
                                            .get());
            assertInstanceOf(
                    IllegalStateException.class,
                    constructionFailure.getCause());
            assertEquals(0, constructionBackend.createCalls);

            RecordingBackend backend = new RecordingBackend();
            FullscreenTriangle triangle =
                    new FullscreenTriangle(guard, backend);
            backend.boundVertexArrays.clear();

            ExecutionException drawFailure =
                    assertThrows(
                            ExecutionException.class,
                            () -> worker.submit(triangle::draw).get());
            assertInstanceOf(
                    IllegalStateException.class, drawFailure.getCause());
            assertEquals(List.of(), backend.boundVertexArrays);
            assertEquals(List.of(), backend.draws);

            ExecutionException cleanupFailure =
                    assertThrows(
                            ExecutionException.class,
                            () -> worker.submit(triangle::cleanup).get());
            assertInstanceOf(
                    IllegalStateException.class, cleanupFailure.getCause());
            assertEquals(List.of(), backend.deletedVertexArrays);

            triangle.cleanup();
            assertEquals(List.of(73), backend.deletedVertexArrays);
        } finally {
            worker.shutdownNow();
        }
    }

    @Test
    void partialCreationKeepsPrimaryAndSuppressesDistinctCleanupFailure() {
        RecordingBackend backend = new RecordingBackend();
        IllegalStateException primary =
                new IllegalStateException("bind failed");
        IllegalStateException cleanup =
                new IllegalStateException("delete failed");
        backend.bindFailure = primary;
        backend.deleteFailure = cleanup;

        IllegalStateException escaped =
                assertThrows(
                        IllegalStateException.class,
                        () ->
                                new FullscreenTriangle(
                                        MainThreadGuard.captureCurrentThread(),
                                        backend));

        assertSame(primary, escaped);
        assertEquals(List.of(cleanup), List.of(escaped.getSuppressed()));
        assertEquals(List.of(73), backend.deletedVertexArrays);
    }

    @Test
    void partialCreationDoesNotSelfSuppressThePrimaryFailure() {
        RecordingBackend backend = new RecordingBackend();
        IllegalStateException primary =
                new IllegalStateException("same failure");
        backend.bindFailure = primary;
        backend.deleteFailure = primary;

        IllegalStateException escaped =
                assertThrows(
                        IllegalStateException.class,
                        () ->
                                new FullscreenTriangle(
                                        MainThreadGuard.captureCurrentThread(),
                                        backend));

        assertSame(primary, escaped);
        assertEquals(0, escaped.getSuppressed().length);
        assertEquals(List.of(73), backend.deletedVertexArrays);
    }

    private record Draw(int firstVertex, int vertexCount) {}

    private static final class RecordingBackend
            implements FullscreenTriangleBackend {
        private int createCalls;
        private final List<Integer> boundVertexArrays = new ArrayList<>();
        private final List<Draw> draws = new ArrayList<>();
        private final List<Integer> deletedVertexArrays = new ArrayList<>();
        private RuntimeException bindFailure;
        private RuntimeException deleteFailure;

        @Override
        public int createVertexArray() {
            createCalls++;
            return 73;
        }

        @Override
        public void bindVertexArray(int vertexArrayId) {
            boundVertexArrays.add(vertexArrayId);
            if (bindFailure != null) {
                throw bindFailure;
            }
        }

        @Override
        public void drawTriangles(int firstVertex, int vertexCount) {
            draws.add(new Draw(firstVertex, vertexCount));
        }

        @Override
        public void deleteVertexArray(int vertexArrayId) {
            deletedVertexArrays.add(vertexArrayId);
            if (deleteFailure != null) {
                throw deleteFailure;
            }
        }
    }
}

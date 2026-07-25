package com.overlord.renderer.shader;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.overlord.assets.ResourceLocation;
import com.overlord.core.thread.MainThreadGuard;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

class ShaderProgramTest {
    @Test
    void reportsVertexCompileDiagnosticsAndDeletesPartialShader() {
        FakeShaderBackend backend = new FakeShaderBackend();
        backend.failCompile(101, "line 4: syntax error");

        ShaderProgramException failure =
                assertThrows(
                        ShaderProgramException.class,
                        () ->
                                new ShaderProgram(
                                        MainThreadGuard.captureCurrentThread(),
                                        sources(),
                                        List.of("projection"),
                                        backend));

        assertTrue(failure.getMessage().contains("world"));
        assertTrue(failure.getMessage().contains("VERTEX"));
        assertTrue(failure.getMessage().contains("overlord:shaders/world.vert"));
        assertTrue(failure.getMessage().contains("line 4: syntax error"));
        assertEquals(List.of(101), backend.deletedShaders());
        assertEquals(List.of(), backend.deletedPrograms());
    }

    @Test
    void reportsLinkDiagnosticsAndDeletesPartialResources() {
        FakeShaderBackend backend = new FakeShaderBackend();
        backend.failLink("varying mismatch");

        ShaderProgramException failure =
                assertThrows(
                        ShaderProgramException.class,
                        () ->
                                new ShaderProgram(
                                        MainThreadGuard.captureCurrentThread(),
                                        sources(),
                                        List.of("projection"),
                                        backend));

        assertTrue(failure.getMessage().contains("world"));
        assertTrue(failure.getMessage().contains("link"));
        assertTrue(failure.getMessage().contains("overlord:shaders/world.vert"));
        assertTrue(failure.getMessage().contains("overlord:shaders/world.frag"));
        assertTrue(failure.getMessage().contains("varying mismatch"));
        assertEquals(List.of(101, 102), backend.deletedShaders());
        assertEquals(List.of(201), backend.deletedPrograms());
    }

    @Test
    void rejectsMissingRequiredUniformAndDeletesProgram() {
        FakeShaderBackend backend = new FakeShaderBackend();
        backend.uniformLocation("projection", -1);
        backend.uniformLocation("view", -1);

        ShaderProgramException failure =
                assertThrows(
                        ShaderProgramException.class,
                        () ->
                                new ShaderProgram(
                                        MainThreadGuard.captureCurrentThread(),
                                        sources(),
                                        List.of("projection", "view"),
                                        backend));

        assertTrue(failure.getMessage().contains("world"));
        assertTrue(failure.getMessage().contains("projection"));
        assertFalse(failure.getMessage().contains("view"));
        assertTrue(failure.getMessage().contains("overlord:shaders/world.vert"));
        assertTrue(failure.getMessage().contains("overlord:shaders/world.frag"));
        assertEquals(1, backend.uniformLocationCalls("projection"));
        assertEquals(0, backend.uniformLocationCalls("view"));
        assertEquals(List.of(101, 102), backend.deletedShaders());
        assertEquals(List.of(201), backend.deletedPrograms());
    }

    @Test
    void suppressesCleanupFailuresWithoutReplacingLinkDiagnostics() {
        FakeShaderBackend backend = new FakeShaderBackend();
        backend.failLink("varying mismatch");
        backend.failDeletingShader(101, "vertex cleanup failed");
        backend.failDeletingShader(102, "fragment cleanup failed");
        backend.failDeletingProgram("program cleanup failed");

        ShaderProgramException failure =
                assertThrows(
                        ShaderProgramException.class,
                        () ->
                                new ShaderProgram(
                                        MainThreadGuard.captureCurrentThread(),
                                        sources(),
                                        List.of("projection"),
                                        backend));

        assertTrue(failure.getMessage().contains("varying mismatch"));
        assertEquals(
                List.of("vertex cleanup failed", "fragment cleanup failed", "program cleanup failed"),
                List.of(failure.getSuppressed()).stream().map(Throwable::getMessage).toList());
        assertEquals(List.of(101, 102), backend.deletedShaders());
        assertEquals(List.of(201), backend.deletedPrograms());
    }

    @Test
    void cachesLocationsUploadsValuesAndCleansUpOnlyOnce() {
        FakeShaderBackend backend = new FakeShaderBackend();
        backend.uniformLocation("projection", 17);
        backend.uniformLocation("textureAtlas", 23);
        ShaderProgram program =
                new ShaderProgram(
                        MainThreadGuard.captureCurrentThread(),
                        sources(),
                        List.of("projection", "textureAtlas"),
                        backend);

        Matrix4f matrix = new Matrix4f().translate(2.0f, 3.0f, 4.0f);
        program.use();
        program.setMatrix4("projection", matrix);
        program.setInt("textureAtlas", 4);
        program.cleanup();
        program.cleanup();

        assertEquals(201, program.programId());
        assertEquals(List.of(101, 102), backend.deletedShaders());
        assertEquals(1, backend.uniformLocationCalls("projection"));
        assertEquals(1, backend.uniformLocationCalls("textureAtlas"));
        assertEquals(List.of(201), backend.usedPrograms());
        assertEquals(List.of(17), backend.matrixLocations());
        assertArrayEquals(matrix.get(new float[16]), backend.matrixValues().get(0));
        assertEquals(List.of(23), backend.intLocations());
        assertEquals(List.of(4), backend.intValues());
        assertEquals(List.of(201), backend.deletedPrograms());
    }

    @Test
    void rejectsDuplicateAndBlankRequiredUniformNamesBeforeBackendCalls() {
        FakeShaderBackend duplicateBackend = new FakeShaderBackend();
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new ShaderProgram(
                                MainThreadGuard.captureCurrentThread(),
                                sources(),
                                List.of("projection", "projection"),
                                duplicateBackend));
        assertEquals(0, duplicateBackend.callCount());

        FakeShaderBackend blankBackend = new FakeShaderBackend();
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new ShaderProgram(
                                MainThreadGuard.captureCurrentThread(),
                                sources(),
                                List.of(" "),
                                blankBackend));
        assertEquals(0, blankBackend.callCount());
    }

    @Test
    void rejectsWorkerThreadBeforeFakeBackendCalls() throws InterruptedException {
        MainThreadGuard guard = MainThreadGuard.captureCurrentThread();
        FakeShaderBackend backend = new FakeShaderBackend();
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            ExecutionException failure =
                    assertThrows(
                            ExecutionException.class,
                            () ->
                                    worker.submit(
                                                    () ->
                                                            new ShaderProgram(
                                                                    guard,
                                                                    sources(),
                                                                    List.of("projection"),
                                                                    backend))
                                            .get());

            assertInstanceOf(IllegalStateException.class, failure.getCause());
            assertEquals(0, backend.callCount());
        } finally {
            worker.shutdownNow();
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private static ShaderSourceSet sources() {
        return new ShaderSourceSet(
                "world",
                ResourceLocation.of("overlord", "shaders/world.vert"),
                "#version 410 core\nvoid main() {}",
                ResourceLocation.of("overlord", "shaders/world.frag"),
                "#version 410 core\nout vec4 color; void main() { color = vec4(1.0); }");
    }

    private static final class FakeShaderBackend implements ShaderBackend {
        private final List<Integer> deletedShaders = new ArrayList<>();
        private final List<Integer> deletedPrograms = new ArrayList<>();
        private final List<Integer> usedPrograms = new ArrayList<>();
        private final List<Integer> matrixLocations = new ArrayList<>();
        private final List<float[]> matrixValues = new ArrayList<>();
        private final List<Integer> intLocations = new ArrayList<>();
        private final List<Integer> intValues = new ArrayList<>();
        private final Map<Integer, String> compileFailures = new HashMap<>();
        private final Map<Integer, String> shaderDeleteFailures = new HashMap<>();
        private final Map<String, Integer> uniformLocations = new HashMap<>();
        private final Map<String, Integer> uniformLocationCalls = new HashMap<>();
        private int calls;
        private boolean linkFails;
        private String linkLog;
        private String programDeleteFailure;
        private int nextShaderId = 101;
        private int nextProgramId = 201;

        void failCompile(int shaderId, String log) {
            compileFailures.put(shaderId, log);
        }

        void failLink(String log) {
            linkFails = true;
            linkLog = log;
        }

        void uniformLocation(String name, int location) {
            uniformLocations.put(name, location);
        }

        void failDeletingShader(int shaderId, String message) {
            shaderDeleteFailures.put(shaderId, message);
        }

        void failDeletingProgram(String message) {
            programDeleteFailure = message;
        }

        int callCount() {
            return calls;
        }

        List<Integer> deletedShaders() {
            return deletedShaders;
        }

        List<Integer> deletedPrograms() {
            return deletedPrograms;
        }

        List<Integer> usedPrograms() {
            return usedPrograms;
        }

        List<Integer> matrixLocations() {
            return matrixLocations;
        }

        List<float[]> matrixValues() {
            return matrixValues;
        }

        List<Integer> intLocations() {
            return intLocations;
        }

        List<Integer> intValues() {
            return intValues;
        }

        int uniformLocationCalls(String name) {
            return uniformLocationCalls.getOrDefault(name, 0);
        }

        @Override
        public int createShader(ShaderStage stage) {
            calls++;
            return nextShaderId++;
        }

        @Override
        public void setSource(int shaderId, String source) {
            calls++;
        }

        @Override
        public void compile(int shaderId) {
            calls++;
        }

        @Override
        public boolean compileSucceeded(int shaderId) {
            calls++;
            return !compileFailures.containsKey(shaderId);
        }

        @Override
        public String shaderInfoLog(int shaderId) {
            calls++;
            return compileFailures.getOrDefault(shaderId, "");
        }

        @Override
        public void deleteShader(int shaderId) {
            calls++;
            deletedShaders.add(shaderId);
            String failure = shaderDeleteFailures.get(shaderId);
            if (failure != null) {
                throw new IllegalStateException(failure);
            }
        }

        @Override
        public int createProgram() {
            calls++;
            return nextProgramId++;
        }

        @Override
        public void attach(int programId, int shaderId) {
            calls++;
        }

        @Override
        public void link(int programId) {
            calls++;
        }

        @Override
        public boolean linkSucceeded(int programId) {
            calls++;
            return !linkFails;
        }

        @Override
        public String programInfoLog(int programId) {
            calls++;
            return linkLog;
        }

        @Override
        public int uniformLocation(int programId, String name) {
            calls++;
            uniformLocationCalls.merge(name, 1, Integer::sum);
            return uniformLocations.getOrDefault(name, 7);
        }

        @Override
        public void useProgram(int programId) {
            calls++;
            usedPrograms.add(programId);
        }

        @Override
        public void uploadMatrix4(int location, float[] columnMajor) {
            calls++;
            matrixLocations.add(location);
            matrixValues.add(columnMajor.clone());
        }

        @Override
        public void uploadInt(int location, int value) {
            calls++;
            intLocations.add(location);
            intValues.add(value);
        }

        @Override
        public void deleteProgram(int programId) {
            calls++;
            deletedPrograms.add(programId);
            if (programDeleteFailure != null) {
                throw new IllegalStateException(programDeleteFailure);
            }
        }
    }
}

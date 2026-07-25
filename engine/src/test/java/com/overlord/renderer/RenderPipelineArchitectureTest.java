package com.overlord.renderer;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class RenderPipelineArchitectureTest {
    private static final Path MAIN = Path.of("src/main");
    private static final Path JAVA = MAIN.resolve("java");
    private static final Pattern GLSL_VERSION_ABOVE_410 =
            Pattern.compile("#version\\s+(?:4[2-9]0|[5-9]\\d{2,})");

    @Test
    void voxelMeshingAndGenerationSourcesDoNotDependOnLwjgl()
            throws IOException {
        List<Path> sources =
                List.of(
                        JAVA.resolve(
                                "com/overlord/voxel/ChunkMeshBuilder.java"),
                        JAVA.resolve(
                                "com/overlord/voxel/ChunkMeshData.java"),
                        JAVA.resolve(
                                "com/overlord/voxel/ChunkMeshManager.java"));

        for (Path source : sources) {
            assertFalse(
                    read(source).contains("org.lwjgl"),
                    source + " must not depend on LWJGL");
        }

        try (Stream<Path> generationSources =
                javaSources(
                        JAVA.resolve("com/overlord/voxel/generation"))) {
            assertTrue(
                    generationSources.noneMatch(
                            source -> read(source).contains("org.lwjgl")),
                    "Voxel generation sources must not depend on LWJGL");
        }
    }

    @Test
    void engineOwnsOpenGlAndGpuResourceTypes() {
        for (Path source :
                List.of(
                        JAVA.resolve(
                                "com/overlord/renderer/shader/"
                                        + "OpenGlShaderBackend.java"),
                        JAVA.resolve(
                                "com/overlord/renderer/state/"
                                        + "OpenGlRenderStateBackend.java"),
                        JAVA.resolve("com/overlord/renderer/Mesh.java"),
                        JAVA.resolve("com/overlord/renderer/Texture.java"),
                        JAVA.resolve("com/overlord/renderer/Renderer.java"),
                        JAVA.resolve("com/overlord/core/Window.java"))) {
            assertTrue(
                    Files.isRegularFile(source),
                    "Engine must own " + source.getFileName());
        }
    }

    @Test
    void shaderProgramAndRenderStateEntryPointsUseMainThreadGuard()
            throws IOException {
        String shaderProgram =
                read(
                        JAVA.resolve(
                                "com/overlord/renderer/shader/"
                                        + "ShaderProgram.java"));
        String renderState =
                read(
                        JAVA.resolve(
                                "com/overlord/renderer/state/"
                                        + "OpenGlRenderStateBackend.java"));

        assertTrue(shaderProgram.contains("private final MainThreadGuard guard"));
        assertTrue(shaderProgram.contains("guard.assertMainThread("));
        assertTrue(
                renderState.contains(
                        "private final MainThreadGuard mainThreadGuard"));
        for (String entryPoint :
                List.of(
                        "capture OpenGL render state",
                        "apply OpenGL render state",
                        "restore OpenGL render state",
                        "clear OpenGL color and depth buffers")) {
            assertTrue(
                    renderState.contains(
                            "mainThreadGuard.assertMainThread(\""
                                    + entryPoint
                                    + "\")"),
                    "Render-state entry point must assert its main thread: "
                            + entryPoint);
        }
    }

    @Test
    void sourceTreeDoesNotUseUnsupportedShaderOrComputeFeatures()
            throws IOException {
        List<String> forbidden =
                List.of(
                        "#version 420",
                        "#version 430",
                        "glDispatchCompute",
                        "GL_SHADER_STORAGE_BUFFER");

        try (Stream<Path> sources = Files.walk(MAIN)) {
            List<Path> offenders =
                    sources.filter(Files::isRegularFile)
                            .filter(
                                    source ->
                                            forbidden.stream()
                                                    .anyMatch(
                                                            token ->
                                                                    read(source)
                                                                            .contains(
                                                                                    token)))
                            .toList();
            assertTrue(
                    offenders.isEmpty(),
                    "Unsupported rendering features found in " + offenders);
        }

        try (Stream<Path> sources = Files.walk(MAIN)) {
            List<Path> versionOffenders =
                    sources.filter(Files::isRegularFile)
                            .filter(
                                    source ->
                                            GLSL_VERSION_ABOVE_410
                                                    .matcher(read(source))
                                                    .find())
                            .toList();
            assertTrue(
                    versionOffenders.isEmpty(),
                    "GLSL versions above 410 found in " + versionOffenders);
        }
    }

    @Test
    void engineBuildVerifiesPackagedShaderResources() throws IOException {
        String buildScript = Files.readString(Path.of("build.gradle"));

        assertTrue(
                buildScript.contains(
                        "tasks.register('verifyPackagedShaderResources')"));
        assertTrue(buildScript.contains("dependsOn tasks.named('jar')"));
        assertTrue(buildScript.contains("new java.util.zip.ZipFile(archive)"));
        assertTrue(
                buildScript.contains(
                        "assets/overlord/shaders/world.vert"));
        assertTrue(
                buildScript.contains(
                        "assets/overlord/shaders/world.frag"));
        assertTrue(
                buildScript.contains("dependsOn tasks.named("
                        + "'verifyPackagedShaderResources')"));
        assertTrue(buildScript.contains("tasks.named('check')"));
    }

    private static Stream<Path> javaSources(Path root) throws IOException {
        if (!Files.isDirectory(root)) {
            return Stream.empty();
        }
        return Files.walk(root)
                .filter(Files::isRegularFile)
                .filter(source -> source.toString().endsWith(".java"));
    }

    private static String read(Path source) {
        try {
            return Files.readString(source);
        } catch (IOException failure) {
            throw new AssertionError("Could not read " + source, failure);
        }
    }
}

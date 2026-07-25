package com.overlord.renderer;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class RenderPipelineArchitectureTest {
    private static final Path MAIN = Path.of("src/main");
    private static final Path JAVA = MAIN.resolve("java");

    @Test
    void voxelMeshingAndGenerationSourcesDoNotDependOnLwjgl()
            throws IOException {
        Path voxel = JAVA.resolve(Path.of("com", "overlord", "voxel"));
        assertTrue(Files.isDirectory(voxel), "Missing voxel source directory");
        List<Path> voxelSources = allJavaSources(voxel);
        assertFalse(voxelSources.isEmpty(), "Voxel source directory is empty");
        for (String type :
                List.of("ChunkMeshBuilder", "ChunkMeshData", "ChunkMeshManager")) {
            assertTrue(
                    voxelSources.contains(
                            JAVA.resolve(
                                    Path.of(
                                            "com",
                                            "overlord",
                                            "voxel",
                                            type + ".java"))),
                    "Voxel sources must contain " + type);
        }
        assertTrue(
                voxelSources.stream()
                        .noneMatch(source -> read(source).contains("org.lwjgl")),
                "Voxel sources must not depend on LWJGL");

        Path generation =
                Path.of(
                        "..",
                        "game",
                        "src",
                        "main",
                        "java",
                        "com",
                        "gaia",
                        "world",
                        "generation");
        assertTrue(
                Files.isDirectory(generation),
                "Missing game generation source directory");
        List<Path> generationSources = allJavaSources(generation);
        assertFalse(
                generationSources.isEmpty(), "Game generation source directory is empty");
        assertTrue(
                generationSources.stream()
                        .noneMatch(source -> read(source).contains("org.lwjgl")),
                "Game generation sources must not depend on LWJGL");
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
        assertTrue(
                renderState.contains(
                        "private final MainThreadGuard mainThreadGuard"));
        assertGuardBeforeFirstCall(
                methodBody(
                        shaderProgram,
                        "ShaderProgram",
                        "ShaderBackend backend"),
                "guard.assertMainThread(",
                "backend.");
        for (String method : List.of("use", "setMatrix4", "setInt", "cleanup")) {
            assertGuardBeforeFirstCall(
                    methodBody(shaderProgram, method),
                    "guard.assertMainThread(",
                    "backend.");
        }
        for (String method : List.of("capture", "apply", "restore", "clearColorAndDepth")) {
            assertGuardBeforeFirstCall(
                    methodBody(renderState, method),
                    "mainThreadGuard.assertMainThread(",
                    "gl");
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
                                            !unsupportedGlslVersions(read(source))
                                                    .isEmpty())
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
                taskBlockDependsOn(
                        buildScript,
                        "check",
                        "verifyPackagedShaderResources"));
    }

    @Test
    void helperContractsRejectUnsupportedVersionsAndGuardOrder() {
        assertTrue(unsupportedGlslVersions("#version 410 core").isEmpty());
        for (int version : List.of(411, 419, 420, 421, 430, 450)) {
            assertTrue(
                    unsupportedGlslVersions("#version " + version + " core")
                            .contains(version));
        }
        assertEquals(
                List.of(411, 450),
                unsupportedGlslVersions(
                        "#version 410 core\n#version 411 core\n#version 450 core"));

        assertTrue(
                methodBody(
                                "void guarded() { if (true) { guard.assertMainThread(); } "
                                        + "backend.create(); }",
                                "guarded")
                        .contains("backend.create()"));

        assertGuardBeforeFirstCall(
                "guard.assertMainThread(); backend.create();",
                "guard.assertMainThread(",
                "backend.");
        assertThrows(
                AssertionError.class,
                () ->
                        assertGuardBeforeFirstCall(
                                "backend.create(); guard.assertMainThread();",
                                "guard.assertMainThread(",
                                "backend."));
    }

    @Test
    void taskBlockDependencyHelperRejectsDependencyOutsideCheckBlock() {
        String script =
                "tasks.named('check') { dependsOn tasks.named('other') }\n"
                        + "dependsOn tasks.named('verifyPackagedShaderResources')";

        assertFalse(
                taskBlockDependsOn(
                        script, "check", "verifyPackagedShaderResources"));
    }

    @Test
    void taskBlockDependencyHelperChecksEverySeparateCheckBlock() {
        String script =
                "tasks.named('check') { dependsOn tasks.named('other') }\n"
                        + "tasks.named('check') { "
                        + "dependsOn tasks.named('verifyPackagedShaderResources') }";

        assertTrue(
                taskBlockDependsOn(
                        script, "check", "verifyPackagedShaderResources"));
    }

    private static List<Path> allJavaSources(Path root) {
        assertTrue(Files.isDirectory(root), "Missing source directory: " + root);
        try (Stream<Path> sources = Files.walk(root)) {
            return sources.filter(Files::isRegularFile)
                    .filter(source -> source.toString().endsWith(".java"))
                    .toList();
        } catch (IOException failure) {
            throw new AssertionError("Could not scan " + root, failure);
        }
    }

    private static List<Integer> unsupportedGlslVersions(String source) {
        Matcher versions = Pattern.compile("#version\\s+(\\d+)").matcher(source);
        List<Integer> unsupported = new ArrayList<>();
        while (versions.find()) {
            int version;
            try {
                version = Integer.parseInt(versions.group(1));
            } catch (NumberFormatException ignored) {
                version = Integer.MAX_VALUE;
            }
            if (version > 410) {
                unsupported.add(version);
            }
        }
        return List.copyOf(unsupported);
    }

    private static String methodBody(String source, String methodName) {
        return methodBody(source, methodName, null);
    }

    private static String methodBody(
            String source, String methodName, String requiredParameter) {
        Matcher declarations =
                Pattern.compile("\\b" + Pattern.quote(methodName) + "\\s*\\(")
                        .matcher(source);
        while (declarations.find()) {
            int parametersStart = declarations.end() - 1;
            int parametersEnd =
                    matchingDelimiter(source, parametersStart, '(', ')');
            String parameters =
                    source.substring(parametersStart + 1, parametersEnd);
            if (requiredParameter != null
                    && !parameters.contains(requiredParameter)) {
                continue;
            }
            int openingBrace = source.indexOf('{', parametersEnd);
            if (openingBrace < 0) {
                throw new AssertionError(
                        "Missing method body for " + methodName);
            }
            int closingBrace =
                    matchingDelimiter(source, openingBrace, '{', '}');
            return source.substring(openingBrace + 1, closingBrace);
        }
        throw new AssertionError("Missing method declaration for " + methodName);
    }

    private static int matchingDelimiter(
            String source, int openingIndex, char opening, char closing) {
        int depth = 0;
        for (int index = openingIndex; index < source.length(); index++) {
            char current = source.charAt(index);
            if (current == opening) {
                depth++;
            } else if (current == closing && --depth == 0) {
                return index;
            }
        }
        throw new AssertionError(
                "Unclosed delimiter starting at " + openingIndex);
    }

    private static void assertGuardBeforeFirstCall(
            String body, String guardCall, String callPrefix) {
        int guardIndex = body.indexOf(guardCall);
        int callIndex = firstCallIndex(body, callPrefix);

        assertTrue(guardIndex >= 0, "Missing main-thread guard: " + guardCall);
        assertTrue(callIndex >= 0, "Missing guarded call: " + callPrefix);
        assertTrue(
                guardIndex < callIndex,
                "Main-thread guard must precede the first " + callPrefix + " call");
    }

    private static int firstCallIndex(String body, String callPrefix) {
        Pattern call =
                callPrefix.equals("backend.")
                        ? Pattern.compile("\\bbackend\\.[A-Za-z_$][\\w$]*\\s*\\(")
                        : Pattern.compile(
                                "\\b"
                                        + Pattern.quote(callPrefix)
                                        + "[A-Za-z_$][\\w$]*\\s*\\(");
        Matcher matches = call.matcher(body);
        return matches.find() ? matches.start() : -1;
    }

    private static boolean taskBlockDependsOn(
            String script, String taskName, String dependencyName) {
        Matcher taskBlock =
                Pattern.compile(
                                "tasks\\.named\\(\\s*['\"]"
                                        + Pattern.quote(taskName)
                                        + "['\"]\\s*\\)\\s*\\{")
                        .matcher(script);
        Pattern dependency =
                Pattern.compile(
                        "dependsOn\\s+tasks\\.named\\(\\s*['\"]"
                                + Pattern.quote(dependencyName)
                                + "['\"]\\s*\\)");
        while (taskBlock.find()) {
            int openingBrace = script.indexOf('{', taskBlock.start());
            String body =
                    script.substring(
                            openingBrace + 1,
                            matchingDelimiter(script, openingBrace, '{', '}'));
            if (dependency.matcher(body).find()) {
                return true;
            }
        }
        return false;
    }

    private static String read(Path source) {
        try {
            return Files.readString(source);
        } catch (IOException failure) {
            throw new AssertionError("Could not read " + source, failure);
        }
    }
}

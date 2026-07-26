package com.overlord.renderer;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.overlord.assets.AssetManager;
import com.overlord.assets.ResourceLocation;
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

    private enum LexicalState {
        CODE,
        LINE_COMMENT,
        BLOCK_COMMENT,
        SINGLE_QUOTED,
        DOUBLE_QUOTED
    }

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
    void worldShadersKeepTheLinearLightingFogAndSingleGammaContract() {
        AssetManager assets = new AssetManager(getClass().getClassLoader());
        String vertex =
                assets.readUtf8(
                        ResourceLocation.parse("overlord:shaders/world.vert"));
        String fragment =
                assets.readUtf8(
                        ResourceLocation.parse("overlord:shaders/world.frag"));

        assertEquals("#version 410 core", vertex.lines().findFirst().orElseThrow());
        assertEquals("#version 410 core", fragment.lines().findFirst().orElseThrow());
        assertTrue(vertex.contains("layout (location = 0) in vec3 aPosition;"));
        assertTrue(vertex.contains("layout (location = 1) in vec2 aUv;"));
        assertTrue(vertex.contains("layout (location = 2) in vec3 aNormal;"));
        assertTrue(vertex.contains("layout (location = 3) in float aFaceLight;"));
        assertTrue(vertex.contains("layout (location = 4) in float aAmbientOcclusion;"));

        String vertexMain = methodBody(vertex, "main");
        assertTrue(vertexMain.contains("aNormal"));
        assertTrue(
                vertexMain.contains(
                        "float vertexLight = mod(aFaceLight, 16.0) / 15.0;"));
        assertTrue(vertexMain.contains("aAmbientOcclusion"));
        assertTrue(vertexMain.contains("vec4 viewPosition = view * worldPosition;"));
        assertTrue(vertexMain.contains("length(viewPosition.xyz)"));

        assertTrue(fragment.contains("vec3 srgbToLinear(vec3 srgb)"));
        assertTrue(fragment.contains("lessThanEqual(srgb, vec3(0.04045))"));
        assertTrue(fragment.contains("srgb / 12.92"));
        assertTrue(fragment.contains("vec3(2.4)"));
        assertTrue(fragment.contains("vec3 linearToSrgb(vec3 linear)"));
        assertTrue(fragment.contains("lessThanEqual(linear, vec3(0.0031308))"));
        assertTrue(fragment.contains("linear * 12.92"));
        assertTrue(fragment.contains("vec3(1.0 / 2.4)"));

        String fragmentMain = methodBody(fragment, "main");
        assertInOrder(
                fragmentMain,
                "texture(textureAtlas, texCoord)",
                "srgbToLinear(sampledColor.rgb)",
                "combinedLight",
                "ambientOcclusion",
                "smoothstep(fogStart, fogEnd, viewDistance)",
                "mix(litColor, fogColor, fogAmount)",
                "linearToSrgb(foggedColor)",
                "sampledColor.a");
        int encode = fragmentMain.indexOf("linearToSrgb(foggedColor)");
        assertFalse(fragmentMain.substring(encode).contains("pow("));
        assertTrue(
                fragmentMain.trim().endsWith(
                        "fragmentColor = vec4(linearToSrgb(foggedColor), sampledColor.a);"));
    }

    @Test
    void productionKeepsFramebufferSrgbDisabledForTheManualGammaPath()
            throws IOException {
        String renderer = read(JAVA.resolve("com/overlord/renderer/Renderer.java"));
        assertTrue(renderer.contains("glDisable(GL_FRAMEBUFFER_SRGB);"));

        try (Stream<Path> sources = Files.walk(MAIN)) {
            List<Path> enables =
                    sources.filter(Files::isRegularFile)
                            .filter(
                                    source ->
                                            read(source)
                                                    .contains(
                                                            "glEnable(GL_FRAMEBUFFER_SRGB)"))
                            .toList();
            assertTrue(
                    enables.isEmpty(),
                    "Manual gamma path forbids framebuffer sRGB enablement: "
                            + enables);
        }
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
    void lexicalGuardHelpersIgnoreCommentsStringsAndQuotedBraces() {
        String escaped =
                "void guarded() { String fake = \"guard.assertMainThread(); \\\" }\"; "
                        + "/* { backend.create(); } */ guard.assertMainThread(); "
                        + "backend.create(); }";
        assertGuardBeforeFirstCall(
                methodBody(escaped, "guarded"),
                "guard.assertMainThread(",
                "backend.");

        assertThrows(
                AssertionError.class,
                () ->
                        assertGuardBeforeFirstCall(
                                "// guard.assertMainThread();\nbackend.create();",
                                "guard.assertMainThread(",
                                "backend."));
        assertThrows(
                AssertionError.class,
                () ->
                        assertGuardBeforeFirstCall(
                                "\"guard.assertMainThread();\"; backend.create();",
                                "guard.assertMainThread(",
                                "backend."));

        String sanitized =
                sanitizeCode(
                        "// fake { }\n'guard.assertMainThread()' \"backend.create()\\\" }\"");
        assertEquals(
                sanitized.length(),
                "// fake { }\n'guard.assertMainThread()' \"backend.create()\\\" }\""
                        .length());
        assertFalse(sanitized.contains("guard.assertMainThread"));
        assertFalse(sanitized.contains("backend.create"));
    }

    @Test
    void taskBlockDependencyHelperRejectsDependencyOutsideCheckBlock() {
        String script =
                "tasks.named('check') {\n"
                        + "    dependsOn tasks.named('other')\n"
                        + "}\n"
                        + "dependsOn tasks.named('verifyPackagedShaderResources')";

        assertFalse(
                taskBlockDependsOn(
                        script, "check", "verifyPackagedShaderResources"));
    }

    @Test
    void taskBlockDependencyHelperChecksEverySeparateCheckBlock() {
        String script =
                "tasks.named('check') {\n"
                        + "    dependsOn tasks.named('other')\n"
                        + "}\n"
                        + "tasks.named('check') {\n"
                        + "    dependsOn tasks.named('verifyPackagedShaderResources')\n"
                        + "}";

        assertTrue(
                taskBlockDependsOn(
                        script, "check", "verifyPackagedShaderResources"));
    }

    @Test
    void taskBlockDependencyHelperIgnoresCommentsStringsAndQuotedBraces() {
        for (String script :
                List.of(
                        "tasks.named('check') {\n"
                                + "    // dependsOn tasks.named('verifyPackagedShaderResources')\n"
                                + "}",
                        "tasks.named('check') {\n"
                                + "    /*\n"
                                + "    dependsOn tasks.named('verifyPackagedShaderResources')\n"
                                + "    */\n"
                                + "}",
                        "tasks.named('check') {\n"
                                + "    \"dependsOn tasks.named('verifyPackagedShaderResources')\"\n"
                                + "}",
                        "\"tasks.named('check') { dependsOn tasks.named('verifyPackagedShaderResources') }\"")) {
            assertFalse(
                    taskBlockDependsOn(
                            script, "check", "verifyPackagedShaderResources"));
        }

        String validScript =
                "tasks.named('check') {\n"
                        + "    def message = \"{ escaped quote: \\\" }\"\n"
                        + "    dependsOn tasks.named('verifyPackagedShaderResources')\n"
                        + "}";
        assertTrue(
                taskBlockDependsOn(
                        validScript, "check", "verifyPackagedShaderResources"));
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

    private static void assertInOrder(String source, String... tokens) {
        int previous = -1;
        for (String token : tokens) {
            int current = source.indexOf(token);
            assertTrue(current >= 0, "Missing shader token: " + token);
            assertTrue(
                    current > previous,
                    "Shader token is out of order: " + token);
            previous = current;
        }
    }

    private static String methodBody(String source, String methodName) {
        return methodBody(source, methodName, null);
    }

    private static String methodBody(
            String source, String methodName, String requiredParameter) {
        String code = sanitizeCode(source);
        Matcher declarations =
                Pattern.compile("\\b" + Pattern.quote(methodName) + "\\s*\\(")
                        .matcher(code);
        while (declarations.find()) {
            int parametersStart = declarations.end() - 1;
            int parametersEnd =
                    matchingDelimiter(code, parametersStart, '(', ')');
            String parameters =
                    code.substring(parametersStart + 1, parametersEnd);
            if (requiredParameter != null
                    && !parameters.contains(requiredParameter)) {
                continue;
            }
            int openingBrace = nextCodeCharacter(code, parametersEnd, '{');
            if (openingBrace < 0) {
                throw new AssertionError(
                        "Missing method body for " + methodName);
            }
            int closingBrace =
                    matchingDelimiter(code, openingBrace, '{', '}');
            return source.substring(openingBrace + 1, closingBrace);
        }
        throw new AssertionError("Missing method declaration for " + methodName);
    }

    private static int matchingDelimiter(
            String source, int openingIndex, char opening, char closing) {
        String code = sanitizeCode(source);
        int depth = 0;
        for (int index = openingIndex; index < code.length(); index++) {
            char current = code.charAt(index);
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
        String code = sanitizeCode(body);
        int guardIndex = code.indexOf(guardCall);
        int callIndex = firstCallIndex(code, callPrefix);

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
        String code = sanitizeCode(script);
        Matcher taskBlock =
                Pattern.compile(
                                "tasks\\.named\\(\\s*['\"]"
                                        + Pattern.quote(taskName)
                                        + "['\"]\\s*\\)\\s*\\{")
                        .matcher(script);
        Pattern dependency =
                Pattern.compile(
                        "(?m)^\\s*dependsOn\\s+tasks\\.named\\(\\s*['\"]"
                                + Pattern.quote(dependencyName)
                                + "['\"]\\s*\\)");
        while (taskBlock.find()) {
            if (!isCodeAt(script, code, taskBlock.start())) {
                continue;
            }
            int openingBrace =
                    nextCodeCharacter(code, taskBlock.end() - 1, '{');
            if (openingBrace < 0) {
                continue;
            }
            int closingBrace =
                    matchingDelimiter(code, openingBrace, '{', '}');
            String body =
                    script.substring(
                            openingBrace + 1,
                            closingBrace);
            String bodyCode =
                    code.substring(openingBrace + 1, closingBrace);
            Matcher dependencies = dependency.matcher(body);
            while (dependencies.find()) {
                int dependencyToken =
                        dependencies.start()
                                + dependencies.group().indexOf("dependsOn");
                if (isCodeAt(body, bodyCode, dependencyToken)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String sanitizeCode(String source) {
        StringBuilder sanitized = new StringBuilder(source.length());
        LexicalState state = LexicalState.CODE;
        for (int index = 0; index < source.length(); index++) {
            char current = source.charAt(index);
            char next = index + 1 < source.length() ? source.charAt(index + 1) : '\0';
            switch (state) {
                case CODE -> {
                    if (current == '/' && next == '/') {
                        sanitized.append(' ').append(' ');
                        index++;
                        state = LexicalState.LINE_COMMENT;
                    } else if (current == '/' && next == '*') {
                        sanitized.append(' ').append(' ');
                        index++;
                        state = LexicalState.BLOCK_COMMENT;
                    } else if (current == '\'') {
                        sanitized.append(' ');
                        state = LexicalState.SINGLE_QUOTED;
                    } else if (current == '"') {
                        sanitized.append(' ');
                        state = LexicalState.DOUBLE_QUOTED;
                    } else {
                        sanitized.append(current);
                    }
                }
                case LINE_COMMENT -> {
                    sanitized.append(whitespace(current));
                    if (current == '\n' || current == '\r') {
                        state = LexicalState.CODE;
                    }
                }
                case BLOCK_COMMENT -> {
                    if (current == '*' && next == '/') {
                        sanitized.append(' ').append(' ');
                        index++;
                        state = LexicalState.CODE;
                    } else {
                        sanitized.append(whitespace(current));
                    }
                }
                case SINGLE_QUOTED -> {
                    if (current == '\\' && next != '\0') {
                        sanitized.append(' ').append(whitespace(next));
                        index++;
                    } else {
                        sanitized.append(whitespace(current));
                        if (current == '\'') {
                            state = LexicalState.CODE;
                        }
                    }
                }
                case DOUBLE_QUOTED -> {
                    if (current == '\\' && next != '\0') {
                        sanitized.append(' ').append(whitespace(next));
                        index++;
                    } else {
                        sanitized.append(whitespace(current));
                        if (current == '"') {
                            state = LexicalState.CODE;
                        }
                    }
                }
            }
        }
        return sanitized.toString();
    }

    private static char whitespace(char character) {
        return character == '\n' || character == '\r' ? character : ' ';
    }

    private static int nextCodeCharacter(String code, int start, char expected) {
        for (int index = start; index < code.length(); index++) {
            if (code.charAt(index) == expected) {
                return index;
            }
        }
        return -1;
    }

    private static boolean isCodeAt(String source, String code, int index) {
        return index >= 0
                && index < source.length()
                && code.charAt(index) == source.charAt(index);
    }

    private static String read(Path source) {
        try {
            return Files.readString(source);
        } catch (IOException failure) {
            throw new AssertionError("Could not read " + source, failure);
        }
    }
}

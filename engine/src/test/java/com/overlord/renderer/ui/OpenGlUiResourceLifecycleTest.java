package com.overlord.renderer.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.opengl.GL30C.GL_BLEND;
import static org.lwjgl.opengl.GL30C.GL_CLAMP_TO_EDGE;
import static org.lwjgl.opengl.GL30C.GL_CULL_FACE;
import static org.lwjgl.opengl.GL30C.GL_DEPTH_TEST;
import static org.lwjgl.opengl.GL30C.GL_FRAMEBUFFER_SRGB;
import static org.lwjgl.opengl.GL30C.GL_FUNC_ADD;
import static org.lwjgl.opengl.GL30C.GL_LINEAR;
import static org.lwjgl.opengl.GL30C.GL_NEAREST;
import static org.lwjgl.opengl.GL30C.GL_ONE;
import static org.lwjgl.opengl.GL30C.GL_ONE_MINUS_SRC_ALPHA;
import static org.lwjgl.opengl.GL30C.GL_POLYGON_OFFSET_FILL;
import static org.lwjgl.opengl.GL30C.GL_SCISSOR_TEST;
import static org.lwjgl.opengl.GL30C.GL_SRC_ALPHA;
import static org.lwjgl.opengl.GL30C.GL_TEXTURE0;
import static org.lwjgl.opengl.GL30C.GL_TRIANGLES;
import static org.lwjgl.opengl.GL30C.GL_UNSIGNED_INT;

import com.overlord.core.thread.MainThreadGuard;
import com.overlord.renderer.state.RenderStateSnapshot;
import com.overlord.renderer.state.RenderStateBackend;
import com.overlord.renderer.state.ScissorBox;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class OpenGlUiResourceLifecycleTest {
    private static final String VERTEX_RESOURCE =
            "assets/overlord/shaders/ui/ui.vert";
    private static final String FRAGMENT_RESOURCE =
            "assets/overlord/shaders/ui/ui.frag";

    @Test
    void concreteBackendAppliesExactTextureVertexBlendViewportAndClipContracts() {
        RecordingOpenGlApi gl = new RecordingOpenGlApi();
        OpenGlUiGpuBackend backend = new OpenGlUiGpuBackend(
                MainThreadGuard.captureCurrentThread(), new NoOpStateBackend(), gl);
        UiTextureData texture =
                new UiTextureData(1, 1, ByteBuffer.wrap(new byte[] {1, 2, 3, 4}));

        int textureId = backend.createTexture(texture);
        backend.configureBatch(30, 31, 32);
        backend.applyUiState(640, 360);
        backend.setClip(Optional.of(new ScissorBox(4, 5, 6, 7)));
        backend.setClip(Optional.empty());
        backend.bindTextureUnitZero(textureId);
        backend.drawBatch(30, 12);

        assertEquals(List.of(new TextureConfig(
                        texture,
                        GL_NEAREST,
                        GL_NEAREST,
                        GL_CLAMP_TO_EDGE,
                        GL_CLAMP_TO_EDGE,
                        0,
                        0)),
                gl.textures);
        assertEquals(List.of(new BatchConfig(
                        30, 31, 32, 32, 0, 0, 1, 8, 2, 16)),
                gl.batches);
        assertEquals(
                List.of(
                        "disable:" + GL_DEPTH_TEST,
                        "depthMask:false",
                        "disable:" + GL_CULL_FACE,
                        "enable:" + GL_BLEND,
                        "blendFunc:" + GL_SRC_ALPHA + ":" + GL_ONE_MINUS_SRC_ALPHA
                                + ":" + GL_ONE + ":" + GL_ONE_MINUS_SRC_ALPHA,
                        "blendEquation:" + GL_FUNC_ADD + ":" + GL_FUNC_ADD,
                        "disable:" + GL_POLYGON_OFFSET_FILL,
                        "disable:" + GL_FRAMEBUFFER_SRGB,
                        "disable:" + GL_SCISSOR_TEST,
                        "viewport:0:0:640:360",
                        "scissor:4:5:6:7",
                        "enable:" + GL_SCISSOR_TEST,
                        "disable:" + GL_SCISSOR_TEST,
                        "activeTexture:" + GL_TEXTURE0,
                        "bindTexture:40",
                        "draw:30:12:" + GL_TRIANGLES + ":" + GL_UNSIGNED_INT),
                gl.calls);
    }

    @Test
    void concreteBackendUsesTheTexturePageSamplingPolicy() {
        RecordingOpenGlApi gl = new RecordingOpenGlApi();
        OpenGlUiGpuBackend backend = new OpenGlUiGpuBackend(
                MainThreadGuard.captureCurrentThread(), new NoOpStateBackend(), gl);
        UiTextureData texture = new UiTextureData(
                1,
                1,
                ByteBuffer.wrap(new byte[] {1, 2, 3, 4}),
                UiTextureSampling.LINEAR);

        backend.createTexture(texture);

        assertEquals(GL_LINEAR, gl.textures.get(0).minFilter());
        assertEquals(GL_LINEAR, gl.textures.get(0).magFilter());
    }

    @Test
    void shaderLinkFailureRemainsPrimaryWhileEveryCleanupFailureIsSuppressedInOrder() {
        RecordingShaderApi gl = new RecordingShaderApi();
        IllegalStateException linkFailure = new IllegalStateException("link failed");
        IllegalArgumentException fragmentDeleteFailure =
                new IllegalArgumentException("fragment delete failed");
        UnsupportedOperationException vertexDeleteFailure =
                new UnsupportedOperationException("vertex delete failed");
        ArithmeticException programDeleteFailure =
                new ArithmeticException("program delete failed");
        gl.linkFailure = linkFailure;
        gl.fragmentDeleteFailure = fragmentDeleteFailure;
        gl.vertexDeleteFailure = vertexDeleteFailure;
        gl.programDeleteFailure = programDeleteFailure;
        LwjglOpenGlUiApi api = new LwjglOpenGlUiApi(gl);

        IllegalStateException escaped = assertThrows(
                IllegalStateException.class,
                () -> api.createProgram("vertex", "fragment"));

        assertSame(linkFailure, escaped);
        assertEquals(
                List.of(fragmentDeleteFailure, vertexDeleteFailure, programDeleteFailure),
                List.of(escaped.getSuppressed()));
        assertEquals(
                List.of("delete-shader:102", "delete-shader:101", "delete-program:103"),
                gl.cleanup);
    }

    @Test
    void shaderCleanupFailureAfterSuccessfulLinkDeletesProgramAndKeepsCleanupPrimary() {
        RecordingShaderApi gl = new RecordingShaderApi();
        IllegalArgumentException fragmentDeleteFailure =
                new IllegalArgumentException("fragment delete failed");
        ArithmeticException programDeleteFailure =
                new ArithmeticException("program delete failed");
        gl.fragmentDeleteFailure = fragmentDeleteFailure;
        gl.programDeleteFailure = programDeleteFailure;
        LwjglOpenGlUiApi api = new LwjglOpenGlUiApi(gl);

        IllegalArgumentException escaped = assertThrows(
                IllegalArgumentException.class,
                () -> api.createProgram("vertex", "fragment"));

        assertSame(fragmentDeleteFailure, escaped);
        assertEquals(List.of(programDeleteFailure), List.of(escaped.getSuppressed()));
        assertEquals(
                List.of("delete-shader:102", "delete-shader:101", "delete-program:103"),
                gl.cleanup);
    }

    @Test
    void vertexCompileFailureChainNamesBothResourcesAndPreservesCauseAndCleanup() {
        RecordingShaderApi gl = new RecordingShaderApi();
        IllegalStateException cause = new IllegalStateException("vertex compile exploded");
        gl.compileFailureShader = 101;
        gl.compileFailure = cause;

        UiInitializationException failure = rendererShaderFailure(gl);

        assertShaderResourceFailure(failure, cause);
        assertEquals(List.of("delete-shader:101"), gl.cleanup);
    }

    @Test
    void fragmentCompileFailureChainNamesBothResourcesAndPreservesCauseAndCleanup() {
        RecordingShaderApi gl = new RecordingShaderApi();
        IllegalArgumentException cause =
                new IllegalArgumentException("fragment compile exploded");
        gl.compileFailureShader = 102;
        gl.compileFailure = cause;

        UiInitializationException failure = rendererShaderFailure(gl);

        assertShaderResourceFailure(failure, cause);
        assertEquals(
                List.of("delete-shader:102", "delete-shader:101"),
                gl.cleanup);
    }

    @Test
    void linkFailureChainNamesBothResourcesAndPreservesCauseAndCleanup() {
        RecordingShaderApi gl = new RecordingShaderApi();
        IllegalStateException cause = new IllegalStateException("link exploded");
        gl.linkFailure = cause;

        UiInitializationException failure = rendererShaderFailure(gl);

        assertShaderResourceFailure(failure, cause);
        assertEquals(
                List.of("delete-shader:102", "delete-shader:101", "delete-program:103"),
                gl.cleanup);
    }

    @Test
    void uniformQueryFailureChainNamesBothResourcesAndPreservesCauseAndCleanup() {
        RecordingShaderApi gl = new RecordingShaderApi();
        UnsupportedOperationException cause =
                new UnsupportedOperationException("uniform query exploded");
        gl.uniformFailure = cause;

        UiInitializationException failure = rendererShaderFailure(gl);

        assertShaderResourceFailure(failure, cause);
        assertEquals(
                List.of("delete-shader:102", "delete-shader:101", "delete-program:103"),
                gl.cleanup);
    }

    @Test
    void shaderReadFailuresNameTheExactResourceAndPreserveIOExceptionIdentity() {
        for (String resource : List.of(VERTEX_RESOURCE, FRAGMENT_RESOURCE)) {
            IOException cause = new IOException("read exploded: " + resource);

            IllegalStateException failure = assertThrows(
                    IllegalStateException.class,
                    () -> UiShader.loadSources(failingShaderLoader(resource, cause)));

            assertTrue(failure.getMessage().contains(resource), failure.getMessage());
            assertSame(cause, failure.getCause());
        }
    }

    @Test
    void batchCreationFailureCleansCompletedResourcesInExactReverseOrder() {
        LifecycleBackend backend = new LifecycleBackend("create-vao");

        UiInitializationException failure = assertThrows(
                UiInitializationException.class,
                () -> UiRenderer.create(
                        bundle(), backend, MainThreadGuard.captureCurrentThread()));

        assertSame(backend.failure, failure.getCause());
        assertEquals(List.of("delete-font", "delete-icons", "delete-program"), backend.cleanup);
    }

    @Test
    void partialBatchFailureCleansItsOwnHandlesBeforeEarlierResources() {
        LifecycleBackend backend = new LifecycleBackend("configure-batch");

        assertThrows(
                UiInitializationException.class,
                () -> UiRenderer.create(
                        bundle(), backend, MainThreadGuard.captureCurrentThread()));

        assertEquals(
                List.of(
                        "delete-ebo",
                        "delete-vbo",
                        "delete-vao",
                        "delete-font",
                        "delete-icons",
                        "delete-program"),
                backend.cleanup);
    }

    @Test
    void closeIsIdempotentAndUsesReverseCreationOrder() {
        LifecycleBackend backend = new LifecycleBackend(null);
        UiRenderer renderer = UiRenderer.create(
                bundle(), backend, MainThreadGuard.captureCurrentThread());

        renderer.close();
        renderer.close();

        assertEquals(
                List.of(
                        "delete-ebo",
                        "delete-vbo",
                        "delete-vao",
                        "delete-font",
                        "delete-icons",
                        "delete-program"),
                backend.cleanup);
    }

    @Test
    void closeContinuesAfterFailuresAndKeepsTheFirstFailurePrimary() {
        LifecycleBackend backend = new LifecycleBackend(null);
        UiRenderer renderer = UiRenderer.create(
                bundle(), backend, MainThreadGuard.captureCurrentThread());
        IllegalStateException eboFailure = new IllegalStateException("ebo cleanup");
        IllegalArgumentException fontFailure = new IllegalArgumentException("font cleanup");
        backend.deleteFailures.put(32, eboFailure);
        backend.deleteFailures.put(21, fontFailure);

        IllegalStateException escaped = assertThrows(IllegalStateException.class, renderer::close);

        assertSame(eboFailure, escaped);
        assertEquals(1, escaped.getSuppressed().length);
        assertSame(fontFailure, escaped.getSuppressed()[0]);
        assertEquals(
                List.of(
                        "delete-ebo",
                        "delete-vbo",
                        "delete-vao",
                        "delete-font",
                        "delete-icons",
                        "delete-program"),
                backend.cleanup);
        renderer.close();
        assertEquals(6, backend.cleanup.size());
    }

    @Test
    void runtimeShaderSourcesProduceTopLeftNdcAndExactStraightAlphaSrgbSemantics() {
        UiShader.Sources sources = UiShader.loadSources();
        ShaderSemanticEvaluator evaluator = ShaderSemanticEvaluator.parse(sources);

        assertEquals(new Point(-1.0d, 1.0d), evaluator.ndc(0.0d, 0.0d, 640.0d, 360.0d));
        assertEquals(new Point(1.0d, -1.0d), evaluator.ndc(640.0d, 360.0d, 640.0d, 360.0d));
        assertEquals(new Point(0.0d, 0.0d), evaluator.ndc(320.0d, 180.0d, 640.0d, 360.0d));

        assertEquals(0.00309597523219814d, evaluator.decode(0.04d), 1.0e-12d);
        assertEquals(0.214041140482233d, evaluator.decode(0.5d), 1.0e-12d);
        assertEquals(0.02584d, evaluator.encode(0.002d), 1.0e-12d);
        assertEquals(0.735356983052449d, evaluator.encode(0.5d), 1.0e-12d);

        Rgba shaded = evaluator.shade(
                new Rgba(0.02d, 0.5d, 0.8d, 0.4d),
                new Rgba(0.5d, 0.03d, 0.4d, 0.25d),
                true);
        assertRgba(
                new Rgba(
                        0.00428082280964465d,
                        0.00642123421446698d,
                        0.313744075829384d,
                        0.1d),
                shaded);

        Rgba solid = evaluator.shade(
                new Rgba(0.0d, 0.0d, 0.0d, 0.0d),
                new Rgba(0.02d, 0.5d, 0.8d, 0.3d),
                false);
        assertRgba(new Rgba(0.02d, 0.5d, 0.8d, 0.3d), solid);
    }

    @Test
    void runtimeShaderSourceContractRejectsOperandOrderTopologyOrSolidMutations() {
        UiShader.Sources sources = UiShader.loadSources();
        String fragment = sources.fragmentSource();
        List<String> mutatedFragments = List.of(
                fragment.replace("srgbToLinear(sampled.rgb)", "sampled.rgb"),
                fragment.replace("srgbToLinear(vTint.rgb)", "vTint.rgb"),
                fragment.replace(
                        "srgbToLinear(sampled.rgb) * srgbToLinear(vTint.rgb)",
                        "srgbToLinear(vTint.rgb) * srgbToLinear(sampled.rgb)"),
                fragment.replace("linearToSrgb(linearRgb)", "linearRgb"),
                fragment.replace("sampled.a * vTint.a", "vTint.a * sampled.a"),
                fragment.replace("vec4(1.0)", "vec4(0.0)"));

        for (String mutation : mutatedFragments) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> ShaderSemanticEvaluator.parse(
                            new UiShader.Sources(sources.vertexSource(), mutation)));
        }
        assertThrows(
                IllegalArgumentException.class,
                () -> ShaderSemanticEvaluator.parse(
                        new UiShader.Sources(
                                sources.vertexSource().replace(
                                        "1.0 - normalized.y * 2.0",
                                        "normalized.y * 2.0 - 1.0"),
                                fragment)));
    }

    @Test
    void runtimeShaderSourceTextIsOnlyPinnedToGlsl410AndForbiddenFeatures() {
        UiShader.Sources sources = UiShader.loadSources();

        for (String source : List.of(sources.vertexSource(), sources.fragmentSource())) {
            assertEquals("#version 410 core", source.lines().findFirst().orElseThrow());
            assertFalse(source.contains("430"));
            assertFalse(source.contains("layout(local_size"));
            assertFalse(source.contains("buffer "));
        }
    }

    private static UiAssetBundle bundle() {
        UiTextureData texture =
                new UiTextureData(1, 1, ByteBuffer.wrap(new byte[] {-1, -1, -1, -1}));
        BitmapGlyph missing = new BitmapGlyph(
                0xfffd, new UiUvRect(0.0f, 0.0f, 1.0f, 1.0f), 1, 0, 1);
        return new UiAssetBundle(texture, texture, new BitmapFont(1, 1, Map.of(), missing));
    }

    private static UiInitializationException rendererShaderFailure(RecordingShaderApi gl) {
        MainThreadGuard guard = MainThreadGuard.captureCurrentThread();
        OpenGlUiGpuBackend backend = new OpenGlUiGpuBackend(
                guard,
                new NoOpStateBackend(),
                new LwjglOpenGlUiApi(gl));
        return assertThrows(
                UiInitializationException.class,
                () -> UiRenderer.create(bundle(), backend, guard));
    }

    private static void assertShaderResourceFailure(
            UiInitializationException failure,
            Throwable expectedCause) {
        Throwable resourceFailure = failure.getCause();
        assertTrue(resourceFailure.getMessage().contains(VERTEX_RESOURCE));
        assertTrue(resourceFailure.getMessage().contains(FRAGMENT_RESOURCE));
        assertSame(expectedCause, resourceFailure.getCause());
    }

    private static ClassLoader failingShaderLoader(String failingResource, IOException failure) {
        return new ClassLoader(null) {
            @Override
            public InputStream getResourceAsStream(String name) {
                if (failingResource.equals(name)) {
                    return new InputStream() {
                        @Override
                        public int read() throws IOException {
                            throw failure;
                        }
                    };
                }
                return new ByteArrayInputStream(
                        "#version 410 core".getBytes(StandardCharsets.UTF_8));
            }
        };
    }

    private static void assertRgba(Rgba expected, Rgba actual) {
        assertEquals(expected.red(), actual.red(), 1.0e-12d);
        assertEquals(expected.green(), actual.green(), 1.0e-12d);
        assertEquals(expected.blue(), actual.blue(), 1.0e-12d);
        assertEquals(expected.alpha(), actual.alpha(), 1.0e-12d);
    }

    private record Point(double x, double y) {}

    private record Rgba(double red, double green, double blue, double alpha) {}

    private static final class ShaderSemanticEvaluator {
        private static final String NUMBER = "([0-9]+(?:\\.[0-9]+)?)";
        private final double decodeCutoff;
        private final double decodeDivisor;
        private final double decodeOffset;
        private final double decodeScale;
        private final double decodeExponent;
        private final double encodeCutoff;
        private final double encodeMultiplier;
        private final double encodeScale;
        private final double encodeExponent;
        private final double encodeOffset;

        private ShaderSemanticEvaluator(
                double decodeCutoff,
                double decodeDivisor,
                double decodeOffset,
                double decodeScale,
                double decodeExponent,
                double encodeCutoff,
                double encodeMultiplier,
                double encodeScale,
                double encodeExponent,
                double encodeOffset) {
            this.decodeCutoff = decodeCutoff;
            this.decodeDivisor = decodeDivisor;
            this.decodeOffset = decodeOffset;
            this.decodeScale = decodeScale;
            this.decodeExponent = decodeExponent;
            this.encodeCutoff = encodeCutoff;
            this.encodeMultiplier = encodeMultiplier;
            this.encodeScale = encodeScale;
            this.encodeExponent = encodeExponent;
            this.encodeOffset = encodeOffset;
        }

        static ShaderSemanticEvaluator parse(UiShader.Sources sources) {
            String vertexMain = compact(functionBody(sources.vertexSource(), "void", "main", ""));
            String expectedVertexMain = compact("""
                    vec2 normalized = aPosition / framebufferSize;
                    vec2 ndc = vec2(normalized.x * 2.0 - 1.0, 1.0 - normalized.y * 2.0);
                    gl_Position = vec4(ndc, 0.0, 1.0);
                    vUv = aUv;
                    vTint = aTint;
                    """);
            requireEqual(expectedVertexMain, vertexMain, "vertex main topology");

            String fragment = sources.fragmentSource();
            Matcher decode = requireMatch(
                    functionBody(fragment, "vec3", "srgbToLinear", "vec3 value"),
                    "bvec3\\s+cutoff\\s*=\\s*lessThanEqual\\(value,\\s*vec3\\(" + NUMBER
                            + "\\)\\);\\s*vec3\\s+lower\\s*=\\s*value\\s*/\\s*" + NUMBER
                            + ";\\s*vec3\\s+upper\\s*=\\s*pow\\(\\(value\\s*\\+\\s*" + NUMBER
                            + "\\)\\s*/\\s*" + NUMBER + ",\\s*vec3\\(" + NUMBER
                            + "\\)\\);\\s*return\\s+mix\\(upper,\\s*lower,\\s*cutoff\\);",
                    "sRGB decode topology");
            Matcher encode = requireMatch(
                    functionBody(fragment, "vec3", "linearToSrgb", "vec3 value"),
                    "bvec3\\s+cutoff\\s*=\\s*lessThanEqual\\(value,\\s*vec3\\(" + NUMBER
                            + "\\)\\);\\s*vec3\\s+lower\\s*=\\s*value\\s*\\*\\s*" + NUMBER
                            + ";\\s*vec3\\s+upper\\s*=\\s*" + NUMBER
                            + "\\s*\\*\\s*pow\\(max\\(value,\\s*vec3\\(0\\.0\\)\\),\\s*vec3\\(1\\.0\\s*/\\s*"
                            + NUMBER + "\\)\\)\\s*-\\s*" + NUMBER
                            + ";\\s*return\\s+mix\\(upper,\\s*lower,\\s*cutoff\\);",
                    "sRGB encode topology");
            String fragmentMain = compact(functionBody(fragment, "void", "main", ""));
            String expectedFragmentMain = compact("""
                    vec4 sampled = textureSamplingEnabled != 0 ? texture(uiTexture, vUv) : vec4(1.0);
                    vec3 linearRgb = srgbToLinear(sampled.rgb) * srgbToLinear(vTint.rgb);
                    fragColor = vec4(linearToSrgb(linearRgb), sampled.a * vTint.a);
                    """);
            requireEqual(expectedFragmentMain, fragmentMain, "fragment main topology");

            return new ShaderSemanticEvaluator(
                    group(decode, 1),
                    group(decode, 2),
                    group(decode, 3),
                    group(decode, 4),
                    group(decode, 5),
                    group(encode, 1),
                    group(encode, 2),
                    group(encode, 3),
                    group(encode, 4),
                    group(encode, 5));
        }

        Point ndc(double x, double y, double framebufferWidth, double framebufferHeight) {
            return new Point(
                    x / framebufferWidth * 2.0d - 1.0d,
                    1.0d - y / framebufferHeight * 2.0d);
        }

        double decode(double value) {
            return value <= decodeCutoff
                    ? value / decodeDivisor
                    : Math.pow((value + decodeOffset) / decodeScale, decodeExponent);
        }

        double encode(double value) {
            return value <= encodeCutoff
                    ? value * encodeMultiplier
                    : encodeScale * Math.pow(Math.max(value, 0.0d), 1.0d / encodeExponent)
                            - encodeOffset;
        }

        Rgba shade(Rgba sampledInput, Rgba tint, boolean textured) {
            Rgba sampled = textured
                    ? sampledInput
                    : new Rgba(1.0d, 1.0d, 1.0d, 1.0d);
            return new Rgba(
                    encode(decode(sampled.red()) * decode(tint.red())),
                    encode(decode(sampled.green()) * decode(tint.green())),
                    encode(decode(sampled.blue()) * decode(tint.blue())),
                    sampled.alpha() * tint.alpha());
        }

        private static String functionBody(
                String source, String returnType, String name, String parameters) {
            Pattern pattern = Pattern.compile(
                    Pattern.quote(returnType)
                            + "\\s+"
                            + Pattern.quote(name)
                            + "\\s*\\(\\s*"
                            + Pattern.quote(parameters)
                            + "\\s*\\)\\s*\\{([^{}]*)\\}",
                    Pattern.DOTALL);
            Matcher matcher = pattern.matcher(source);
            if (!matcher.find()) {
                throw new IllegalArgumentException("missing shader function: " + name);
            }
            return matcher.group(1);
        }

        private static Matcher requireMatch(String value, String regex, String label) {
            Matcher matcher = Pattern.compile("\\s*" + regex + "\\s*", Pattern.DOTALL).matcher(value);
            if (!matcher.matches()) {
                throw new IllegalArgumentException("invalid " + label);
            }
            return matcher;
        }

        private static void requireEqual(String expected, String actual, String label) {
            if (!expected.equals(actual)) {
                throw new IllegalArgumentException("invalid " + label);
            }
        }

        private static String compact(String value) {
            return value.replaceAll("\\s+", " ").trim();
        }

        private static double group(Matcher matcher, int group) {
            return Double.parseDouble(matcher.group(group));
        }
    }

    private static final class LifecycleBackend implements UiGpuBackend {
        private final String failAt;
        private final RuntimeException failure = new IllegalStateException("create failed");
        private final List<String> cleanup = new ArrayList<>();
        private final Map<Integer, RuntimeException> deleteFailures = new java.util.HashMap<>();
        private int textures;
        private int buffers;
        private String vertexSource;
        private String fragmentSource;

        private LifecycleBackend(String failAt) {
            this.failAt = failAt;
        }

        @Override
        public int createProgram(String vertexSource, String fragmentSource) {
            this.vertexSource = vertexSource;
            this.fragmentSource = fragmentSource;
            fail("create-program");
            return 10;
        }

        @Override public void useProgram(int program) {}
        @Override public void setFramebufferSize(int program, float width, float height) {}
        @Override public void setTextureSampler(int program, int textureUnit) {}
        @Override public void setTextureSamplingEnabled(int program, boolean enabled) {}

        @Override
        public void deleteProgram(int program) {
            cleanup.add("delete-program");
            throwDelete(program);
        }

        @Override
        public int createTexture(UiTextureData texture) {
            fail(textures == 0 ? "create-icons" : "create-font");
            return textures++ == 0 ? 20 : 21;
        }

        @Override public void bindTextureUnitZero(int texture) {}

        @Override
        public void deleteTexture(int texture) {
            cleanup.add(texture == 20 ? "delete-icons" : "delete-font");
            throwDelete(texture);
        }

        @Override
        public int createVertexArray() {
            fail("create-vao");
            return 30;
        }

        @Override
        public int createBuffer() {
            fail(buffers == 0 ? "create-vbo" : "create-ebo");
            return buffers++ == 0 ? 31 : 32;
        }

        @Override
        public void configureBatch(int vertexArray, int vertexBuffer, int elementBuffer) {
            fail("configure-batch");
        }

        @Override public void uploadBatch(int vertexArray, int vertexBuffer, int elementBuffer, float[] vertices, int[] indices) {}
        @Override public void drawBatch(int vertexArray, int indexCount) {}

        @Override
        public void deleteBuffer(int buffer) {
            cleanup.add(buffer == 31 ? "delete-vbo" : "delete-ebo");
            throwDelete(buffer);
        }

        @Override
        public void deleteVertexArray(int vertexArray) {
            cleanup.add("delete-vao");
            throwDelete(vertexArray);
        }

        @Override public RenderStateSnapshot captureState() { return null; }
        @Override public void applyUiState(int framebufferWidth, int framebufferHeight) {}
        @Override public void setClip(Optional<ScissorBox> clip) {}
        @Override public void restoreState(RenderStateSnapshot snapshot) {}

        private void fail(String operation) {
            if (operation.equals(failAt)) {
                throw failure;
            }
        }

        private void throwDelete(int handle) {
            RuntimeException failure = deleteFailures.get(handle);
            if (failure != null) {
                throw failure;
            }
        }
    }

    private record TextureConfig(
            UiTextureData texture,
            int minFilter,
            int magFilter,
            int wrapS,
            int wrapT,
            int baseLevel,
            int maxLevel) {}

    private record BatchConfig(
            int vertexArray,
            int vertexBuffer,
            int elementBuffer,
            int stride,
            int positionLocation,
            int positionOffset,
            int uvLocation,
            int uvOffset,
            int tintLocation,
            int tintOffset) {}

    private static final class RecordingOpenGlApi implements OpenGlUiApi {
        private final List<TextureConfig> textures = new ArrayList<>();
        private final List<BatchConfig> batches = new ArrayList<>();
        private final List<String> calls = new ArrayList<>();

        @Override public int createProgram(String vertexSource, String fragmentSource) { return 10; }
        @Override public int uniformLocation(int program, String name) { return 1; }
        @Override public void useProgram(int program) {}
        @Override public void uniform2f(int location, float x, float y) {}
        @Override public void uniform1i(int location, int value) {}
        @Override public void deleteProgram(int program) {}
        @Override
        public int createTexture(
                UiTextureData texture,
                int minFilter,
                int magFilter,
                int wrapS,
                int wrapT,
                int baseLevel,
                int maxLevel) {
            textures.add(new TextureConfig(
                    texture, minFilter, magFilter, wrapS, wrapT, baseLevel, maxLevel));
            return 40;
        }
        @Override public void activeTexture(int textureUnit) { calls.add("activeTexture:" + textureUnit); }
        @Override public void bindTexture2d(int texture) { calls.add("bindTexture:" + texture); }
        @Override public void deleteTexture(int texture) {}
        @Override public int createVertexArray() { return 30; }
        @Override public int createBuffer() { return 31; }
        @Override
        public void configureBatch(
                int vertexArray,
                int vertexBuffer,
                int elementBuffer,
                int stride,
                int positionLocation,
                int positionOffset,
                int uvLocation,
                int uvOffset,
                int tintLocation,
                int tintOffset) {
            batches.add(new BatchConfig(
                    vertexArray,
                    vertexBuffer,
                    elementBuffer,
                    stride,
                    positionLocation,
                    positionOffset,
                    uvLocation,
                    uvOffset,
                    tintLocation,
                    tintOffset));
        }
        @Override public void uploadBatch(int vertexArray, int vertexBuffer, int elementBuffer, float[] vertices, int[] indices) {}
        @Override public void drawBatch(int vertexArray, int indexCount, int primitive, int indexType) {
            calls.add("draw:" + vertexArray + ":" + indexCount + ":" + primitive + ":" + indexType);
        }
        @Override public void deleteBuffer(int buffer) {}
        @Override public void deleteVertexArray(int vertexArray) {}
        @Override public void enable(int capability) { calls.add("enable:" + capability); }
        @Override public void disable(int capability) { calls.add("disable:" + capability); }
        @Override public void depthMask(boolean enabled) { calls.add("depthMask:" + enabled); }
        @Override public void blendFuncSeparate(int sourceRgb, int destinationRgb, int sourceAlpha, int destinationAlpha) {
            calls.add("blendFunc:" + sourceRgb + ":" + destinationRgb + ":" + sourceAlpha + ":" + destinationAlpha);
        }
        @Override public void blendEquationSeparate(int rgb, int alpha) { calls.add("blendEquation:" + rgb + ":" + alpha); }
        @Override public void viewport(int x, int y, int width, int height) { calls.add("viewport:" + x + ":" + y + ":" + width + ":" + height); }
        @Override public void scissor(int x, int y, int width, int height) { calls.add("scissor:" + x + ":" + y + ":" + width + ":" + height); }
    }

    private static final class RecordingShaderApi implements OpenGlUiShaderApi {
        private final List<String> cleanup = new ArrayList<>();
        private int shaderCount;
        private int compileFailureShader = -1;
        private RuntimeException compileFailure;
        private RuntimeException linkFailure;
        private RuntimeException uniformFailure;
        private RuntimeException fragmentDeleteFailure;
        private RuntimeException vertexDeleteFailure;
        private RuntimeException programDeleteFailure;

        @Override public int createShader(int type) { return shaderCount++ == 0 ? 101 : 102; }
        @Override public void setShaderSource(int shader, String source) {}
        @Override
        public void compileShader(int shader) {
            if (shader == compileFailureShader) {
                throw compileFailure;
            }
        }
        @Override public boolean shaderCompileSucceeded(int shader) { return true; }
        @Override public String shaderInfoLog(int shader) { return ""; }
        @Override public int createProgram() { return 103; }
        @Override public void attachShader(int program, int shader) {}
        @Override
        public void linkProgram(int program) {
            if (linkFailure != null) {
                throw linkFailure;
            }
        }
        @Override public boolean programLinkSucceeded(int program) { return true; }
        @Override public String programInfoLog(int program) { return ""; }
        @Override
        public int uniformLocation(int program, String name) {
            if (uniformFailure != null) {
                throw uniformFailure;
            }
            return 1;
        }
        @Override
        public void deleteShader(int shader) {
            cleanup.add("delete-shader:" + shader);
            RuntimeException failure = shader == 102
                    ? fragmentDeleteFailure
                    : vertexDeleteFailure;
            if (failure != null) {
                throw failure;
            }
        }
        @Override
        public void deleteProgram(int program) {
            cleanup.add("delete-program:" + program);
            if (programDeleteFailure != null) {
                throw programDeleteFailure;
            }
        }
    }

    private static final class NoOpStateBackend implements RenderStateBackend {
        @Override public RenderStateSnapshot capture() { return null; }
        @Override public void apply(com.overlord.renderer.state.RenderStateSpec state) {}
        @Override public void restore(RenderStateSnapshot snapshot) {}
        @Override public void clearColorAndDepth() {}
    }
}

package com.overlord.renderer.pass;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.overlord.assets.ResourceLocation;
import com.overlord.renderer.RenderSurfaceMetrics;
import com.overlord.renderer.TextureBinding;
import com.overlord.renderer.feedback.FeedbackVisibility;
import com.overlord.renderer.feedback.InteractionFeedbackFrame;
import com.overlord.renderer.feedback.ParticleRenderBatch;
import com.overlord.renderer.feedback.UnitCubeMesh;
import com.overlord.renderer.feedback.WorldItemVisual;
import com.overlord.renderer.queue.RenderQueue;
import com.overlord.renderer.shader.ShaderBinding;
import com.overlord.renderer.state.BlendMode;
import com.overlord.renderer.state.DepthFunction;
import com.overlord.renderer.state.RenderStateBackend;
import com.overlord.renderer.state.RenderStateSnapshot;
import com.overlord.renderer.state.RenderStateSpec;
import com.overlord.renderer.state.Viewport;
import com.overlord.renderer.texture.TextureRegion;
import com.overlord.renderer.visual.RenderVisualSettings;
import com.overlord.worlditem.api.WorldItemId;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector2fc;
import org.joml.Vector3fc;
import org.junit.jupiter.api.Test;

class WorldItemVisualPassTest {
    private static final TextureRegion STONE =
            new TextureRegion(
                    ResourceLocation.parse("gaia:stone_top"), 16, 0, 16, 16, 48, 16);
    private static final TextureRegion MISSING =
            new TextureRegion(
                    ResourceLocation.parse("gaia:missing"), 0, 0, 16, 16, 48, 16);
    private static final RenderStateSnapshot INCOMING =
            new RenderStateSnapshot(
                    false,
                    DepthFunction.LESS,
                    false,
                    true,
                    1,
                    2,
                    3,
                    4,
                    5,
                    6,
                    false,
                    17,
                    18,
                    19,
                    true,
                    2.0f,
                    3.0f,
                    20,
                    21,
                    22,
                    new Viewport(7, 8, 900, 700));

    @Test
    void drawsOneSharedQuarterScaleCubeAtEachExactLogicalPositionAndUv() {
        Fixture fixture = new Fixture();
        List<WorldItemVisual> visuals =
                List.of(
                        visual(2, 7, 1.25, 2.5, 3.75, STONE),
                        visual(9, 4, -6.0, 5.0, -4.0, MISSING));
        RenderContext context = context(visuals, fixture.drawMetrics);

        fixture.pass.render(context, new RenderQueue());

        assertEquals("world-items", fixture.pass.id());
        assertEquals(1, fixture.shader.useCalls);
        assertEquals(List.of(new Matrix4f()), fixture.shader.projections);
        assertEquals(List.of(new Matrix4f()), fixture.shader.views);
        assertEquals(List.of(0), fixture.shader.samplerUnits);
        assertEquals(
                List.of(
                        new Matrix4f().translation(1.25f, 2.5f, 3.75f).scale(0.25f),
                        new Matrix4f().translation(-6.0f, 5.0f, -4.0f).scale(0.25f)),
                fixture.shader.models);
        assertEquals(List.of(STONE.uMin(), MISSING.uMin()), fixture.shader.uMins);
        assertEquals(List.of(STONE.uMax(), MISSING.uMax()), fixture.shader.uMaxs);
        assertEquals(List.of(STONE.vMin(), MISSING.vMin()), fixture.shader.vMins);
        assertEquals(List.of(STONE.vMax(), MISSING.vMax()), fixture.shader.vMaxs);
        assertEquals(List.of(0), fixture.texture.units);
        assertEquals(2, fixture.cube.drawCalls);
        assertEquals(List.of(12L, 12L), fixture.drawMetrics);
        assertEquals(
                new RenderStateSpec(
                        true,
                        DepthFunction.LEQUAL,
                        true,
                        BlendMode.DISABLED,
                        false,
                        false,
                        0.0f,
                        0.0f),
                fixture.state.requestedState);
        assertEquals(INCOMING, fixture.state.current);
        assertEquals(1, fixture.state.restoreCalls);
        assertEquals(visuals, context.feedback().worldItems());
    }

    @Test
    void disablesCullingForTheDrawAndRestoresAnIncomingEnabledCullState() {
        RenderStateSnapshot incomingWithCulling =
                new RenderStateSnapshot(
                        false,
                        DepthFunction.LESS,
                        false,
                        true,
                        1,
                        2,
                        3,
                        4,
                        5,
                        6,
                        true,
                        17,
                        18,
                        19,
                        true,
                        2.0f,
                        3.0f,
                        20,
                        21,
                        22,
                        new Viewport(7, 8, 900, 700));
        Fixture fixture = new Fixture(incomingWithCulling);

        fixture.pass.render(
                context(List.of(visual(1, 0, 1, 2, 3, STONE))),
                new RenderQueue());

        assertFalse(fixture.state.requestedState.cullFace());
        assertEquals(incomingWithCulling, fixture.state.current);
        assertEquals(1, fixture.state.restoreCalls);
    }

    @Test
    void emptyVisualListPerformsNoStateShaderTextureOrDrawWork() {
        Fixture fixture = new Fixture();

        fixture.pass.render(context(List.of(), fixture.drawMetrics), new RenderQueue());

        assertEquals(0, fixture.state.captureCalls);
        assertEquals(0, fixture.shader.useCalls);
        assertEquals(List.of(), fixture.texture.units);
        assertEquals(0, fixture.cube.drawCalls);
        assertEquals(List.of(), fixture.drawMetrics);
    }

    @Test
    void shaderFailureRestoresExactIncomingState() {
        Fixture fixture = new Fixture();
        IllegalStateException failure = new IllegalStateException("shader failed");
        fixture.shader.failure = failure;

        IllegalStateException escaped =
                assertThrows(
                        IllegalStateException.class,
                        () ->
                                fixture.pass.render(
                                        context(List.of(visual(1, 0, 1, 2, 3, STONE))),
                                        new RenderQueue()));

        assertSame(failure, escaped);
        assertEquals(INCOMING, fixture.state.current);
        assertEquals(1, fixture.state.restoreCalls);
    }

    @Test
    void drawFailureAfterShaderTextureAndMeshBindingRestoresExactIncomingState() {
        Fixture fixture = new Fixture();
        IllegalStateException failure = new IllegalStateException("draw failed");
        fixture.cube.failure = failure;

        IllegalStateException escaped =
                assertThrows(
                        IllegalStateException.class,
                        () ->
                                fixture.pass.render(
                                        context(List.of(visual(1, 0, 1, 2, 3, STONE))),
                                        new RenderQueue()));

        assertSame(failure, escaped);
        assertEquals(INCOMING, fixture.state.current);
        assertEquals(1, fixture.state.restoreCalls);
        assertEquals(List.of(), fixture.drawMetrics);
    }

    @Test
    void bytecodeHasNoGameplayServiceMutationReservationPhysicsOrAlternateStoreDependency()
            throws Exception {
        String resource = WorldItemVisualPass.class.getName().replace('.', '/') + ".class";
        byte[] bytecode;
        try (InputStream input =
                WorldItemVisualPass.class.getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(input, resource);
            bytecode = input.readAllBytes();
        }
        String constantPool = new String(bytecode, StandardCharsets.ISO_8859_1);
        for (String forbidden :
                List.of(
                        "WorldItemService",
                        "LogicalWorldItemService",
                        "WorldItemSnapshot",
                        "WorldItemReservation",
                        "InventoryReservation",
                        "WorldMutation",
                        "PhysicsBody",
                        "ChunkDirty",
                        "ChunkMesh")) {
            assertFalse(constantPool.contains(forbidden), forbidden);
        }
    }

    @Test
    void shadersUseGlsl410SharedAtlasUvAndExactAlphaCutout() throws Exception {
        String vertex = readResource("assets/overlord/shaders/feedback/world_item.vert");
        String fragment = readResource("assets/overlord/shaders/feedback/world_item.frag");

        assertTrue(vertex.startsWith("#version 410 core"));
        assertTrue(fragment.startsWith("#version 410 core"));
        assertTrue(vertex.contains("layout (location = 0) in vec3 aPosition"));
        assertTrue(vertex.contains("layout (location = 1) in vec2 aUv"));
        assertTrue(vertex.contains("uniform mat4 model"));
        assertTrue(fragment.contains("uniform sampler2D blockAtlas"));
        assertTrue(fragment.contains("sampled.a < 0.1"));
        assertTrue(fragment.contains("discard"));
        assertTrue(fragment.contains("vec3 srgbToLinear(vec3 srgb)"));
        assertTrue(fragment.contains("vec3 linearToSrgb(vec3 linear)"));
        assertTrue(fragment.contains("vec3 linearColor = srgbToLinear(sampled.rgb)"));
        assertTrue(fragment.contains("vec3 encodedColor = linearToSrgb(linearColor)"));
        assertTrue(fragment.contains("fragmentColor = vec4(encodedColor, sampled.a)"));
        assertEquals(2, occurrences(fragment, "srgbToLinear("));
        assertEquals(2, occurrences(fragment, "linearToSrgb("));
        assertFalse(fragment.contains("GL_FRAMEBUFFER_SRGB"));
        for (String source : List.of(vertex, fragment)) {
            assertFalse(source.contains("#version 420"));
            assertFalse(source.contains("#version 430"));
            assertFalse(source.contains("buffer "));
            assertFalse(source.contains("imageStore"));
        }
    }

    private static String readResource(String path) throws Exception {
        try (InputStream input =
                WorldItemVisualPassTest.class.getClassLoader().getResourceAsStream(path)) {
            assertNotNull(input, path);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static int occurrences(String source, String token) {
        return (source.length() - source.replace(token, "").length()) / token.length();
    }

    private static WorldItemVisual visual(
            long id,
            long revision,
            double x,
            double y,
            double z,
            TextureRegion region) {
        return new WorldItemVisual(new WorldItemId(id), revision, x, y, z, region);
    }

    private static RenderContext context(List<WorldItemVisual> visuals) {
        return context(visuals, ignored -> {});
    }

    private static RenderContext context(
            List<WorldItemVisual> visuals, List<Long> drawMetrics) {
        return context(visuals, drawMetrics::add);
    }

    private static RenderContext context(
            List<WorldItemVisual> visuals,
            com.overlord.renderer.metrics.RenderMetricsRecorder metricsRecorder) {
        return new RenderContext(
                new Matrix4f(),
                new Matrix4f(),
                RenderVisualSettings.milestoneOneDefaults(),
                metricsRecorder,
                new RenderSurfaceMetrics(1024, 768, 1024, 768, 1, 1),
                new InteractionFeedbackFrame(
                        new FeedbackVisibility(true, true, true, false),
                        Optional.empty(),
                        visuals,
                        new ParticleRenderBatch(List.of())));
    }

    private static final class Fixture {
        private final RecordingState state;
        private final RecordingShader shader;
        private final RecordingTexture texture;
        private final RecordingCube cube;
        private final List<Long> drawMetrics = new ArrayList<>();
        private final WorldItemVisualPass pass;

        private Fixture() {
            this(INCOMING);
        }

        private Fixture(RenderStateSnapshot incoming) {
            state = new RecordingState(incoming);
            shader = new RecordingShader(state);
            texture = new RecordingTexture(state);
            cube = new RecordingCube(state);
            pass = new WorldItemVisualPass(state, shader, texture, cube);
        }
    }

    private static final class RecordingShader implements ShaderBinding {
        private final RecordingState state;
        private int useCalls;
        private RuntimeException failure;
        private final List<Matrix4f> projections = new ArrayList<>();
        private final List<Matrix4f> views = new ArrayList<>();
        private final List<Matrix4f> models = new ArrayList<>();
        private final List<Integer> samplerUnits = new ArrayList<>();
        private final List<Float> uMins = new ArrayList<>();
        private final List<Float> uMaxs = new ArrayList<>();
        private final List<Float> vMins = new ArrayList<>();
        private final List<Float> vMaxs = new ArrayList<>();

        private RecordingShader(RecordingState state) {
            this.state = state;
        }

        @Override
        public int programId() {
            return 120;
        }

        @Override
        public void use() {
            useCalls++;
            state.setProgram(120);
            if (failure != null) {
                throw failure;
            }
        }

        @Override
        public void setMatrix4(String uniform, Matrix4fc value) {
            switch (uniform) {
                case "projection" -> projections.add(new Matrix4f(value));
                case "view" -> views.add(new Matrix4f(value));
                case "model" -> models.add(new Matrix4f(value));
                default -> throw new AssertionError(uniform);
            }
        }

        @Override
        public void setInt(String uniform, int value) {
            assertEquals("blockAtlas", uniform);
            samplerUnits.add(value);
        }

        @Override
        public void setFloat(String uniform, float value) {
            switch (uniform) {
                case "uMin" -> uMins.add(value);
                case "uMax" -> uMaxs.add(value);
                case "vMin" -> vMins.add(value);
                case "vMax" -> vMaxs.add(value);
                default -> throw new AssertionError(uniform);
            }
        }

        @Override
        public void setVector2(String uniform, Vector2fc value) {}

        @Override
        public void setVector3(String uniform, Vector3fc value) {}
    }

    private static final class RecordingTexture implements TextureBinding {
        private final RecordingState state;
        private final List<Integer> units = new ArrayList<>();

        private RecordingTexture(RecordingState state) {
            this.state = state;
        }

        @Override
        public void bind(int textureUnit) {
            units.add(textureUnit);
            state.setTexture(textureUnit, 220);
        }
    }

    private static final class RecordingCube implements UnitCubeMesh {
        private final RecordingState state;
        private int drawCalls;
        private RuntimeException failure;

        private RecordingCube(RecordingState state) {
            this.state = state;
        }

        @Override
        public void draw() {
            drawCalls++;
            state.setBuffers(301, 302, 0);
            if (failure != null) {
                throw failure;
            }
        }

        @Override
        public void cleanup() {}
    }

    private static final class RecordingState implements RenderStateBackend {
        private RenderStateSnapshot current;
        private int captureCalls;
        private int restoreCalls;
        private RenderStateSpec requestedState;

        private RecordingState(RenderStateSnapshot current) {
            this.current = current;
        }

        @Override
        public RenderStateSnapshot capture() {
            captureCalls++;
            return current;
        }

        @Override
        public void apply(RenderStateSpec state) {
            requestedState = state;
            current =
                    new RenderStateSnapshot(
                            state.depthTest(),
                            state.depthFunction(),
                            state.depthWrite(),
                            state.blendMode() != BlendMode.DISABLED,
                            current.blendSourceRgb(),
                            current.blendDestinationRgb(),
                            current.blendSourceAlpha(),
                            current.blendDestinationAlpha(),
                            current.blendEquationRgb(),
                            current.blendEquationAlpha(),
                            state.cullFace(),
                            current.vertexArray(),
                            current.arrayBuffer(),
                            current.elementArrayBuffer(),
                            state.polygonOffsetFill(),
                            state.polygonOffsetFactor(),
                            state.polygonOffsetUnits(),
                            current.currentProgram(),
                            current.activeTexture(),
                            current.texture2dUnit0(),
                            current.viewport());
        }

        @Override
        public void restore(RenderStateSnapshot snapshot) {
            current = snapshot;
            restoreCalls++;
        }

        @Override
        public void clearColorAndDepth() {}

        private void setProgram(int program) {
            current = copy(
                    current.vertexArray(),
                    current.arrayBuffer(),
                    current.elementArrayBuffer(),
                    program,
                    current.activeTexture(),
                    current.texture2dUnit0());
        }

        private void setTexture(int unit, int texture) {
            current = copy(
                    current.vertexArray(),
                    current.arrayBuffer(),
                    current.elementArrayBuffer(),
                    current.currentProgram(),
                    unit,
                    texture);
        }

        private void setBuffers(int vertexArray, int arrayBuffer, int elementArrayBuffer) {
            current = copy(
                    vertexArray,
                    arrayBuffer,
                    elementArrayBuffer,
                    current.currentProgram(),
                    current.activeTexture(),
                    current.texture2dUnit0());
        }

        private RenderStateSnapshot copy(
                int vertexArray,
                int arrayBuffer,
                int elementArrayBuffer,
                int program,
                int activeTexture,
                int texture) {
            return new RenderStateSnapshot(
                    current.depthTest(),
                    current.depthFunction(),
                    current.depthWrite(),
                    current.blend(),
                    current.blendSourceRgb(),
                    current.blendDestinationRgb(),
                    current.blendSourceAlpha(),
                    current.blendDestinationAlpha(),
                    current.blendEquationRgb(),
                    current.blendEquationAlpha(),
                    current.cullFace(),
                    vertexArray,
                    arrayBuffer,
                    elementArrayBuffer,
                    current.polygonOffsetFill(),
                    current.polygonOffsetFactor(),
                    current.polygonOffsetUnits(),
                    program,
                    activeTexture,
                    texture,
                    current.viewport());
        }
    }
}

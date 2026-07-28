package com.overlord.renderer.pass;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.overlord.renderer.RenderSurfaceMetrics;
import com.overlord.renderer.TextureBinding;
import com.overlord.renderer.feedback.BlockDamageVisual;
import com.overlord.renderer.feedback.DamageAtlasLayout;
import com.overlord.renderer.feedback.DamageAtlasResourceLoader;
import com.overlord.renderer.feedback.FeedbackVisibility;
import com.overlord.renderer.feedback.InteractionFeedbackFrame;
import com.overlord.renderer.feedback.ParticleRenderBatch;
import com.overlord.renderer.feedback.UnitCubeMesh;
import com.overlord.renderer.queue.RenderQueue;
import com.overlord.renderer.shader.ShaderBinding;
import com.overlord.renderer.state.BlendMode;
import com.overlord.renderer.state.DepthFunction;
import com.overlord.renderer.state.RenderStateBackend;
import com.overlord.renderer.state.RenderStateSnapshot;
import com.overlord.renderer.state.RenderStateSpec;
import com.overlord.renderer.state.Viewport;
import com.overlord.renderer.visual.RenderVisualSettings;
import java.lang.reflect.Field;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector2fc;
import org.joml.Vector3fc;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class BlockDamageOverlayPassTest {
    private static final RenderStateSnapshot INCOMING =
            new RenderStateSnapshot(
                    false,
                    DepthFunction.LESS,
                    true,
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
                    false,
                    2.0f,
                    3.0f,
                    20,
                    21,
                    22,
                    new Viewport(7, 8, 900, 700));

    @Test
    void drawsOneSharedCubeAtExactBlockTransformUsingAuthoritativeStage() {
        Fixture fixture = new Fixture();

        fixture.pass.render(
                context(
                        visible(),
                        new BlockDamageVisual(3, 4, -5, 7),
                        fixture.drawMetrics),
                new RenderQueue());

        assertEquals("block-damage", fixture.pass.id());
        assertEquals(1, fixture.shader.useCalls);
        assertEquals(List.of(new Matrix4f().translation(3, 4, -5)), fixture.shader.models);
        assertEquals(List.of(new Matrix4f()), fixture.shader.projections);
        assertEquals(List.of(new Matrix4f()), fixture.shader.views);
        assertEquals(List.of(0), fixture.shader.samplerUnits);
        var region = fixture.layout.region(7);
        assertEquals(List.of(region.uMin()), fixture.shader.uMins);
        assertEquals(List.of(region.uMax()), fixture.shader.uMaxs);
        assertEquals(List.of(region.vMin()), fixture.shader.vMins);
        assertEquals(List.of(region.vMax()), fixture.shader.vMaxs);
        assertEquals(1, fixture.texture.bindCalls);
        assertEquals(List.of(0), fixture.texture.units);
        assertEquals(1, fixture.cube.drawCalls);
        assertEquals(List.of(12L), fixture.drawMetrics);
        assertEquals(
                new RenderStateSpec(
                        true,
                        DepthFunction.LEQUAL,
                        false,
                        BlendMode.DISABLED,
                        false,
                        true,
                        -1.0f,
                        -1.0f),
                fixture.state.requestedState);
        assertEquals(INCOMING, fixture.state.current);
        assertEquals(1, fixture.state.restoreCalls);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("hiddenOrInvalidCases")
    void skipsAbsentHiddenOrInvalidDamage(
            String label, FeedbackVisibility visibility, BlockDamageVisual damage) {
        Fixture fixture = new Fixture();

        fixture.pass.render(context(visibility, damage), new RenderQueue());

        assertEquals(0, fixture.state.captureCalls);
        assertEquals(0, fixture.shader.useCalls);
        assertEquals(0, fixture.texture.bindCalls);
        assertEquals(0, fixture.cube.drawCalls);
        assertEquals(List.of(), fixture.drawMetrics);
    }

    @Test
    void targetReplacementUsesOnlyCurrentImmutableFrameTarget() {
        Fixture fixture = new Fixture();

        fixture.pass.render(context(visible(), new BlockDamageVisual(1, 2, 3, 2)), new RenderQueue());
        fixture.pass.render(context(visible(), new BlockDamageVisual(8, 9, 10, 3)), new RenderQueue());

        assertEquals(
                List.of(
                        new Matrix4f().translation(1, 2, 3),
                        new Matrix4f().translation(8, 9, 10)),
                fixture.shader.models);
        assertEquals(2, fixture.cube.drawCalls);
    }

    @Test
    void restoresCompleteIncomingStateWhenShaderFails() {
        Fixture fixture = new Fixture();
        IllegalStateException failure = new IllegalStateException("shader failed");
        fixture.shader.useFailure = failure;

        IllegalStateException escaped =
                assertThrows(
                        IllegalStateException.class,
                        () ->
                                fixture.pass.render(
                                        context(visible(), new BlockDamageVisual(1, 2, 3, 4)),
                                        new RenderQueue()));

        assertSame(failure, escaped);
        assertEquals(INCOMING, fixture.state.current);
        assertEquals(1, fixture.state.restoreCalls);
    }

    @Test
    void restoresCompleteIncomingStateWhenDrawFails() {
        Fixture fixture = new Fixture();
        IllegalStateException failure = new IllegalStateException("draw failed");
        fixture.cube.drawFailure = failure;

        IllegalStateException escaped =
                assertThrows(
                        IllegalStateException.class,
                        () ->
                                fixture.pass.render(
                                        context(visible(), new BlockDamageVisual(1, 2, 3, 4)),
                                        new RenderQueue()));

        assertSame(failure, escaped);
        assertEquals(INCOMING, fixture.state.current);
        assertEquals(1, fixture.state.restoreCalls);
    }

    @Test
    void exposesNoMutationChunkRevisionDirtyOrMeshRebuildDependency() {
        for (Field field : BlockDamageOverlayPass.class.getDeclaredFields()) {
            String type = field.getType().getName();
            assertFalse(type.contains("WorldMutation"), type);
            assertFalse(type.contains("ChunkRepository"), type);
            assertFalse(type.contains("ChunkDirty"), type);
            assertFalse(type.contains("ChunkMesh"), type);
        }
    }

    @Test
    void bytecodeHasNoMethodLocalOrStaticMutationDirtyOrMeshReferences()
            throws Exception {
        String resource =
                BlockDamageOverlayPass.class.getName().replace('.', '/') + ".class";
        byte[] bytecode;
        try (InputStream input =
                BlockDamageOverlayPass.class.getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(input, resource);
            bytecode = input.readAllBytes();
        }
        String constantPool = new String(bytecode, StandardCharsets.ISO_8859_1);
        for (String forbidden :
                List.of(
                        "WorldMutation",
                        "BlockWorldMutationOutcome",
                        "ChunkRepository",
                        "ChunkDirty",
                        "ChunkMesh",
                        "compareAndSetBlock",
                        "markDirty",
                        "requestRebuild")) {
            assertFalse(constantPool.contains(forbidden), forbidden);
        }
    }

    @Test
    void damageShadersUseGlsl410IndependentAtlasUvsAndExactAlphaCutout()
            throws Exception {
        String vertex = readResource("assets/overlord/shaders/feedback/block_damage.vert");
        String fragment = readResource("assets/overlord/shaders/feedback/block_damage.frag");

        assertTrue(vertex.startsWith("#version 410 core"));
        assertTrue(fragment.startsWith("#version 410 core"));
        assertTrue(vertex.contains("layout (location = 0) in vec3 aPosition"));
        assertTrue(vertex.contains("layout (location = 1) in vec2 aUv"));
        assertTrue(fragment.contains("uniform sampler2D damageAtlas"));
        assertTrue(fragment.contains("sampled.a < 0.1"));
        assertTrue(fragment.contains("discard"));
        assertFalse(vertex.contains("430"));
        assertFalse(fragment.contains("430"));
        assertFalse(vertex.contains("buffer "));
        assertFalse(fragment.contains("buffer "));
    }

    private static String readResource(String path) throws Exception {
        try (InputStream input =
                BlockDamageOverlayPassTest.class.getClassLoader().getResourceAsStream(path)) {
            assertNotNull(input, path);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static Stream<Arguments> hiddenOrInvalidCases() {
        return Stream.of(
                Arguments.of("zero progress is absent", visible(), null),
                Arguments.of("creative is absent", visible(), null),
                Arguments.of("cancelled is absent", visible(), null),
                Arguments.of("unloaded is absent", visible(), null),
                Arguments.of(
                        "loading",
                        new FeedbackVisibility(false, true, true, false),
                        new BlockDamageVisual(1, 2, 3, 2)),
                Arguments.of(
                        "cursor released",
                        new FeedbackVisibility(true, false, true, false),
                        new BlockDamageVisual(1, 2, 3, 2)),
                Arguments.of(
                        "focus lost",
                        new FeedbackVisibility(true, true, false, false),
                        new BlockDamageVisual(1, 2, 3, 2)),
                Arguments.of(
                        "blocking UI",
                        new FeedbackVisibility(true, true, true, true),
                        new BlockDamageVisual(1, 2, 3, 2)),
                Arguments.of("negative stage", visible(), new BlockDamageVisual(1, 2, 3, -1)),
                Arguments.of("stage past atlas", visible(), new BlockDamageVisual(1, 2, 3, 10)));
    }

    private static FeedbackVisibility visible() {
        return new FeedbackVisibility(true, true, true, false);
    }

    private static RenderContext context(
            FeedbackVisibility visibility, BlockDamageVisual damage) {
        return context(visibility, damage, new ArrayList<>());
    }

    private static RenderContext context(
            FeedbackVisibility visibility,
            BlockDamageVisual damage,
            List<Long> drawMetrics) {
        return new RenderContext(
                new Matrix4f(),
                new Matrix4f(),
                RenderVisualSettings.milestoneOneDefaults(),
                drawMetrics::add,
                new RenderSurfaceMetrics(1024, 768, 1024, 768, 1.0f, 1.0f),
                new InteractionFeedbackFrame(
                        visibility,
                        Optional.ofNullable(damage),
                        List.of(),
                        new ParticleRenderBatch(List.of())));
    }

    private static final class Fixture {
        private final RecordingStateBackend state = new RecordingStateBackend(INCOMING);
        private final RecordingShader shader = new RecordingShader(state);
        private final RecordingTexture texture = new RecordingTexture(state);
        private final RecordingCube cube = new RecordingCube(state);
        private final DamageAtlasLayout layout =
                new DamageAtlasLayout(DamageAtlasResourceLoader.fallbackImage(), 10);
        private final List<Long> drawMetrics = new ArrayList<>();
        private final BlockDamageOverlayPass pass =
                new BlockDamageOverlayPass(state, shader, texture, layout, cube);
    }

    private static final class RecordingShader implements ShaderBinding {
        private final RecordingStateBackend state;
        private int useCalls;
        private RuntimeException useFailure;
        private final List<Matrix4f> projections = new ArrayList<>();
        private final List<Matrix4f> views = new ArrayList<>();
        private final List<Matrix4f> models = new ArrayList<>();
        private final List<Integer> samplerUnits = new ArrayList<>();
        private final List<Float> uMins = new ArrayList<>();
        private final List<Float> uMaxs = new ArrayList<>();
        private final List<Float> vMins = new ArrayList<>();
        private final List<Float> vMaxs = new ArrayList<>();

        private RecordingShader(RecordingStateBackend state) {
            this.state = state;
        }

        @Override
        public int programId() {
            return 120;
        }

        @Override
        public void use() {
            useCalls++;
            state.setCurrentProgram(120);
            if (useFailure != null) {
                throw useFailure;
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
            assertEquals("damageAtlas", uniform);
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
        private final RecordingStateBackend state;
        private int bindCalls;
        private final List<Integer> units = new ArrayList<>();

        private RecordingTexture(RecordingStateBackend state) {
            this.state = state;
        }

        @Override
        public void bind(int textureUnit) {
            bindCalls++;
            units.add(textureUnit);
            state.setTextureBinding(textureUnit, 220);
        }
    }

    private static final class RecordingCube implements UnitCubeMesh {
        private final RecordingStateBackend state;
        private int drawCalls;
        private RuntimeException drawFailure;

        private RecordingCube(RecordingStateBackend state) {
            this.state = state;
        }

        @Override
        public void draw() {
            drawCalls++;
            state.setBufferBindings(301, 302, 0);
            if (drawFailure != null) {
                throw drawFailure;
            }
        }

        @Override
        public void cleanup() {}
    }

    private static final class RecordingStateBackend implements RenderStateBackend {
        private RenderStateSnapshot current;
        private int captureCalls;
        private int restoreCalls;
        private RenderStateSpec requestedState;

        private RecordingStateBackend(RenderStateSnapshot initial) {
            current = initial;
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

        private void setCurrentProgram(int program) {
            current = copy(current.vertexArray(), current.arrayBuffer(), current.elementArrayBuffer(), program,
                    current.activeTexture(), current.texture2dUnit0());
        }

        private void setTextureBinding(int unit, int texture) {
            current = copy(current.vertexArray(), current.arrayBuffer(), current.elementArrayBuffer(),
                    current.currentProgram(), unit, texture);
        }

        private void setBufferBindings(int vao, int vbo, int ebo) {
            current = copy(vao, vbo, ebo, current.currentProgram(), current.activeTexture(),
                    current.texture2dUnit0());
        }

        private RenderStateSnapshot copy(
                int vao, int vbo, int ebo, int program, int activeTexture, int texture) {
            return new RenderStateSnapshot(
                    current.depthTest(), current.depthFunction(), current.depthWrite(), current.blend(),
                    current.blendSourceRgb(), current.blendDestinationRgb(), current.blendSourceAlpha(),
                    current.blendDestinationAlpha(), current.blendEquationRgb(), current.blendEquationAlpha(),
                    current.cullFace(), vao, vbo, ebo, current.polygonOffsetFill(),
                    current.polygonOffsetFactor(), current.polygonOffsetUnits(), program, activeTexture,
                    texture, current.viewport());
        }
    }
}

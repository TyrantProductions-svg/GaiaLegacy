package com.overlord.renderer.pass;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.overlord.assets.ResourceLocation;
import com.overlord.renderer.RenderSurfaceMetrics;
import com.overlord.renderer.TextureBinding;
import com.overlord.renderer.feedback.FeedbackVisibility;
import com.overlord.renderer.feedback.InteractionFeedbackFrame;
import com.overlord.renderer.feedback.ParticleRenderBatch;
import com.overlord.renderer.feedback.ParticleVisual;
import com.overlord.renderer.feedback.StreamingTexturedCubeBatch;
import com.overlord.renderer.particle.ParticleCategory;
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

class ParticleRenderPassTest {
    private static final TextureRegion REGION =
            new TextureRegion(new ResourceLocation("gaia", "stone"), 16, 0, 16, 16, 128, 16);
    private static final RenderStateSnapshot INCOMING =
            new RenderStateSnapshot(
                    false,
                    DepthFunction.LESS,
                    true,
                    false,
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

    @Test
    void nonEmptyBatchUsesOneStreamUploadAndDrawAndRecordsExactTriangles() {
        Fixture fixture = new Fixture();
        ParticleRenderBatch particles =
                new ParticleRenderBatch(List.of(particle(0), particle(1)));

        fixture.pass.render(context(particles, fixture.drawMetrics), new RenderQueue());

        assertEquals("particles", fixture.pass.id());
        assertEquals(1, fixture.state.captureCalls);
        assertEquals(
                new RenderStateSpec(
                        true,
                        DepthFunction.LEQUAL,
                        false,
                        BlendMode.ALPHA,
                        false,
                        false,
                        0.0f,
                        0.0f),
                fixture.state.requestedState);
        assertEquals(1, fixture.shader.useCalls);
        assertEquals(List.of(new Matrix4f()), fixture.shader.projections);
        assertEquals(List.of(new Matrix4f()), fixture.shader.views);
        assertEquals(List.of(0), fixture.shader.samplerUnits);
        assertEquals(List.of(0), fixture.texture.units);
        assertEquals(List.of(particles), fixture.batch.uploaded);
        assertEquals(1, fixture.batch.drawCalls);
        assertEquals(List.of(24L), fixture.drawMetrics);
        assertEquals(INCOMING, fixture.state.current);
        assertEquals(1, fixture.state.restoreCalls);
    }

    @Test
    void emptyBatchPerformsNoStateCaptureUploadOrDraw() {
        Fixture fixture = new Fixture();

        fixture.pass.render(
                context(new ParticleRenderBatch(List.of()), fixture.drawMetrics),
                new RenderQueue());

        assertEquals(0, fixture.state.captureCalls);
        assertEquals(0, fixture.shader.useCalls);
        assertEquals(0, fixture.texture.units.size());
        assertEquals(0, fixture.batch.uploaded.size());
        assertEquals(0, fixture.batch.drawCalls);
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
                                        context(batchOfOne(), fixture.drawMetrics),
                                        new RenderQueue()));

        assertSame(failure, escaped);
        assertEquals(INCOMING, fixture.state.current);
        assertEquals(1, fixture.state.restoreCalls);
    }

    @Test
    void uploadFailureRestoresExactIncomingState() {
        Fixture fixture = new Fixture();
        IllegalStateException failure = new IllegalStateException("upload failed");
        fixture.batch.uploadFailure = failure;

        IllegalStateException escaped =
                assertThrows(
                        IllegalStateException.class,
                        () ->
                                fixture.pass.render(
                                        context(batchOfOne(), fixture.drawMetrics),
                                        new RenderQueue()));

        assertSame(failure, escaped);
        assertEquals(INCOMING, fixture.state.current);
        assertEquals(1, fixture.state.restoreCalls);
        assertEquals(List.of(), fixture.drawMetrics);
    }

    @Test
    void drawFailureRestoresExactIncomingState() {
        Fixture fixture = new Fixture();
        IllegalStateException failure = new IllegalStateException("draw failed");
        fixture.batch.drawFailure = failure;

        IllegalStateException escaped =
                assertThrows(
                        IllegalStateException.class,
                        () ->
                                fixture.pass.render(
                                        context(batchOfOne(), fixture.drawMetrics),
                                        new RenderQueue()));

        assertSame(failure, escaped);
        assertEquals(INCOMING, fixture.state.current);
        assertEquals(1, fixture.state.restoreCalls);
        assertEquals(List.of(), fixture.drawMetrics);
    }

    @Test
    void bytecodeHasNoGameplayMutationServicePhysicsOrWorldDependencies() throws Exception {
        String resource = ParticleRenderPass.class.getName().replace('.', '/') + ".class";
        byte[] bytecode;
        try (InputStream input =
                ParticleRenderPass.class.getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(input, resource);
            bytecode = input.readAllBytes();
        }
        String constantPool = new String(bytecode, StandardCharsets.ISO_8859_1);
        for (String forbidden :
                List.of(
                        "WorldMutation",
                        "WorldItemService",
                        "Inventory",
                        "Raycast",
                        "PhysicsBody",
                        "ChunkDirty",
                        "ChunkMesh")) {
            assertFalse(constantPool.contains(forbidden), forbidden);
        }
    }

    private static ParticleVisual particle(long sequence) {
        return new ParticleVisual(
                sequence,
                2,
                3,
                0.1f,
                REGION,
                ParticleCategory.BREAK_COMMITTED,
                sequence);
    }

    private static ParticleRenderBatch batchOfOne() {
        return new ParticleRenderBatch(List.of(particle(0)));
    }

    private static RenderContext context(
            ParticleRenderBatch particles, List<Long> drawMetrics) {
        return new RenderContext(
                new Matrix4f(),
                new Matrix4f(),
                RenderVisualSettings.milestoneOneDefaults(),
                drawMetrics::add,
                new RenderSurfaceMetrics(1024, 768, 1024, 768, 1, 1),
                new InteractionFeedbackFrame(
                        new FeedbackVisibility(true, true, true, false),
                        Optional.empty(),
                        List.of(),
                        particles));
    }

    private static final class Fixture {
        private final RecordingState state = new RecordingState(INCOMING);
        private final RecordingShader shader = new RecordingShader(state);
        private final RecordingTexture texture = new RecordingTexture(state);
        private final RecordingBatch batch = new RecordingBatch(state);
        private final List<Long> drawMetrics = new ArrayList<>();
        private final ParticleRenderPass pass =
                new ParticleRenderPass(state, shader, texture, batch);
    }

    private static final class RecordingShader implements ShaderBinding {
        private final RecordingState state;
        private int useCalls;
        private RuntimeException failure;
        private final List<Matrix4f> projections = new ArrayList<>();
        private final List<Matrix4f> views = new ArrayList<>();
        private final List<Integer> samplerUnits = new ArrayList<>();

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
                default -> throw new AssertionError(uniform);
            }
        }

        @Override
        public void setInt(String uniform, int value) {
            assertEquals("blockAtlas", uniform);
            samplerUnits.add(value);
        }

        @Override
        public void setFloat(String uniform, float value) {}

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

    private static final class RecordingBatch implements StreamingTexturedCubeBatch {
        private final RecordingState state;
        private final List<ParticleRenderBatch> uploaded = new ArrayList<>();
        private int drawCalls;
        private RuntimeException uploadFailure;
        private RuntimeException drawFailure;

        private RecordingBatch(RecordingState state) {
            this.state = state;
        }

        @Override
        public void upload(ParticleRenderBatch particles) {
            uploaded.add(particles);
            state.setBuffers(301, 302, 0);
            if (uploadFailure != null) {
                throw uploadFailure;
            }
        }

        @Override
        public void draw() {
            drawCalls++;
            if (drawFailure != null) {
                throw drawFailure;
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
            current = copy(current.vertexArray(), current.arrayBuffer(), program, current.activeTexture(), current.texture2dUnit0());
        }

        private void setTexture(int unit, int texture) {
            current = copy(current.vertexArray(), current.arrayBuffer(), current.currentProgram(), unit, texture);
        }

        private void setBuffers(int vertexArray, int arrayBuffer, int elementArrayBuffer) {
            current =
                    new RenderStateSnapshot(
                            current.depthTest(), current.depthFunction(), current.depthWrite(), current.blend(),
                            current.blendSourceRgb(), current.blendDestinationRgb(), current.blendSourceAlpha(),
                            current.blendDestinationAlpha(), current.blendEquationRgb(), current.blendEquationAlpha(),
                            current.cullFace(), vertexArray, arrayBuffer, elementArrayBuffer,
                            current.polygonOffsetFill(), current.polygonOffsetFactor(), current.polygonOffsetUnits(),
                            current.currentProgram(), current.activeTexture(), current.texture2dUnit0(), current.viewport());
        }

        private RenderStateSnapshot copy(
                int vertexArray, int arrayBuffer, int program, int activeTexture, int texture) {
            return new RenderStateSnapshot(
                    current.depthTest(), current.depthFunction(), current.depthWrite(), current.blend(),
                    current.blendSourceRgb(), current.blendDestinationRgb(), current.blendSourceAlpha(),
                    current.blendDestinationAlpha(), current.blendEquationRgb(), current.blendEquationAlpha(),
                    current.cullFace(), vertexArray, arrayBuffer, current.elementArrayBuffer(),
                    current.polygonOffsetFill(), current.polygonOffsetFactor(), current.polygonOffsetUnits(),
                    program, activeTexture, texture, current.viewport());
        }
    }
}

package com.overlord.renderer.pass;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.overlord.renderer.RenderSurfaceMetrics;
import com.overlord.renderer.feedback.CrosshairGeometry;
import com.overlord.renderer.feedback.FeedbackVisibility;
import com.overlord.renderer.feedback.InteractionFeedbackFrame;
import com.overlord.renderer.feedback.ParticleRenderBatch;
import com.overlord.renderer.feedback.ScreenQuad;
import com.overlord.renderer.feedback.ScreenQuadBatch;
import com.overlord.renderer.queue.RenderQueue;
import com.overlord.renderer.shader.ShaderBinding;
import com.overlord.renderer.state.BlendMode;
import com.overlord.renderer.state.DepthFunction;
import com.overlord.renderer.state.RenderStateBackend;
import com.overlord.renderer.state.RenderStateSnapshot;
import com.overlord.renderer.state.RenderStateSpec;
import com.overlord.renderer.state.Viewport;
import com.overlord.renderer.visual.RenderVisualSettings;
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

class CrosshairRenderPassTest {
    private static final RenderStateSnapshot INCOMING =
            new RenderStateSnapshot(
                    true,
                    DepthFunction.LEQUAL,
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
                    true,
                    -1.0f,
                    -1.0f,
                    20,
                    21,
                    22,
                    new Viewport(7, 8, 9, 10));

    @Test
    void usesFramebufferPixelsUploadsFourQuadsAndDrawsOnce() {
        RecordingStateBackend state = new RecordingStateBackend(INCOMING);
        RecordingShader shader = new RecordingShader(state);
        RecordingBatch batch = new RecordingBatch(state);
        CrosshairRenderPass pass = new CrosshairRenderPass(state, shader, batch);
        RenderContext context =
                context(
                        new FeedbackVisibility(true, true, true, false),
                        new RenderSurfaceMetrics(1024, 768, 2048, 1536, 2.0f, 2.0f));

        pass.render(context, new RenderQueue());

        assertEquals("crosshair", pass.id());
        assertEquals(1, shader.useCalls);
        assertEquals(List.of(new Vector2Value(2048.0f, 1536.0f)), shader.framebufferSizes);
        assertEquals(List.of(CrosshairGeometry.quads(2048, 1536)), batch.uploads);
        assertEquals(1, batch.drawCalls);
        assertEquals(new Viewport(0, 0, 2048, 1536), state.requestedViewport);
        assertEquals(
                new RenderStateSpec(false, false, BlendMode.DISABLED, false),
                state.requestedState);
        assertEquals(INCOMING, state.current);
        assertEquals(1, state.restoreCalls);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("hiddenCases")
    void skipsEveryHiddenLifecycleOrNonDrawableFramebuffer(
            String label, FeedbackVisibility visibility, int width, int height) {
        RecordingStateBackend state = new RecordingStateBackend(INCOMING);
        RecordingShader shader = new RecordingShader(state);
        RecordingBatch batch = new RecordingBatch(state);
        CrosshairRenderPass pass = new CrosshairRenderPass(state, shader, batch);

        pass.render(
                context(
                        visibility,
                        new RenderSurfaceMetrics(1024, 768, width, height, 1.0f, 1.0f)),
                new RenderQueue());

        assertEquals(0, shader.useCalls);
        assertEquals(List.of(), batch.uploads);
        assertEquals(0, batch.drawCalls);
        assertEquals(0, state.captureCalls);
        assertEquals(INCOMING, state.current);
    }

    @Test
    void recaptureWithAllLifecycleConditionsRestoredDrawsAgain() {
        RecordingStateBackend state = new RecordingStateBackend(INCOMING);
        RecordingBatch batch = new RecordingBatch(state);
        CrosshairRenderPass pass = new CrosshairRenderPass(state, new RecordingShader(state), batch);

        pass.render(
                context(
                        new FeedbackVisibility(true, false, true, false),
                        new RenderSurfaceMetrics(1024, 768, 1024, 768, 1.0f, 1.0f)),
                new RenderQueue());
        pass.render(
                context(
                        new FeedbackVisibility(true, true, true, false),
                        new RenderSurfaceMetrics(1024, 768, 1024, 768, 1.0f, 1.0f)),
                new RenderQueue());

        assertEquals(1, batch.drawCalls);
    }

    @Test
    void restoresCompleteIncomingStateWhenDrawThrows() {
        RecordingStateBackend state = new RecordingStateBackend(INCOMING);
        IllegalStateException failure = new IllegalStateException("draw failed");
        RecordingBatch batch = new RecordingBatch(state);
        batch.drawFailure = failure;
        CrosshairRenderPass pass = new CrosshairRenderPass(state, new RecordingShader(state), batch);

        IllegalStateException escaped =
                assertThrows(
                        IllegalStateException.class,
                        () ->
                                pass.render(
                                        context(
                                                new FeedbackVisibility(true, true, true, false),
                                                new RenderSurfaceMetrics(
                                                        1024,
                                                        768,
                                                        1024,
                                                        768,
                                                        1.0f,
                                                        1.0f)),
                                        new RenderQueue()));

        assertSame(failure, escaped);
        assertEquals(INCOMING, state.current);
        assertEquals(1, state.restoreCalls);
    }

    private static Stream<Arguments> hiddenCases() {
        return Stream.of(
                Arguments.of(
                        "loading", new FeedbackVisibility(false, true, true, false), 1024, 768),
                Arguments.of(
                        "cursor released", new FeedbackVisibility(true, false, true, false), 1024, 768),
                Arguments.of(
                        "focus lost", new FeedbackVisibility(true, true, false, false), 1024, 768),
                Arguments.of(
                        "blocking UI", new FeedbackVisibility(true, true, true, true), 1024, 768),
                Arguments.of(
                        "zero framebuffer width", new FeedbackVisibility(true, true, true, false), 0, 768),
                Arguments.of(
                        "zero framebuffer height", new FeedbackVisibility(true, true, true, false), 1024, 0));
    }

    private static RenderContext context(
            FeedbackVisibility visibility, RenderSurfaceMetrics surfaceMetrics) {
        return new RenderContext(
                new Matrix4f(),
                new Matrix4f(),
                RenderVisualSettings.milestoneOneDefaults(),
                triangles -> {},
                surfaceMetrics,
                new InteractionFeedbackFrame(
                        visibility,
                        Optional.empty(),
                        List.of(),
                        new ParticleRenderBatch(List.of())));
    }

    private record Vector2Value(float x, float y) {}

    private static final class RecordingShader implements ShaderBinding {
        private static final int CROSSHAIR_PROGRAM = 120;
        private final RecordingStateBackend state;
        private int useCalls;
        private final List<Vector2Value> framebufferSizes = new ArrayList<>();

        private RecordingShader(RecordingStateBackend state) {
            this.state = state;
        }

        @Override
        public int programId() {
            return 20;
        }

        @Override
        public void use() {
            useCalls++;
            state.setCurrentProgram(CROSSHAIR_PROGRAM);
        }

        @Override
        public void setMatrix4(String uniform, Matrix4fc value) {}

        @Override
        public void setInt(String uniform, int value) {}

        @Override
        public void setFloat(String uniform, float value) {}

        @Override
        public void setVector2(String uniform, Vector2fc value) {
            assertEquals("framebufferSize", uniform);
            framebufferSizes.add(new Vector2Value(value.x(), value.y()));
        }

        @Override
        public void setVector3(String uniform, Vector3fc value) {}
    }

    private static final class RecordingBatch implements ScreenQuadBatch {
        private static final int CROSSHAIR_VAO = 217;
        private static final int CROSSHAIR_VBO = 218;
        private static final int CROSSHAIR_EBO = 219;
        private final RecordingStateBackend state;
        private final List<List<ScreenQuad>> uploads = new ArrayList<>();
        private int drawCalls;
        private RuntimeException drawFailure;

        private RecordingBatch(RecordingStateBackend state) {
            this.state = state;
        }

        @Override
        public void upload(List<ScreenQuad> quads) {
            uploads.add(List.copyOf(quads));
            state.setBufferBindings(CROSSHAIR_VAO, CROSSHAIR_VBO, CROSSHAIR_EBO);
        }

        @Override
        public void draw() {
            drawCalls++;
            state.setBufferBindings(CROSSHAIR_VAO + 10, CROSSHAIR_VBO + 10, CROSSHAIR_EBO + 10);
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
        private Viewport requestedViewport;

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

        @Override
        public void setViewport(Viewport viewport) {
            requestedViewport = viewport;
            current =
                    new RenderStateSnapshot(
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
                            current.vertexArray(),
                            current.arrayBuffer(),
                            current.elementArrayBuffer(),
                            current.polygonOffsetFill(),
                            current.polygonOffsetFactor(),
                            current.polygonOffsetUnits(),
                            current.currentProgram(),
                            current.activeTexture(),
                            current.texture2dUnit0(),
                            viewport);
        }

        private void setCurrentProgram(int currentProgram) {
            current =
                    new RenderStateSnapshot(
                            current.depthTest(), current.depthFunction(), current.depthWrite(),
                            current.blend(), current.blendSourceRgb(), current.blendDestinationRgb(),
                            current.blendSourceAlpha(), current.blendDestinationAlpha(),
                            current.blendEquationRgb(), current.blendEquationAlpha(),
                            current.cullFace(), current.vertexArray(), current.arrayBuffer(),
                            current.elementArrayBuffer(), current.polygonOffsetFill(),
                            current.polygonOffsetFactor(), current.polygonOffsetUnits(),
                            currentProgram, current.activeTexture(), current.texture2dUnit0(),
                            current.viewport());
        }

        private void setBufferBindings(int vertexArray, int arrayBuffer, int elementArrayBuffer) {
            current =
                    new RenderStateSnapshot(
                            current.depthTest(), current.depthFunction(), current.depthWrite(),
                            current.blend(), current.blendSourceRgb(), current.blendDestinationRgb(),
                            current.blendSourceAlpha(), current.blendDestinationAlpha(),
                            current.blendEquationRgb(), current.blendEquationAlpha(),
                            current.cullFace(), vertexArray, arrayBuffer, elementArrayBuffer,
                            current.polygonOffsetFill(), current.polygonOffsetFactor(),
                            current.polygonOffsetUnits(), current.currentProgram(),
                            current.activeTexture(), current.texture2dUnit0(), current.viewport());
        }
    }
}

package com.overlord.renderer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.overlord.assets.ResourceLocation;
import com.overlord.core.thread.MainThreadGuard;
import com.overlord.renderer.feedback.InteractionFeedbackAssets;
import com.overlord.renderer.feedback.ParticleRenderBatch;
import com.overlord.renderer.feedback.ScreenQuad;
import com.overlord.renderer.feedback.ScreenQuadBatch;
import com.overlord.renderer.feedback.StreamingTexturedCubeBatch;
import com.overlord.renderer.feedback.UnitCubeMesh;
import com.overlord.renderer.pass.RenderContext;
import com.overlord.renderer.pass.RenderPass;
import com.overlord.renderer.pass.RenderPipeline;
import com.overlord.renderer.queue.RenderQueue;
import com.overlord.renderer.shader.ShaderBinding;
import com.overlord.renderer.texture.TextureImage;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.lang.reflect.Field;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector2fc;
import org.joml.Vector3fc;
import org.junit.jupiter.api.Test;

class InteractionFeedbackRendererLifecycleTest {
    private static final List<String> CREATION_ORDER = List.of(
            "shader:block-damage",
            "shader:world-items",
            "shader:particles",
            "shader:crosshair",
            "texture:damage-atlas",
            "mesh:unit-cube",
            "batch:particles",
            "batch:crosshair");

    @Test
    void productionPipelineUsesTheExactSevenPassOrder() {
        RenderPipeline pipeline = Renderer.createPipeline(
                pass("sky"),
                pass("world"),
                pass("block-damage"),
                pass("world-items"),
                pass("particles"),
                pass("debug"),
                pass("crosshair"));

        assertEquals(
                List.of(
                        "sky",
                        "world",
                        "block-damage",
                        "world-items",
                        "particles",
                        "debug",
                        "crosshair"),
                pipeline.passIds());
    }

    @Test
    void eachFeedbackResourceFailureCleansEarlierResourcesOnceInReverseOrder() {
        for (int failureIndex = 0; failureIndex < CREATION_ORDER.size(); failureIndex++) {
            List<String> trace = new ArrayList<>();
            RuntimeException expected = new RuntimeException("failure-" + failureIndex);
            RecordingFactory factory = new RecordingFactory(trace, failureIndex, expected);

            RuntimeException escaped = assertThrows(
                    RuntimeException.class,
                    () -> Renderer.FeedbackResources.create(
                            MainThreadGuard.captureCurrentThread(),
                            factory,
                            InteractionFeedbackAssets.fallback()));

            assertSame(expected, escaped);
            List<String> expectedTrace = new ArrayList<>(
                    CREATION_ORDER.subList(0, failureIndex + 1));
            for (int clean = failureIndex - 1; clean >= 0; clean--) {
                expectedTrace.add("cleanup:" + CREATION_ORDER.get(clean));
            }
            assertEquals(expectedTrace, trace, "failure index " + failureIndex);
        }
    }

    @Test
    void successfulFeedbackResourcesCleanInReverseOrderExactlyOnce() {
        List<String> trace = new ArrayList<>();
        RecordingFactory factory = new RecordingFactory(trace, -1, null);
        Renderer.FeedbackResources resources = Renderer.FeedbackResources.create(
                MainThreadGuard.captureCurrentThread(),
                factory,
                InteractionFeedbackAssets.fallback());

        resources.cleanup();
        resources.cleanup();

        List<String> expected = new ArrayList<>(CREATION_ORDER);
        for (int clean = CREATION_ORDER.size() - 1; clean >= 0; clean--) {
            expected.add("cleanup:" + CREATION_ORDER.get(clean));
        }
        assertEquals(expected, trace);
    }

    @Test
    void feedbackResourceCreationRejectsOffOwnerThreadBeforeFactoryCalls()
            throws InterruptedException {
        MainThreadGuard guard = MainThreadGuard.captureCurrentThread();
        List<String> trace = new ArrayList<>();
        RecordingFactory factory = new RecordingFactory(trace, -1, null);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread worker = new Thread(
                () -> {
                    try {
                        Renderer.FeedbackResources.create(
                                guard, factory, InteractionFeedbackAssets.fallback());
                    } catch (Throwable thrown) {
                        failure.set(thrown);
                    }
                },
                "feedback-resource-worker");

        worker.start();
        worker.join();

        assertTrue(failure.get() instanceof IllegalStateException);
        assertTrue(trace.isEmpty());
    }

    @Test
    void feedbackCleanupRejectsOffOwnerThreadWithoutConsumingOwnerCleanup()
            throws InterruptedException {
        MainThreadGuard guard = MainThreadGuard.captureCurrentThread();
        List<String> trace = new ArrayList<>();
        Renderer.FeedbackResources resources = Renderer.FeedbackResources.create(
                guard,
                new RecordingFactory(trace, -1, null),
                InteractionFeedbackAssets.fallback());
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread worker = new Thread(
                () -> {
                    try {
                        resources.cleanup();
                    } catch (Throwable thrown) {
                        failure.set(thrown);
                    }
                },
                "feedback-cleanup-worker");

        worker.start();
        worker.join();

        assertTrue(failure.get() instanceof IllegalStateException);
        assertEquals(CREATION_ORDER, trace);

        resources.cleanup();
        List<String> expected = new ArrayList<>(CREATION_ORDER);
        for (int clean = CREATION_ORDER.size() - 1; clean >= 0; clean--) {
            expected.add("cleanup:" + CREATION_ORDER.get(clean));
        }
        assertEquals(expected, trace);
    }

    @Test
    void rendererCleanupClearsSurfaceLifecycleStateForSafeReinitialization()
            throws ReflectiveOperationException {
        Renderer renderer = new Renderer(
                MainThreadGuard.captureCurrentThread(), RenderAssets.missing());
        RenderSurfaceMetrics metrics =
                new RenderSurfaceMetrics(800, 600, 1600, 1200, 2.0f, 2.0f);
        setField(renderer, "surfaceMetrics", metrics);
        setField(renderer, "surfaceController", new RenderSurfaceController(metrics));

        renderer.cleanup();

        assertEquals(null, field(renderer, "surfaceMetrics"));
        assertEquals(null, field(renderer, "surfaceController"));
    }

    @Test
    void drawableDispatchInvokesNoPassesForZeroSurfaceAndAllPassesForDrawableSurface() {
        List<String> trace = new ArrayList<>();
        RenderPipeline pipeline = Renderer.createPipeline(
                recordingPass("sky", trace),
                recordingPass("world", trace),
                recordingPass("block-damage", trace),
                recordingPass("world-items", trace),
                recordingPass("particles", trace),
                recordingPass("debug", trace),
                recordingPass("crosshair", trace));
        RenderContext context = new RenderContext(new Matrix4f(), new Matrix4f());
        RenderQueue queue = new RenderQueue();

        for (RenderSurfaceMetrics metrics : List.of(
                new RenderSurfaceMetrics(800, 600, 0, 600, 1.0f, 1.0f),
                new RenderSurfaceMetrics(800, 600, 800, 0, 1.0f, 1.0f))) {
            Renderer.dispatchIfDrawable(
                    new RenderSurfaceController(metrics),
                    () -> pipeline.render(context, queue));
            assertTrue(trace.isEmpty());
        }

        Renderer.dispatchIfDrawable(
                new RenderSurfaceController(
                        new RenderSurfaceMetrics(800, 600, 800, 600, 1.0f, 1.0f)),
                () -> pipeline.render(context, queue));

        assertEquals(
                List.of(
                        "sky", "world", "block-damage", "world-items",
                        "particles", "debug", "crosshair"),
                trace);
    }

    private static void setField(Renderer renderer, String name, Object value)
            throws ReflectiveOperationException {
        Field field = Renderer.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(renderer, value);
    }

    private static Object field(Renderer renderer, String name)
            throws ReflectiveOperationException {
        Field field = Renderer.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(renderer);
    }

    private static RenderPass pass(String id) {
        return new RenderPass() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public void render(RenderContext context, RenderQueue queue) {}
        };
    }

    private static RenderPass recordingPass(String id, List<String> trace) {
        return new RenderPass() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public void render(RenderContext context, RenderQueue queue) {
                trace.add(id);
            }
        };
    }

    private static final class RecordingFactory implements Renderer.FeedbackResourceFactory {
        private final List<String> trace;
        private final int failureIndex;
        private final RuntimeException failure;
        private int nextIndex;

        private RecordingFactory(
                List<String> trace, int failureIndex, RuntimeException failure) {
            this.trace = trace;
            this.failureIndex = failureIndex;
            this.failure = failure;
        }

        @Override
        public Renderer.Managed<ShaderBinding> createShader(
                String label,
                ResourceLocation vertex,
                ResourceLocation fragment,
                List<String> uniforms) {
            String id = "shader:" + label;
            beforeCreate(id);
            return new Renderer.Managed<>(new NoOpShader(), () -> trace.add("cleanup:" + id));
        }

        @Override
        public Renderer.Managed<TextureBinding> createDamageTexture(TextureImage image) {
            String id = "texture:damage-atlas";
            beforeCreate(id);
            return new Renderer.Managed<>(textureUnit -> {}, () -> trace.add("cleanup:" + id));
        }

        @Override
        public UnitCubeMesh createUnitCube() {
            String id = "mesh:unit-cube";
            beforeCreate(id);
            return closableCube(id);
        }

        @Override
        public StreamingTexturedCubeBatch createParticleBatch() {
            String id = "batch:particles";
            beforeCreate(id);
            return new StreamingTexturedCubeBatch() {
                @Override public void upload(ParticleRenderBatch particles) {}
                @Override public void draw() {}
                @Override public void cleanup() { trace.add("cleanup:" + id); }
            };
        }

        @Override
        public ScreenQuadBatch createCrosshairBatch() {
            String id = "batch:crosshair";
            beforeCreate(id);
            return new ScreenQuadBatch() {
                @Override public void upload(List<ScreenQuad> quads) {}
                @Override public void draw() {}
                @Override public void cleanup() { trace.add("cleanup:" + id); }
            };
        }

        private UnitCubeMesh closableCube(String id) {
            return new UnitCubeMesh() {
                @Override public void draw() {}
                @Override public void cleanup() { trace.add("cleanup:" + id); }
            };
        }

        private void beforeCreate(String id) {
            trace.add(id);
            if (nextIndex++ == failureIndex) {
                throw failure;
            }
        }
    }

    private static final class NoOpShader implements ShaderBinding {
        @Override public int programId() { return 1; }
        @Override public void use() {}
        @Override public void setMatrix4(String uniform, Matrix4fc value) {}
        @Override public void setInt(String uniform, int value) {}
        @Override public void setFloat(String uniform, float value) {}
        @Override public void setVector2(String uniform, Vector2fc value) {}
        @Override public void setVector3(String uniform, Vector3fc value) {}
    }
}

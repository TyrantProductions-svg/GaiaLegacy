package com.overlord.renderer.ui;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.overlord.core.thread.MainThreadGuard;
import com.overlord.renderer.RenderSurfaceMetrics;
import com.overlord.renderer.state.DepthFunction;
import com.overlord.renderer.state.RenderStateSnapshot;
import com.overlord.renderer.state.ScissorBox;
import com.overlord.renderer.state.Viewport;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class UiRendererTest {
    private static final RenderStateSnapshot INCOMING =
            new RenderStateSnapshot(
                    true,
                    DepthFunction.GEQUAL,
                    true,
                    false,
                    11,
                    12,
                    13,
                    14,
                    15,
                    16,
                    true,
                    17,
                    18,
                    19,
                    true,
                    -2.0f,
                    3.0f,
                    20,
                    21,
                    22,
                    new Viewport(23, 24, 25, 26),
                    true,
                    new ScissorBox(27, 28, 29, 30),
                    31,
                    32);
    private static final RenderSurfaceMetrics SURFACE =
            new RenderSurfaceMetrics(320, 180, 640, 360, 2.0f, 2.0f);

    @Test
    void uploadsExactPositionUvTintVerticesAndUsesUnitZeroForOrderedRuns() {
        RecordingBackend backend = new RecordingBackend();
        UiRenderer renderer = create(backend);
        backend.resetFrame();
        UiDrawCommand icon = command(
                UiTextureId.ICON_ATLAS,
                new UiRect(10.0d, 20.0d, 30.0d, 40.0d),
                new UiUvRect(0.1f, 0.2f, 0.3f, 0.4f),
                new UiColor(0.5f, 0.6f, 0.7f, 0.8f),
                Optional.of(new UiRect(4.0d, 5.0d, 44.0d, 55.0d)));
        UiDrawCommand font = command(UiTextureId.FONT_ATLAS, 50.0d, Optional.empty());
        UiDrawCommand iconAgain = command(UiTextureId.ICON_ATLAS, 70.0d, Optional.empty());
        UiDrawCommand solid = command(UiTextureId.SOLID, 90.0d, Optional.empty());

        renderer.render(new UiFrame(List.of(icon, font, iconAgain, solid)), SURFACE);

        assertEquals(List.of(20, 21, 20, 0), backend.boundTextures);
        assertEquals(List.of(true, true, true, false), backend.textureSampling);
        assertEquals(List.of(
                        Optional.of(new ScissorBox(4, 305, 40, 50)),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty()),
                backend.clips);
        assertArrayEquals(
                new float[] {
                    10.0f, 20.0f, 0.1f, 0.2f, 0.5f, 0.6f, 0.7f, 0.8f,
                    30.0f, 20.0f, 0.3f, 0.2f, 0.5f, 0.6f, 0.7f, 0.8f,
                    30.0f, 40.0f, 0.3f, 0.4f, 0.5f, 0.6f, 0.7f, 0.8f,
                    10.0f, 40.0f, 0.1f, 0.4f, 0.5f, 0.6f, 0.7f, 0.8f
                },
                backend.uploadedVertices.get(0));
        assertArrayEquals(new int[] {0, 1, 2, 0, 2, 3}, backend.uploadedIndices.get(0));
        assertEquals(List.of(6, 6, 6, 6), backend.drawCounts);
        assertEquals(List.of(new Size(640.0f, 360.0f)), backend.framebufferSizes);
        assertEquals(List.of(0), backend.samplerUnits);
        assertEquals(1, backend.applyCalls);
        assertEquals(INCOMING, backend.restored);
    }

    @Test
    void mergesOnlyConsecutiveEqualTextureAndClipRunsWithoutReordering() {
        RecordingBackend backend = new RecordingBackend();
        UiRenderer renderer = create(backend);
        backend.resetFrame();
        UiRect clip = new UiRect(2.0d, 3.0d, 22.0d, 23.0d);
        UiDrawCommand first = command(UiTextureId.ICON_ATLAS, 0.0d, Optional.of(clip));
        UiDrawCommand second = command(UiTextureId.ICON_ATLAS, 10.0d, Optional.of(clip));
        UiDrawCommand font = command(UiTextureId.FONT_ATLAS, 20.0d, Optional.of(clip));
        UiDrawCommand third = command(UiTextureId.ICON_ATLAS, 30.0d, Optional.of(clip));

        renderer.render(new UiFrame(List.of(first, second, font, third)), SURFACE);

        assertEquals(List.of(12, 6, 6), backend.drawCounts);
        assertEquals(List.of(20, 21, 20), backend.boundTextures);
        assertEquals(3, backend.uploadedVertices.size());
        assertEquals(64, backend.uploadedVertices.get(0).length);
    }

    @Test
    void bindsDisplayAndBodyPagesThroughTheSameRenderer() {
        RecordingBackend backend = new RecordingBackend();
        UiRenderer renderer = UiRenderer.create(
                multiPageBundle(), backend, MainThreadGuard.captureCurrentThread());
        backend.resetFrame();

        renderer.render(new UiFrame(List.of(
                command(UiTextureId.FONT_DISPLAY, 0.0d, Optional.empty()),
                command(UiTextureId.FONT_BODY, 10.0d, Optional.empty()))), SURFACE);

        assertEquals(List.of(21, 22), backend.boundTextures);
        assertEquals(List.of(true, true), backend.textureSampling);
    }

    @Test
    void drawsOneFullscreenHeroThroughTheExistingRendererAndShaderPath() {
        RecordingBackend backend = new RecordingBackend();
        UiRenderer renderer = UiRenderer.create(
                multiPageBundle(), backend, MainThreadGuard.captureCurrentThread());
        backend.resetFrame();
        UiDrawCommand hero = command(
                UiTextureId.HERO_BACKGROUND,
                new UiRect(0.0d, 0.0d, 640.0d, 360.0d),
                new UiUvRect(0.0f, 0.0f, 1.0f, 1.0f),
                new UiColor(1.0f, 1.0f, 1.0f, 1.0f),
                Optional.empty());

        renderer.render(new UiFrame(List.of(hero)), SURFACE);

        assertEquals(List.of(23), backend.boundTextures);
        assertEquals(List.of(true), backend.textureSampling);
        assertEquals(List.of(6), backend.drawCounts);
        assertEquals(32, backend.uploadedVertices.get(0).length);
    }

    @Test
    void zeroFramebufferPerformsNoCaptureUploadOrDraw() {
        RecordingBackend backend = new RecordingBackend();
        UiRenderer renderer = create(backend);
        backend.resetFrame();
        UiFrame frame = new UiFrame(List.of(command(UiTextureId.ICON_ATLAS, 0.0d, Optional.empty())));

        renderer.render(frame, new RenderSurfaceMetrics(0, 180, 0, 360, 1.0f, 2.0f));
        renderer.render(frame, new RenderSurfaceMetrics(320, 0, 640, 0, 2.0f, 1.0f));

        assertEquals(0, backend.captureCalls);
        assertEquals(0, backend.applyCalls);
        assertEquals(List.of(), backend.uploadedVertices);
        assertEquals(List.of(), backend.drawCounts);
    }

    @Test
    void restoresTheCompleteIncomingStateWhenSetupOrDrawFails() {
        RecordingBackend setupBackend = new RecordingBackend();
        UiRenderer setupRenderer = create(setupBackend);
        setupBackend.resetFrame();
        IllegalStateException setupFailure = new IllegalStateException("setup failed");
        setupBackend.applyFailure = setupFailure;

        IllegalStateException setupEscaped = assertThrows(
                IllegalStateException.class,
                () -> setupRenderer.render(UiFrame.empty(), SURFACE));

        assertSame(setupFailure, setupEscaped);
        assertEquals(INCOMING, setupBackend.restored);

        RecordingBackend drawBackend = new RecordingBackend();
        UiRenderer drawRenderer = create(drawBackend);
        drawBackend.resetFrame();
        IllegalArgumentException drawFailure = new IllegalArgumentException("draw failed");
        drawBackend.drawFailure = drawFailure;

        IllegalArgumentException drawEscaped = assertThrows(
                IllegalArgumentException.class,
                () -> drawRenderer.render(
                        new UiFrame(List.of(command(UiTextureId.ICON_ATLAS, 0.0d, Optional.empty()))),
                        SURFACE));

        assertSame(drawFailure, drawEscaped);
        assertEquals(INCOMING, drawBackend.restored);
    }

    @Test
    void preservesRenderFailureAndSuppressesDistinctRestoreFailure() {
        RecordingBackend backend = new RecordingBackend();
        UiRenderer renderer = create(backend);
        backend.resetFrame();
        IllegalStateException drawFailure = new IllegalStateException("draw failed");
        IllegalArgumentException restoreFailure = new IllegalArgumentException("restore failed");
        backend.drawFailure = drawFailure;
        backend.restoreFailure = restoreFailure;

        IllegalStateException escaped = assertThrows(
                IllegalStateException.class,
                () -> renderer.render(
                        new UiFrame(List.of(command(UiTextureId.ICON_ATLAS, 0.0d, Optional.empty()))),
                        SURFACE));

        assertSame(drawFailure, escaped);
        assertEquals(1, escaped.getSuppressed().length);
        assertSame(restoreFailure, escaped.getSuppressed()[0]);
    }

    @Test
    void creationRenderAndCloseRejectNonOwnerThreadsBeforeGpuWork() throws Exception {
        MainThreadGuard owner = MainThreadGuard.captureCurrentThread();
        RecordingBackend createBackend = new RecordingBackend();

        Throwable createFailure = onWorker(() -> UiRenderer.create(bundle(), createBackend, owner));

        assertTrue(createFailure instanceof IllegalStateException);
        assertEquals(0, createBackend.createProgramCalls);

        RecordingBackend backend = new RecordingBackend();
        UiRenderer renderer = UiRenderer.create(bundle(), backend, owner);
        backend.resetFrame();

        Throwable renderFailure = onWorker(() -> renderer.render(UiFrame.empty(), SURFACE));
        Throwable closeFailure = onWorker(renderer::close);

        assertTrue(renderFailure instanceof IllegalStateException);
        assertTrue(closeFailure instanceof IllegalStateException);
        assertEquals(0, backend.captureCalls);
        assertFalse(backend.closedAnything());
    }

    private static UiRenderer create(RecordingBackend backend) {
        return UiRenderer.create(bundle(), backend, MainThreadGuard.captureCurrentThread());
    }

    private static UiAssetBundle bundle() {
        UiTextureData texture =
                new UiTextureData(1, 1, ByteBuffer.wrap(new byte[] {-1, -1, -1, -1}));
        BitmapGlyph missing = new BitmapGlyph(
                0xfffd, new UiUvRect(0.0f, 0.0f, 1.0f, 1.0f), 1, 0, 1);
        return new UiAssetBundle(texture, texture, new BitmapFont(1, 1, Map.of(), missing));
    }

    private static UiAssetBundle multiPageBundle() {
        UiTextureData nearest = new UiTextureData(
                1, 1, ByteBuffer.wrap(new byte[] {-1, -1, -1, -1}));
        UiTextureData linear = new UiTextureData(
                1, 1, ByteBuffer.wrap(new byte[] {-1, -1, -1, -1}),
                UiTextureSampling.LINEAR);
        BitmapGlyph missing = new BitmapGlyph(
                0xfffd, new UiUvRect(0.0f, 0.0f, 1.0f, 1.0f), 1, 0, 1);
        BitmapFont font = new BitmapFont(1, 1, Map.of(), missing);
        TypographyCatalog.Face display = new TypographyCatalog.Face(
                font, UiTextureId.FONT_DISPLAY);
        TypographyCatalog.Face body = new TypographyCatalog.Face(
                font, UiTextureId.FONT_BODY);
        return new UiAssetBundle(
                Map.of(
                        UiTextureId.ICON_ATLAS, nearest,
                        UiTextureId.FONT_DISPLAY, nearest,
                        UiTextureId.FONT_BODY, linear,
                        UiTextureId.HERO_BACKGROUND, linear),
                new TypographyCatalog(
                        Map.of(
                                TypographyRole.DISPLAY_TITLE, display,
                                TypographyRole.HEADING_LARGE, display,
                                TypographyRole.BODY, body,
                                TypographyRole.FUNCTIONAL, body,
                                TypographyRole.HUD, body),
                        TypographyRole.BODY));
    }

    private static UiDrawCommand command(
            UiTextureId texture, double left, Optional<UiRect> clip) {
        return command(
                texture,
                new UiRect(left, 1.0d, left + 8.0d, 9.0d),
                new UiUvRect(0.0f, 0.0f, 1.0f, 1.0f),
                new UiColor(1.0f, 1.0f, 1.0f, 1.0f),
                clip);
    }

    private static UiDrawCommand command(
            UiTextureId texture,
            UiRect bounds,
            UiUvRect uv,
            UiColor tint,
            Optional<UiRect> clip) {
        return new UiDrawCommand(texture, bounds, uv, tint, clip);
    }

    private static Throwable onWorker(ThrowingRunnable action) throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try {
                action.run();
            } catch (Throwable caught) {
                failure.set(caught);
            }
        }, "ui-test-worker");
        worker.start();
        worker.join();
        return failure.get();
    }

    private interface ThrowingRunnable {
        void run();
    }

    private record Size(float width, float height) {}

    static class RecordingBackend implements UiGpuBackend {
        int createProgramCalls;
        int captureCalls;
        int applyCalls;
        int deleteProgramCalls;
        int deleteTextureCalls;
        int deleteBufferCalls;
        int deleteVertexArrayCalls;
        RuntimeException applyFailure;
        RuntimeException drawFailure;
        RuntimeException restoreFailure;
        RenderStateSnapshot restored;
        final List<Integer> boundTextures = new ArrayList<>();
        final List<Boolean> textureSampling = new ArrayList<>();
        final List<Optional<ScissorBox>> clips = new ArrayList<>();
        final List<float[]> uploadedVertices = new ArrayList<>();
        final List<int[]> uploadedIndices = new ArrayList<>();
        final List<Integer> drawCounts = new ArrayList<>();
        final List<Size> framebufferSizes = new ArrayList<>();
        final List<Integer> samplerUnits = new ArrayList<>();

        void resetFrame() {
            captureCalls = 0;
            applyCalls = 0;
            restored = null;
            boundTextures.clear();
            textureSampling.clear();
            clips.clear();
            uploadedVertices.clear();
            uploadedIndices.clear();
            drawCounts.clear();
            framebufferSizes.clear();
            samplerUnits.clear();
        }

        boolean closedAnything() {
            return deleteProgramCalls != 0
                    || deleteTextureCalls != 0
                    || deleteBufferCalls != 0
                    || deleteVertexArrayCalls != 0;
        }

        @Override
        public int createProgram(String vertexSource, String fragmentSource) {
            createProgramCalls++;
            return 10;
        }

        @Override
        public void useProgram(int program) {}

        @Override
        public void setFramebufferSize(int program, float width, float height) {
            framebufferSizes.add(new Size(width, height));
        }

        @Override
        public void setTextureSampler(int program, int textureUnit) {
            samplerUnits.add(textureUnit);
        }

        @Override
        public void setTextureSamplingEnabled(int program, boolean enabled) {
            textureSampling.add(enabled);
        }

        @Override
        public void deleteProgram(int program) {
            deleteProgramCalls++;
        }

        @Override
        public int createTexture(UiTextureData texture) {
            return nextTexture++;
        }

        @Override
        public void bindTextureUnitZero(int texture) {
            boundTextures.add(texture);
        }

        @Override
        public void deleteTexture(int texture) {
            deleteTextureCalls++;
        }

        private int nextTexture = 20;

        @Override
        public int createVertexArray() {
            return 30;
        }

        @Override
        public int createBuffer() {
            return createBufferCount++ == 0 ? 31 : 32;
        }

        private int createBufferCount;

        @Override
        public void configureBatch(int vertexArray, int vertexBuffer, int elementBuffer) {}

        @Override
        public void uploadBatch(
                int vertexArray,
                int vertexBuffer,
                int elementBuffer,
                float[] vertices,
                int[] indices) {
            uploadedVertices.add(vertices.clone());
            uploadedIndices.add(indices.clone());
        }

        @Override
        public void drawBatch(int vertexArray, int indexCount) {
            drawCounts.add(indexCount);
            if (drawFailure != null) {
                throw drawFailure;
            }
        }

        @Override
        public void deleteBuffer(int buffer) {
            deleteBufferCalls++;
        }

        @Override
        public void deleteVertexArray(int vertexArray) {
            deleteVertexArrayCalls++;
        }

        @Override
        public RenderStateSnapshot captureState() {
            captureCalls++;
            return INCOMING;
        }

        @Override
        public void applyUiState(int framebufferWidth, int framebufferHeight) {
            applyCalls++;
            if (applyFailure != null) {
                throw applyFailure;
            }
        }

        @Override
        public void setClip(Optional<ScissorBox> clip) {
            clips.add(clip);
        }

        @Override
        public void restoreState(RenderStateSnapshot snapshot) {
            restored = snapshot;
            if (restoreFailure != null) {
                throw restoreFailure;
            }
        }
    }
}

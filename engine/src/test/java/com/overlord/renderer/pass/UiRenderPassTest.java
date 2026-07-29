package com.overlord.renderer.pass;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.overlord.core.thread.MainThreadGuard;
import com.overlord.renderer.RenderFrameInput;
import com.overlord.renderer.RenderSurfaceMetrics;
import com.overlord.renderer.pass.UiRenderPass;
import com.overlord.renderer.queue.RenderQueue;
import com.overlord.renderer.ui.BitmapFont;
import com.overlord.renderer.ui.BitmapGlyph;
import com.overlord.renderer.ui.UiAssetBundle;
import com.overlord.renderer.ui.UiColor;
import com.overlord.renderer.ui.UiDrawCommand;
import com.overlord.renderer.ui.UiFrame;
import com.overlord.renderer.ui.UiGpuBackend;
import com.overlord.renderer.ui.UiRect;
import com.overlord.renderer.ui.UiTextureData;
import com.overlord.renderer.ui.UiTextureId;
import com.overlord.renderer.ui.UiUvRect;
import com.overlord.renderer.ui.UiRenderer;
import com.overlord.renderer.state.RenderStateSnapshot;
import com.overlord.renderer.state.ScissorBox;
import com.overlord.renderer.visual.RenderVisualSettings;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

class UiRenderPassTest {
    @Test
    void rendersOnlyTheImmutableUiFrameFromItsContextAtFramebufferSize() {
        RecordingBackend backend = new RecordingBackend();
        UiRenderer renderer = UiRenderer.create(
                bundle(), backend, MainThreadGuard.captureCurrentThread());
        UiRenderPass pass = new UiRenderPass(renderer);
        UiFrame frame = new UiFrame(List.of(new UiDrawCommand(
                UiTextureId.SOLID,
                new UiRect(1.0d, 2.0d, 3.0d, 4.0d),
                new UiUvRect(0.0f, 0.0f, 1.0f, 1.0f),
                new UiColor(1.0f, 1.0f, 1.0f, 1.0f),
                Optional.empty())));
        RenderSurfaceMetrics surface =
                new RenderSurfaceMetrics(320, 180, 640, 360, 2.0f, 2.0f);
        RenderContext context = new RenderContext(
                new Matrix4f(),
                new Matrix4f(),
                RenderVisualSettings.milestoneOneDefaults(),
                triangles -> {},
                surface,
                com.overlord.renderer.feedback.InteractionFeedbackFrame.hidden(),
                frame);

        pass.render(context, new RenderQueue());

        assertEquals("ui", pass.id());
        assertEquals(640, backend.width);
        assertEquals(360, backend.height);
        assertEquals(1, backend.drawCalls);
    }

    @Test
    void emptyFrameRecordsNoUiDrawMetrics() {
        RecordingBackend backend = new RecordingBackend();
        UiRenderPass pass = pass(backend);
        List<Long> triangles = new ArrayList<>();

        pass.render(context(UiFrame.empty(), triangles), new RenderQueue());

        assertEquals(List.of(), triangles);
        assertEquals(0, backend.drawCalls);
    }

    @Test
    void oneSuccessfulBatchRunRecordsOneDrawAndTwoTrianglesPerCommand() {
        RecordingBackend backend = new RecordingBackend();
        UiRenderPass pass = pass(backend);
        List<Long> triangles = new ArrayList<>();
        UiFrame frame = new UiFrame(List.of(
                command(UiTextureId.SOLID, 0),
                command(UiTextureId.SOLID, 10)));

        pass.render(context(frame, triangles), new RenderQueue());

        assertEquals(List.of(4L), triangles);
        assertEquals(1, backend.drawCalls);
    }

    @Test
    void splitBatchRunsRecordEachSuccessfulDrawWithItsOwnTriangleCount() {
        RecordingBackend backend = new RecordingBackend();
        UiRenderPass pass = pass(backend);
        List<Long> triangles = new ArrayList<>();
        UiFrame frame = new UiFrame(List.of(
                command(UiTextureId.SOLID, 0),
                command(UiTextureId.SOLID, 10),
                command(UiTextureId.ICON_ATLAS, 20),
                command(UiTextureId.SOLID, 30)));

        pass.render(context(frame, triangles), new RenderQueue());

        assertEquals(List.of(4L, 2L, 2L), triangles);
        assertEquals(3, backend.drawCalls);
    }

    @Test
    void failedBatchDrawDoesNotRecordThatRunOrAnyLaterRun() {
        RecordingBackend backend = new RecordingBackend();
        UiRenderPass pass = pass(backend);
        List<Long> triangles = new ArrayList<>();
        RuntimeException failure = new IllegalStateException("second draw failed");
        backend.drawFailureAt = 2;
        backend.drawFailure = failure;
        UiFrame frame = new UiFrame(List.of(
                command(UiTextureId.SOLID, 0),
                command(UiTextureId.ICON_ATLAS, 10),
                command(UiTextureId.SOLID, 20)));

        RuntimeException escaped = assertThrows(
                RuntimeException.class,
                () -> pass.render(context(frame, triangles), new RenderQueue()));

        assertSame(failure, escaped);
        assertEquals(List.of(2L), triangles);
        assertEquals(2, backend.drawCalls);
    }

    @Test
    void renderInputsRequireUiFramesWhileCompatibilityConstructorsDefaultToEmpty() {
        RenderFrameInput compatible = new RenderFrameInput(List.of(), 0.0d, 0);
        RenderContext compatibleContext = new RenderContext(new Matrix4f(), new Matrix4f());

        assertEquals(UiFrame.empty(), compatible.uiFrame());
        assertEquals(UiFrame.empty(), compatibleContext.uiFrame());
        assertThrows(
                NullPointerException.class,
                () -> new RenderFrameInput(
                        List.of(),
                        0.0d,
                        0,
                        com.overlord.renderer.feedback.InteractionFeedbackFrame.hidden(),
                        null));
        assertThrows(
                NullPointerException.class,
                () -> new RenderContext(
                        new Matrix4f(),
                        new Matrix4f(),
                        RenderVisualSettings.milestoneOneDefaults(),
                        triangles -> {},
                        new RenderSurfaceMetrics(0, 0, 0, 0, 1.0f, 1.0f),
                        com.overlord.renderer.feedback.InteractionFeedbackFrame.hidden(),
                        null));
    }

    @Test
    void rejectsMissingDependenciesOrRenderArguments() {
        UiRenderer renderer = UiRenderer.create(
                bundle(), new RecordingBackend(), MainThreadGuard.captureCurrentThread());
        UiRenderPass pass = new UiRenderPass(renderer);
        RenderContext context = new RenderContext(new Matrix4f(), new Matrix4f());

        assertThrows(NullPointerException.class, () -> new UiRenderPass(null));
        assertThrows(NullPointerException.class, () -> pass.render(null, new RenderQueue()));
        assertThrows(NullPointerException.class, () -> pass.render(context, null));
    }

    private static UiAssetBundle bundle() {
        UiTextureData texture =
                new UiTextureData(1, 1, ByteBuffer.wrap(new byte[] {-1, -1, -1, -1}));
        BitmapGlyph missing = new BitmapGlyph(
                0xfffd, new UiUvRect(0.0f, 0.0f, 1.0f, 1.0f), 1, 0, 1);
        return new UiAssetBundle(texture, texture, new BitmapFont(1, 1, Map.of(), missing));
    }

    private static UiRenderPass pass(RecordingBackend backend) {
        return new UiRenderPass(UiRenderer.create(
                bundle(), backend, MainThreadGuard.captureCurrentThread()));
    }

    private static RenderContext context(UiFrame frame, List<Long> triangles) {
        return new RenderContext(
                new Matrix4f(),
                new Matrix4f(),
                RenderVisualSettings.milestoneOneDefaults(),
                triangles::add,
                new RenderSurfaceMetrics(320, 180, 640, 360, 2.0f, 2.0f),
                com.overlord.renderer.feedback.InteractionFeedbackFrame.hidden(),
                frame);
    }

    private static UiDrawCommand command(UiTextureId texture, double left) {
        return new UiDrawCommand(
                texture,
                new UiRect(left, 0, left + 8, 8),
                new UiUvRect(0, 0, 1, 1),
                new UiColor(1, 1, 1, 1),
                Optional.empty());
    }

    private static final class RecordingBackend implements UiGpuBackend {
        private int width;
        private int height;
        private int textureId = 20;
        private int bufferId = 30;
        private int drawCalls;
        private int drawFailureAt = -1;
        private RuntimeException drawFailure;

        @Override public int createProgram(String vertexSource, String fragmentSource) { return 10; }
        @Override public void useProgram(int program) {}
        @Override public void setFramebufferSize(int program, float width, float height) {}
        @Override public void setTextureSampler(int program, int textureUnit) {}
        @Override public void setTextureSamplingEnabled(int program, boolean enabled) {}
        @Override public void deleteProgram(int program) {}
        @Override public int createTexture(UiTextureData texture) { return textureId++; }
        @Override public void bindTextureUnitZero(int texture) {}
        @Override public void deleteTexture(int texture) {}
        @Override public int createVertexArray() { return 29; }
        @Override public int createBuffer() { return bufferId++; }
        @Override public void configureBatch(int vertexArray, int vertexBuffer, int elementBuffer) {}
        @Override public void uploadBatch(int vertexArray, int vertexBuffer, int elementBuffer, float[] vertices, int[] indices) {}
        @Override
        public void drawBatch(int vertexArray, int indexCount) {
            drawCalls++;
            if (drawCalls == drawFailureAt) {
                throw drawFailure;
            }
        }
        @Override public void deleteBuffer(int buffer) {}
        @Override public void deleteVertexArray(int vertexArray) {}
        @Override public RenderStateSnapshot captureState() { return null; }
        @Override public void applyUiState(int framebufferWidth, int framebufferHeight) {
            width = framebufferWidth;
            height = framebufferHeight;
        }
        @Override public void setClip(Optional<ScissorBox> clip) {}
        @Override public void restoreState(RenderStateSnapshot snapshot) {}
    }
}

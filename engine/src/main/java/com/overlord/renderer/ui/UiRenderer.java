package com.overlord.renderer.ui;

import com.overlord.core.thread.MainThreadGuard;
import com.overlord.renderer.RenderSurfaceMetrics;
import com.overlord.renderer.metrics.RenderMetricsRecorder;
import com.overlord.renderer.state.RenderStateSnapshot;
import com.overlord.renderer.state.ScissorBox;
import java.util.Objects;
import java.util.Optional;

public final class UiRenderer implements AutoCloseable {
    private static final RenderMetricsRecorder NO_METRICS = triangles -> {};

    private final UiGpuBackend backend;
    private final MainThreadGuard guard;
    private final UiBatchPlanner planner;
    private final UiShader shader;
    private final UiTexture icons;
    private final UiTexture font;
    private final UiBatch batch;
    private boolean closed;

    private UiRenderer(
            UiGpuBackend backend,
            MainThreadGuard guard,
            UiShader shader,
            UiTexture icons,
            UiTexture font,
            UiBatch batch) {
        this.backend = backend;
        this.guard = guard;
        this.planner = new UiBatchPlanner();
        this.shader = shader;
        this.icons = icons;
        this.font = font;
        this.batch = batch;
    }

    public static UiRenderer create(
            UiAssetBundle assets,
            UiGpuBackend backend,
            MainThreadGuard guard) {
        Objects.requireNonNull(assets, "assets");
        Objects.requireNonNull(backend, "backend");
        Objects.requireNonNull(guard, "guard");
        guard.assertMainThread("UI renderer creation");

        UiShader shader = null;
        UiTexture icons = null;
        UiTexture font = null;
        UiBatch batch = null;
        try {
            shader = UiShader.create(backend, guard);
            icons = UiTexture.create(assets.icons(), backend, guard);
            font = UiTexture.create(assets.font(), backend, guard);
            batch = UiBatch.create(backend, guard);
            return new UiRenderer(backend, guard, shader, icons, font, batch);
        } catch (RuntimeException | Error failure) {
            Throwable primary = failure;
            primary = close(batch, primary);
            primary = close(font, primary);
            primary = close(icons, primary);
            primary = close(shader, primary);
            throw new UiInitializationException("Failed to initialize UI renderer", primary);
        }
    }

    public void render(UiFrame frame, RenderSurfaceMetrics surface) {
        render(frame, surface, NO_METRICS);
    }

    public void render(
            UiFrame frame,
            RenderSurfaceMetrics surface,
            RenderMetricsRecorder metricsRecorder) {
        Objects.requireNonNull(frame, "frame");
        Objects.requireNonNull(surface, "surface");
        Objects.requireNonNull(metricsRecorder, "metricsRecorder");
        guard.assertMainThread("UI renderer render");
        ensureOpen();
        int framebufferWidth = surface.framebufferWidth();
        int framebufferHeight = surface.framebufferHeight();
        if (framebufferWidth == 0 || framebufferHeight == 0) {
            return;
        }

        RenderStateSnapshot incoming = backend.captureState();
        Throwable failure = null;
        try {
            backend.applyUiState(framebufferWidth, framebufferHeight);
            shader.bind(framebufferWidth, framebufferHeight);
            for (UiBatchRun run : planner.plan(frame.commands())) {
                backend.setClip(toScissor(run.clip(), framebufferWidth, framebufferHeight));
                bind(run.texture());
                batch.upload(run.commands());
                batch.draw();
                metricsRecorder.recordDraw((long) run.commands().size() * 2L);
            }
        } catch (RuntimeException | Error renderFailure) {
            failure = renderFailure;
        }

        try {
            backend.restoreState(incoming);
        } catch (RuntimeException | Error restoreFailure) {
            failure = UiBatch.appendFailure(failure, restoreFailure);
        }
        if (failure != null) {
            UiBatch.rethrow(failure);
        }
    }

    @Override
    public void close() {
        guard.assertMainThread("UI renderer cleanup");
        if (closed) {
            return;
        }
        closed = true;
        Throwable failure = null;
        failure = close(batch, failure);
        failure = close(font, failure);
        failure = close(icons, failure);
        failure = close(shader, failure);
        if (failure != null) {
            UiBatch.rethrow(failure);
        }
    }

    private void bind(UiTextureId textureId) {
        switch (textureId) {
            case ICON_ATLAS -> {
                shader.setTextureSamplingEnabled(true);
                icons.bindUnitZero();
            }
            case FONT_ATLAS -> {
                shader.setTextureSamplingEnabled(true);
                font.bindUnitZero();
            }
            case SOLID -> {
                shader.setTextureSamplingEnabled(false);
                backend.bindTextureUnitZero(0);
            }
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("UI renderer has been cleaned up");
        }
    }

    private static Optional<ScissorBox> toScissor(
            Optional<UiRect> clip,
            int framebufferWidth,
            int framebufferHeight) {
        if (clip.isEmpty()) {
            return Optional.empty();
        }
        UiRect rectangle = clip.orElseThrow();
        int left = clamp((int) Math.floor(rectangle.left()), 0, framebufferWidth);
        int right = clamp((int) Math.ceil(rectangle.right()), 0, framebufferWidth);
        int top = clamp((int) Math.floor(rectangle.top()), 0, framebufferHeight);
        int bottom = clamp((int) Math.ceil(rectangle.bottom()), 0, framebufferHeight);
        return Optional.of(new ScissorBox(
                left,
                framebufferHeight - bottom,
                Math.max(0, right - left),
                Math.max(0, bottom - top)));
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(value, maximum));
    }

    private static Throwable close(AutoCloseable resource, Throwable failure) {
        if (resource == null) {
            return failure;
        }
        try {
            resource.close();
        } catch (RuntimeException | Error cleanupFailure) {
            return UiBatch.appendFailure(failure, cleanupFailure);
        } catch (Exception impossible) {
            return UiBatch.appendFailure(failure, new AssertionError(impossible));
        }
        return failure;
    }
}

package com.overlord.renderer.pass;

import com.overlord.renderer.RenderSurfaceMetrics;
import com.overlord.renderer.feedback.CrosshairGeometry;
import com.overlord.renderer.feedback.ScreenQuadBatch;
import com.overlord.renderer.queue.RenderQueue;
import com.overlord.renderer.shader.ShaderBinding;
import com.overlord.renderer.state.BlendMode;
import com.overlord.renderer.state.RenderStateBackend;
import com.overlord.renderer.state.RenderStateScope;
import com.overlord.renderer.state.RenderStateSpec;
import com.overlord.renderer.state.Viewport;
import java.util.Objects;
import org.joml.Vector2f;

public final class CrosshairRenderPass implements RenderPass {
    private static final RenderStateSpec CROSSHAIR_STATE =
            new RenderStateSpec(false, false, BlendMode.DISABLED, false);

    private final RenderStateBackend stateBackend;
    private final ShaderBinding shader;
    private final ScreenQuadBatch batch;

    public CrosshairRenderPass(
            RenderStateBackend stateBackend,
            ShaderBinding shader,
            ScreenQuadBatch batch) {
        this.stateBackend = Objects.requireNonNull(stateBackend, "stateBackend");
        this.shader = Objects.requireNonNull(shader, "shader");
        this.batch = Objects.requireNonNull(batch, "batch");
    }

    @Override
    public String id() {
        return "crosshair";
    }

    @Override
    public void render(RenderContext context, RenderQueue queue) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(queue, "queue");
        RenderSurfaceMetrics surface = context.surfaceMetrics();
        if (!context.feedback().visibility().showGameplayFeedback()
                || surface.framebufferWidth() <= 0
                || surface.framebufferHeight() <= 0) {
            return;
        }

        try (RenderStateScope ignored = RenderStateScope.open(stateBackend, CROSSHAIR_STATE)) {
            stateBackend.setViewport(
                    new Viewport(
                            0,
                            0,
                            surface.framebufferWidth(),
                            surface.framebufferHeight()));
            shader.use();
            shader.setVector2(
                    "framebufferSize",
                    new Vector2f(surface.framebufferWidth(), surface.framebufferHeight()));
            batch.upload(
                    CrosshairGeometry.quads(
                            surface.framebufferWidth(), surface.framebufferHeight()));
            batch.draw();
            context.metricsRecorder().recordDraw(8L);
        }
    }
}

package com.overlord.renderer.pass;

import com.overlord.renderer.queue.RenderQueue;
import com.overlord.renderer.ui.UiRenderer;
import java.util.Objects;

public final class UiRenderPass implements RenderPass {
    private final UiRenderer renderer;

    public UiRenderPass(UiRenderer renderer) {
        this.renderer = Objects.requireNonNull(renderer, "renderer");
    }

    @Override
    public String id() {
        return "ui";
    }

    @Override
    public void render(RenderContext context, RenderQueue queue) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(queue, "queue");
        renderer.render(
                context.uiFrame(),
                context.surfaceMetrics(),
                context.metricsRecorder());
    }
}

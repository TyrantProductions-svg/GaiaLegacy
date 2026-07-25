package com.overlord.renderer.pass;

import com.overlord.renderer.queue.RenderQueue;
import java.util.Objects;

public final class DebugRenderPass implements RenderPass {
    @Override
    public String id() {
        return "debug";
    }

    @Override
    public void render(RenderContext context, RenderQueue queue) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(queue, "queue");
    }
}

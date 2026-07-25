package com.overlord.renderer.pass;

import com.overlord.renderer.queue.RenderQueue;

public interface RenderPass {
    String id();

    void render(RenderContext context, RenderQueue queue);
}

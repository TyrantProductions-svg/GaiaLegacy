package com.overlord.renderer.pass;

import com.overlord.renderer.queue.RenderQueue;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class RenderPipeline {
    private final List<RenderPass> passes;

    public RenderPipeline(List<RenderPass> passes) {
        this.passes = List.copyOf(Objects.requireNonNull(passes, "passes"));
        Set<String> ids = new HashSet<>();
        for (RenderPass pass : this.passes) {
            Objects.requireNonNull(pass, "pass");
            if (!ids.add(Objects.requireNonNull(pass.id(), "pass id"))) {
                throw new IllegalArgumentException("Duplicate render pass id: " + pass.id());
            }
        }
    }

    public void render(RenderContext context, RenderQueue queue) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(queue, "queue");
        try {
            for (RenderPass pass : passes) {
                pass.render(context, queue);
            }
        } finally {
            queue.clear();
        }
    }

    public List<String> passIds() {
        return passes.stream().map(RenderPass::id).toList();
    }
}

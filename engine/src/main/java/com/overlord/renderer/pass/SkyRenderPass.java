package com.overlord.renderer.pass;

import com.overlord.renderer.queue.RenderQueue;
import com.overlord.renderer.state.BlendMode;
import com.overlord.renderer.state.RenderStateBackend;
import com.overlord.renderer.state.RenderStateScope;
import com.overlord.renderer.state.RenderStateSpec;
import java.util.Objects;

public final class SkyRenderPass implements RenderPass {
    private static final RenderStateSpec SKY_STATE =
            new RenderStateSpec(false, true, BlendMode.DISABLED, false);

    private final RenderStateBackend stateBackend;

    public SkyRenderPass(RenderStateBackend stateBackend) {
        this.stateBackend = Objects.requireNonNull(stateBackend, "stateBackend");
    }

    @Override
    public String id() {
        return "sky";
    }

    @Override
    public void render(RenderContext context, RenderQueue queue) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(queue, "queue");
        try (RenderStateScope ignored = RenderStateScope.open(stateBackend, SKY_STATE)) {
            stateBackend.clearColorAndDepth();
        }
    }
}

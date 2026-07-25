package com.overlord.renderer.pass;

import com.overlord.renderer.material.Material;
import com.overlord.renderer.queue.RenderItem;
import com.overlord.renderer.queue.RenderQueue;
import com.overlord.renderer.shader.ShaderBinding;
import com.overlord.renderer.state.BlendMode;
import com.overlord.renderer.state.RenderStateBackend;
import com.overlord.renderer.state.RenderStateScope;
import com.overlord.renderer.state.RenderStateSpec;
import java.util.List;
import java.util.Objects;

public final class WorldRenderPass implements RenderPass {
    private static final RenderStateSpec OPAQUE_STATE =
            new RenderStateSpec(true, true, BlendMode.DISABLED, false);
    private static final RenderStateSpec TRANSPARENT_STATE =
            new RenderStateSpec(true, false, BlendMode.ALPHA, false);

    private final RenderStateBackend stateBackend;

    public WorldRenderPass(RenderStateBackend stateBackend) {
        this.stateBackend = Objects.requireNonNull(stateBackend, "stateBackend");
    }

    @Override
    public String id() {
        return "world";
    }

    @Override
    public void render(RenderContext context, RenderQueue queue) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(queue, "queue");
        renderCategory(context, queue.opaqueItems(), OPAQUE_STATE);
        renderCategory(context, queue.transparentItems(), TRANSPARENT_STATE);
    }

    private void renderCategory(
            RenderContext context,
            List<RenderItem> items,
            RenderStateSpec state) {
        if (items.isEmpty()) {
            return;
        }
        try (RenderStateScope ignored = RenderStateScope.open(stateBackend, state)) {
            for (RenderItem item : items) {
                renderItem(context, item);
            }
        }
    }

    private static void renderItem(RenderContext context, RenderItem item) {
        Material material = item.material();
        ShaderBinding shader = material.shader();
        shader.use();
        material.texture().bind(0);
        shader.setMatrix4("projection", context.projection());
        shader.setMatrix4("view", context.view());
        shader.setMatrix4("model", item.object().modelMatrix());
        shader.setInt("textureAtlas", 0);
        item.object().mesh().draw();
    }
}

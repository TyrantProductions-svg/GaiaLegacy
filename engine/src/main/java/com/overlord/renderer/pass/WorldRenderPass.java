package com.overlord.renderer.pass;

import com.overlord.renderer.material.Material;
import com.overlord.renderer.queue.RenderItem;
import com.overlord.renderer.queue.RenderQueue;
import com.overlord.renderer.shader.ShaderBinding;
import com.overlord.renderer.state.BlendMode;
import com.overlord.renderer.state.RenderStateBackend;
import com.overlord.renderer.state.RenderStateScope;
import com.overlord.renderer.state.RenderStateSpec;
import com.overlord.renderer.visual.LinearColor;
import com.overlord.renderer.visual.RenderVisualSettings;
import com.overlord.renderer.feedback.BlockVisualCoordinate;
import com.overlord.config.GameConfig;
import java.util.List;
import java.util.Objects;
import org.joml.Vector3f;

public final class WorldRenderPass implements RenderPass {
    private static final RenderStateSpec OPAQUE_STATE =
            new RenderStateSpec(true, true, BlendMode.DISABLED, false);
    private static final RenderStateSpec TRANSPARENT_STATE =
            new RenderStateSpec(true, false, BlendMode.ALPHA, false);

    private final RenderStateBackend stateBackend;
    private final Vector3f excludedCellScratch = new Vector3f();

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

    private void renderItem(RenderContext context, RenderItem item) {
        Material material = item.material();
        ShaderBinding shader = material.shader();
        shader.use();
        material.texture().bind(0);
        shader.setMatrix4("projection", context.projection());
        shader.setMatrix4("view", context.view());
        shader.setMatrix4("model", item.object().modelMatrix());
        shader.setInt("textureAtlas", 0);
        RenderVisualSettings settings = context.visualSettings();
        shader.setVector3("sunDirection", settings.sunDirection());
        shader.setFloat("ambientStrength", settings.ambientStrength());
        shader.setFloat(
                "directionalStrength", settings.directionalStrength());
        LinearColor fogColor = settings.fogColor();
        shader.setVector3(
                "fogColor",
                new Vector3f(
                        fogColor.red(), fogColor.green(), fogColor.blue()));
        shader.setFloat("fogStart", settings.fogStart());
        shader.setFloat("fogEnd", settings.fogEnd());
        List<BlockVisualCoordinate> excluded = context.feedback().excludedBlockCells();
        int excludedInChunk = 0;
        for (BlockVisualCoordinate cell : excluded) {
            if (Math.floorDiv(cell.x(), GameConfig.Chunk.SIZE) != item.object().key().x()
                    || Math.floorDiv(cell.z(), GameConfig.Chunk.SIZE)
                            != item.object().key().z()) {
                continue;
            }
            shader.setVector3(
                    "excludedBlockCells[" + excludedInChunk + "]",
                    excludedCellScratch.set(cell.x(), cell.y(), cell.z()));
            excludedInChunk++;
        }
        shader.setInt("excludedBlockCount", excludedInChunk);
        item.object().mesh().draw();
        context.metricsRecorder().recordDraw(item.object().mesh().vertexCount() / 3L);
    }
}

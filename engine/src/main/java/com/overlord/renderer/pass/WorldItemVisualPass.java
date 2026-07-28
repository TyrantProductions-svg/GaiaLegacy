package com.overlord.renderer.pass;

import com.overlord.renderer.TextureBinding;
import com.overlord.renderer.feedback.UnitCubeMesh;
import com.overlord.renderer.feedback.WorldItemVisual;
import com.overlord.renderer.queue.RenderQueue;
import com.overlord.renderer.shader.ShaderBinding;
import com.overlord.renderer.state.BlendMode;
import com.overlord.renderer.state.DepthFunction;
import com.overlord.renderer.state.RenderStateBackend;
import com.overlord.renderer.state.RenderStateScope;
import com.overlord.renderer.state.RenderStateSpec;
import com.overlord.renderer.texture.TextureRegion;
import java.util.List;
import java.util.Objects;
import org.joml.Matrix4f;

/** Renders immutable logical-world-item presentation values as shared small cubes. */
public final class WorldItemVisualPass implements RenderPass {
    private static final float EDGE_LENGTH = 0.25f;
    private static final RenderStateSpec WORLD_ITEM_STATE =
            new RenderStateSpec(
                    true,
                    DepthFunction.LEQUAL,
                    true,
                    BlendMode.DISABLED,
                    false,
                    false,
                    0.0f,
                    0.0f);

    private final RenderStateBackend stateBackend;
    private final ShaderBinding shader;
    private final TextureBinding blockAtlas;
    private final UnitCubeMesh cube;

    public WorldItemVisualPass(
            RenderStateBackend stateBackend,
            ShaderBinding shader,
            TextureBinding blockAtlas,
            UnitCubeMesh cube) {
        this.stateBackend = Objects.requireNonNull(stateBackend, "stateBackend");
        this.shader = Objects.requireNonNull(shader, "shader");
        this.blockAtlas = Objects.requireNonNull(blockAtlas, "blockAtlas");
        this.cube = Objects.requireNonNull(cube, "cube");
    }

    @Override
    public String id() {
        return "world-items";
    }

    @Override
    public void render(RenderContext context, RenderQueue queue) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(queue, "queue");
        List<WorldItemVisual> worldItems = context.feedback().worldItems();
        if (worldItems.isEmpty()) {
            return;
        }

        try (RenderStateScope ignored =
                RenderStateScope.open(stateBackend, WORLD_ITEM_STATE)) {
            shader.use();
            shader.setMatrix4("projection", context.projection());
            shader.setMatrix4("view", context.view());
            shader.setInt("blockAtlas", 0);
            blockAtlas.bind(0);
            for (WorldItemVisual item : worldItems) {
                TextureRegion region = item.region();
                shader.setMatrix4(
                        "model",
                        new Matrix4f()
                                .translation((float) item.x(), (float) item.y(), (float) item.z())
                                .scale(EDGE_LENGTH));
                shader.setFloat("uMin", region.uMin());
                shader.setFloat("uMax", region.uMax());
                shader.setFloat("vMin", region.vMin());
                shader.setFloat("vMax", region.vMax());
                cube.draw();
                context.metricsRecorder().recordDraw(12L);
            }
        }
    }
}

package com.overlord.renderer.pass;

import com.overlord.renderer.TextureBinding;
import com.overlord.renderer.feedback.BlockDamageVisual;
import com.overlord.renderer.feedback.DamageAtlasLayout;
import com.overlord.renderer.feedback.UnitCubeMesh;
import com.overlord.renderer.queue.RenderQueue;
import com.overlord.renderer.shader.ShaderBinding;
import com.overlord.renderer.state.BlendMode;
import com.overlord.renderer.state.DepthFunction;
import com.overlord.renderer.state.RenderStateBackend;
import com.overlord.renderer.state.RenderStateScope;
import com.overlord.renderer.state.RenderStateSpec;
import com.overlord.renderer.texture.TextureRegion;
import java.util.Objects;
import org.joml.Matrix4f;

public final class BlockDamageOverlayPass implements RenderPass {
    private static final RenderStateSpec OVERLAY_STATE =
            new RenderStateSpec(
                    true,
                    DepthFunction.LEQUAL,
                    false,
                    BlendMode.DISABLED,
                    false,
                    true,
                    -1.0f,
                    -1.0f);

    private final RenderStateBackend stateBackend;
    private final ShaderBinding shader;
    private final TextureBinding damageAtlas;
    private final DamageAtlasLayout layout;
    private final UnitCubeMesh cube;

    public BlockDamageOverlayPass(
            RenderStateBackend stateBackend,
            ShaderBinding shader,
            TextureBinding damageAtlas,
            DamageAtlasLayout layout,
            UnitCubeMesh cube) {
        this.stateBackend = Objects.requireNonNull(stateBackend, "stateBackend");
        this.shader = Objects.requireNonNull(shader, "shader");
        this.damageAtlas = Objects.requireNonNull(damageAtlas, "damageAtlas");
        this.layout = Objects.requireNonNull(layout, "layout");
        this.cube = Objects.requireNonNull(cube, "cube");
    }

    @Override
    public String id() {
        return "block-damage";
    }

    @Override
    public void render(RenderContext context, RenderQueue queue) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(queue, "queue");
        if (!context.feedback().visibility().showGameplayFeedback()
                || context.feedback().blockDamage().isEmpty()) {
            return;
        }
        BlockDamageVisual damage = context.feedback().blockDamage().orElseThrow();
        if (damage.crackStage() < 0 || damage.crackStage() >= layout.stageCount()) {
            return;
        }
        TextureRegion region = layout.region(damage.crackStage());

        try (RenderStateScope ignored = RenderStateScope.open(stateBackend, OVERLAY_STATE)) {
            shader.use();
            shader.setMatrix4("projection", context.projection());
            shader.setMatrix4("view", context.view());
            shader.setMatrix4(
                    "model",
                    new Matrix4f().translation(
                            damage.blockX(), damage.blockY(), damage.blockZ()));
            shader.setInt("damageAtlas", 0);
            shader.setFloat("uMin", region.uMin());
            shader.setFloat("uMax", region.uMax());
            shader.setFloat("vMin", region.vMin());
            shader.setFloat("vMax", region.vMax());
            damageAtlas.bind(0);
            cube.draw();
            context.metricsRecorder().recordDraw(12L);
        }
    }
}

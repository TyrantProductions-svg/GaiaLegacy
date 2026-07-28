package com.overlord.renderer.pass;

import com.overlord.renderer.TextureBinding;
import com.overlord.renderer.feedback.ParticleRenderBatch;
import com.overlord.renderer.feedback.StreamingTexturedCubeBatch;
import com.overlord.renderer.queue.RenderQueue;
import com.overlord.renderer.shader.ShaderBinding;
import com.overlord.renderer.state.BlendMode;
import com.overlord.renderer.state.DepthFunction;
import com.overlord.renderer.state.RenderStateBackend;
import com.overlord.renderer.state.RenderStateScope;
import com.overlord.renderer.state.RenderStateSpec;
import java.util.Objects;

public final class ParticleRenderPass implements RenderPass {
    private static final RenderStateSpec PARTICLE_STATE =
            new RenderStateSpec(
                    true,
                    DepthFunction.LEQUAL,
                    false,
                    BlendMode.ALPHA,
                    false,
                    false,
                    0.0f,
                    0.0f);

    private final RenderStateBackend stateBackend;
    private final ShaderBinding shader;
    private final TextureBinding blockAtlas;
    private final StreamingTexturedCubeBatch batch;

    public ParticleRenderPass(
            RenderStateBackend stateBackend,
            ShaderBinding shader,
            TextureBinding blockAtlas,
            StreamingTexturedCubeBatch batch) {
        this.stateBackend = Objects.requireNonNull(stateBackend, "stateBackend");
        this.shader = Objects.requireNonNull(shader, "shader");
        this.blockAtlas = Objects.requireNonNull(blockAtlas, "blockAtlas");
        this.batch = Objects.requireNonNull(batch, "batch");
    }

    @Override
    public String id() {
        return "particles";
    }

    @Override
    public void render(RenderContext context, RenderQueue queue) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(queue, "queue");
        ParticleRenderBatch particles = context.feedback().particles();
        if (particles.particles().isEmpty()) {
            return;
        }

        try (RenderStateScope ignored = RenderStateScope.open(stateBackend, PARTICLE_STATE)) {
            shader.use();
            shader.setMatrix4("projection", context.projection());
            shader.setMatrix4("view", context.view());
            shader.setInt("blockAtlas", 0);
            blockAtlas.bind(0);
            batch.upload(particles);
            batch.draw();
            context.metricsRecorder().recordDraw((long) particles.particles().size() * 12L);
        }
    }
}

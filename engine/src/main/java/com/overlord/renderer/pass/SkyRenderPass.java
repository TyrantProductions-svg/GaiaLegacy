package com.overlord.renderer.pass;

import com.overlord.renderer.queue.RenderQueue;
import com.overlord.renderer.shader.ShaderBinding;
import com.overlord.renderer.state.BlendMode;
import com.overlord.renderer.state.RenderStateBackend;
import com.overlord.renderer.state.RenderStateScope;
import com.overlord.renderer.state.RenderStateSpec;
import com.overlord.renderer.visual.LinearColor;
import com.overlord.renderer.visual.RenderVisualSettings;
import java.util.Objects;
import org.joml.Vector3f;

public final class SkyRenderPass implements RenderPass {
    private static final RenderStateSpec SKY_STATE =
            new RenderStateSpec(false, false, BlendMode.DISABLED, false);

    private final RenderStateBackend stateBackend;
    private final ShaderBinding shader;
    private final Runnable drawSky;

    public SkyRenderPass(
            RenderStateBackend stateBackend,
            ShaderBinding shader,
            Runnable drawSky) {
        this.stateBackend = Objects.requireNonNull(stateBackend, "stateBackend");
        this.shader = Objects.requireNonNull(shader, "shader");
        this.drawSky = Objects.requireNonNull(drawSky, "drawSky");
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
            shader.use();
            RenderVisualSettings settings = context.visualSettings();
            shader.setVector3(
                    "skyHorizon", vector(settings.skyHorizon()));
            shader.setVector3("skyTop", vector(settings.skyTop()));
            drawSky.run();
        }
    }

    private static Vector3f vector(LinearColor color) {
        return new Vector3f(color.red(), color.green(), color.blue());
    }
}

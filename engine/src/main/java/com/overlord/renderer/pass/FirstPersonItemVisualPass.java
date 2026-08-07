package com.overlord.renderer.pass;

import com.overlord.renderer.TextureBinding;
import com.overlord.renderer.feedback.FirstPersonItemVisual;
import com.overlord.renderer.feedback.UnitCubeMesh;
import com.overlord.renderer.queue.RenderQueue;
import com.overlord.renderer.shader.ShaderBinding;
import com.overlord.renderer.state.BlendMode;
import com.overlord.renderer.state.DepthFunction;
import com.overlord.renderer.state.RenderStateBackend;
import com.overlord.renderer.state.RenderStateScope;
import com.overlord.renderer.state.RenderStateSpec;
import java.util.Objects;
import org.joml.Matrix4f;

/** Draws the smallest canonical held-item representation in view space. */
public final class FirstPersonItemVisualPass implements RenderPass {
    private static final RenderStateSpec STATE =
            new RenderStateSpec(
                    true,
                    DepthFunction.LESS,
                    true,
                    BlendMode.ALPHA,
                    true,
                    false,
                    0.0f,
                    0.0f);
    private static final float ANCHOR_X = 0.58f;
    private static final float ANCHOR_Y = -0.58f;
    private static final float ANCHOR_Z = -1.15f;
    private static final float BASE_PITCH_DEGREES = 5.0f;
    private static final float BASE_YAW_DEGREES = 8.0f;
    private static final float BASE_ROLL_DEGREES = -3.0f;
    private static final float MOVEMENT_TRANSLATION_RESPONSE = 0.65f;
    private static final float MOVEMENT_VERTICAL_RESPONSE = 0.55f;
    private static final float MOVEMENT_ROLL_RESPONSE = 0.5f;
    private static final float HELD_SCALE = 0.35f;

    private final RenderStateBackend stateBackend;
    private final ShaderBinding shader;
    private final TextureBinding blockAtlas;
    private final UnitCubeMesh cube;

    public FirstPersonItemVisualPass(
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
        return "first-person-item";
    }

    @Override
    public void render(RenderContext context, RenderQueue queue) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(queue, "queue");
        FirstPersonItemVisual visual = context.feedback().firstPersonItem().orElse(null);
        if (visual == null) {
            return;
        }
        var transform = visual.transform();
        var movement = context.feedback().movementVisual();
        try (RenderStateScope ignored = RenderStateScope.open(stateBackend, STATE)) {
            stateBackend.clearDepth();
            shader.use();
            shader.setMatrix4("projection", context.projection());
            shader.setMatrix4("view", new Matrix4f());
            shader.setInt("blockAtlas", 0);
            shader.setFloat("visualAlpha", transform.alpha());
            blockAtlas.bind(0);
            shader.setMatrix4("model", new Matrix4f()
                    .translation(ANCHOR_X, ANCHOR_Y, ANCHOR_Z)
                    .rotateX((float) Math.toRadians(BASE_PITCH_DEGREES))
                    .rotateY((float) Math.toRadians(BASE_YAW_DEGREES))
                    .rotateZ((float) Math.toRadians(BASE_ROLL_DEGREES))
                    .translate(
                            -movement.translationX() * MOVEMENT_TRANSLATION_RESPONSE,
                            -movement.translationY() * MOVEMENT_VERTICAL_RESPONSE,
                            0.0f)
                    .rotateZ((float) Math.toRadians(
                            -movement.rollDegrees() * MOVEMENT_ROLL_RESPONSE))
                    .translate(
                            transform.translationX(),
                            transform.translationY(),
                            transform.translationZ())
                    .rotateX((float) Math.toRadians(transform.pitchDegrees()))
                    .rotateY((float) Math.toRadians(transform.yawDegrees()))
                    .rotateZ((float) Math.toRadians(transform.rollDegrees()))
                    .scale(HELD_SCALE * transform.scale())
                    .translate(-0.5f, -0.5f, -0.5f));
            TransientBlockVisualPass.setFaces(shader, visual.faces());
            cube.draw();
            context.metricsRecorder().recordDraw(12L);
        }
    }
}

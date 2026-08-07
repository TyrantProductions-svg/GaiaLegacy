package com.overlord.renderer.pass;

import com.overlord.renderer.TextureBinding;
import com.overlord.renderer.feedback.TransientBlockVisual;
import com.overlord.renderer.feedback.UnitCubeMesh;
import com.overlord.renderer.queue.RenderQueue;
import com.overlord.renderer.shader.ShaderBinding;
import com.overlord.renderer.state.BlendMode;
import com.overlord.renderer.state.DepthFunction;
import com.overlord.renderer.state.RenderStateBackend;
import com.overlord.renderer.state.RenderStateScope;
import com.overlord.renderer.state.RenderStateSpec;
import com.overlord.renderer.texture.TextureRegion;
import com.overlord.voxel.BlockFace;
import java.util.List;
import java.util.Objects;
import org.joml.Matrix4f;

/** Draws collision-free immutable proxies for committed block transitions. */
public final class TransientBlockVisualPass implements RenderPass {
    private static final RenderStateSpec STATE = new RenderStateSpec(
            true, DepthFunction.LEQUAL, false, BlendMode.ALPHA,
            false, false, 0.0f, 0.0f);

    private final RenderStateBackend stateBackend;
    private final ShaderBinding shader;
    private final TextureBinding blockAtlas;
    private final UnitCubeMesh cube;

    public TransientBlockVisualPass(
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
        return "transient-blocks";
    }

    @Override
    public void render(RenderContext context, RenderQueue queue) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(queue, "queue");
        List<TransientBlockVisual> visuals = context.feedback().transientBlocks();
        if (visuals.isEmpty()) {
            return;
        }
        try (RenderStateScope ignored = RenderStateScope.open(stateBackend, STATE)) {
            shader.use();
            shader.setMatrix4("projection", context.projection());
            shader.setMatrix4("view", context.view());
            shader.setInt("blockAtlas", 0);
            blockAtlas.bind(0);
            for (TransientBlockVisual visual : visuals) {
                var coordinate = visual.coordinate();
                var transform = visual.transform();
                shader.setFloat("visualAlpha", transform.alpha());
                shader.setMatrix4("model", new Matrix4f()
                        .translation(
                                coordinate.x() + 0.5f + transform.translationX(),
                                coordinate.y() + 0.5f + transform.translationY(),
                                coordinate.z() + 0.5f + transform.translationZ())
                        .rotateX((float) Math.toRadians(transform.pitchDegrees()))
                        .rotateY((float) Math.toRadians(transform.yawDegrees()))
                        .rotateZ((float) Math.toRadians(transform.rollDegrees()))
                        .scale(transform.scale())
                        .translate(-0.5f, -0.5f, -0.5f));
                setFaces(shader, visual.faces());
                cube.draw();
                context.metricsRecorder().recordDraw(12L);
            }
        }
    }

    static void setFaces(
            ShaderBinding shader,
            com.overlord.renderer.feedback.WorldItemFaceRegions faces) {
        for (BlockFace face : BlockFace.values()) {
            int index = face.ordinal();
            TextureRegion region = faces.region(face);
            shader.setFloat("uMin[" + index + "]", region.uMin());
            shader.setFloat("uMax[" + index + "]", region.uMax());
            shader.setFloat("vMin[" + index + "]", region.vMin());
            shader.setFloat("vMax[" + index + "]", region.vMax());
        }
    }
}

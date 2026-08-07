package com.overlord.renderer.pass;

import com.overlord.renderer.RenderSurfaceMetrics;
import com.overlord.renderer.TextureBinding;
import com.overlord.renderer.feedback.InteractionFeedbackFrame;
import com.overlord.renderer.feedback.UnitCubeMesh;
import com.overlord.renderer.metrics.RenderMetricsRecorder;
import com.overlord.renderer.shader.ShaderBinding;
import com.overlord.renderer.state.RenderStateBackend;
import com.overlord.renderer.state.RenderStateSnapshot;
import com.overlord.renderer.state.RenderStateSpec;
import com.overlord.renderer.visual.RenderVisualSettings;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3fc;

final class FeedbackVisualPassTestSupport {
    static final RenderStateSnapshot INCOMING = new RenderStateSnapshot(
            false, false, false, 1, 2, 3, 4, 5, 6, true, 7, 8, 9);

    private FeedbackVisualPassTestSupport() {}

    static RenderContext context(
            InteractionFeedbackFrame feedback,
            RenderMetricsRecorder metricsRecorder) {
        return new RenderContext(
                new Matrix4f(),
                new Matrix4f(),
                RenderVisualSettings.milestoneOneDefaults(),
                metricsRecorder,
                new RenderSurfaceMetrics(1024, 768, 1024, 768, 1, 1),
                feedback);
    }

    static final class RecordingState implements RenderStateBackend {
        RenderStateSnapshot current = INCOMING;
        RenderStateSpec applied;
        int captureCalls;
        int restoreCalls;
        int clearDepthCalls;

        @Override
        public RenderStateSnapshot capture() {
            captureCalls++;
            return current;
        }

        @Override
        public void apply(RenderStateSpec state) {
            applied = state;
        }

        @Override
        public void restore(RenderStateSnapshot snapshot) {
            current = snapshot;
            restoreCalls++;
        }

        @Override
        public void clearColorAndDepth() {}

        @Override
        public void clearDepth() {
            clearDepthCalls++;
        }
    }

    static final class RecordingShader implements ShaderBinding {
        final List<Matrix4f> models = new ArrayList<>();
        final List<Matrix4f> projections = new ArrayList<>();
        final List<Matrix4f> views = new ArrayList<>();
        final Map<String, List<Float>> floats = new HashMap<>();
        int useCalls;

        @Override
        public int programId() {
            return 1;
        }

        @Override
        public void use() {
            useCalls++;
        }

        @Override
        public void setMatrix4(String uniform, Matrix4fc value) {
            if (uniform.equals("model")) {
                models.add(new Matrix4f(value));
            } else if (uniform.equals("projection")) {
                projections.add(new Matrix4f(value));
            } else if (uniform.equals("view")) {
                views.add(new Matrix4f(value));
            }
        }

        @Override
        public void setInt(String uniform, int value) {}

        @Override
        public void setFloat(String uniform, float value) {
            floats.computeIfAbsent(uniform, ignored -> new ArrayList<>()).add(value);
        }

        @Override
        public void setVector3(String uniform, Vector3fc value) {}
    }

    static final class RecordingTexture implements TextureBinding {
        int bindCalls;

        @Override
        public void bind(int textureUnit) {
            bindCalls++;
        }
    }

    static final class RecordingCube implements UnitCubeMesh {
        int drawCalls;
        RuntimeException failure;

        @Override
        public void draw() {
            drawCalls++;
            if (failure != null) {
                throw failure;
            }
        }

        @Override
        public void cleanup() {}
    }
}

package com.overlord.renderer.pass;

import com.overlord.renderer.visual.RenderVisualSettings;
import com.overlord.renderer.metrics.RenderMetricsRecorder;
import java.util.Objects;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

public final class RenderContext {
    private final Matrix4f projection;
    private final Matrix4f view;
    private final RenderVisualSettings visualSettings;
    private final RenderMetricsRecorder metricsRecorder;

    public RenderContext(Matrix4fc projection, Matrix4fc view) {
        this(projection, view, RenderVisualSettings.milestoneOneDefaults(), triangles -> {});
    }

    public RenderContext(
            Matrix4fc projection,
            Matrix4fc view,
            RenderVisualSettings visualSettings) {
        this(projection, view, visualSettings, triangles -> {});
    }

    public RenderContext(
            Matrix4fc projection,
            Matrix4fc view,
            RenderVisualSettings visualSettings,
            RenderMetricsRecorder metricsRecorder) {
        this.projection = new Matrix4f(Objects.requireNonNull(projection, "projection"));
        this.view = new Matrix4f(Objects.requireNonNull(view, "view"));
        this.visualSettings =
                Objects.requireNonNull(visualSettings, "visualSettings");
        this.metricsRecorder = Objects.requireNonNull(metricsRecorder, "metricsRecorder");
    }

    public Matrix4f projection() {
        return new Matrix4f(projection);
    }

    public Matrix4f view() {
        return new Matrix4f(view);
    }

    public RenderVisualSettings visualSettings() {
        return visualSettings;
    }

    public RenderMetricsRecorder metricsRecorder() {
        return metricsRecorder;
    }
}

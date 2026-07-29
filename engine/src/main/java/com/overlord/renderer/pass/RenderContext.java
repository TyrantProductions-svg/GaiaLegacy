package com.overlord.renderer.pass;

import com.overlord.renderer.metrics.RenderMetricsRecorder;
import com.overlord.renderer.RenderSurfaceMetrics;
import com.overlord.renderer.feedback.InteractionFeedbackFrame;
import com.overlord.renderer.ui.UiFrame;
import com.overlord.renderer.visual.RenderVisualSettings;
import java.util.Objects;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

public final class RenderContext {
    private static final RenderSurfaceMetrics HIDDEN_SURFACE =
            new RenderSurfaceMetrics(0, 0, 0, 0, 1.0f, 1.0f);

    private final Matrix4f projection;
    private final Matrix4f view;
    private final RenderVisualSettings visualSettings;
    private final RenderMetricsRecorder metricsRecorder;
    private final RenderSurfaceMetrics surfaceMetrics;
    private final InteractionFeedbackFrame feedback;
    private final UiFrame uiFrame;

    public RenderContext(Matrix4fc projection, Matrix4fc view) {
        this(
                projection,
                view,
                RenderVisualSettings.milestoneOneDefaults(),
                triangles -> {},
                HIDDEN_SURFACE,
                InteractionFeedbackFrame.hidden(),
                UiFrame.empty());
    }

    public RenderContext(
            Matrix4fc projection,
            Matrix4fc view,
            RenderVisualSettings visualSettings) {
        this(
                projection,
                view,
                visualSettings,
                triangles -> {},
                HIDDEN_SURFACE,
                InteractionFeedbackFrame.hidden(),
                UiFrame.empty());
    }

    public RenderContext(
            Matrix4fc projection,
            Matrix4fc view,
            RenderVisualSettings visualSettings,
            RenderMetricsRecorder metricsRecorder) {
        this(
                projection,
                view,
                visualSettings,
                metricsRecorder,
                HIDDEN_SURFACE,
                InteractionFeedbackFrame.hidden(),
                UiFrame.empty());
    }

    public RenderContext(
            Matrix4fc projection,
            Matrix4fc view,
            RenderVisualSettings visualSettings,
            RenderMetricsRecorder metricsRecorder,
            RenderSurfaceMetrics surfaceMetrics,
            InteractionFeedbackFrame feedback) {
        this(
                projection,
                view,
                visualSettings,
                metricsRecorder,
                surfaceMetrics,
                feedback,
                UiFrame.empty());
    }

    public RenderContext(
            Matrix4fc projection,
            Matrix4fc view,
            RenderVisualSettings visualSettings,
            RenderMetricsRecorder metricsRecorder,
            RenderSurfaceMetrics surfaceMetrics,
            InteractionFeedbackFrame feedback,
            UiFrame uiFrame) {
        this.projection = new Matrix4f(Objects.requireNonNull(projection, "projection"));
        this.view = new Matrix4f(Objects.requireNonNull(view, "view"));
        this.visualSettings =
                Objects.requireNonNull(visualSettings, "visualSettings");
        this.metricsRecorder = Objects.requireNonNull(metricsRecorder, "metricsRecorder");
        this.surfaceMetrics = Objects.requireNonNull(surfaceMetrics, "surfaceMetrics");
        this.feedback = Objects.requireNonNull(feedback, "feedback");
        this.uiFrame = Objects.requireNonNull(uiFrame, "uiFrame");
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

    public RenderSurfaceMetrics surfaceMetrics() {
        return surfaceMetrics;
    }

    public InteractionFeedbackFrame feedback() {
        return feedback;
    }

    public UiFrame uiFrame() {
        return uiFrame;
    }
}

package com.overlord.renderer.pass;

import com.overlord.renderer.visual.RenderVisualSettings;
import java.util.Objects;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

public final class RenderContext {
    private final Matrix4f projection;
    private final Matrix4f view;
    private final RenderVisualSettings visualSettings;

    public RenderContext(Matrix4fc projection, Matrix4fc view) {
        this(projection, view, RenderVisualSettings.milestoneOneDefaults());
    }

    public RenderContext(
            Matrix4fc projection,
            Matrix4fc view,
            RenderVisualSettings visualSettings) {
        this.projection = new Matrix4f(Objects.requireNonNull(projection, "projection"));
        this.view = new Matrix4f(Objects.requireNonNull(view, "view"));
        this.visualSettings =
                Objects.requireNonNull(visualSettings, "visualSettings");
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
}

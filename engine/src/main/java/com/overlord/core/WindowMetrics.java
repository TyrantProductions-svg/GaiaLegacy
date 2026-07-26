package com.overlord.core;

import java.util.Optional;
import com.overlord.renderer.RenderSurfaceMetrics;

public final class WindowMetrics {
    private RenderSurfaceMetrics current;
    private RenderSurfaceMetrics pending;

    public WindowMetrics(
            int logicalWidth, int logicalHeight, int framebufferWidth, int framebufferHeight) {
        this(new RenderSurfaceMetrics(logicalWidth, logicalHeight, framebufferWidth, framebufferHeight, 1.0f, 1.0f));
    }
    public WindowMetrics(RenderSurfaceMetrics initial) {
        current = java.util.Objects.requireNonNull(initial, "initial");
    }

    public void updateLogicalSize(int width, int height) {
        update(new RenderSurfaceMetrics(width, height, current.framebufferWidth(), current.framebufferHeight(), current.contentScaleX(), current.contentScaleY()));
    }

    public void updateFramebufferSize(int width, int height) {
        update(new RenderSurfaceMetrics(current.logicalWidth(), current.logicalHeight(), width, height, current.contentScaleX(), current.contentScaleY()));
    }

    public void updateContentScale(float x, float y) { update(new RenderSurfaceMetrics(current.logicalWidth(), current.logicalHeight(), current.framebufferWidth(), current.framebufferHeight(), x, y)); }
    public Optional<RenderSurfaceMetrics> consumeSurfaceUpdate() {
        RenderSurfaceMetrics update = pending; pending = null; return Optional.ofNullable(update);
    }
    public RenderSurfaceMetrics current() { return current; }
    private void update(RenderSurfaceMetrics update) { current = update; pending = update; }

    public int logicalWidth() {
        return current.logicalWidth();
    }

    public int logicalHeight() {
        return current.logicalHeight();
    }

    public int framebufferWidth() {
        return current.framebufferWidth();
    }

    public int framebufferHeight() {
        return current.framebufferHeight();
    }

    private static void validateDimensions(int width, int height) {
        if (width < 0 || height < 0) {
            throw new IllegalArgumentException("Window dimensions must not be negative");
        }
    }

}

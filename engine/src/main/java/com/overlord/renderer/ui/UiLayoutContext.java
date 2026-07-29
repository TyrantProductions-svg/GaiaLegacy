package com.overlord.renderer.ui;

import com.overlord.renderer.RenderSurfaceMetrics;
import java.util.Objects;

public final class UiLayoutContext {
    private final int logicalWindowWidth;
    private final int logicalWindowHeight;
    private final int framebufferWidth;
    private final int framebufferHeight;
    private final float contentScaleX;
    private final float contentScaleY;
    private final double logicalWidth;
    private final double logicalHeight;
    private final UiRect safeArea;

    public UiLayoutContext(RenderSurfaceMetrics surface) {
        Objects.requireNonNull(surface, "surface");
        logicalWindowWidth = surface.logicalWidth();
        logicalWindowHeight = surface.logicalHeight();
        framebufferWidth = surface.framebufferWidth();
        framebufferHeight = surface.framebufferHeight();
        contentScaleX = surface.contentScaleX();
        contentScaleY = surface.contentScaleY();
        logicalWidth = framebufferWidth / (double) contentScaleX;
        logicalHeight = framebufferHeight / (double) contentScaleY;
        safeArea = new UiRect(0.0d, 0.0d, logicalWidth, logicalHeight);
    }

    public int logicalWindowWidth() {
        return logicalWindowWidth;
    }

    public int logicalWindowHeight() {
        return logicalWindowHeight;
    }

    public int framebufferWidth() {
        return framebufferWidth;
    }

    public int framebufferHeight() {
        return framebufferHeight;
    }

    public float contentScaleX() {
        return contentScaleX;
    }

    public float contentScaleY() {
        return contentScaleY;
    }

    public double logicalWidth() {
        return logicalWidth;
    }

    public double logicalHeight() {
        return logicalHeight;
    }

    public UiRect safeArea() {
        return safeArea;
    }

    public int snapX(double logicalX) {
        return snap(logicalX, contentScaleX);
    }

    public int snapY(double logicalY) {
        return snap(logicalY, contentScaleY);
    }

    public UiRect toFramebuffer(UiRect logicalBounds) {
        Objects.requireNonNull(logicalBounds, "logicalBounds");
        return new UiRect(
                snapX(logicalBounds.left()),
                snapY(logicalBounds.top()),
                snapX(logicalBounds.right()),
                snapY(logicalBounds.bottom()));
    }

    private static int snap(double logicalCoordinate, float contentScale) {
        if (!Double.isFinite(logicalCoordinate)) {
            throw new IllegalArgumentException("logical UI coordinate must be finite");
        }
        long rounded = Math.round(logicalCoordinate * contentScale);
        if (rounded < Integer.MIN_VALUE || rounded > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("snapped UI coordinate exceeds integer range");
        }
        return (int) rounded;
    }
}

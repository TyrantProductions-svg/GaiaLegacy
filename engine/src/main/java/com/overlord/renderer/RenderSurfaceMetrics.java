package com.overlord.renderer;

public record RenderSurfaceMetrics(
        int logicalWidth, int logicalHeight,
        int framebufferWidth, int framebufferHeight,
        float contentScaleX, float contentScaleY) {
    public RenderSurfaceMetrics {
        if (logicalWidth < 0 || logicalHeight < 0 || framebufferWidth < 0 || framebufferHeight < 0) {
            throw new IllegalArgumentException("surface dimensions must be non-negative");
        }
        if (!Float.isFinite(contentScaleX) || !Float.isFinite(contentScaleY)
                || contentScaleX <= 0.0f || contentScaleY <= 0.0f) {
            throw new IllegalArgumentException("content scales must be finite and positive");
        }
    }
}

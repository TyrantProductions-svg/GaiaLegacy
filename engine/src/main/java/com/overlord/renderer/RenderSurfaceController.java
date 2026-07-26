package com.overlord.renderer;

final class RenderSurfaceController {
    private RenderSurfaceMetrics current;
    private RenderSurfaceMetrics lastPositive;

    RenderSurfaceController(RenderSurfaceMetrics initial) {
        update(initial);
    }

    boolean update(RenderSurfaceMetrics next) {
        boolean changed = lastPositive == null
                || lastPositive.framebufferWidth() != next.framebufferWidth()
                || lastPositive.framebufferHeight() != next.framebufferHeight();
        current = next;
        if (next.framebufferWidth() > 0 && next.framebufferHeight() > 0) {
            lastPositive = next;
            return changed;
        }
        return false;
    }

    boolean drawable() { return current.framebufferWidth() > 0 && current.framebufferHeight() > 0; }
    RenderSurfaceMetrics current() { return current; }
    RenderSurfaceMetrics lastPositive() { return lastPositive; }
}

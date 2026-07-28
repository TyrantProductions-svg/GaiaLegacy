package com.overlord.renderer.feedback;

import java.util.List;

public final class CrosshairGeometry {
    private CrosshairGeometry() {}

    public static List<ScreenQuad> quads(int framebufferWidth, int framebufferHeight) {
        if (framebufferWidth < 0 || framebufferHeight < 0) {
            throw new IllegalArgumentException("framebuffer dimensions must be non-negative");
        }
        float cx = framebufferWidth / 2.0f;
        float cy = framebufferHeight / 2.0f;
        return List.of(
                new ScreenQuad(cx - 8, cy - 1, cx - 2, cy + 1),
                new ScreenQuad(cx + 2, cy - 1, cx + 8, cy + 1),
                new ScreenQuad(cx - 1, cy - 8, cx + 1, cy - 2),
                new ScreenQuad(cx - 1, cy + 2, cx + 1, cy + 8));
    }
}

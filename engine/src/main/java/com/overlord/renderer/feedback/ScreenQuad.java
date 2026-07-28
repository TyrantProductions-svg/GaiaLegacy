package com.overlord.renderer.feedback;

public record ScreenQuad(float xMin, float yMin, float xMax, float yMax) {
    public ScreenQuad {
        if (!Float.isFinite(xMin)
                || !Float.isFinite(yMin)
                || !Float.isFinite(xMax)
                || !Float.isFinite(yMax)) {
            throw new IllegalArgumentException("quad coordinates must be finite");
        }
        if (xMax < xMin || yMax < yMin) {
            throw new IllegalArgumentException("quad maximums must not precede minimums");
        }
    }
}

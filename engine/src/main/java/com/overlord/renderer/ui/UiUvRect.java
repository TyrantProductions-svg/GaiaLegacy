package com.overlord.renderer.ui;

public record UiUvRect(float left, float top, float right, float bottom) {
    public UiUvRect {
        if (!Float.isFinite(left)
                || !Float.isFinite(top)
                || !Float.isFinite(right)
                || !Float.isFinite(bottom)) {
            throw new IllegalArgumentException("UI UV edges must be finite");
        }
        if (left < 0.0f || top < 0.0f || right > 1.0f || bottom > 1.0f) {
            throw new IllegalArgumentException("UI UV edges must be within the atlas");
        }
        if (right < left || bottom < top) {
            throw new IllegalArgumentException("UI UV edges must not be inverted");
        }
    }
}

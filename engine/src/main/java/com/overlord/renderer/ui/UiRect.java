package com.overlord.renderer.ui;

public record UiRect(double left, double top, double right, double bottom) {
    public UiRect {
        if (!Double.isFinite(left)
                || !Double.isFinite(top)
                || !Double.isFinite(right)
                || !Double.isFinite(bottom)) {
            throw new IllegalArgumentException("UI rectangle edges must be finite");
        }
        if (right < left || bottom < top) {
            throw new IllegalArgumentException("UI rectangle edges must not be inverted");
        }
    }
}

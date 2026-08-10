package com.gaia.shell.ui;

import com.overlord.renderer.ui.UiRect;
import java.util.Objects;

/** Immutable logical hit bounds and presentation metadata for one product action. */
public record UiHitRegion(
        UiActionId action,
        UiRect logicalBounds,
        UiRect logicalViewport,
        boolean enabled,
        float contentScaleX,
        float contentScaleY) {
    public UiHitRegion {
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(logicalBounds, "logicalBounds");
        Objects.requireNonNull(logicalViewport, "logicalViewport");
        if (!Float.isFinite(contentScaleX)
                || !Float.isFinite(contentScaleY)
                || contentScaleX <= 0.0f
                || contentScaleY <= 0.0f) {
            throw new IllegalArgumentException("content scales must be finite and positive");
        }
    }

    public boolean contains(double logicalX, double logicalY) {
        return Double.isFinite(logicalX)
                && Double.isFinite(logicalY)
                && logicalX >= logicalBounds.left()
                && logicalX <= logicalBounds.right()
                && logicalY >= logicalBounds.top()
                && logicalY <= logicalBounds.bottom();
    }

    public double centerX() {
        return logicalBounds.left() + (logicalBounds.right() - logicalBounds.left()) / 2.0d;
    }

    public double centerY() {
        return logicalBounds.top() + (logicalBounds.bottom() - logicalBounds.top()) / 2.0d;
    }

    public boolean withinViewport(double logicalX, double logicalY) {
        return Double.isFinite(logicalX)
                && Double.isFinite(logicalY)
                && logicalX >= logicalViewport.left()
                && logicalX < logicalViewport.right()
                && logicalY >= logicalViewport.top()
                && logicalY < logicalViewport.bottom();
    }
}

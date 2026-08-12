package com.gaia.shell.ui;

import com.overlord.renderer.ui.UiFrame;
import com.overlord.renderer.ui.UiLayoutContext;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;

/** Immutable product-screen frame paired with the hit regions that produced it. */
public record ProductUiLayout(
        UiFrame frame,
        List<UiHitRegion> hitRegions,
        UiLayoutContext layoutContext) {
    public ProductUiLayout {
        Objects.requireNonNull(frame, "frame");
        hitRegions = List.copyOf(Objects.requireNonNull(hitRegions, "hitRegions"));
        Objects.requireNonNull(layoutContext, "layoutContext");
        if (!hitRegions.isEmpty()) {
            float scaleX = hitRegions.get(0).contentScaleX();
            float scaleY = hitRegions.get(0).contentScaleY();
            var viewport = hitRegions.get(0).logicalViewport();
            if (hitRegions.stream().anyMatch(region ->
                    Float.compare(scaleX, region.contentScaleX()) != 0
                            || Float.compare(scaleY, region.contentScaleY()) != 0)) {
                throw new IllegalArgumentException("all hit regions must use one content scale");
            }
            if (hitRegions.stream().anyMatch(region -> !viewport.equals(region.logicalViewport()))) {
                throw new IllegalArgumentException("all hit regions must use one logical viewport");
            }
            if (Float.compare(scaleX, layoutContext.contentScaleX()) != 0
                    || Float.compare(scaleY, layoutContext.contentScaleY()) != 0
                    || !viewport.equals(layoutContext.safeArea())) {
                throw new IllegalArgumentException(
                        "hit regions must use the layout context scale and viewport");
            }
        }
    }

    public UiHitRegion region(UiActionId action) {
        return region((UiControlId) action);
    }

    public UiHitRegion region(UiControlId id) {
        Objects.requireNonNull(id, "id");
        return hitRegions.stream()
                .filter(region -> region.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new NoSuchElementException("No UI region for " + id));
    }

    public float contentScaleX() {
        return hitRegions.isEmpty() ? 1.0f : hitRegions.get(0).contentScaleX();
    }

    public float contentScaleY() {
        return hitRegions.isEmpty() ? 1.0f : hitRegions.get(0).contentScaleY();
    }

    public boolean withinViewport(double logicalX, double logicalY) {
        return !hitRegions.isEmpty()
                && hitRegions.get(0).withinViewport(logicalX, logicalY);
    }

    public boolean canMapWindowPointer() {
        return layoutContext.logicalWindowWidth() > 0
                && layoutContext.logicalWindowHeight() > 0;
    }

    public double windowToLogicalX(double windowX) {
        return layoutContext.windowToLogicalX(windowX);
    }

    public double windowToLogicalY(double windowY) {
        return layoutContext.windowToLogicalY(windowY);
    }
}

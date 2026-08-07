package com.gaia.worlditem;

import com.overlord.worlditem.api.WorldItemId;
import com.overlord.worlditem.api.WorldItemPhysicalSnapshot;
import java.util.Objects;

/**
 * Immutable presentation pair derived from one canonical physical snapshot.
 * It contains no mutable projection or alternate item representation.
 */
public record WorldItemPresentationSnapshot(
        WorldItemPhysicalSnapshot runtime,
        double previousX,
        double previousY,
        double previousZ,
        double currentX,
        double currentY,
        double currentZ) {
    public WorldItemPresentationSnapshot {
        runtime = Objects.requireNonNull(runtime, "runtime");
        requireFinite(previousX, "previousX");
        requireFinite(previousY, "previousY");
        requireFinite(previousZ, "previousZ");
        requireFinite(currentX, "currentX");
        requireFinite(currentY, "currentY");
        requireFinite(currentZ, "currentZ");
    }

    public WorldItemId id() {
        return runtime.id();
    }

    public long revision() {
        return runtime.runtime().item().revision();
    }

    public double positionX(float alpha) {
        return interpolate(previousX, currentX, alpha);
    }

    public double positionY(float alpha) {
        return interpolate(previousY, currentY, alpha);
    }

    public double positionZ(float alpha) {
        return interpolate(previousZ, currentZ, alpha);
    }

    private static double interpolate(double previous, double current, float alpha) {
        if (!Float.isFinite(alpha) || alpha < 0 || alpha > 1) {
            throw new IllegalArgumentException("alpha must be finite and in [0, 1]");
        }
        return previous + (current - previous) * alpha;
    }

    private static void requireFinite(double value, String name) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite");
        }
    }
}

package com.gaia.inventory;

/** Immutable spawn transform supplied by the player/interactions layer. */
public record InventoryDropLocation(
        double positionX,
        double positionY,
        double positionZ,
        double velocityX,
        double velocityY,
        double velocityZ) {
    public InventoryDropLocation {
        if (!Double.isFinite(positionX) || !Double.isFinite(positionY)
                || !Double.isFinite(positionZ) || !Double.isFinite(velocityX)
                || !Double.isFinite(velocityY) || !Double.isFinite(velocityZ)) {
            throw new IllegalArgumentException("drop transform values must be finite");
        }
    }
}

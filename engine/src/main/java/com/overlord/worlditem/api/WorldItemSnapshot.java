package com.overlord.worlditem.api;

import com.overlord.inventory.api.ItemStack;
import java.util.Objects;

public record WorldItemSnapshot(
        WorldItemId id,
        ItemStack stack,
        double positionX,
        double positionY,
        double positionZ,
        double velocityX,
        double velocityY,
        double velocityZ,
        long revision) {
    public WorldItemSnapshot {
        id = Objects.requireNonNull(id, "id");
        stack = Objects.requireNonNull(stack, "stack");
        requireFinite(positionX, "positionX");
        requireFinite(positionY, "positionY");
        requireFinite(positionZ, "positionZ");
        requireFinite(velocityX, "velocityX");
        requireFinite(velocityY, "velocityY");
        requireFinite(velocityZ, "velocityZ");
        if (revision < 0) {
            throw new IllegalArgumentException("revision must be non-negative");
        }
    }

    private static void requireFinite(double value, String name) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite");
        }
    }
}

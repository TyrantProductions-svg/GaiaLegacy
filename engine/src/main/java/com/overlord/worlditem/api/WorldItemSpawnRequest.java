package com.overlord.worlditem.api;

import com.overlord.interaction.api.EntityRef;
import com.overlord.inventory.api.ItemStack;
import java.util.Objects;
import java.util.Optional;

public record WorldItemSpawnRequest(
        ItemStack stack,
        double positionX,
        double positionY,
        double positionZ,
        double velocityX,
        double velocityY,
        double velocityZ,
        Optional<EntityRef> source,
        long tick) {
    public WorldItemSpawnRequest {
        stack = Objects.requireNonNull(stack, "stack");
        requireFinite(positionX, "positionX");
        requireFinite(positionY, "positionY");
        requireFinite(positionZ, "positionZ");
        requireFinite(velocityX, "velocityX");
        requireFinite(velocityY, "velocityY");
        requireFinite(velocityZ, "velocityZ");
        source = Objects.requireNonNull(source, "source");
        if (tick < 0) {
            throw new IllegalArgumentException("tick must be non-negative");
        }
    }

    private static void requireFinite(double value, String name) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite");
        }
    }
}

package com.overlord.worlditem.api;

import java.util.Objects;

/** Expected-revision canonical transform update from a physical projection. */
public record WorldItemMotionUpdate(
        WorldItemId itemId,
        long expectedRevision,
        double positionX,
        double positionY,
        double positionZ,
        double velocityX,
        double velocityY,
        double velocityZ,
        WorldItemPhysicalState state) {
    public WorldItemMotionUpdate {
        itemId = Objects.requireNonNull(itemId, "itemId");
        state = Objects.requireNonNull(state, "state");
        if (expectedRevision < 0) {
            throw new IllegalArgumentException(
                    "expectedRevision must be non-negative");
        }
    }
}

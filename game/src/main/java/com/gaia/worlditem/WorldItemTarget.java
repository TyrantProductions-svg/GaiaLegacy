package com.gaia.worlditem;

import com.overlord.worlditem.api.WorldItemId;
import com.overlord.worlditem.api.WorldItemPhysicalSnapshot;
import java.util.Objects;

/** Immutable result of one independent world-item eye ray. */
public record WorldItemTarget(
        WorldItemId itemId,
        WorldItemPhysicalSnapshot snapshot,
        float distance) {
    public WorldItemTarget {
        itemId = Objects.requireNonNull(itemId, "itemId");
        snapshot = Objects.requireNonNull(snapshot, "snapshot");
        if (!itemId.equals(snapshot.id())) {
            throw new IllegalArgumentException("target ID must match its physical snapshot");
        }
        if (!Float.isFinite(distance) || distance < 0.0f) {
            throw new IllegalArgumentException("distance must be finite and non-negative");
        }
    }
}

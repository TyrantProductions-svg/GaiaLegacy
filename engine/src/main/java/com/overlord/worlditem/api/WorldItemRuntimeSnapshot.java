package com.overlord.worlditem.api;

import com.overlord.interaction.api.EntityRef;
import java.util.Objects;
import java.util.Optional;

public record WorldItemRuntimeSnapshot(
        WorldItemSnapshot item,
        Optional<EntityRef> source,
        long spawnTick,
        long pickupAvailableTick) {
    public WorldItemRuntimeSnapshot {
        item = Objects.requireNonNull(item, "item");
        source = Objects.requireNonNull(source, "source");
        if (spawnTick < 0 || pickupAvailableTick < spawnTick) {
            throw new IllegalArgumentException(
                    "world item pickup timing must be ordered and non-negative");
        }
    }
}

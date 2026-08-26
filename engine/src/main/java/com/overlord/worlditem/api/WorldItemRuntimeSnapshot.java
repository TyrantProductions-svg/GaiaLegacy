package com.overlord.worlditem.api;

import com.overlord.interaction.api.EntityRef;
import java.util.Objects;
import java.util.Optional;

public record WorldItemRuntimeSnapshot(
        WorldItemSnapshot item,
        Optional<EntityRef> source,
        long spawnTick,
        long pickupAvailableTick,
        long expiresAtWorldTick) {
    public static final long WORLD_ITEM_TTL_TICKS = 18_000L;

    public WorldItemRuntimeSnapshot(
            WorldItemSnapshot item,
            Optional<EntityRef> source,
            long spawnTick,
            long pickupAvailableTick) {
        this(
                item,
                source,
                spawnTick,
                pickupAvailableTick,
                saturatingExpiry(spawnTick));
    }

    public WorldItemRuntimeSnapshot {
        item = Objects.requireNonNull(item, "item");
        source = Objects.requireNonNull(source, "source");
        if (spawnTick < 0
                || pickupAvailableTick < spawnTick
                || expiresAtWorldTick < pickupAvailableTick) {
            throw new IllegalArgumentException(
                    "world item timing must be ordered and non-negative");
        }
    }

    public static long saturatingExpiry(long spawnTick) {
        if (spawnTick < 0L) {
            throw new IllegalArgumentException("spawnTick must be non-negative");
        }
        return spawnTick > Long.MAX_VALUE - WORLD_ITEM_TTL_TICKS
                ? Long.MAX_VALUE
                : spawnTick + WORLD_ITEM_TTL_TICKS;
    }
}

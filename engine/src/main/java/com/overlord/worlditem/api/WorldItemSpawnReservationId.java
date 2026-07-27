package com.overlord.worlditem.api;

public record WorldItemSpawnReservationId(long value) {
    public WorldItemSpawnReservationId {
        if (value < 0) {
            throw new IllegalArgumentException("world item spawn reservation ID must be non-negative");
        }
    }
}

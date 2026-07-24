package com.overlord.worlditem.api;

public record WorldItemReservationId(long value) {
    public WorldItemReservationId {
        if (value < 0) {
            throw new IllegalArgumentException("value must be non-negative");
        }
    }
}

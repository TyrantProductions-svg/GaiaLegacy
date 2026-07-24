package com.overlord.worlditem.api;

public record WorldItemId(long value) {
    public WorldItemId {
        if (value < 0) {
            throw new IllegalArgumentException("value must be non-negative");
        }
    }
}

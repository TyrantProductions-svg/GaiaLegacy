package com.overlord.interaction.api;

public record EntityRef(int id) {
    public EntityRef {
        if (id < 0) {
            throw new IllegalArgumentException(
                    "entity id must be non-negative");
        }
    }
}

package com.overlord.worlditem.api;

import java.util.Objects;
import java.util.UUID;

/** Engine-neutral identity of one durable save world. */
public record SaveIdentity(UUID value) {
    public SaveIdentity {
        Objects.requireNonNull(value, "value");
    }
}

package com.overlord.worlditem.api;

import java.util.Objects;

/** Opaque service-issued capability for one persistence plan. */
public final class WorldItemPersistenceTicket {
    private final Object issuer;

    private WorldItemPersistenceTicket(Object issuer) {
        this.issuer = issuer;
    }

    public static WorldItemPersistenceTicket issuedBy(Object issuer) {
        return new WorldItemPersistenceTicket(Objects.requireNonNull(issuer, "issuer"));
    }

    public boolean belongsTo(Object issuer) {
        return this.issuer == issuer;
    }
}

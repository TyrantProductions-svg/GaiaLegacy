package com.overlord.worlditem.api;

import java.util.Objects;

/** Opaque capability for one prepared WorldItem activation. */
public final class WorldItemActivationTicket {
    private final Object authority;

    private WorldItemActivationTicket(Object authority) {
        this.authority = Objects.requireNonNull(authority, "authority");
    }

    /** Creates an opaque ticket bound to an issuer-private authority object. */
    public static WorldItemActivationTicket issuedBy(Object authority) {
        return new WorldItemActivationTicket(authority);
    }

    /** Returns whether this ticket was issued by the supplied opaque authority. */
    public boolean belongsTo(Object authority) {
        return this.authority == authority;
    }
}

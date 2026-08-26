package com.overlord.worlditem.api;

import java.util.Objects;

/** Opaque capability for one prepared WorldItem hibernation. */
public final class WorldItemHibernateTicket {
    private final Object authority;

    private WorldItemHibernateTicket(Object authority) {
        this.authority = Objects.requireNonNull(authority, "authority");
    }

    /** Creates an opaque ticket bound to an issuer-private authority object. */
    public static WorldItemHibernateTicket issuedBy(Object authority) {
        return new WorldItemHibernateTicket(authority);
    }

    /** Returns whether this ticket was issued by the supplied opaque authority. */
    public boolean belongsTo(Object authority) {
        return this.authority == authority;
    }
}

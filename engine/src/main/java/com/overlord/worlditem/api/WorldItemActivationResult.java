package com.overlord.worlditem.api;

import java.util.Objects;
import java.util.Optional;

/** Closed result for preparing, committing, canceling, or rolling back activation. */
public record WorldItemActivationResult(
        Status status,
        Optional<WorldItemActivationTicket> ticket) {
    public WorldItemActivationResult {
        status = Objects.requireNonNull(status, "status");
        ticket = Objects.requireNonNull(ticket, "ticket");
        if ((status == Status.PREPARED) != ticket.isPresent()) {
            throw new IllegalArgumentException(
                    "only a prepared activation carries a ticket");
        }
    }

    public enum Status {
        PREPARED,
        COMMITTED,
        CANCELED,
        ROLLED_BACK,
        CAPACITY_EXCEEDED,
        ALL_PINNED,
        DUPLICATE_ID,
        COLLISION,
        MISSING_METADATA,
        METADATA_MISMATCH,
        INVALID_VIEW,
        EXPIRED,
        WRONG_CHUNK,
        INVALID_ALLOCATOR,
        INVALID_PAYLOAD,
        RESERVED,
        STALE_REVISION,
        STALE_TICKET,
        FOREIGN_TICKET
    }
}

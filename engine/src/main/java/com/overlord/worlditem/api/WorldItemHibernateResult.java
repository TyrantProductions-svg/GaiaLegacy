package com.overlord.worlditem.api;

import java.util.Objects;
import java.util.Optional;

/** Closed result for preparing, committing, or canceling hibernation. */
public record WorldItemHibernateResult(
        Status status,
        Optional<WorldItemHibernateTicket> ticket,
        Optional<WorldItemHibernatePayload> payload,
        Optional<WorldItemPersistenceTicket> persistenceTicket,
        Optional<WorldItemPersistencePlan> persistencePlan) {
    public WorldItemHibernateResult {
        status = Objects.requireNonNull(status, "status");
        ticket = Objects.requireNonNull(ticket, "ticket");
        payload = Objects.requireNonNull(payload, "payload");
        persistenceTicket = Objects.requireNonNull(
                persistenceTicket, "persistenceTicket");
        persistencePlan = Objects.requireNonNull(persistencePlan, "persistencePlan");
        boolean hibernationPrepared = status == Status.PREPARED;
        boolean persistenceOnly = status == Status.PERSISTENCE_PREPARED;
        if (hibernationPrepared != (ticket.isPresent() && payload.isPresent())) {
            throw new IllegalArgumentException(
                    "only a prepared hibernation carries a ticket and payload");
        }
        if (persistenceTicket.isPresent() != persistencePlan.isPresent()) {
            throw new IllegalArgumentException(
                    "persistence ticket and plan must be carried together");
        }
        if (persistenceOnly && persistenceTicket.isEmpty()) {
            throw new IllegalArgumentException(
                    "persistence-only preparation requires its ticket and plan");
        }
        if (!(hibernationPrepared || persistenceOnly)
                && persistenceTicket.isPresent()) {
            throw new IllegalArgumentException(
                    "only a prepared hibernation may carry persistence work");
        }
    }

    public WorldItemHibernateResult(
            Status status,
            Optional<WorldItemHibernateTicket> ticket,
            Optional<WorldItemHibernatePayload> payload) {
        this(status, ticket, payload, Optional.empty(), Optional.empty());
    }

    public enum Status {
        PREPARED,
        PERSISTENCE_PREPARED,
        COMMITTED,
        CANCELED,
        RESERVED,
        WRONG_CHUNK,
        STALE_REVISION,
        STALE_TICKET,
        FOREIGN_TICKET,
        TICKET_LIMIT,
        ALL_PINNED,
        DIRTY_LIMIT,
        PAGE_NOT_RESIDENT,
        INVALID_REQUEST
    }
}

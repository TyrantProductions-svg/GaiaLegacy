package com.gaia.interaction;

import java.util.Objects;
import java.util.Optional;

public record BreakTrackerResult(
        Status status, Optional<BlockBreakSession> session) {
    public BreakTrackerResult {
        status = Objects.requireNonNull(status, "status");
        session = Objects.requireNonNull(session, "session");
        boolean requiresSession = switch (status) {
            case STARTED, ADVANCED, COMPLETED -> true;
            case IDLE, CANCELLED, UNBREAKABLE -> false;
        };
        if (requiresSession != session.isPresent()) {
            throw new IllegalArgumentException(
                    status + " has inconsistent session payload");
        }
    }

    public enum Status {
        IDLE,
        STARTED,
        ADVANCED,
        COMPLETED,
        CANCELLED,
        UNBREAKABLE
    }
}

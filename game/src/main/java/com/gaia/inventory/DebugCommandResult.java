package com.gaia.inventory;

import java.util.Objects;

/** Immutable console-command result. */
public record DebugCommandResult(Status status, String message) {
    public DebugCommandResult {
        status = Objects.requireNonNull(status, "status");
        message = Objects.requireNonNull(message, "message");
    }

    public enum Status {
        APPLIED,
        UNKNOWN_COMMAND
    }
}

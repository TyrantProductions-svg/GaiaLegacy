package com.overlord.worlditem.api;

import java.util.Objects;

/** Closed outcome of a canonical logical-world-item restore attempt. */
public record WorldItemRestoreResult(Status status, int restoredCount) {
    public WorldItemRestoreResult {
        status = Objects.requireNonNull(status, "status");
        if (restoredCount < 0) {
            throw new IllegalArgumentException("restoredCount must be non-negative");
        }
        if (status != Status.RESTORED && restoredCount != 0) {
            throw new IllegalArgumentException(
                    "a failed restore must have restoredCount zero");
        }
    }

    public enum Status {
        RESTORED,
        INVALID_SNAPSHOT,
        TARGET_NOT_FRESH,
        CAPACITY_EXCEEDED
    }
}

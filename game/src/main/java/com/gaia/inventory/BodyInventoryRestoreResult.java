package com.gaia.inventory;

import java.util.Objects;

/** Closed outcome of a canonical inventory restore attempt. */
public record BodyInventoryRestoreResult(Status status) {
    public BodyInventoryRestoreResult {
        status = Objects.requireNonNull(status, "status");
    }

    public enum Status {
        RESTORED,
        INVALID_SNAPSHOT,
        TARGET_NOT_FRESH
    }
}

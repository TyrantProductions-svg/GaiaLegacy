package com.overlord.worlditem.api;

import java.util.Objects;
import java.util.Optional;

/** Closed result for a revision-checked canonical motion update. */
public record WorldItemMotionUpdateResult(
        Status status,
        Optional<WorldItemPhysicalSnapshot> snapshot) {
    public WorldItemMotionUpdateResult {
        status = Objects.requireNonNull(status, "status");
        snapshot = Objects.requireNonNull(snapshot, "snapshot");
        if (status == Status.UNKNOWN_ITEM) {
            if (snapshot.isPresent()) {
                throw new IllegalArgumentException(
                        "UNKNOWN_ITEM must not include a snapshot");
            }
        } else if (snapshot.isEmpty()) {
            throw new IllegalArgumentException(
                    status + " requires the authoritative snapshot");
        }
    }

    public enum Status {
        APPLIED,
        STALE_REVISION,
        UNKNOWN_ITEM,
        INVALID_MOTION,
        REVISION_EXHAUSTED
    }
}

package com.gaia.interaction;

import com.overlord.interaction.api.BlockChangeResult;
import java.util.Objects;
import java.util.Optional;

public record BlockBreakResult(
        Status status,
        Optional<BlockChangeResult> mutation,
        int produced,
        int inventoryCommitted,
        int worldItemCommitted,
        Optional<Throwable> failure) {
    public BlockBreakResult {
        status = Objects.requireNonNull(status, "status");
        mutation = Objects.requireNonNull(mutation, "mutation");
        failure = Objects.requireNonNull(failure, "failure");
        if (produced < 0 || inventoryCommitted < 0 || worldItemCommitted < 0) {
            throw new IllegalArgumentException("item counts must be non-negative");
        }
        boolean applied = status == Status.APPLIED
                || status == Status.APPLIED_WITH_NOTIFICATION_FAILURE;
        if (applied && produced != inventoryCommitted + worldItemCommitted) {
            throw new IllegalArgumentException(
                    "applied break must conserve the complete produced count");
        }
        if (!applied && (inventoryCommitted != 0 || worldItemCommitted != 0)) {
            throw new IllegalArgumentException(
                    "rejected break cannot commit item transfers");
        }
        if (status == Status.APPLIED_WITH_NOTIFICATION_FAILURE && failure.isEmpty()) {
            throw new IllegalArgumentException(
                    "notification failure status requires a cause");
        }
    }

    public enum Status {
        APPLIED,
        APPLIED_WITH_NOTIFICATION_FAILURE,
        RESERVATION_REJECTED,
        MUTATION_REJECTED
    }
}

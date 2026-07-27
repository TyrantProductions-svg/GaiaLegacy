package com.gaia.interaction;

import com.overlord.interaction.api.BlockChangeResult;
import java.util.Objects;
import java.util.Optional;

public record BlockPlacementResult(
        Status status,
        Optional<BlockChangeResult> mutation,
        int inventoryCommitted,
        Optional<Throwable> failure) {
    public BlockPlacementResult {
        status = Objects.requireNonNull(status, "status");
        mutation = Objects.requireNonNull(mutation, "mutation");
        failure = Objects.requireNonNull(failure, "failure");
        if (inventoryCommitted < 0 || inventoryCommitted > 1) {
            throw new IllegalArgumentException(
                    "placement inventory commit count must be zero or one");
        }
        boolean applied = status == Status.APPLIED
                || status == Status.APPLIED_WITH_NOTIFICATION_FAILURE;
        if (!applied && inventoryCommitted != 0) {
            throw new IllegalArgumentException(
                    "rejected placement cannot consume inventory");
        }
        if (status == Status.APPLIED_WITH_NOTIFICATION_FAILURE && failure.isEmpty()) {
            throw new IllegalArgumentException(
                    "notification failure status requires a cause");
        }
    }

    public enum Status {
        APPLIED,
        APPLIED_WITH_NOTIFICATION_FAILURE,
        NO_ITEM,
        UNKNOWN_ITEM,
        CHUNK_NOT_LOADED,
        NOT_REPLACEABLE,
        PLAYER_INTERSECTION,
        INVENTORY_REJECTED,
        MUTATION_REJECTED
    }
}

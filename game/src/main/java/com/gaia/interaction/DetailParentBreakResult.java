package com.gaia.interaction;

import com.overlord.interaction.api.DetailMutationResult;
import java.util.Objects;
import java.util.Optional;

public record DetailParentBreakResult(
        Status status,
        Optional<DetailMutationResult> mutation,
        int producedItems,
        int worldItemCommitted,
        Optional<Throwable> notificationFailure) {
    public DetailParentBreakResult(
            Status status,
            Optional<DetailMutationResult> mutation,
            int producedItems) {
        this(status, mutation, producedItems, 0, Optional.empty());
    }

    public DetailParentBreakResult {
        status = Objects.requireNonNull(status, "status");
        mutation = Objects.requireNonNull(mutation, "mutation");
        if (producedItems < 0) {
            throw new IllegalArgumentException("producedItems must be nonnegative");
        }
        if (worldItemCommitted < 0 || worldItemCommitted > producedItems) {
            throw new IllegalArgumentException(
                    "worldItemCommitted must be within producedItems");
        }
        notificationFailure = Objects.requireNonNull(
                notificationFailure, "notificationFailure");
        if ((status == Status.APPLIED
                        || status == Status.APPLIED_WITH_NOTIFICATION_FAILURE)
                && (mutation.isEmpty()
                        || mutation.orElseThrow().status()
                                != DetailMutationResult.Status.APPLIED)) {
            throw new IllegalArgumentException(
                    "APPLIED requires an applied canonical mutation");
        }
    }

    public boolean feedbackEligible() {
        return status == Status.APPLIED
                || status == Status.APPLIED_WITH_NOTIFICATION_FAILURE;
    }

    public enum Status {
        APPLIED,
        APPLIED_WITH_NOTIFICATION_FAILURE,
        INVALID_TARGET,
        ACTION_REJECTED,
        RESERVATION_REJECTED,
        MUTATION_REJECTED
    }
}

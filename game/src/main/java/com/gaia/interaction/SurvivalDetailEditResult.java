package com.gaia.interaction;

import com.overlord.interaction.api.DetailMutationResult;
import java.util.Objects;
import java.util.Optional;

/** Bounded outcome and material-conservation audit for one Survival detail edit. */
public record SurvivalDetailEditResult(
        Status status,
        Optional<DetailMutationResult> mutation,
        int occupiedDelta,
        int inventoryDelta,
        Optional<Throwable> notificationFailure) {
    public SurvivalDetailEditResult {
        status = Objects.requireNonNull(status, "status");
        mutation = Objects.requireNonNull(mutation, "mutation");
        notificationFailure = Objects.requireNonNull(
                notificationFailure, "notificationFailure");
        if (status == Status.APPLIED || status == Status.APPLIED_WITH_NOTIFICATION_FAILURE) {
            if (mutation.isEmpty()
                    || mutation.orElseThrow().status() != DetailMutationResult.Status.APPLIED) {
                throw new IllegalArgumentException(
                        "applied detail edit requires an applied canonical mutation");
            }
            if (Math.abs(occupiedDelta) != 1 || inventoryDelta != -occupiedDelta) {
                throw new IllegalArgumentException(
                        "applied precision edit must conserve exactly one material unit");
            }
        } else if (occupiedDelta != 0 || inventoryDelta != 0) {
            throw new IllegalArgumentException("rejected detail edit must have zero deltas");
        }
        if ((status == Status.APPLIED_WITH_NOTIFICATION_FAILURE)
                != notificationFailure.isPresent()) {
            throw new IllegalArgumentException(
                    "notification failure must match applied-notification status");
        }
    }

    public boolean materialConserved() {
        return occupiedDelta + inventoryDelta == 0;
    }

    public boolean feedbackEligible() {
        return status == Status.APPLIED || status == Status.APPLIED_WITH_NOTIFICATION_FAILURE;
    }

    public enum Status {
        APPLIED,
        APPLIED_WITH_NOTIFICATION_FAILURE,
        ACTION_REJECTED,
        INVENTORY_FULL,
        INVENTORY_ITEM_UNAVAILABLE,
        INVALID_CANDIDATE,
        UNAVAILABLE,
        PLAYER_INTERSECTION,
        MUTATION_REJECTED
    }
}

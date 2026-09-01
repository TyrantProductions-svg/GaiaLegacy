package com.gaia.interaction;

import com.overlord.interaction.api.DetailMutationResult;
import java.util.Objects;
import java.util.Optional;

public record DetailEditResult(
        Status status,
        Optional<DetailMutationResult> mutation) {
    public DetailEditResult {
        status = Objects.requireNonNull(status, "status");
        mutation = Objects.requireNonNull(mutation, "mutation");
        if (status == Status.APPLIED
                && (mutation.isEmpty()
                        || mutation.orElseThrow().status()
                                != DetailMutationResult.Status.APPLIED)) {
            throw new IllegalArgumentException(
                    "APPLIED requires an applied canonical mutation");
        }
    }

    public boolean feedbackEligible() {
        return status == Status.APPLIED;
    }

    public enum Status {
        APPLIED,
        INVALID_CANDIDATE,
        UNAVAILABLE,
        PLAYER_INTERSECTION,
        MUTATION_REJECTED
    }
}

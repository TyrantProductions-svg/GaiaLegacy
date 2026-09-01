package com.overlord.interaction.api;

import java.util.Objects;

/** Reports a post-mutation observer failure while preserving the canonical outcome. */
public final class DetailMutationEventDispatchException extends RuntimeException {
    private final DetailMutationResult mutation;

    public DetailMutationEventDispatchException(
            String message,
            Throwable cause,
            DetailMutationResult mutation) {
        super(message, cause);
        this.mutation = Objects.requireNonNull(mutation, "mutation");
    }

    public DetailMutationResult mutation() {
        return mutation;
    }

    public boolean stateChangeApplied() {
        return mutation.status() == DetailMutationResult.Status.APPLIED;
    }
}

package com.overlord.interaction.api;

public final class BlockChangeDispatchException
        extends RuntimeException {
    private final boolean mutationApplied;

    public BlockChangeDispatchException(
            String message,
            Throwable cause,
            boolean mutationApplied) {
        super(message, cause);
        this.mutationApplied = mutationApplied;
    }

    public boolean mutationApplied() {
        return mutationApplied;
    }
}

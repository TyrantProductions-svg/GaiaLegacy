package com.overlord.interaction.api;

public final class BlockMutationReentrancyException
        extends IllegalStateException {
    public BlockMutationReentrancyException(String message) {
        super(message);
    }
}

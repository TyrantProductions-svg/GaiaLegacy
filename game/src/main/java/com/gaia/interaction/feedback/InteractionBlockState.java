package com.gaia.interaction.feedback;

/** Read-only Phase 10 extension point for UI states that block world interaction. */
@FunctionalInterface
public interface InteractionBlockState {
    boolean blocked();

    static InteractionBlockState unblocked() {
        return () -> false;
    }
}

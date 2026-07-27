package com.gaia.interaction;

import com.overlord.interaction.api.EntityRef;
import com.overlord.interaction.api.InteractionAction;
import com.overlord.interaction.api.InteractionContext;
import com.overlord.inventory.api.BodySlot;
import java.util.Objects;

public record GaiaInteractionContext(
        EntityRef actor,
        BodySlot activeBodySlot,
        InteractionAction action,
        long tick,
        long timestampNanos)
        implements InteractionContext {
    public GaiaInteractionContext {
        actor = Objects.requireNonNull(actor, "actor");
        activeBodySlot = Objects.requireNonNull(activeBodySlot, "activeBodySlot");
        action = Objects.requireNonNull(action, "action");
        if (tick < 0 || timestampNanos < 0) {
            throw new IllegalArgumentException(
                    "tick and timestampNanos must be non-negative");
        }
    }
}

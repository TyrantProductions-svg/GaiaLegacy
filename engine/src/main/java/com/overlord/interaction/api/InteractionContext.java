package com.overlord.interaction.api;

import com.overlord.inventory.api.BodySlot;

public interface InteractionContext {
    EntityRef actor();

    BodySlot activeBodySlot();

    InteractionAction action();

    long tick();

    long timestampNanos();
}

package com.overlord.inventory.api;

import com.overlord.event.Event;

/** A post-commit inventory notification that cannot veto committed history. */
public abstract class CommittedInventoryEvent extends Event {
    @Override
    public final boolean isCancelled() {
        return false;
    }

    @Override
    public final void cancel() {
        // Post-commit observations cannot cancel or hide an applied state change.
    }
}

package com.overlord.inventory.api;

import com.overlord.interaction.api.EntityRef;
import java.util.Objects;

/** Post-selection notification. Inventory contents and revisions are unchanged. */
public final class ActiveBodySlotChanged extends CommittedInventoryEvent {
    private final EntityRef owner;
    private final BodySlot previousSlot;
    private final BodySlot activeSlot;

    public ActiveBodySlotChanged(
            EntityRef owner, BodySlot previousSlot, BodySlot activeSlot) {
        this.owner = Objects.requireNonNull(owner, "owner");
        this.previousSlot = Objects.requireNonNull(previousSlot, "previousSlot");
        this.activeSlot = Objects.requireNonNull(activeSlot, "activeSlot");
    }

    public EntityRef owner() {
        return owner;
    }

    public BodySlot previousSlot() {
        return previousSlot;
    }

    public BodySlot activeSlot() {
        return activeSlot;
    }

    @Override
    public String getEventType() {
        return "active_body_slot_changed";
    }
}

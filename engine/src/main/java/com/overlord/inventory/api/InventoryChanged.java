package com.overlord.inventory.api;

import com.overlord.interaction.api.EntityRef;
import java.util.Objects;

/** Post-mutation notification carrying only immutable inventory identity data. */
public final class InventoryChanged extends CommittedInventoryEvent {
    private final EntityRef owner;
    private final long revision;

    public InventoryChanged(EntityRef owner, long revision) {
        this.owner = Objects.requireNonNull(owner, "owner");
        if (revision < 0) {
            throw new IllegalArgumentException("revision must be non-negative");
        }
        this.revision = revision;
    }

    public EntityRef owner() {
        return owner;
    }

    public long revision() {
        return revision;
    }

    @Override
    public String getEventType() {
        return "inventory_changed";
    }
}

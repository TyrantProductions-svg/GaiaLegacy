package com.overlord.inventory.api;

import com.overlord.interaction.api.EntityRef;
import java.util.Objects;
import java.util.Optional;

public record InventoryChangeRequest(
        EntityRef owner,
        BodySlot slot,
        long expectedRevision,
        Optional<ItemStackView> replacement) {
    public InventoryChangeRequest {
        owner = Objects.requireNonNull(owner, "owner");
        slot = Objects.requireNonNull(slot, "slot");
        replacement =
                Objects.requireNonNull(replacement, "replacement");
        if (expectedRevision < 0) {
            throw new IllegalArgumentException(
                    "expectedRevision must be non-negative");
        }
    }
}

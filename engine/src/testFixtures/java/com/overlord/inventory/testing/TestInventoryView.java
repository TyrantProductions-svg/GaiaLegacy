package com.overlord.inventory.testing;

import com.overlord.interaction.api.EntityRef;
import com.overlord.inventory.api.BodySlot;
import com.overlord.inventory.api.InventoryView;
import com.overlord.inventory.api.ItemStackView;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public record TestInventoryView(
        EntityRef owner,
        long revision,
        Map<BodySlot, ItemStackView> stacks)
        implements InventoryView {
    public TestInventoryView {
        owner = Objects.requireNonNull(owner, "owner");
        if (revision < 0) {
            throw new IllegalArgumentException(
                    "revision must be non-negative");
        }
        stacks = Map.copyOf(stacks);
    }

    @Override
    public Optional<ItemStackView> stack(BodySlot slot) {
        Objects.requireNonNull(slot, "slot");
        return Optional.ofNullable(stacks.get(slot));
    }
}

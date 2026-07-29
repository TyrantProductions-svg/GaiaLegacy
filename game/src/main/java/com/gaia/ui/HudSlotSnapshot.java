package com.gaia.ui;

import com.overlord.inventory.api.BodySlot;
import com.overlord.inventory.api.ItemStack;
import java.util.Objects;
import java.util.Optional;

/** One immutable physical body-slot presentation projection. */
public record HudSlotSnapshot(
        BodySlot slot,
        Optional<ItemStack> stack,
        boolean active,
        boolean lockedCompanion,
        Optional<BodySlot> sharedAnchor) {
    public HudSlotSnapshot {
        slot = Objects.requireNonNull(slot, "slot");
        stack = Objects.requireNonNull(stack, "stack");
        sharedAnchor = Objects.requireNonNull(sharedAnchor, "sharedAnchor");
        stack.ifPresent(value -> Objects.requireNonNull(value, "stack value"));
        if (lockedCompanion) {
            if (stack.isPresent()) {
                throw new IllegalArgumentException("a locked companion cannot duplicate the anchor stack");
            }
            BodySlot anchor = sharedAnchor.orElseThrow(() ->
                    new IllegalArgumentException("a locked companion requires its shared anchor"));
            if (!isHand(slot) || !isHand(anchor) || slot == anchor) {
                throw new IllegalArgumentException("a shared anchor must name the other hand");
            }
        } else if (sharedAnchor.isPresent()) {
            throw new IllegalArgumentException("only a locked companion may name a shared anchor");
        }
    }

    public static HudSlotSnapshot empty(BodySlot slot, boolean active) {
        return new HudSlotSnapshot(slot, Optional.empty(), active, false, Optional.empty());
    }

    private static boolean isHand(BodySlot slot) {
        return slot == BodySlot.LEFT_HAND || slot == BodySlot.RIGHT_HAND;
    }
}

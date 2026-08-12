package com.gaia.inventory;

import com.overlord.interaction.api.EntityRef;
import com.overlord.inventory.api.BodySlot;
import com.overlord.inventory.api.ItemStack;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/** Canonical direct-slot inventory state for persistence handoff. */
public record BodyInventoryCanonicalSnapshot(
        EntityRef owner,
        Map<BodySlot, ItemStack> stacks,
        BodySlot activeSlot,
        boolean twoHandedHandsOccupied,
        long revision) {
    public BodyInventoryCanonicalSnapshot {
        owner = Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(stacks, "stacks");
        activeSlot = Objects.requireNonNull(activeSlot, "activeSlot");
        EnumMap<BodySlot, ItemStack> directStacks = new EnumMap<>(BodySlot.class);
        for (Map.Entry<BodySlot, ItemStack> entry : stacks.entrySet()) {
            BodySlot slot = Objects.requireNonNull(
                    entry.getKey(), "stacks must not contain null keys");
            ItemStack stack = Objects.requireNonNull(
                    entry.getValue(), "stacks must not contain null values");
            directStacks.put(slot, stack);
        }
        stacks = Collections.unmodifiableMap(directStacks);
    }
}

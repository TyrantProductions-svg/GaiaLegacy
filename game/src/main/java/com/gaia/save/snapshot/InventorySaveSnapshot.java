package com.gaia.save.snapshot;

import com.gaia.inventory.BodyInventoryCanonicalSnapshot;
import com.overlord.interaction.api.EntityRef;
import com.overlord.inventory.api.BodySlot;
import com.overlord.inventory.api.ItemStack;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/** Immutable save-owned copy of one body's canonical direct-slot inventory. */
public record InventorySaveSnapshot(
        EntityRef owner,
        Map<BodySlot, ItemStack> stacks,
        BodySlot activeSlot,
        boolean twoHandedHandsOccupied,
        long revision) {
    public InventorySaveSnapshot {
        owner = Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(stacks, "stacks");
        activeSlot = Objects.requireNonNull(activeSlot, "activeSlot");
        if (revision < 0) {
            throw new IllegalArgumentException("revision must be nonnegative");
        }

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

    public InventorySaveSnapshot(BodyInventoryCanonicalSnapshot snapshot) {
        this(
                requireSnapshot(snapshot).owner(),
                snapshot.stacks(),
                snapshot.activeSlot(),
                snapshot.twoHandedHandsOccupied(),
                snapshot.revision());
    }

    public BodyInventoryCanonicalSnapshot canonicalSnapshot() {
        return new BodyInventoryCanonicalSnapshot(
                owner, stacks, activeSlot, twoHandedHandsOccupied, revision);
    }

    private static BodyInventoryCanonicalSnapshot requireSnapshot(
            BodyInventoryCanonicalSnapshot snapshot) {
        return Objects.requireNonNull(snapshot, "snapshot");
    }
}

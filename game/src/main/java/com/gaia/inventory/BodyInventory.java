package com.gaia.inventory;

import com.overlord.interaction.api.EntityRef;
import com.overlord.inventory.api.BodyInventoryViewModel;
import com.overlord.inventory.api.BodySlot;
import com.overlord.inventory.api.InventoryView;
import com.overlord.inventory.api.ItemStack;
import com.overlord.inventory.api.ItemStackView;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Mutable physical three-slot inventory state. Only package-private methods
 * mutate it; callers must use {@link BodyInventoryService}.
 */
public final class BodyInventory {
    private final EntityRef owner;
    private final EnumMap<BodySlot, ItemStack> stacks =
            new EnumMap<>(BodySlot.class);
    private BodySlot activeSlot = BodySlot.LEFT_HAND;
    private boolean twoHandedHandsOccupied;
    private boolean restoreEligible = true;
    private long revision;

    BodyInventory(EntityRef owner) {
        this.owner = Objects.requireNonNull(owner, "owner");
    }

    static BodyInventory restored(
            EntityRef owner,
            Map<BodySlot, ItemStack> directStacks,
            BodySlot activeSlot,
            boolean twoHandedHandsOccupied,
            long revision) {
        BodyInventory restored = new BodyInventory(owner);
        restored.stacks.putAll(directStacks);
        restored.activeSlot = Objects.requireNonNull(activeSlot, "activeSlot");
        restored.twoHandedHandsOccupied = twoHandedHandsOccupied;
        restored.restoreEligible = false;
        restored.revision = revision;
        return restored;
    }

    EntityRef owner() {
        return owner;
    }

    long revision() {
        return revision;
    }

    BodySlot activeSlot() {
        return activeSlot;
    }

    void setActiveSlot(BodySlot activeSlot) {
        BodySlot selected = Objects.requireNonNull(activeSlot, "activeSlot");
        if (this.activeSlot != selected) {
            restoreEligible = false;
            this.activeSlot = selected;
        }
    }

    boolean restoreEligible() {
        return restoreEligible;
    }

    boolean hasTwoHandedHandsOccupied() {
        return twoHandedHandsOccupied;
    }

    BodySlot anchor(BodySlot slot) {
        Objects.requireNonNull(slot, "slot");
        if (twoHandedHandsOccupied && isHand(slot)) {
            return BodySlot.LEFT_HAND;
        }
        return slot;
    }

    ItemStack stack(BodySlot slot) {
        return stacks.get(anchor(slot));
    }

    ItemStack directStack(BodySlot slot) {
        return stacks.get(Objects.requireNonNull(slot, "slot"));
    }

    void setSingle(BodySlot slot, ItemStack stack) {
        if (twoHandedHandsOccupied && isHand(slot)) {
            throw new IllegalStateException("cannot write one hand of a two-handed stack");
        }
        if (stack == null) {
            stacks.remove(slot);
        } else {
            stacks.put(slot, stack);
        }
    }

    void setTwoHanded(ItemStack stack) {
        Objects.requireNonNull(stack, "stack");
        stacks.remove(BodySlot.RIGHT_HAND);
        stacks.put(BodySlot.LEFT_HAND, stack);
        twoHandedHandsOccupied = true;
    }

    void clearHands() {
        stacks.remove(BodySlot.LEFT_HAND);
        stacks.remove(BodySlot.RIGHT_HAND);
        twoHandedHandsOccupied = false;
    }

    boolean clearAt(BodySlot slot) {
        if (twoHandedHandsOccupied && isHand(slot)) {
            boolean changed = stacks.get(BodySlot.LEFT_HAND) != null;
            clearHands();
            return changed;
        }
        return stacks.remove(slot) != null;
    }

    void incrementRevision() {
        if (revision == Long.MAX_VALUE) {
            throw new IllegalStateException("inventory revision sequence exhausted");
        }
        restoreEligible = false;
        revision++;
    }

    InventoryView snapshot() {
        EnumMap<BodySlot, ItemStack> copy = new EnumMap<>(BodySlot.class);
        for (BodySlot slot : BodySlot.values()) {
            ItemStack stack = stack(slot);
            if (stack != null) {
                copy.put(slot, stack);
            }
        }
        return new Snapshot(owner, revision, copy);
    }

    BodyInventoryViewModel viewModel() {
        return new ViewSnapshot(owner, activeSlot, snapshot());
    }

    static boolean isHand(BodySlot slot) {
        return slot == BodySlot.LEFT_HAND || slot == BodySlot.RIGHT_HAND;
    }

    private record Snapshot(
            EntityRef owner,
            long revision,
            Map<BodySlot, ItemStack> stacks) implements InventoryView {
        private Snapshot {
            owner = Objects.requireNonNull(owner, "owner");
            if (revision < 0) {
                throw new IllegalArgumentException("revision must be non-negative");
            }
            stacks = Map.copyOf(stacks);
        }

        @Override
        public Optional<ItemStackView> stack(BodySlot slot) {
            return Optional.ofNullable(
                    stacks.get(Objects.requireNonNull(slot, "slot")));
        }
    }

    private record ViewSnapshot(
            EntityRef owner,
            BodySlot activeSlot,
            InventoryView inventory) implements BodyInventoryViewModel {
        private ViewSnapshot {
            owner = Objects.requireNonNull(owner, "owner");
            activeSlot = Objects.requireNonNull(activeSlot, "activeSlot");
            inventory = Objects.requireNonNull(inventory, "inventory");
        }
    }
}

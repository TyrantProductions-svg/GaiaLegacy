package com.gaia.inventory;

import com.overlord.inventory.api.ItemStack;
import java.util.Objects;

/** Data used only by explicit development inventory commands. */
public record DebugInventoryProfile(
        ItemStack leftPartial,
        ItemStack leftFull,
        ItemStack rightFull,
        ItemStack mouthStack) {
    public DebugInventoryProfile {
        leftPartial = Objects.requireNonNull(leftPartial, "leftPartial");
        leftFull = Objects.requireNonNull(leftFull, "leftFull");
        rightFull = Objects.requireNonNull(rightFull, "rightFull");
        mouthStack = Objects.requireNonNull(mouthStack, "mouthStack");
    }
}

package com.overlord.inventory.api;

import com.overlord.assets.ResourceLocation;

/** A read-only item stack snapshot or projection. */
public interface ItemStackView {
    ResourceLocation itemId();

    /** Returns a positive count for a valid stack. */
    int count();
}

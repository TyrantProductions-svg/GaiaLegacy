package com.overlord.inventory.api;

import com.overlord.assets.ResourceLocation;

public interface ItemStackView {
    ResourceLocation itemId();

    /** Returns a positive count for a valid stack. */
    int count();
}

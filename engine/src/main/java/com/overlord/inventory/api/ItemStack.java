package com.overlord.inventory.api;

import com.overlord.assets.ResourceLocation;
import java.util.Objects;

public record ItemStack(ResourceLocation itemId, int count) implements ItemStackView {
    public ItemStack {
        itemId = Objects.requireNonNull(itemId, "itemId");
        if (count <= 0) {
            throw new IllegalArgumentException("count must be positive");
        }
    }
}

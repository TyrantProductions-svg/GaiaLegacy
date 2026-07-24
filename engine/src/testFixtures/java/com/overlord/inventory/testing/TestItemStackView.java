package com.overlord.inventory.testing;

import com.overlord.assets.ResourceLocation;
import com.overlord.inventory.api.ItemStackView;
import java.util.Objects;

public record TestItemStackView(
        ResourceLocation itemId, int count)
        implements ItemStackView {
    public TestItemStackView {
        itemId = Objects.requireNonNull(itemId, "itemId");
        if (count <= 0) {
            throw new IllegalArgumentException(
                    "count must be positive");
        }
    }
}

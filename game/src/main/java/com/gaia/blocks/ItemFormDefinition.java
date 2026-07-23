package com.gaia.blocks;

import com.overlord.assets.ResourceLocation;
import java.util.Objects;

public record ItemFormDefinition(
        ResourceLocation id,
        int maxStackSize,
        boolean mouthHoldable,
        boolean twoHanded) {
    public ItemFormDefinition {
        Objects.requireNonNull(id, "id");
        if (maxStackSize < 1 || maxStackSize > 64) {
            throw new IllegalArgumentException(
                    "maxStackSize must be within 1..64");
        }
    }
}

package com.gaia.blocks;

import com.overlord.assets.ResourceLocation;
import java.util.Objects;

public record ItemVisualReference(
        ItemVisualType type,
        ResourceLocation atlas,
        ResourceLocation region) {
    public ItemVisualReference {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(atlas, "atlas");
        Objects.requireNonNull(region, "region");
    }
}

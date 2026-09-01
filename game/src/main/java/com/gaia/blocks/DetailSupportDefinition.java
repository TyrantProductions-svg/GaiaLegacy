package com.gaia.blocks;

import com.overlord.assets.ResourceLocation;
import java.util.Objects;

/** Canonical block-to-detail-unit mapping owned by the block definition. */
public record DetailSupportDefinition(ResourceLocation unitItem) {
    public DetailSupportDefinition {
        Objects.requireNonNull(unitItem, "unitItem");
    }
}

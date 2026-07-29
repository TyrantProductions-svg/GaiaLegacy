package com.gaia.ui;

import com.overlord.assets.ResourceLocation;
import com.overlord.renderer.ui.UiUvRect;
import java.util.Objects;

public record UiIconDefinition(
        ResourceLocation itemId,
        String displayName,
        UiUvRect region) {
    public UiIconDefinition {
        Objects.requireNonNull(itemId, "itemId");
        displayName = Objects.requireNonNull(displayName, "displayName").trim();
        if (displayName.isEmpty()) {
            throw new IllegalArgumentException("displayName must not be blank");
        }
        Objects.requireNonNull(region, "region");
    }
}

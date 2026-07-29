package com.gaia.ui;

import com.overlord.assets.ResourceLocation;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class UiIconAtlas {
    private final ResourceLocation texture;
    private final int atlasWidth;
    private final int atlasHeight;
    private final Map<ResourceLocation, UiIconDefinition> icons;
    private final UiIconDefinition fallback;
    private final List<Integer> unassignedCells;

    public UiIconAtlas(
            ResourceLocation texture,
            int atlasWidth,
            int atlasHeight,
            Map<ResourceLocation, UiIconDefinition> icons,
            UiIconDefinition fallback,
            List<Integer> unassignedCells) {
        this.texture = Objects.requireNonNull(texture, "texture");
        if (atlasWidth <= 0 || atlasHeight <= 0) {
            throw new IllegalArgumentException("icon atlas dimensions must be positive");
        }
        this.atlasWidth = atlasWidth;
        this.atlasHeight = atlasHeight;
        this.fallback = Objects.requireNonNull(fallback, "fallback");

        Objects.requireNonNull(icons, "icons");
        Map<ResourceLocation, UiIconDefinition> copied = new LinkedHashMap<>();
        icons.forEach((id, definition) -> {
            Objects.requireNonNull(id, "icon id");
            Objects.requireNonNull(definition, "icon definition");
            if (!id.equals(definition.itemId())) {
                throw new IllegalArgumentException(
                        "icon map key must match the definition item id");
            }
            copied.put(id, definition);
        });
        if (copied.get(fallback.itemId()) != fallback) {
            throw new IllegalArgumentException("fallback must be an icon atlas definition");
        }
        this.icons = Collections.unmodifiableMap(copied);
        this.unassignedCells = List.copyOf(
                Objects.requireNonNull(unassignedCells, "unassignedCells"));
    }

    public ResourceLocation texture() {
        return texture;
    }

    public int atlasWidth() {
        return atlasWidth;
    }

    public int atlasHeight() {
        return atlasHeight;
    }

    public Map<ResourceLocation, UiIconDefinition> icons() {
        return icons;
    }

    public UiIconDefinition fallback() {
        return fallback;
    }

    public List<Integer> unassignedCells() {
        return unassignedCells;
    }
}

package com.overlord.renderer.texture;

import com.overlord.assets.ResourceLocation;
import java.util.Map;
import java.util.Objects;

public record TextureAtlasMetadata(
        ResourceLocation id,
        ResourceLocation texture,
        int width,
        int height,
        Map<ResourceLocation, TextureRegion> regions) {
    public TextureAtlasMetadata {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(texture, "texture");
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException(
                    "Atlas dimensions must be positive");
        }
        regions = Map.copyOf(Objects.requireNonNull(regions, "regions"));
        for (TextureRegion region : regions.values()) {
            if (region.atlasWidth() != width
                    || region.atlasHeight() != height) {
                throw new IllegalArgumentException(
                        "Region dimensions do not match atlas " + id);
            }
        }
    }

    public TextureRegion requireRegion(ResourceLocation region) {
        TextureRegion value = regions.get(region);
        if (value == null) {
            throw new IllegalArgumentException(
                    "Unknown texture region: " + region);
        }
        return value;
    }
}

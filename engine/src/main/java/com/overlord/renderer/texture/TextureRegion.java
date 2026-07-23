package com.overlord.renderer.texture;

import com.overlord.assets.ResourceLocation;
import java.util.Objects;

public record TextureRegion(
        ResourceLocation id,
        int x,
        int y,
        int width,
        int height,
        int atlasWidth,
        int atlasHeight) {
    public TextureRegion {
        Objects.requireNonNull(id, "id");
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException(
                    "Region dimensions must be positive");
        }
        if (atlasWidth <= 0 || atlasHeight <= 0) {
            throw new IllegalArgumentException(
                    "Atlas dimensions must be positive");
        }
        if (x < 0 || y < 0) {
            throw new IllegalArgumentException(
                    "Region coordinates must be non-negative");
        }
        if ((long) x + width > atlasWidth
                || (long) y + height > atlasHeight) {
            throw new IllegalArgumentException(
                    "Region bounds must be within the atlas");
        }
    }

    public float uMin() {
        return (float) x / atlasWidth;
    }

    public float uMax() {
        return (float) (x + width) / atlasWidth;
    }

    public float vMin() {
        return (float) y / atlasHeight;
    }

    public float vMax() {
        return (float) (y + height) / atlasHeight;
    }
}

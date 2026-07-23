package com.overlord.renderer;

import com.overlord.renderer.texture.TextureImage;
import java.util.Objects;

public record RenderAssets(TextureImage blockAtlas) {
    public RenderAssets {
        Objects.requireNonNull(blockAtlas, "blockAtlas");
    }

    public static RenderAssets missing() {
        return new RenderAssets(TextureImage.missing());
    }
}

package com.overlord.renderer.shader;

import com.overlord.assets.AssetManager;
import com.overlord.assets.ResourceLocation;
import java.util.Objects;

public final class ShaderResourceLoader {
    private final AssetManager assets;

    public ShaderResourceLoader(AssetManager assets) {
        this.assets = Objects.requireNonNull(assets, "assets");
    }

    public ShaderSourceSet load(
            String label,
            ResourceLocation vertexResource,
            ResourceLocation fragmentResource) {
        label = Objects.requireNonNull(label, "label");
        vertexResource = Objects.requireNonNull(vertexResource, "vertexResource");
        fragmentResource = Objects.requireNonNull(fragmentResource, "fragmentResource");
        if (label.isBlank()) {
            throw new IllegalArgumentException("label must not be blank");
        }
        return new ShaderSourceSet(
                label,
                vertexResource,
                assets.readUtf8(vertexResource),
                fragmentResource,
                assets.readUtf8(fragmentResource));
    }
}

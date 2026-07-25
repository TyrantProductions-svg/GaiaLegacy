package com.overlord.renderer;

import com.overlord.assets.ResourceLocation;
import com.overlord.renderer.material.MaterialDefinition;
import com.overlord.renderer.material.RenderType;
import com.overlord.renderer.texture.TextureImage;
import java.util.Objects;

public record RenderAssets(
        TextureImage blockAtlas,
        MaterialDefinition worldMaterial,
        ResourceLocation worldVertexShader,
        ResourceLocation worldFragmentShader) {
    public static final ResourceLocation DEFAULT_WORLD_VERTEX_SHADER =
            ResourceLocation.parse("overlord:shaders/world.vert");
    public static final ResourceLocation DEFAULT_WORLD_FRAGMENT_SHADER =
            ResourceLocation.parse("overlord:shaders/world.frag");

    public RenderAssets {
        Objects.requireNonNull(blockAtlas, "blockAtlas");
        Objects.requireNonNull(worldMaterial, "worldMaterial");
        Objects.requireNonNull(worldVertexShader, "worldVertexShader");
        Objects.requireNonNull(worldFragmentShader, "worldFragmentShader");
    }

    public static RenderAssets missing() {
        ResourceLocation missing = ResourceLocation.parse("overlord:missing");
        return new RenderAssets(
                TextureImage.missing(),
                new MaterialDefinition(
                        missing,
                        missing,
                        RenderType.OPAQUE,
                        0.5f,
                        missing),
                DEFAULT_WORLD_VERTEX_SHADER,
                DEFAULT_WORLD_FRAGMENT_SHADER);
    }
}

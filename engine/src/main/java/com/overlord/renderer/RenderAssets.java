package com.overlord.renderer;

import com.overlord.assets.ResourceLocation;
import com.overlord.renderer.material.MaterialDefinition;
import com.overlord.renderer.material.RenderType;
import com.overlord.renderer.feedback.InteractionFeedbackAssets;
import com.overlord.renderer.texture.TextureImage;
import java.util.Objects;

public record RenderAssets(
        TextureImage blockAtlas,
        MaterialDefinition worldMaterial,
        ResourceLocation worldVertexShader,
        ResourceLocation worldFragmentShader,
        ResourceLocation skyVertexShader,
        ResourceLocation skyFragmentShader,
        InteractionFeedbackAssets feedback) {
    public static final ResourceLocation DEFAULT_WORLD_VERTEX_SHADER =
            ResourceLocation.parse("overlord:shaders/world.vert");
    public static final ResourceLocation DEFAULT_WORLD_FRAGMENT_SHADER =
            ResourceLocation.parse("overlord:shaders/world.frag");
    public static final ResourceLocation DEFAULT_SKY_VERTEX_SHADER =
            ResourceLocation.parse("overlord:shaders/sky.vert");
    public static final ResourceLocation DEFAULT_SKY_FRAGMENT_SHADER =
            ResourceLocation.parse("overlord:shaders/sky.frag");

    public RenderAssets {
        Objects.requireNonNull(blockAtlas, "blockAtlas");
        Objects.requireNonNull(worldMaterial, "worldMaterial");
        Objects.requireNonNull(worldVertexShader, "worldVertexShader");
        Objects.requireNonNull(worldFragmentShader, "worldFragmentShader");
        Objects.requireNonNull(skyVertexShader, "skyVertexShader");
        Objects.requireNonNull(skyFragmentShader, "skyFragmentShader");
        Objects.requireNonNull(feedback, "feedback");
    }

    public RenderAssets(
            TextureImage blockAtlas,
            MaterialDefinition worldMaterial,
            ResourceLocation worldVertexShader,
            ResourceLocation worldFragmentShader,
            ResourceLocation skyVertexShader,
            ResourceLocation skyFragmentShader) {
        this(
                blockAtlas,
                worldMaterial,
                worldVertexShader,
                worldFragmentShader,
                skyVertexShader,
                skyFragmentShader,
                InteractionFeedbackAssets.fallback());
    }

    public RenderAssets(
            TextureImage blockAtlas,
            MaterialDefinition worldMaterial,
            ResourceLocation worldVertexShader,
            ResourceLocation worldFragmentShader) {
        this(
                blockAtlas,
                worldMaterial,
                worldVertexShader,
                worldFragmentShader,
                DEFAULT_SKY_VERTEX_SHADER,
                DEFAULT_SKY_FRAGMENT_SHADER,
                InteractionFeedbackAssets.fallback());
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
                DEFAULT_WORLD_FRAGMENT_SHADER,
                DEFAULT_SKY_VERTEX_SHADER,
                DEFAULT_SKY_FRAGMENT_SHADER,
                InteractionFeedbackAssets.fallback());
    }
}

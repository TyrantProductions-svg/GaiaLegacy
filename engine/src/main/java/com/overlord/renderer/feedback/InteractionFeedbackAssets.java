package com.overlord.renderer.feedback;

import com.overlord.assets.ResourceLocation;
import java.util.Objects;

/** Immutable resource locations and CPU damage-atlas data used by feedback passes. */
public record InteractionFeedbackAssets(
        DamageAtlasLayout damageAtlas,
        ResourceLocation damageVertexShader,
        ResourceLocation damageFragmentShader,
        ResourceLocation worldItemVertexShader,
        ResourceLocation worldItemFragmentShader,
        ResourceLocation particleVertexShader,
        ResourceLocation particleFragmentShader,
        ResourceLocation crosshairVertexShader,
        ResourceLocation crosshairFragmentShader) {
    public static final ResourceLocation DEFAULT_DAMAGE_VERTEX_SHADER =
            ResourceLocation.parse("overlord:shaders/feedback/block_damage.vert");
    public static final ResourceLocation DEFAULT_DAMAGE_FRAGMENT_SHADER =
            ResourceLocation.parse("overlord:shaders/feedback/block_damage.frag");
    public static final ResourceLocation DEFAULT_WORLD_ITEM_VERTEX_SHADER =
            ResourceLocation.parse("overlord:shaders/feedback/world_item.vert");
    public static final ResourceLocation DEFAULT_WORLD_ITEM_FRAGMENT_SHADER =
            ResourceLocation.parse("overlord:shaders/feedback/world_item.frag");
    public static final ResourceLocation DEFAULT_PARTICLE_VERTEX_SHADER =
            ResourceLocation.parse("overlord:shaders/feedback/particle.vert");
    public static final ResourceLocation DEFAULT_PARTICLE_FRAGMENT_SHADER =
            ResourceLocation.parse("overlord:shaders/feedback/particle.frag");
    public static final ResourceLocation DEFAULT_CROSSHAIR_VERTEX_SHADER =
            ResourceLocation.parse("overlord:shaders/feedback/crosshair.vert");
    public static final ResourceLocation DEFAULT_CROSSHAIR_FRAGMENT_SHADER =
            ResourceLocation.parse("overlord:shaders/feedback/crosshair.frag");

    public InteractionFeedbackAssets {
        Objects.requireNonNull(damageAtlas, "damageAtlas");
        Objects.requireNonNull(damageVertexShader, "damageVertexShader");
        Objects.requireNonNull(damageFragmentShader, "damageFragmentShader");
        Objects.requireNonNull(worldItemVertexShader, "worldItemVertexShader");
        Objects.requireNonNull(worldItemFragmentShader, "worldItemFragmentShader");
        Objects.requireNonNull(particleVertexShader, "particleVertexShader");
        Objects.requireNonNull(particleFragmentShader, "particleFragmentShader");
        Objects.requireNonNull(crosshairVertexShader, "crosshairVertexShader");
        Objects.requireNonNull(crosshairFragmentShader, "crosshairFragmentShader");
    }

    public static InteractionFeedbackAssets withDamageAtlas(DamageAtlasLayout damageAtlas) {
        return new InteractionFeedbackAssets(
                damageAtlas,
                DEFAULT_DAMAGE_VERTEX_SHADER,
                DEFAULT_DAMAGE_FRAGMENT_SHADER,
                DEFAULT_WORLD_ITEM_VERTEX_SHADER,
                DEFAULT_WORLD_ITEM_FRAGMENT_SHADER,
                DEFAULT_PARTICLE_VERTEX_SHADER,
                DEFAULT_PARTICLE_FRAGMENT_SHADER,
                DEFAULT_CROSSHAIR_VERTEX_SHADER,
                DEFAULT_CROSSHAIR_FRAGMENT_SHADER);
    }

    public static InteractionFeedbackAssets fallback() {
        return withDamageAtlas(new DamageAtlasLayout(
                DamageAtlasResourceLoader.fallbackImage(),
                DamageAtlasResourceLoader.STAGE_COUNT));
    }
}

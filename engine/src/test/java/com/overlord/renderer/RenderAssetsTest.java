package com.overlord.renderer;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.overlord.assets.AssetManager;
import com.overlord.assets.ResourceLocation;
import com.overlord.core.Engine;
import com.overlord.core.thread.MainThreadGuard;
import com.overlord.renderer.material.RenderType;
import com.overlord.renderer.feedback.DamageAtlasResourceLoader;
import com.overlord.renderer.feedback.InteractionFeedbackAssets;
import com.overlord.renderer.texture.TextureImage;
import com.overlord.renderer.visual.RenderVisualSettings;
import org.junit.jupiter.api.Test;

class RenderAssetsTest {
    @Test
    void missingAssetsProvideExplicitWorldMaterialAndDefaultShaders() {
        RenderAssets assets = RenderAssets.missing();

        assertNotNull(assets.worldMaterial());
        assertEquals(
                ResourceLocation.parse("overlord:missing"),
                assets.worldMaterial().id());
        assertEquals(
                ResourceLocation.parse("overlord:missing"),
                assets.worldMaterial().atlas());
        assertEquals(RenderType.OPAQUE, assets.worldMaterial().renderType());
        assertEquals(0.5f, assets.worldMaterial().alphaCutoff());
        assertEquals(
                ResourceLocation.parse("overlord:missing"),
                assets.worldMaterial().missingRegion());
        assertEquals(
                RenderAssets.DEFAULT_WORLD_VERTEX_SHADER,
                assets.worldVertexShader());
        assertEquals(
                RenderAssets.DEFAULT_WORLD_FRAGMENT_SHADER,
                assets.worldFragmentShader());
        assertEquals(
                ResourceLocation.parse("overlord:shaders/sky.vert"),
                RenderAssets.DEFAULT_SKY_VERTEX_SHADER);
        assertEquals(
                ResourceLocation.parse("overlord:shaders/sky.frag"),
                RenderAssets.DEFAULT_SKY_FRAGMENT_SHADER);
        assertEquals(
                RenderAssets.DEFAULT_SKY_VERTEX_SHADER,
                assets.skyVertexShader());
        assertEquals(
                RenderAssets.DEFAULT_SKY_FRAGMENT_SHADER,
                assets.skyFragmentShader());
        assertNotNull(assets.feedback());
        assertEquals(
                DamageAtlasResourceLoader.WIDTH,
                assets.feedback().damageAtlas().image().width());
        assertEquals(
                InteractionFeedbackAssets.DEFAULT_CROSSHAIR_FRAGMENT_SHADER,
                assets.feedback().crosshairFragmentShader());
    }

    @Test
    void legacyConstructorsSupplyExplicitFallbackFeedbackAssets() {
        RenderAssets missing = RenderAssets.missing();
        RenderAssets legacySixArgument = new RenderAssets(
                missing.blockAtlas(),
                missing.worldMaterial(),
                missing.worldVertexShader(),
                missing.worldFragmentShader(),
                missing.skyVertexShader(),
                missing.skyFragmentShader());
        RenderAssets legacyFourArgument = new RenderAssets(
                missing.blockAtlas(),
                missing.worldMaterial(),
                missing.worldVertexShader(),
                missing.worldFragmentShader());

        assertEquals(InteractionFeedbackAssets.fallback(), legacySixArgument.feedback());
        assertEquals(InteractionFeedbackAssets.fallback(), legacyFourArgument.feedback());
    }

    @Test
    void textureAcceptsCpuImageInsteadOfClasspathResourcePath() {
        assertDoesNotThrow(
                () ->
                        Texture.class.getConstructor(
                                MainThreadGuard.class,
                                TextureImage.class));
        assertThrows(
                NoSuchMethodException.class,
                () ->
                        Texture.class.getConstructor(
                                MainThreadGuard.class,
                                String.class));
    }

    @Test
    void rendererAndEngineAcceptInjectedRenderAssets() {
        assertDoesNotThrow(
                () ->
                        Renderer.class.getConstructor(
                                MainThreadGuard.class,
                                RenderAssets.class));
        assertDoesNotThrow(
                () ->
                        Engine.class.getConstructor(
                                MainThreadGuard.class,
                                RenderAssets.class));
        assertDoesNotThrow(
                () ->
                        Renderer.class.getConstructor(
                                MainThreadGuard.class,
                                RenderAssets.class,
                                AssetManager.class));
        assertDoesNotThrow(
                () ->
                        Engine.class.getConstructor(
                                MainThreadGuard.class,
                                RenderAssets.class,
                                AssetManager.class));
        assertDoesNotThrow(
                () ->
                        Renderer.class.getConstructor(
                                MainThreadGuard.class,
                                RenderAssets.class,
                                AssetManager.class,
                                RenderVisualSettings.class));
        assertDoesNotThrow(
                () ->
                        Engine.class.getConstructor(
                                MainThreadGuard.class,
                                RenderAssets.class,
                                AssetManager.class,
                                RenderVisualSettings.class));
    }
}

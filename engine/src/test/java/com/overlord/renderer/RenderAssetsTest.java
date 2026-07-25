package com.overlord.renderer;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.overlord.core.Engine;
import com.overlord.core.thread.MainThreadGuard;
import com.overlord.assets.ResourceLocation;
import com.overlord.renderer.material.RenderType;
import com.overlord.renderer.texture.TextureImage;
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
    }
}

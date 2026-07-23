package com.overlord.renderer;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.overlord.core.Engine;
import com.overlord.core.thread.MainThreadGuard;
import com.overlord.renderer.texture.TextureImage;
import org.junit.jupiter.api.Test;

class RenderAssetsTest {
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

package com.overlord.renderer;

import com.overlord.core.thread.MainThreadGuard;
import com.overlord.renderer.texture.TextureBackend;
import com.overlord.renderer.texture.TextureImage;
import java.nio.ByteBuffer;
import java.util.Objects;

import static org.lwjgl.opengl.GL11C.GL_NEAREST;
import static org.lwjgl.opengl.GL11C.GL_TEXTURE_MAG_FILTER;
import static org.lwjgl.opengl.GL11C.GL_TEXTURE_MIN_FILTER;
import static org.lwjgl.opengl.GL11C.GL_TEXTURE_WRAP_S;
import static org.lwjgl.opengl.GL11C.GL_TEXTURE_WRAP_T;
import static org.lwjgl.opengl.GL12C.GL_CLAMP_TO_EDGE;
import static org.lwjgl.opengl.GL12C.GL_TEXTURE_BASE_LEVEL;
import static org.lwjgl.opengl.GL12C.GL_TEXTURE_MAX_LEVEL;

public class Texture implements TextureBinding {
    private final MainThreadGuard mainThreadGuard;
    private final TextureBackend backend;
    private int textureId;
    private int width;
    private int height;

    public Texture(
            MainThreadGuard mainThreadGuard,
            TextureImage image) {
        this(mainThreadGuard, image, TextureBackend.openGl());
    }

    public Texture(
            MainThreadGuard mainThreadGuard,
            TextureImage image,
            TextureBackend backend) {
        this.mainThreadGuard = Objects.requireNonNull(mainThreadGuard, "mainThreadGuard");
        this.backend = Objects.requireNonNull(backend, "backend");
        Objects.requireNonNull(image, "image");
        this.mainThreadGuard.assertMainThread("texture GPU upload");
        this.width = image.width();
        this.height = image.height();
        ByteBuffer pixels = image.rgbaPixels();

        textureId = backend.createTexture();
        try {
            backend.bindTexture2d(textureId);

            backend.setTextureParameter(GL_TEXTURE_MIN_FILTER, GL_NEAREST);
            backend.setTextureParameter(GL_TEXTURE_MAG_FILTER, GL_NEAREST);
            backend.setTextureParameter(GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
            backend.setTextureParameter(GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
            backend.setTextureParameter(GL_TEXTURE_BASE_LEVEL, 0);
            backend.setTextureParameter(GL_TEXTURE_MAX_LEVEL, 0);
            backend.uploadRgba8(width, height, pixels);
        } catch (RuntimeException | Error failure) {
            cleanupAfterFailedConstruction(failure);
            throw failure;
        }
    }

    public void bind() {
        mainThreadGuard.assertMainThread("texture bind");
        backend.bindTexture2d(textureId);
    }

    @Override
    public void bind(int textureUnit) {
        mainThreadGuard.assertMainThread("texture unit bind");
        backend.activateTextureUnit(textureUnit);
        backend.bindTexture2d(textureId);
    }

    public int getWidth() {
        return width;
    }
    
    public int getHeight() {
        return height;
    }

    public void cleanup() {
        mainThreadGuard.assertMainThread("texture cleanup");
        if (textureId != 0) {
            int textureToDelete = textureId;
            textureId = 0;
            backend.deleteTexture(textureToDelete);
        }
    }

    private void cleanupAfterFailedConstruction(Throwable failure) {
        if (textureId == 0) {
            return;
        }
        int textureToDelete = textureId;
        textureId = 0;
        try {
            backend.deleteTexture(textureToDelete);
        } catch (RuntimeException | Error cleanupFailure) {
            if (cleanupFailure != failure) {
                failure.addSuppressed(cleanupFailure);
            }
        }
    }
}

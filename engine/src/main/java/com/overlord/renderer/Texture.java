package com.overlord.renderer;

import com.overlord.core.thread.MainThreadGuard;
import com.overlord.renderer.texture.TextureImage;
import java.nio.ByteBuffer;
import java.util.Objects;

import static org.lwjgl.opengl.GL30C.*;

public class Texture {
    private final MainThreadGuard mainThreadGuard;
    private int textureId;
    private int width;
    private int height;

    public Texture(
            MainThreadGuard mainThreadGuard,
            TextureImage image) {
        this.mainThreadGuard = Objects.requireNonNull(mainThreadGuard, "mainThreadGuard");
        Objects.requireNonNull(image, "image");
        this.mainThreadGuard.assertMainThread("texture GPU upload");
        this.width = image.width();
        this.height = image.height();
        ByteBuffer pixels = image.rgbaPixels();

        textureId = glGenTextures();
        try {
            glBindTexture(GL_TEXTURE_2D, textureId);

            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);

            glTexImage2D(
                    GL_TEXTURE_2D,
                    0,
                    GL_RGBA,
                    width,
                    height,
                    0,
                    GL_RGBA,
                    GL_UNSIGNED_BYTE,
                    pixels);
            glGenerateMipmap(GL_TEXTURE_2D);
        } catch (RuntimeException | Error failure) {
            if (textureId != 0) {
                glDeleteTextures(textureId);
                textureId = 0;
            }
            throw failure;
        }
    }

    public void bind() {
        mainThreadGuard.assertMainThread("texture bind");
        glBindTexture(GL_TEXTURE_2D, textureId);
    }

    public void bind(int textureUnit) {
        mainThreadGuard.assertMainThread("texture unit bind");
        glActiveTexture(GL_TEXTURE0 + textureUnit);
        glBindTexture(GL_TEXTURE_2D, textureId);
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
            glDeleteTextures(textureId);
            textureId = 0;
        }
    }
}

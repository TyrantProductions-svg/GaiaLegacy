package com.overlord.renderer;

import com.overlord.core.thread.MainThreadGuard;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.Objects;
import org.lwjgl.BufferUtils;
import org.lwjgl.stb.STBImage;

import static org.lwjgl.opengl.GL30C.*;

public class Texture {
    private final MainThreadGuard mainThreadGuard;
    private int textureId;
    private int width;
    private int height;

    public Texture(MainThreadGuard mainThreadGuard, String resourcePath) {
        this.mainThreadGuard = Objects.requireNonNull(mainThreadGuard, "mainThreadGuard");
        this.mainThreadGuard.assertMainThread("texture GPU upload");
        ByteBuffer image = loadImageFromResource(resourcePath);
        if (image == null) {
            throw new RuntimeException("Failed to load texture: " + resourcePath);
        }

        try {
            textureId = glGenTextures();
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
                    image);
            glGenerateMipmap(GL_TEXTURE_2D);
        } catch (RuntimeException | Error failure) {
            if (textureId != 0) {
                glDeleteTextures(textureId);
                textureId = 0;
            }
            throw failure;
        } finally {
            STBImage.stbi_image_free(image);
        }
    }

    private ByteBuffer loadImageFromResource(String resourcePath) {
        InputStream inputStream = getClass().getClassLoader().getResourceAsStream(resourcePath);
        if (inputStream == null) {
            System.err.println("[Texture] Resource not found: " + resourcePath);
            return null;
        }
        
        try {
            byte[] bytes = inputStream.readAllBytes();
            inputStream.close();
            
            ByteBuffer byteBuffer = BufferUtils.createByteBuffer(bytes.length);
            byteBuffer.put(bytes);
            byteBuffer.flip();
            
            IntBuffer width = BufferUtils.createIntBuffer(1);
            IntBuffer height = BufferUtils.createIntBuffer(1);
            IntBuffer channels = BufferUtils.createIntBuffer(1);
            
            ByteBuffer image = STBImage.stbi_load_from_memory(byteBuffer, width, height, channels, 4);
            if (image == null) {
                System.err.println("[Texture] Failed to decode image: " + resourcePath);
                return null;
            }
            
            this.width = width.get(0);
            this.height = height.get(0);
            
            System.out.println("[Texture] Loaded: " + resourcePath + " (" + this.width + "x" + this.height + ")");
            
            return image;
        } catch (Exception e) {
            System.err.println("[Texture] Error loading resource: " + e.getMessage());
            return null;
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

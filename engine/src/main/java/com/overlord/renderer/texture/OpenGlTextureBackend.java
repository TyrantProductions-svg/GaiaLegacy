package com.overlord.renderer.texture;

import static org.lwjgl.opengl.GL11C.GL_RGBA;
import static org.lwjgl.opengl.GL11C.GL_RGBA8;
import static org.lwjgl.opengl.GL11C.GL_TEXTURE_2D;
import static org.lwjgl.opengl.GL11C.GL_UNSIGNED_BYTE;
import static org.lwjgl.opengl.GL11C.glBindTexture;
import static org.lwjgl.opengl.GL11C.glDeleteTextures;
import static org.lwjgl.opengl.GL11C.glGenTextures;
import static org.lwjgl.opengl.GL11C.glTexImage2D;
import static org.lwjgl.opengl.GL11C.glTexParameteri;
import static org.lwjgl.opengl.GL13C.GL_TEXTURE0;
import static org.lwjgl.opengl.GL13C.glActiveTexture;

import java.nio.ByteBuffer;

final class OpenGlTextureBackend implements TextureBackend {
    @Override
    public int createTexture() {
        return glGenTextures();
    }

    @Override
    public void activateTextureUnit(int textureUnit) {
        glActiveTexture(GL_TEXTURE0 + textureUnit);
    }

    @Override
    public void bindTexture2d(int textureId) {
        glBindTexture(GL_TEXTURE_2D, textureId);
    }

    @Override
    public void setTextureParameter(int parameterName, int value) {
        glTexParameteri(GL_TEXTURE_2D, parameterName, value);
    }

    @Override
    public void uploadRgba8(int width, int height, ByteBuffer pixels) {
        glTexImage2D(
                GL_TEXTURE_2D,
                0,
                GL_RGBA8,
                width,
                height,
                0,
                GL_RGBA,
                GL_UNSIGNED_BYTE,
                pixels);
    }

    @Override
    public void deleteTexture(int textureId) {
        glDeleteTextures(textureId);
    }
}

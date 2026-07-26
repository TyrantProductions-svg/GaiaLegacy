package com.overlord.renderer.texture;

import java.nio.ByteBuffer;

public interface TextureBackend {
    static TextureBackend openGl() {
        return new OpenGlTextureBackend();
    }

    int createTexture();

    void activateTextureUnit(int textureUnit);

    void bindTexture2d(int textureId);

    void setTextureParameter(int parameterName, int value);

    void uploadRgba8(int width, int height, ByteBuffer pixels);

    void deleteTexture(int textureId);
}

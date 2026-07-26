package com.overlord.renderer.texture;

public final class TextureBackends {
    private TextureBackends() {}

    public static TextureBackend openGl() {
        return new OpenGlTextureBackend();
    }
}

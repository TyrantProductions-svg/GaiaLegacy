package com.overlord.renderer.material;

import com.overlord.renderer.TextureBinding;
import com.overlord.renderer.shader.ShaderBinding;
import java.util.Objects;

public record Material(
        MaterialDefinition definition,
        ShaderBinding shader,
        TextureBinding texture) {
    public Material {
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(shader, "shader");
        Objects.requireNonNull(texture, "texture");
    }
}

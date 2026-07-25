package com.overlord.renderer.shader;

import com.overlord.assets.ResourceLocation;
import java.util.Objects;

public record ShaderSourceSet(
        String label,
        ResourceLocation vertexResource,
        String vertexSource,
        ResourceLocation fragmentResource,
        String fragmentSource) {
    public ShaderSourceSet {
        label = Objects.requireNonNull(label, "label");
        vertexResource = Objects.requireNonNull(vertexResource, "vertexResource");
        vertexSource = Objects.requireNonNull(vertexSource, "vertexSource");
        fragmentResource = Objects.requireNonNull(fragmentResource, "fragmentResource");
        fragmentSource = Objects.requireNonNull(fragmentSource, "fragmentSource");
        if (label.isBlank()) {
            throw new IllegalArgumentException("label must not be blank");
        }
    }
}

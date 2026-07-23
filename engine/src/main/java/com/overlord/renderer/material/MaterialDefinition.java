package com.overlord.renderer.material;

import com.overlord.assets.ResourceLocation;
import java.util.Objects;

public record MaterialDefinition(
        ResourceLocation id,
        ResourceLocation atlas,
        RenderType renderType,
        float alphaCutoff,
        ResourceLocation missingRegion) {
    public MaterialDefinition {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(atlas, "atlas");
        Objects.requireNonNull(renderType, "renderType");
        Objects.requireNonNull(missingRegion, "missingRegion");
        if (!Float.isFinite(alphaCutoff)
                || alphaCutoff < 0.0f
                || alphaCutoff > 1.0f) {
            throw new IllegalArgumentException(
                    "alphaCutoff must be finite and within 0..1");
        }
    }
}

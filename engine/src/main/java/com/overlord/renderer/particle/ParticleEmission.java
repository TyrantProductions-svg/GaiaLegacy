package com.overlord.renderer.particle;

import com.overlord.renderer.texture.TextureRegion;
import java.util.Objects;

public record ParticleEmission(
        ParticleCategory category,
        float x,
        float y,
        float z,
        TextureRegion region,
        int count,
        long deterministicSeed) {
    public ParticleEmission {
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(region, "region");
        if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z)) {
            throw new IllegalArgumentException("particle emission position must be finite");
        }
        if (count <= 0) {
            throw new IllegalArgumentException("particle emission count must be positive");
        }
    }
}

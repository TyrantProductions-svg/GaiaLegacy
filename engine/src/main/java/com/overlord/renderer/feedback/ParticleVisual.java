package com.overlord.renderer.feedback;

import com.overlord.renderer.particle.ParticleCategory;
import com.overlord.renderer.texture.TextureRegion;
import java.util.Objects;

public record ParticleVisual(
        float x,
        float y,
        float z,
        float size,
        TextureRegion region,
        ParticleCategory category,
        long spawnSequence) {
    public ParticleVisual {
        Objects.requireNonNull(region, "region");
        Objects.requireNonNull(category, "category");
    }
}

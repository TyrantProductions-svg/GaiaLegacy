package com.overlord.renderer.feedback;

import com.overlord.renderer.particle.ParticleCategory;
import com.overlord.renderer.particle.ParticlePriority;
import com.overlord.renderer.texture.TextureRegion;
import java.util.Objects;

/** Immutable particle presentation plus testable deterministic motion state. */
public record ParticleVisual(
        float x,
        float y,
        float z,
        float velocityX,
        float velocityY,
        float velocityZ,
        float age,
        float lifetime,
        float size,
        TextureRegion region,
        ParticleTint tint,
        ParticleCategory category,
        ParticlePriority priority,
        long spawnSequence) {
    public ParticleVisual {
        if (!Float.isFinite(x)
                || !Float.isFinite(y)
                || !Float.isFinite(z)
                || !Float.isFinite(velocityX)
                || !Float.isFinite(velocityY)
                || !Float.isFinite(velocityZ)
                || !Float.isFinite(age)
                || !Float.isFinite(lifetime)
                || !Float.isFinite(size)) {
            throw new IllegalArgumentException("particle visual values must be finite");
        }
        Objects.requireNonNull(region, "region");
        Objects.requireNonNull(tint, "tint");
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(priority, "priority");
    }

    public ParticleVisual(
            float x,
            float y,
            float z,
            float size,
            TextureRegion region,
            ParticleCategory category,
            ParticlePriority priority,
            long spawnSequence) {
        this(
                x, y, z, 0, 0, 0, 0, 1, size, region,
                ParticleTint.white(), category, priority, spawnSequence);
    }

    public ParticleVisual(
            float x,
            float y,
            float z,
            float size,
            TextureRegion region,
            ParticleCategory category,
            long spawnSequence) {
        this(
                x, y, z, size, region, category,
                category == ParticleCategory.BREAK_CONTINUOUS
                        ? ParticlePriority.LOW
                        : ParticlePriority.HIGH,
                spawnSequence);
    }
}

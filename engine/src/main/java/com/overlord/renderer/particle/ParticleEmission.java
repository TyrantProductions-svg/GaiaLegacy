package com.overlord.renderer.particle;

import com.overlord.renderer.feedback.WorldItemFaceRegions;
import com.overlord.renderer.feedback.ParticleTint;
import com.overlord.renderer.texture.TextureRegion;
import java.util.Objects;

/** Immutable bounded particle request with complete canonical face data. */
public record ParticleEmission(
        ParticleCategory category,
        ParticlePriority priority,
        float x,
        float y,
        float z,
        WorldItemFaceRegions faces,
        ParticleTint tint,
        float normalX,
        float normalY,
        float normalZ,
        int count,
        long deterministicSeed) {
    public ParticleEmission {
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(priority, "priority");
        Objects.requireNonNull(faces, "faces");
        Objects.requireNonNull(tint, "tint");
        if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z)) {
            throw new IllegalArgumentException("particle emission position must be finite");
        }
        if (!Float.isFinite(normalX)
                || !Float.isFinite(normalY)
                || !Float.isFinite(normalZ)) {
            throw new IllegalArgumentException("particle emission normal must be finite");
        }
        if (count <= 0 || count > ParticleSystem.MAX_PARTICLES_PER_REQUEST) {
            throw new IllegalArgumentException(
                    "particle emission count must be within 1.."
                            + ParticleSystem.MAX_PARTICLES_PER_REQUEST);
        }
    }

    public ParticleEmission(
            ParticleCategory category,
            ParticlePriority priority,
            float x,
            float y,
            float z,
            WorldItemFaceRegions faces,
            float normalX,
            float normalY,
            float normalZ,
            int count,
            long deterministicSeed) {
        this(
                category, priority, x, y, z, faces, ParticleTint.white(),
                normalX, normalY, normalZ, count, deterministicSeed);
    }

    public ParticleEmission(
            ParticleCategory category,
            ParticlePriority priority,
            float x,
            float y,
            float z,
            TextureRegion region,
            int count,
            long deterministicSeed) {
        this(
                category, priority, x, y, z,
                WorldItemFaceRegions.uniform(Objects.requireNonNull(region, "region")),
                ParticleTint.white(),
                0, 1, 0,
                count, deterministicSeed);
    }

    public ParticleEmission(
            ParticleCategory category,
            float x,
            float y,
            float z,
            TextureRegion region,
            int count,
            long deterministicSeed) {
        this(category, defaultPriority(category), x, y, z, region, count, deterministicSeed);
    }

    /** Compatibility representative region for single-face consumers. */
    public TextureRegion region() {
        return faces.region(com.overlord.voxel.BlockFace.UP);
    }

    private static ParticlePriority defaultPriority(ParticleCategory category) {
        Objects.requireNonNull(category, "category");
        return category == ParticleCategory.BREAK_CONTINUOUS
                ? ParticlePriority.LOW
                : ParticlePriority.HIGH;
    }
}

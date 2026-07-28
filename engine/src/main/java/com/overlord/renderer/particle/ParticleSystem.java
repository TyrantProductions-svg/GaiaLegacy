package com.overlord.renderer.particle;

import com.overlord.renderer.feedback.ParticleRenderBatch;
import com.overlord.renderer.feedback.ParticleVisual;
import com.overlord.renderer.texture.TextureRegion;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;

public final class ParticleSystem {
    public static final int MAX_PARTICLES = 512;
    public static final float FIXED_STEP_SECONDS = 1.0f / 60.0f;

    private static final long GOLDEN_GAMMA = 0x9E3779B97F4A7C15L;
    private static final long CATEGORY_SALT = 0xD1B54A32D192ED03L;

    private final Deque<ParticleState> particles = new ArrayDeque<>();
    private long nextSpawnSequence;

    public void emit(ParticleEmission emission) {
        Objects.requireNonNull(emission, "emission");
        for (int localIndex = 0; localIndex < emission.count(); localIndex++) {
            if (particles.size() == MAX_PARTICLES) {
                particles.removeFirst();
            }
            particles.addLast(createParticle(emission, localIndex, nextSpawnSequence++));
        }
    }

    public void fixedUpdate(float fixedDeltaSeconds) {
        if (!Float.isFinite(fixedDeltaSeconds)
                || Float.compare(fixedDeltaSeconds, FIXED_STEP_SECONDS) != 0) {
            throw new IllegalArgumentException("particle update must use the fixed 1/60 step");
        }
        int count = particles.size();
        for (int index = 0; index < count; index++) {
            ParticleState advanced = particles.removeFirst().advance();
            if (advanced.age() < advanced.lifetime()) {
                particles.addLast(advanced);
            }
        }
    }

    public ParticleRenderBatch snapshot() {
        List<ParticleVisual> visuals = new ArrayList<>(particles.size());
        for (ParticleState particle : particles) {
            visuals.add(particle.visual());
        }
        return new ParticleRenderBatch(visuals);
    }

    public void clear() {
        particles.clear();
    }

    private static ParticleState createParticle(
            ParticleEmission emission, int localIndex, long sequence) {
        long base =
                mix64(
                        emission.deterministicSeed()
                                ^ ((long) emission.category().ordinal() * CATEGORY_SALT)
                                ^ ((long) localIndex * GOLDEN_GAMMA));
        float px = emission.x() + signedUnit(mix64(base + 1L)) * 0.1f;
        float py = emission.y() + unit(mix64(base + 2L)) * 0.15f;
        float pz = emission.z() + signedUnit(mix64(base + 3L)) * 0.1f;
        float horizontalSpeed =
                emission.category() == ParticleCategory.BREAK_COMMITTED ? 1.2f : 0.3f;
        float verticalBase =
                emission.category() == ParticleCategory.BREAK_COMMITTED ? 0.5f : 0.25f;
        float verticalRange =
                emission.category() == ParticleCategory.BREAK_COMMITTED ? 1.0f : 0.35f;
        float vx = signedUnit(mix64(base + 4L)) * horizontalSpeed;
        float vy = verticalBase + unit(mix64(base + 5L)) * verticalRange;
        float vz = signedUnit(mix64(base + 6L)) * horizontalSpeed;
        float sizeBase =
                emission.category() == ParticleCategory.BREAK_COMMITTED ? 0.06f : 0.04f;
        float sizeRange =
                emission.category() == ParticleCategory.BREAK_COMMITTED ? 0.06f : 0.03f;
        float size = sizeBase + unit(mix64(base + 7L)) * sizeRange;
        float lifetime = 0.35f + unit(mix64(base + 8L)) * 0.4f;
        return new ParticleState(
                px,
                py,
                pz,
                vx,
                vy,
                vz,
                0.0f,
                lifetime,
                size,
                emission.region(),
                emission.category(),
                sequence);
    }

    private static long mix64(long value) {
        value = (value ^ (value >>> 30)) * 0xBF58476D1CE4E5B9L;
        value = (value ^ (value >>> 27)) * 0x94D049BB133111EBL;
        return value ^ (value >>> 31);
    }

    private static float unit(long mixed) {
        return (float) ((mixed >>> 40) * 0x1.0p-24);
    }

    private static float signedUnit(long mixed) {
        return unit(mixed) * 2.0f - 1.0f;
    }

    private record ParticleState(
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
            ParticleCategory category,
            long spawnSequence) {
        private ParticleState advance() {
            return new ParticleState(
                    x + velocityX * FIXED_STEP_SECONDS,
                    y + velocityY * FIXED_STEP_SECONDS,
                    z + velocityZ * FIXED_STEP_SECONDS,
                    velocityX,
                    velocityY,
                    velocityZ,
                    age + FIXED_STEP_SECONDS,
                    lifetime,
                    size,
                    region,
                    category,
                    spawnSequence);
        }

        private ParticleVisual visual() {
            return new ParticleVisual(x, y, z, size, region, category, spawnSequence);
        }
    }
}

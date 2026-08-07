package com.overlord.renderer.particle;

import com.overlord.renderer.feedback.ParticleRenderBatch;
import com.overlord.renderer.feedback.ParticleVisual;
import com.overlord.renderer.feedback.ParticleTint;
import com.overlord.renderer.texture.TextureRegion;
import com.overlord.voxel.BlockFace;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;

public final class ParticleSystem {
    public static final int MAX_PARTICLES = 512;
    public static final int MAX_LOW_PRIORITY_PARTICLES = 384;
    public static final int MAX_REQUESTS_PER_FIXED_STEP = 64;
    public static final int MAX_PARTICLES_PER_REQUEST = 32;
    public static final float FIXED_STEP_SECONDS = 1.0f / 60.0f;

    private static final long GOLDEN_GAMMA = 0x9E3779B97F4A7C15L;
    private static final long CATEGORY_SALT = 0xD1B54A32D192ED03L;
    private static final double GOLDEN_ANGLE = Math.PI * (3.0 - Math.sqrt(5.0));

    private final Deque<ParticleState> particles = new ArrayDeque<>();
    private long nextSpawnSequence;
    private int lowActive;
    private int highActive;
    private int requestsSinceFixedUpdate;
    private long receivedRequests;
    private long admittedRequests;
    private long rejectedRequests;
    private long particleStatesCreated;
    private long particleStatesAdvanced;
    private long evictions;

    public ParticleEmissionResult emit(ParticleEmission emission) {
        Objects.requireNonNull(emission, "emission");
        receivedRequests++;
        requestsSinceFixedUpdate++;
        if (requestsSinceFixedUpdate > MAX_REQUESTS_PER_FIXED_STEP) {
            rejectedRequests++;
            return rejected(ParticleEmissionResult.Status.REJECTED_REQUEST_CAP);
        }
        if (emission.priority() == ParticlePriority.LOW) {
            if (lowActive + emission.count() > MAX_LOW_PRIORITY_PARTICLES) {
                rejectedRequests++;
                return rejected(ParticleEmissionResult.Status.REJECTED_LOW_CAP);
            }
            if (particles.size() + emission.count() > MAX_PARTICLES) {
                rejectedRequests++;
                return rejected(ParticleEmissionResult.Status.REJECTED_TOTAL_CAP);
            }
        }
        int evicted = 0;
        for (int localIndex = 0; localIndex < emission.count(); localIndex++) {
            if (particles.size() == MAX_PARTICLES) {
                evictForHighPriority();
                evicted++;
            }
            particles.addLast(createParticle(emission, localIndex, nextSpawnSequence++));
            if (emission.priority() == ParticlePriority.LOW) {
                lowActive++;
            } else {
                highActive++;
            }
        }
        admittedRequests++;
        particleStatesCreated += emission.count();
        evictions += evicted;
        return new ParticleEmissionResult(
                ParticleEmissionResult.Status.ADMITTED, emission.count(), evicted);
    }

    public void fixedUpdate(float fixedDeltaSeconds) {
        if (!Float.isFinite(fixedDeltaSeconds)
                || Float.compare(fixedDeltaSeconds, FIXED_STEP_SECONDS) != 0) {
            throw new IllegalArgumentException("particle update must use the fixed 1/60 step");
        }
        requestsSinceFixedUpdate = 0;
        int count = particles.size();
        for (int index = 0; index < count; index++) {
            ParticleState current = particles.removeFirst();
            ParticleState advanced = current.advance();
            particleStatesAdvanced++;
            if (advanced.age() < advanced.lifetime()) {
                particles.addLast(advanced);
            } else if (current.priority() == ParticlePriority.LOW) {
                lowActive--;
            } else {
                highActive--;
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
        lowActive = 0;
        highActive = 0;
        requestsSinceFixedUpdate = 0;
    }

    public ParticleAllocationMetrics metrics() {
        return new ParticleAllocationMetrics(
                receivedRequests,
                admittedRequests,
                rejectedRequests,
                particleStatesCreated,
                particleStatesAdvanced,
                evictions,
                lowActive,
                highActive);
    }

    public void resetMetrics() {
        receivedRequests = 0;
        admittedRequests = 0;
        rejectedRequests = 0;
        particleStatesCreated = 0;
        particleStatesAdvanced = 0;
        evictions = 0;
    }

    private void evictForHighPriority() {
        ParticleState evicted = null;
        for (java.util.Iterator<ParticleState> iterator = particles.iterator();
                iterator.hasNext();) {
            ParticleState candidate = iterator.next();
            if (candidate.priority() == ParticlePriority.LOW) {
                iterator.remove();
                evicted = candidate;
                break;
            }
        }
        if (evicted == null) {
            evicted = particles.removeFirst();
        }
        if (evicted.priority() == ParticlePriority.LOW) {
            lowActive--;
        } else {
            highActive--;
        }
    }

    private static ParticleEmissionResult rejected(ParticleEmissionResult.Status status) {
        return new ParticleEmissionResult(status, 0, 0);
    }

    private static ParticleState createParticle(
            ParticleEmission emission, int localIndex, long sequence) {
        long base =
                mix64(
                        emission.deterministicSeed()
                                ^ ((long) emission.category().ordinal() * CATEGORY_SALT)
                                ^ ((long) localIndex * GOLDEN_GAMMA));
        ParticleInitialization initialization = switch (emission.category()) {
            case BREAK_DEBRIS -> breakDebris(emission, localIndex, base);
            case BREAK_ASTRAL -> breakAstral(emission, localIndex, base);
            case PLACEMENT_DEBRIS -> placementDebris(emission, localIndex, base);
            case PLACEMENT_ASTRAL -> placementAstral(emission, localIndex, base);
            case PICKUP_COMMITTED -> pickup(emission, localIndex, base);
            case BREAK_CONTINUOUS, BREAK_COMMITTED -> legacy(emission, base);
        };
        TextureRegion region = emission.faces().region(BlockFace.values()[
                Math.floorMod((int) mix64(base + 17L), BlockFace.values().length)]);
        return new ParticleState(
                initialization.x,
                initialization.y,
                initialization.z,
                initialization.velocityX,
                initialization.velocityY,
                initialization.velocityZ,
                0.0f,
                initialization.lifetime,
                initialization.size,
                initialization.size,
                initialization.gravity,
                initialization.drag,
                region,
                emission.tint(),
                emission.category(),
                emission.priority(),
                sequence);
    }

    private static ParticleInitialization breakDebris(
            ParticleEmission emission, int index, long base) {
        float vertical = -0.65f + (index + 0.5f) / emission.count() * 1.50f;
        float radial = (float) Math.sqrt(Math.max(0.0, 1.0 - vertical * vertical));
        double rotation = unit(mix64(emission.deterministicSeed())) * Math.PI * 2.0;
        double angle = rotation + index * GOLDEN_ANGLE;
        float directionX = radial * (float) Math.cos(angle);
        float directionZ = radial * (float) Math.sin(angle);
        float speed = 1.8f + unit(mix64(base + 4L)) * 0.8f;
        float radius = 0.06f + unit(mix64(base + 3L)) * 0.17f;
        return new ParticleInitialization(
                emission.x() + directionX * radius,
                emission.y() + vertical * radius,
                emission.z() + directionZ * radius,
                directionX * speed,
                vertical * speed,
                directionZ * speed,
                0.045f + unit(mix64(base + 7L)) * 0.040f,
                0.28f + unit(mix64(base + 8L)) * 0.24f,
                -12.0f,
                0.965f);
    }

    private static ParticleInitialization breakAstral(
            ParticleEmission emission, int index, long base) {
        double rotation = unit(mix64(emission.deterministicSeed() ^ CATEGORY_SALT))
                * Math.PI * 2.0;
        double angle = rotation + index * GOLDEN_ANGLE;
        float radial = 0.18f + unit(mix64(base + 1L)) * 0.12f;
        float directionY = -0.15f + unit(mix64(base + 2L)) * 0.55f;
        float speed = 0.55f + unit(mix64(base + 3L)) * 0.45f;
        return new ParticleInitialization(
                emission.x() + (float) Math.cos(angle) * radial,
                emission.y() + directionY * radial,
                emission.z() + (float) Math.sin(angle) * radial,
                (float) Math.cos(angle) * speed,
                directionY * speed,
                (float) Math.sin(angle) * speed,
                0.018f + unit(mix64(base + 7L)) * 0.018f,
                0.18f + unit(mix64(base + 8L)) * 0.16f,
                -3.0f,
                0.98f);
    }

    private static ParticleInitialization placementDebris(
            ParticleEmission emission, int index, long base) {
        Basis basis = basis(emission);
        double angle = index * GOLDEN_ANGLE
                + unit(mix64(emission.deterministicSeed())) * Math.PI * 2.0;
        float tangentX = basis.tangentX * (float) Math.cos(angle)
                + basis.bitangentX * (float) Math.sin(angle);
        float tangentY = basis.tangentY * (float) Math.cos(angle)
                + basis.bitangentY * (float) Math.sin(angle);
        float tangentZ = basis.tangentZ * (float) Math.cos(angle)
                + basis.bitangentZ * (float) Math.sin(angle);
        float speed = 1.0f + unit(mix64(base + 4L)) * 0.9f;
        float vx = (tangentX * 0.92f - basis.normalX * 0.28f) * speed;
        float vy = (tangentY * 0.92f - basis.normalY * 0.28f) * speed;
        float vz = (tangentZ * 0.92f - basis.normalZ * 0.28f) * speed;
        return new ParticleInitialization(
                emission.x() - basis.normalX * 0.47f + tangentX * 0.08f,
                emission.y() - basis.normalY * 0.47f + tangentY * 0.08f,
                emission.z() - basis.normalZ * 0.47f + tangentZ * 0.08f,
                vx, vy, vz,
                0.035f + unit(mix64(base + 7L)) * 0.025f,
                0.22f + unit(mix64(base + 8L)) * 0.16f,
                -9.0f,
                0.965f);
    }

    private static ParticleInitialization placementAstral(
            ParticleEmission emission, int index, long base) {
        ParticleInitialization debris = placementDebris(emission, index, base);
        return new ParticleInitialization(
                debris.x, debris.y, debris.z,
                debris.velocityX * 0.45f,
                debris.velocityY * 0.45f,
                debris.velocityZ * 0.45f,
                0.016f + unit(mix64(base + 7L)) * 0.014f,
                0.18f + unit(mix64(base + 8L)) * 0.16f,
                -2.5f,
                0.98f);
    }

    private static ParticleInitialization pickup(
            ParticleEmission emission, int index, long base) {
        float vertical = -0.8f + (index + 0.5f) / emission.count() * 1.6f;
        float radial = (float) Math.sqrt(Math.max(0.0, 1.0 - vertical * vertical));
        double angle = index * GOLDEN_ANGLE
                + unit(mix64(emission.deterministicSeed())) * Math.PI * 2.0;
        float offsetX = radial * (float) Math.cos(angle) * 0.18f;
        float offsetY = vertical * 0.18f;
        float offsetZ = radial * (float) Math.sin(angle) * 0.18f;
        float inverseLength = 1.0f / (float) Math.sqrt(
                offsetX * offsetX + offsetY * offsetY + offsetZ * offsetZ);
        float speed = 1.1f + unit(mix64(base + 4L)) * 0.7f;
        return new ParticleInitialization(
                emission.x() + offsetX,
                emission.y() + offsetY,
                emission.z() + offsetZ,
                -offsetX * inverseLength * speed,
                -offsetY * inverseLength * speed,
                -offsetZ * inverseLength * speed,
                0.018f + unit(mix64(base + 7L)) * 0.016f,
                0.18f + unit(mix64(base + 8L)) * 0.16f,
                0.0f,
                0.985f);
    }

    private static ParticleInitialization legacy(ParticleEmission emission, long base) {
        float px = emission.x() + signedUnit(mix64(base + 1L)) * 0.1f;
        float py = emission.y() + unit(mix64(base + 2L)) * 0.15f;
        float pz = emission.z() + signedUnit(mix64(base + 3L)) * 0.1f;
        boolean committed = emission.category() == ParticleCategory.BREAK_COMMITTED;
        float horizontalSpeed = committed ? 1.2f : 0.3f;
        float verticalBase = committed ? 0.5f : 0.25f;
        float verticalRange = committed ? 1.0f : 0.35f;
        return new ParticleInitialization(
                px, py, pz,
                signedUnit(mix64(base + 4L)) * horizontalSpeed,
                verticalBase + unit(mix64(base + 5L)) * verticalRange,
                signedUnit(mix64(base + 6L)) * horizontalSpeed,
                (committed ? 0.06f : 0.04f)
                        + unit(mix64(base + 7L)) * (committed ? 0.06f : 0.03f),
                0.35f + unit(mix64(base + 8L)) * 0.4f,
                committed ? -8.0f : -5.0f,
                0.975f);
    }

    private static Basis basis(ParticleEmission emission) {
        float nx = emission.normalX();
        float ny = emission.normalY();
        float nz = emission.normalZ();
        float length = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (length < 1.0e-6f) {
            nx = 0;
            ny = 1;
            nz = 0;
        } else {
            nx /= length;
            ny /= length;
            nz /= length;
        }
        float tx;
        float ty;
        float tz;
        if (Math.abs(ny) > 0.9f) {
            tx = 1;
            ty = 0;
            tz = 0;
        } else {
            float tangentLength = (float) Math.sqrt(nx * nx + nz * nz);
            tx = -nz / tangentLength;
            ty = 0;
            tz = nx / tangentLength;
        }
        float bx = ny * tz - nz * ty;
        float by = nz * tx - nx * tz;
        float bz = nx * ty - ny * tx;
        return new Basis(nx, ny, nz, tx, ty, tz, bx, by, bz);
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
            float initialSize,
            float gravity,
            float drag,
            TextureRegion region,
            ParticleTint tint,
            ParticleCategory category,
            ParticlePriority priority,
            long spawnSequence) {
        private ParticleState advance() {
            float nextVelocityX = velocityX * drag;
            float nextVelocityY = (velocityY + gravity * FIXED_STEP_SECONDS) * drag;
            float nextVelocityZ = velocityZ * drag;
            float nextAge = age + FIXED_STEP_SECONDS;
            float remaining = Math.max(0.0f, 1.0f - nextAge / lifetime);
            return new ParticleState(
                    x + nextVelocityX * FIXED_STEP_SECONDS,
                    y + nextVelocityY * FIXED_STEP_SECONDS,
                    z + nextVelocityZ * FIXED_STEP_SECONDS,
                    nextVelocityX,
                    nextVelocityY,
                    nextVelocityZ,
                    nextAge,
                    lifetime,
                    initialSize * (float) Math.sqrt(remaining),
                    initialSize,
                    gravity,
                    drag,
                    region,
                    tint,
                    category,
                    priority,
                    spawnSequence);
        }

        private ParticleVisual visual() {
            return new ParticleVisual(
                    x, y, z,
                    velocityX, velocityY, velocityZ,
                    age, lifetime, size,
                    region, tint, category, priority, spawnSequence);
        }
    }

    private record ParticleInitialization(
            float x,
            float y,
            float z,
            float velocityX,
            float velocityY,
            float velocityZ,
            float size,
            float lifetime,
            float gravity,
            float drag) {}

    private record Basis(
            float normalX,
            float normalY,
            float normalZ,
            float tangentX,
            float tangentY,
            float tangentZ,
            float bitangentX,
            float bitangentY,
            float bitangentZ) {}
}

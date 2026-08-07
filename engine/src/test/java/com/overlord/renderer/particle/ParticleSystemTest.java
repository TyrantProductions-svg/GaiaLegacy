package com.overlord.renderer.particle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.overlord.assets.ResourceLocation;
import com.overlord.renderer.feedback.ParticleRenderBatch;
import com.overlord.renderer.feedback.ParticleVisual;
import com.overlord.renderer.feedback.WorldItemFaceRegions;
import com.overlord.renderer.texture.TextureRegion;
import java.util.List;
import org.junit.jupiter.api.Test;

class ParticleSystemTest {
    private static final TextureRegion REGION =
            new TextureRegion(new ResourceLocation("gaia", "stone"), 16, 0, 16, 16, 128, 16);

    @Test
    void requestedCommittedEmissionCreatesExactlyTwentyFourParticles() {
        ParticleSystem system = new ParticleSystem();

        system.emit(emission(ParticleCategory.BREAK_COMMITTED, 24, 81L));

        assertEquals(24, system.snapshot().particles().size());
        assertTrue(
                system.snapshot().particles().stream()
                        .allMatch(particle -> particle.category() == ParticleCategory.BREAK_COMMITTED));
    }

    @Test
    void fiveHundredThirteenthInsertionEvictsExactlyTheOldestSequence() {
        ParticleSystem system = new ParticleSystem();
        emitBatches(system, ParticleCategory.BREAK_COMMITTED,
                ParticlePriority.HIGH, 16, 32, 1L);
        List<Long> before = sequences(system.snapshot());

        system.emit(emission(
                ParticleCategory.BREAK_COMMITTED, ParticlePriority.HIGH, 1, 20L));

        List<Long> after = sequences(system.snapshot());
        assertEquals(ParticleSystem.MAX_PARTICLES, after.size());
        assertFalse(after.contains(before.get(0)));
        assertEquals(before.subList(1, before.size()), after.subList(0, after.size() - 1));
        assertEquals(before.get(before.size() - 1) + 1L, after.get(after.size() - 1));
    }

    @Test
    void sameSeedCategoryAndLocalIndicesProduceIdenticalStates() {
        ParticleSystem first = new ParticleSystem();
        ParticleSystem second = new ParticleSystem();
        ParticleEmission emission = emission(ParticleCategory.BREAK_COMMITTED, 24, 0xCAFE_BABEL);

        first.emit(emission);
        second.emit(emission);

        assertEquals(first.snapshot(), second.snapshot());
        first.fixedUpdate(ParticleSystem.FIXED_STEP_SECONDS);
        second.fixedUpdate(ParticleSystem.FIXED_STEP_SECONDS);
        assertEquals(first.snapshot(), second.snapshot());
        assertNotEquals(
                emission.x(),
                first.snapshot().particles().get(0).x(),
                "deterministic variation must affect the initial particle position");
    }

    @Test
    void lifetimesNeverExpireBeforePointThreeFiveAndAllExpireByPointSevenFive() {
        ParticleSystem system = new ParticleSystem();
        emitBatches(system, ParticleCategory.BREAK_COMMITTED,
                ParticlePriority.HIGH, 16, 32, 42L);

        for (int step = 0; step < 20; step++) {
            system.fixedUpdate(ParticleSystem.FIXED_STEP_SECONDS);
        }
        assertEquals(512, system.snapshot().particles().size());

        for (int step = 20; step < 45; step++) {
            system.fixedUpdate(ParticleSystem.FIXED_STEP_SECONDS);
        }
        assertTrue(system.snapshot().particles().isEmpty());
    }

    @Test
    void fixedUpdateAdvancesPositionAndRemovesParticleAtLifetime() {
        ParticleSystem system = new ParticleSystem();
        system.emit(emission(ParticleCategory.BREAK_CONTINUOUS, 1, 12L));
        ParticleVisual before = system.snapshot().particles().get(0);

        system.fixedUpdate(ParticleSystem.FIXED_STEP_SECONDS);

        ParticleVisual after = system.snapshot().particles().get(0);
        assertNotEquals(List.of(before.x(), before.y(), before.z()), List.of(after.x(), after.y(), after.z()));

        for (int step = 1; step < 45; step++) {
            system.fixedUpdate(ParticleSystem.FIXED_STEP_SECONDS);
        }
        assertTrue(system.snapshot().particles().isEmpty());
    }

    @Test
    void snapshotsAreImmutableAndClearDoesNotMutateAnEarlierSnapshot() {
        ParticleSystem system = new ParticleSystem();
        system.emit(emission(ParticleCategory.BREAK_CONTINUOUS, 2, 3L));
        ParticleRenderBatch beforeClear = system.snapshot();

        assertThrows(
                UnsupportedOperationException.class,
                () -> beforeClear.particles().add(beforeClear.particles().get(0)));

        system.clear();

        assertEquals(2, beforeClear.particles().size());
        assertTrue(system.snapshot().particles().isEmpty());
    }

    @Test
    void rejectsNonFiniteAndNonFixedUpdateValuesWithoutChangingState() {
        ParticleSystem system = new ParticleSystem();
        system.emit(emission(ParticleCategory.BREAK_CONTINUOUS, 1, 9L));
        ParticleRenderBatch before = system.snapshot();

        for (float invalid : List.of(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, 0.0f, 1.0f / 59.0f)) {
            assertThrows(IllegalArgumentException.class, () -> system.fixedUpdate(invalid));
            assertEquals(before, system.snapshot());
        }
    }

    @Test
    void emissionRejectsInvalidInputs() {
        assertThrows(
                NullPointerException.class,
                () -> new ParticleEmission(null, 0, 0, 0, REGION, 1, 1L));
        assertThrows(
                NullPointerException.class,
                () -> new ParticleEmission(ParticleCategory.BREAK_CONTINUOUS, 0, 0, 0, null, 1, 1L));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ParticleEmission(ParticleCategory.BREAK_CONTINUOUS, Float.NaN, 0, 0, REGION, 1, 1L));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ParticleEmission(ParticleCategory.BREAK_CONTINUOUS, 0, 0, 0, REGION, 0, 1L));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ParticleEmission(ParticleCategory.BREAK_CONTINUOUS,
                        ParticlePriority.LOW, 0, 0, 0, REGION, 33, 1L));
    }

    @Test
    void lowPriorityCannotEvictCommittedParticles() {
        ParticleSystem system = new ParticleSystem();
        emitBatches(system, ParticleCategory.BREAK_COMMITTED,
                ParticlePriority.HIGH, 4, 32, 1L);
        emitBatches(system, ParticleCategory.BREAK_CONTINUOUS,
                ParticlePriority.LOW, 12, 32, 5L);
        List<Long> committedBefore = system.snapshot().particles().stream()
                .filter(particle -> particle.priority() == ParticlePriority.HIGH)
                .map(ParticleVisual::spawnSequence)
                .toList();

        ParticleEmissionResult rejected = system.emit(emission(
                ParticleCategory.BREAK_CONTINUOUS, ParticlePriority.LOW, 1, 30L));

        assertEquals(ParticleEmissionResult.Status.REJECTED_LOW_CAP, rejected.status());
        assertEquals(committedBefore, system.snapshot().particles().stream()
                .filter(particle -> particle.priority() == ParticlePriority.HIGH)
                .map(ParticleVisual::spawnSequence)
                .toList());
        assertEquals(512, system.snapshot().particles().size());
    }

    @Test
    void highPriorityEvictsOldestLowBeforeAnyHigh() {
        ParticleSystem system = new ParticleSystem();
        emitBatches(system, ParticleCategory.BREAK_CONTINUOUS,
                ParticlePriority.LOW, 12, 32, 1L);
        emitBatches(system, ParticleCategory.BREAK_COMMITTED,
                ParticlePriority.HIGH, 4, 32, 20L);
        long oldestLow = system.snapshot().particles().stream()
                .filter(particle -> particle.priority() == ParticlePriority.LOW)
                .findFirst().orElseThrow().spawnSequence();
        List<Long> highBefore = system.snapshot().particles().stream()
                .filter(particle -> particle.priority() == ParticlePriority.HIGH)
                .map(ParticleVisual::spawnSequence).toList();

        ParticleEmissionResult result = system.emit(emission(
                ParticleCategory.PICKUP_COMMITTED, ParticlePriority.HIGH, 1, 30L));

        assertEquals(ParticleEmissionResult.Status.ADMITTED, result.status());
        assertEquals(1, result.evictedCount());
        assertFalse(sequences(system.snapshot()).contains(oldestLow));
        assertTrue(sequences(system.snapshot()).containsAll(highBefore));
    }

    @Test
    void sixtyFifthRequestIsRejectedUntilNextFixedUpdate() {
        ParticleSystem system = new ParticleSystem();
        for (int request = 0; request < 64; request++) {
            assertEquals(ParticleEmissionResult.Status.ADMITTED,
                    system.emit(emission(ParticleCategory.BREAK_COMMITTED,
                            ParticlePriority.HIGH, 1, request)).status());
        }

        assertEquals(ParticleEmissionResult.Status.REJECTED_REQUEST_CAP,
                system.emit(emission(ParticleCategory.BREAK_COMMITTED,
                        ParticlePriority.HIGH, 1, 100)).status());
        system.fixedUpdate(ParticleSystem.FIXED_STEP_SECONDS);
        assertEquals(ParticleEmissionResult.Status.ADMITTED,
                system.emit(emission(ParticleCategory.BREAK_COMMITTED,
                        ParticlePriority.HIGH, 1, 101)).status());
    }

    @Test
    void allocationMetricsAreImmutableExactAndResetExplicitly() {
        ParticleSystem system = new ParticleSystem();
        system.emit(emission(ParticleCategory.BREAK_CONTINUOUS,
                ParticlePriority.LOW, 2, 1));
        system.fixedUpdate(ParticleSystem.FIXED_STEP_SECONDS);

        ParticleAllocationMetrics metrics = system.metrics();
        assertEquals(1, metrics.receivedRequests());
        assertEquals(1, metrics.admittedRequests());
        assertEquals(0, metrics.rejectedRequests());
        assertEquals(2, metrics.particleStatesCreated());
        assertEquals(2, metrics.particleStatesAdvanced());
        assertEquals(2, metrics.lowActive());
        assertEquals(0, metrics.highActive());

        system.clear();
        assertEquals(1, system.metrics().admittedRequests());
        assertEquals(0, system.metrics().lowActive());
        system.resetMetrics();
        assertEquals(0, system.metrics().receivedRequests());
    }

    @Test
    void sixteenBreakDebrisCoverQuadrantsIncludeDownwardMotionAndAreNotUpBiased() {
        ParticleSystem system = new ParticleSystem();
        system.emit(new ParticleEmission(
                ParticleCategory.BREAK_DEBRIS,
                ParticlePriority.HIGH,
                0, 0, 0,
                WorldItemFaceRegions.uniform(REGION),
                0, 1, 0,
                16,
                991L));

        List<ParticleVisual> particles = system.snapshot().particles();
        assertEquals(16, particles.size());
        assertTrue(particles.stream().filter(particle -> particle.velocityY() <= 0).count() >= 4);
        assertTrue(particles.stream().anyMatch(particle ->
                particle.velocityX() > 0 && particle.velocityZ() > 0));
        assertTrue(particles.stream().anyMatch(particle ->
                particle.velocityX() < 0 && particle.velocityZ() > 0));
        assertTrue(particles.stream().anyMatch(particle ->
                particle.velocityX() < 0 && particle.velocityZ() < 0));
        assertTrue(particles.stream().anyMatch(particle ->
                particle.velocityX() > 0 && particle.velocityZ() < 0));
        double averageY = particles.stream()
                .mapToDouble(ParticleVisual::velocityY)
                .average()
                .orElseThrow();
        double averageHorizontal = particles.stream()
                .mapToDouble(particle -> Math.hypot(
                        particle.velocityX(), particle.velocityZ()))
                .average()
                .orElseThrow();
        assertTrue(Math.abs(averageY) < averageHorizontal * 0.5);
        assertTrue(particles.stream().allMatch(ParticleSystemTest::finite));
    }

    @Test
    void debrisGravityBendsVelocityDownwardShrinksAndExpiresByApprovedLifetime() {
        ParticleSystem system = new ParticleSystem();
        system.emit(new ParticleEmission(
                ParticleCategory.BREAK_DEBRIS,
                ParticlePriority.HIGH,
                0, 0, 0,
                WorldItemFaceRegions.uniform(REGION),
                0, 1, 0,
                16,
                123L));
        ParticleVisual initial = system.snapshot().particles().get(0);
        for (int step = 0; step < 12; step++) {
            system.fixedUpdate(ParticleSystem.FIXED_STEP_SECONDS);
        }
        ParticleVisual advanced = system.snapshot().particles().get(0);
        assertTrue(advanced.velocityY() < initial.velocityY());
        assertTrue(advanced.size() < initial.size());
        for (int step = 12; step < 32; step++) {
            system.fixedUpdate(ParticleSystem.FIXED_STEP_SECONDS);
        }
        assertTrue(system.snapshot().particles().isEmpty());
    }

    private static boolean finite(ParticleVisual particle) {
        return Float.isFinite(particle.x())
                && Float.isFinite(particle.y())
                && Float.isFinite(particle.z())
                && Float.isFinite(particle.velocityX())
                && Float.isFinite(particle.velocityY())
                && Float.isFinite(particle.velocityZ())
                && Float.isFinite(particle.size());
    }

    private static ParticleEmission emission(ParticleCategory category, int count, long seed) {
        return new ParticleEmission(category, 4.0f, 5.0f, -6.0f, REGION, count, seed);
    }

    private static ParticleEmission emission(
            ParticleCategory category,
            ParticlePriority priority,
            int count,
            long seed) {
        return new ParticleEmission(
                category, priority, 4.0f, 5.0f, -6.0f, REGION, count, seed);
    }

    private static void emitBatches(
            ParticleSystem system,
            ParticleCategory category,
            ParticlePriority priority,
            int batches,
            int count,
            long seed) {
        for (int batch = 0; batch < batches; batch++) {
            assertEquals(ParticleEmissionResult.Status.ADMITTED,
                    system.emit(emission(category, priority, count, seed + batch)).status());
        }
    }

    private static List<Long> sequences(ParticleRenderBatch batch) {
        return batch.particles().stream().map(ParticleVisual::spawnSequence).toList();
    }
}

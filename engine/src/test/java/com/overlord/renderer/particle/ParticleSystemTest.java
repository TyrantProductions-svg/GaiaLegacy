package com.overlord.renderer.particle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.overlord.assets.ResourceLocation;
import com.overlord.renderer.feedback.ParticleRenderBatch;
import com.overlord.renderer.feedback.ParticleVisual;
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
        system.emit(emission(ParticleCategory.BREAK_CONTINUOUS, 512, 1L));
        List<Long> before = sequences(system.snapshot());

        system.emit(emission(ParticleCategory.BREAK_CONTINUOUS, 1, 2L));

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
        system.emit(emission(ParticleCategory.BREAK_COMMITTED, 512, 42L));

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
    }

    private static ParticleEmission emission(ParticleCategory category, int count, long seed) {
        return new ParticleEmission(category, 4.0f, 5.0f, -6.0f, REGION, count, seed);
    }

    private static List<Long> sequences(ParticleRenderBatch batch) {
        return batch.particles().stream().map(ParticleVisual::spawnSequence).toList();
    }
}

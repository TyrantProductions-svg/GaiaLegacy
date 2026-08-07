package com.gaia.interaction.feedback;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.gaia.worlditem.WorldItemPickupReceipt;
import com.overlord.assets.ResourceLocation;
import com.overlord.interaction.api.BlockHitResult;
import com.overlord.inventory.api.ItemStack;
import com.overlord.renderer.feedback.WorldItemFaceRegions;
import com.overlord.renderer.particle.ParticleCategory;
import com.overlord.renderer.particle.ParticlePriority;
import com.overlord.renderer.particle.ParticleSystem;
import com.overlord.renderer.texture.TextureRegion;
import com.overlord.worlditem.api.WorldItemId;
import org.junit.jupiter.api.Test;

class GameplayParticleFeedbackTest {
    private static final ResourceLocation STONE = ResourceLocation.parse("gaia:stone");
    private static final WorldItemFaceRegions FACES = WorldItemFaceRegions.uniform(
            new TextureRegion(STONE, 0, 0, 16, 16, 16, 16));

    @Test
    void committedBreakAndPlacementHaveExactHighPriorityCategorySplits() {
        ParticleSystem breakParticles = new ParticleSystem();
        GameplayParticleFeedback breakFeedback = new GameplayParticleFeedback(breakParticles);
        breakFeedback.onBreak(hit(), FACES, 10L);

        assertEquals(16, count(breakParticles, ParticleCategory.BREAK_DEBRIS));
        assertEquals(4, count(breakParticles, ParticleCategory.BREAK_ASTRAL));
        assertEquals(20, breakParticles.snapshot().particles().size());
        breakParticles.snapshot().particles().stream()
                .filter(particle -> particle.category() == ParticleCategory.BREAK_ASTRAL)
                .forEach(particle -> {
                    org.junit.jupiter.api.Assertions.assertNotEquals(
                            com.overlord.renderer.feedback.ParticleTint.white(),
                            particle.tint());
                    org.junit.jupiter.api.Assertions.assertTrue(
                            particle.tint().alpha() < 1.0f);
                });

        ParticleSystem placementParticles = new ParticleSystem();
        GameplayParticleFeedback placementFeedback =
                new GameplayParticleFeedback(placementParticles);
        placementFeedback.onPlacement(hit(), FACES, 11L);
        assertEquals(6, count(placementParticles, ParticleCategory.PLACEMENT_DEBRIS));
        assertEquals(2, count(placementParticles, ParticleCategory.PLACEMENT_ASTRAL));
        assertEquals(8, placementParticles.snapshot().particles().size());
        placementParticles.snapshot().particles().forEach(particle ->
                assertEquals(ParticlePriority.HIGH, particle.priority()));
    }

    @Test
    void committedPickupEmitsExactlyEightConvergingHighPriorityParticles() {
        ParticleSystem particles = new ParticleSystem();
        GameplayParticleFeedback feedback = new GameplayParticleFeedback(particles);
        feedback.onPickup(new WorldItemPickupReceipt(
                new WorldItemId(4),
                new ItemStack(STONE, 1),
                1.0, 2.0, 3.0,
                20), FACES);

        assertEquals(8, count(particles, ParticleCategory.PICKUP_COMMITTED));
        particles.snapshot().particles().forEach(particle -> {
            double towardX = 1.0 - particle.x();
            double towardY = 2.0 - particle.y();
            double towardZ = 3.0 - particle.z();
            double dot = towardX * particle.velocityX()
                    + towardY * particle.velocityY()
                    + towardZ * particle.velocityZ();
            org.junit.jupiter.api.Assertions.assertTrue(dot > 0.0);
            assertEquals(ParticlePriority.HIGH, particle.priority());
        });
    }

    private static long count(ParticleSystem particles, ParticleCategory category) {
        return particles.snapshot().particles().stream()
                .filter(particle -> particle.category() == category)
                .count();
    }

    private static BlockHitResult hit() {
        return new BlockHitResult(
                1, 2, 3, 2, 2, 3, STONE,
                1, 0, 0, 2, 2.5f, 3.5f, 2);
    }
}

package com.gaia.interaction.feedback;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.gaia.interaction.DetailPlacementCandidate;
import com.gaia.interaction.DetailPrecisionTarget;
import com.overlord.assets.ResourceLocation;
import com.overlord.interaction.api.BlockFace;
import com.overlord.physics.FullRaycastTarget;
import com.overlord.renderer.feedback.WorldItemFaceRegions;
import com.overlord.renderer.particle.ParticleCategory;
import com.overlord.renderer.particle.ParticleSystem;
import com.overlord.renderer.texture.TextureRegion;
import com.overlord.voxel.ChunkKey;
import com.overlord.voxel.FullCellState;
import com.overlord.voxel.LocalSubVoxelPosition;
import com.overlord.voxel.ParentCellObservation;
import com.overlord.voxel.ParentCellObservationResult;
import java.util.List;
import org.junit.jupiter.api.Test;

class DetailInteractionFeedbackTest {
    private static final ResourceLocation STONE = ResourceLocation.parse("gaia:stone");
    private static final WorldItemFaceRegions FACES = WorldItemFaceRegions.uniform(
            new TextureRegion(STONE, 0, 0, 16, 16, 16, 16));

    @Test
    void committedQuarterRemoveAndPlaceAreLocalizedBoundedAndDeterministic() {
        ParticleSystem first = new ParticleSystem();
        GameplayParticleFeedback feedback = new GameplayParticleFeedback(first);
        DetailPrecisionTarget removal = target();
        DetailPlacementCandidate placement = candidate(removal);

        feedback.onDetailRemoval(removal, FACES, 41L);
        feedback.onDetailPlacement(placement, FACES, 42L);

        assertEquals(7, first.snapshot().particles().size());
        assertEquals(4, count(first, ParticleCategory.BREAK_DEBRIS));
        assertEquals(3, count(first, ParticleCategory.PLACEMENT_DEBRIS));
        first.snapshot().particles().forEach(particle -> {
            assertEquals(17.375f, particle.x(), 0.5f);
            assertEquals(4.625f, particle.y(), 0.5f);
            assertEquals(-15.125f, particle.z(), 0.5f);
        });

        ParticleSystem repeated = new ParticleSystem();
        GameplayParticleFeedback repeatedFeedback = new GameplayParticleFeedback(repeated);
        repeatedFeedback.onDetailRemoval(removal, FACES, 41L);
        repeatedFeedback.onDetailPlacement(placement, FACES, 42L);
        assertEquals(first.snapshot(), repeated.snapshot());
    }

    @Test
    void previewOrRejectedActionWithoutCommittedCallbackProducesNoParticles() {
        ParticleSystem particles = new ParticleSystem();
        new GameplayParticleFeedback(particles);

        assertEquals(List.of(), particles.snapshot().particles());
    }

    private static DetailPrecisionTarget target() {
        return new DetailPrecisionTarget(
                17, 4, -16, new LocalSubVoxelPosition(1, 2, 3),
                BlockFace.EAST, STONE, 7, FullRaycastTarget.INSTANCE);
    }

    private static DetailPlacementCandidate candidate(DetailPrecisionTarget source) {
        ParentCellObservation observation = new ParentCellObservation(
                new ChunkKey(1, -1), 1, 4, 0, 7, new FullCellState((byte) 0));
        return new DetailPlacementCandidate(
                source, 17, 4, -16, new LocalSubVoxelPosition(1, 2, 3), STONE,
                ParentCellObservationResult.available(observation),
                DetailPlacementCandidate.Status.VALID_FULL_AIR);
    }

    private static long count(ParticleSystem particles, ParticleCategory category) {
        return particles.snapshot().particles().stream()
                .filter(particle -> particle.category() == category)
                .count();
    }
}

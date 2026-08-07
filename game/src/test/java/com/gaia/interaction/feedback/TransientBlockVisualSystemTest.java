package com.gaia.interaction.feedback;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.overlord.assets.ResourceLocation;
import com.overlord.renderer.feedback.BlockVisualCoordinate;
import com.overlord.renderer.feedback.TransientBlockVisual;
import com.overlord.renderer.feedback.WorldItemFaceRegions;
import com.overlord.renderer.texture.TextureRegion;
import org.junit.jupiter.api.Test;

class TransientBlockVisualSystemTest {
    private static final WorldItemFaceRegions FACES = WorldItemFaceRegions.uniform(
            new TextureRegion(ResourceLocation.parse("gaia:stone"), 0, 0, 16, 16, 16, 16));

    @Test
    void placementStartsAtApprovedScaleAndExpiresWithMaskRemoval() {
        TransientBlockVisualSystem system = new TransientBlockVisualSystem();
        BlockVisualCoordinate coordinate = new BlockVisualCoordinate(1, 2, 3);

        system.registerPlacement(coordinate, FACES, 10L);

        TransientBlockVisual initial = system.snapshot().get(0);
        assertEquals(TransientBlockVisual.Type.PLACEMENT, initial.type());
        assertEquals(0.85f, initial.transform().scale(), 1.0e-6f);
        assertEquals(1.0f, initial.transform().alpha(), 1.0e-6f);
        assertEquals(1, system.excludedCells().size());
        system.update(0.139);
        assertTrue(system.snapshot().get(0).transform().scale() <= 1.015f);
        system.update(0.001);
        assertTrue(system.snapshot().isEmpty());
        assertTrue(system.excludedCells().isEmpty());
    }

    @Test
    void breakRetainsFacesIsRenderOnlyAndReplacementIsDeterministic() {
        TransientBlockVisualSystem system = new TransientBlockVisualSystem();
        BlockVisualCoordinate coordinate = new BlockVisualCoordinate(-1, 5, 7);
        system.registerBreak(coordinate, FACES, 20L);
        TransientBlockVisual initial = system.snapshot().get(0);
        assertEquals(FACES, initial.faces());
        assertEquals(1.0f, initial.transform().scale(), 1.0e-6f);
        assertEquals(java.util.List.of(coordinate), system.excludedCells());

        system.update(0.09);
        TransientBlockVisual midway = system.snapshot().get(0);
        assertTrue(midway.transform().scale() < 1.0f);
        assertTrue(midway.transform().alpha() < 1.0f);
        assertTrue(midway.transform().translationY() < 0.0f);

        system.registerPlacement(coordinate, FACES, 21L);
        assertEquals(1, system.snapshot().size());
        assertEquals(21L, system.snapshot().get(0).eventIdentity());
        assertEquals(TransientBlockVisual.Type.PLACEMENT, system.snapshot().get(0).type());
        system.close();
        system.close();
        assertTrue(system.snapshot().isEmpty());
        assertFalse(system.isOpen());
    }

    @Test
    void hardCapEvictsOldestTransitionWithoutLeakingAnOverride() {
        TransientBlockVisualSystem system = new TransientBlockVisualSystem(2);
        system.registerPlacement(new BlockVisualCoordinate(0, 0, 0), FACES, 1L);
        system.registerPlacement(new BlockVisualCoordinate(1, 0, 0), FACES, 2L);
        system.registerPlacement(new BlockVisualCoordinate(2, 0, 0), FACES, 3L);

        assertEquals(2, system.snapshot().size());
        assertEquals(2L, system.snapshot().get(0).eventIdentity());
        assertEquals(3L, system.snapshot().get(1).eventIdentity());
        assertEquals(2, system.excludedCells().size());
    }

    @Test
    void rejectsCapacityAboveRendererExclusionLimit() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new TransientBlockVisualSystem(
                        TransientBlockVisualSystem.DEFAULT_CAPACITY + 1));
    }

    @Test
    void closeIsIdempotentAndCommittedEventsCannotResurrectTransitions() {
        TransientBlockVisualSystem system = new TransientBlockVisualSystem();
        system.registerPlacement(new BlockVisualCoordinate(0, 0, 0), FACES, 1L);

        system.close();
        system.close();

        assertThrows(
                IllegalStateException.class,
                () -> system.registerPlacement(
                        new BlockVisualCoordinate(1, 0, 0), FACES, 2L));
        assertThrows(
                IllegalStateException.class,
                () -> system.registerBreak(
                        new BlockVisualCoordinate(2, 0, 0), FACES, 3L));
        assertTrue(system.snapshot().isEmpty());
        assertTrue(system.excludedCells().isEmpty());
    }
}

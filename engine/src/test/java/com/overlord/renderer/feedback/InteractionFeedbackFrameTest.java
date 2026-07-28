package com.overlord.renderer.feedback;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.overlord.assets.ResourceLocation;
import com.overlord.renderer.particle.ParticleCategory;
import com.overlord.renderer.texture.TextureRegion;
import com.overlord.worlditem.api.WorldItemId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class InteractionFeedbackFrameTest {
    @Test
    void snapshotsMutableWorldItemAndParticleInputs() {
        List<WorldItemVisual> worldItems = new ArrayList<>();
        worldItems.add(worldItem(1L));
        List<ParticleVisual> particles = new ArrayList<>();
        particles.add(particle(2L));

        InteractionFeedbackFrame frame =
                new InteractionFeedbackFrame(
                        new FeedbackVisibility(true, true, true, false),
                        Optional.of(new BlockDamageVisual(1, 2, 3, 4)),
                        worldItems,
                        new ParticleRenderBatch(particles));
        worldItems.clear();
        particles.clear();

        assertEquals(List.of(worldItem(1L)), frame.worldItems());
        assertEquals(List.of(particle(2L)), frame.particles().particles());
        assertThrows(
                UnsupportedOperationException.class,
                () -> frame.worldItems().add(worldItem(3L)));
        assertThrows(
                UnsupportedOperationException.class,
                () -> frame.particles().particles().add(particle(4L)));
    }

    @Test
    void visibilityRequiresRunningCaptureFocusAndNoBlockingUi() {
        assertTrue(new FeedbackVisibility(true, true, true, false).showGameplayFeedback());
        assertFalse(new FeedbackVisibility(false, true, true, false).showGameplayFeedback());
        assertFalse(new FeedbackVisibility(true, false, true, false).showGameplayFeedback());
        assertFalse(new FeedbackVisibility(true, true, false, false).showGameplayFeedback());
        assertFalse(new FeedbackVisibility(true, true, true, true).showGameplayFeedback());
    }

    @Test
    void hiddenFrameContainsNoPresentationAndCannotBecomeVisible() {
        InteractionFeedbackFrame hidden = InteractionFeedbackFrame.hidden();

        assertFalse(hidden.visibility().showGameplayFeedback());
        assertEquals(Optional.empty(), hidden.blockDamage());
        assertEquals(List.of(), hidden.worldItems());
        assertEquals(List.of(), hidden.particles().particles());
    }

    private static WorldItemVisual worldItem(long id) {
        return new WorldItemVisual(new WorldItemId(id), 7L, 1.0, 2.0, 3.0, region());
    }

    private static ParticleVisual particle(long sequence) {
        return new ParticleVisual(
                1.0f,
                2.0f,
                3.0f,
                0.1f,
                region(),
                ParticleCategory.BREAK_CONTINUOUS,
                sequence);
    }

    private static TextureRegion region() {
        return new TextureRegion(
                ResourceLocation.of("overlord", "textures/test.png"),
                0,
                0,
                16,
                16,
                16,
                16);
    }
}

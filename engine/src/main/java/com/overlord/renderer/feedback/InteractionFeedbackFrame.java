package com.overlord.renderer.feedback;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record InteractionFeedbackFrame(
        FeedbackVisibility visibility,
        Optional<BlockDamageVisual> blockDamage,
        List<WorldItemVisual> worldItems,
        ParticleRenderBatch particles) {
    public InteractionFeedbackFrame {
        Objects.requireNonNull(visibility, "visibility");
        Objects.requireNonNull(blockDamage, "blockDamage");
        worldItems = List.copyOf(Objects.requireNonNull(worldItems, "worldItems"));
        for (WorldItemVisual worldItem : worldItems) {
            Objects.requireNonNull(worldItem, "worldItem");
        }
        Objects.requireNonNull(particles, "particles");
    }

    public static InteractionFeedbackFrame hidden() {
        return new InteractionFeedbackFrame(
                new FeedbackVisibility(false, false, false, true),
                Optional.empty(),
                List.of(),
                new ParticleRenderBatch(List.of()));
    }
}

package com.overlord.renderer.feedback;

import com.overlord.renderer.shader.WorldShaderUniforms;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record InteractionFeedbackFrame(
        FeedbackVisibility visibility,
        Optional<BlockDamageVisual> blockDamage,
        List<WorldItemVisual> worldItems,
        ParticleRenderBatch particles,
        Optional<FirstPersonItemVisual> firstPersonItem,
        FirstPersonMovementVisual movementVisual,
        CameraImpulseVisual cameraImpulse,
        List<TransientBlockVisual> transientBlocks,
        List<BlockVisualCoordinate> excludedBlockCells) {
    public InteractionFeedbackFrame {
        Objects.requireNonNull(visibility, "visibility");
        Objects.requireNonNull(blockDamage, "blockDamage");
        worldItems = List.copyOf(Objects.requireNonNull(worldItems, "worldItems"));
        for (WorldItemVisual worldItem : worldItems) {
            Objects.requireNonNull(worldItem, "worldItem");
        }
        Objects.requireNonNull(particles, "particles");
        firstPersonItem = Objects.requireNonNull(firstPersonItem, "firstPersonItem");
        Objects.requireNonNull(movementVisual, "movementVisual");
        Objects.requireNonNull(cameraImpulse, "cameraImpulse");
        transientBlocks = List.copyOf(Objects.requireNonNull(
                transientBlocks, "transientBlocks"));
        transientBlocks.forEach(visual -> Objects.requireNonNull(
                visual, "transientBlock"));
        excludedBlockCells = List.copyOf(Objects.requireNonNull(
                excludedBlockCells, "excludedBlockCells"));
        excludedBlockCells.forEach(cell -> Objects.requireNonNull(
                cell, "excludedBlockCell"));
        if (excludedBlockCells.size() > WorldShaderUniforms.MAX_EXCLUDED_BLOCK_CELLS) {
            throw new IllegalArgumentException(
                    "excludedBlockCells cannot exceed the shader cap of "
                            + WorldShaderUniforms.MAX_EXCLUDED_BLOCK_CELLS);
        }
    }

    public InteractionFeedbackFrame(
            FeedbackVisibility visibility,
            Optional<BlockDamageVisual> blockDamage,
            List<WorldItemVisual> worldItems,
            ParticleRenderBatch particles,
            Optional<FirstPersonItemVisual> firstPersonItem,
            CameraImpulseVisual cameraImpulse,
            List<TransientBlockVisual> transientBlocks,
            List<BlockVisualCoordinate> excludedBlockCells) {
        this(
                visibility, blockDamage, worldItems, particles, firstPersonItem,
                FirstPersonMovementVisual.identity(), cameraImpulse,
                transientBlocks, excludedBlockCells);
    }

    public InteractionFeedbackFrame(
            FeedbackVisibility visibility,
            Optional<BlockDamageVisual> blockDamage,
            List<WorldItemVisual> worldItems,
            ParticleRenderBatch particles) {
        this(
                visibility, blockDamage, worldItems, particles,
                Optional.empty(), FirstPersonMovementVisual.identity(),
                CameraImpulseVisual.identity(), List.of(), List.of());
    }

    public static InteractionFeedbackFrame hidden() {
        return new InteractionFeedbackFrame(
                new FeedbackVisibility(false, false, false, true),
                Optional.empty(),
                List.of(),
                new ParticleRenderBatch(List.of()),
                Optional.empty(),
                FirstPersonMovementVisual.identity(),
                CameraImpulseVisual.identity(),
                List.of(),
                List.of());
    }
}

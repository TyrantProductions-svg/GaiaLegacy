package com.overlord.interaction.testing;

import com.overlord.interaction.api.BlockFace;
import com.overlord.interaction.api.BlockHitResult;
import com.overlord.interaction.api.InteractionFailureReason;
import com.overlord.interaction.api.InteractionMode;
import com.overlord.interaction.api.InteractionViewModel;
import com.overlord.inventory.api.ItemStackView;
import java.util.Objects;
import java.util.Optional;

/** Immutable test fixture for read-only interaction presentation snapshots. */
public record StubInteractionViewModel(
        Optional<BlockHitResult> target,
        Optional<BlockFace> hitFace,
        double progress,
        InteractionMode mode,
        Optional<ItemStackView> activeItem,
        Optional<InteractionFailureReason> failureReason)
        implements InteractionViewModel {
    public StubInteractionViewModel {
        target = Objects.requireNonNull(target, "target");
        hitFace = Objects.requireNonNull(hitFace, "hitFace");
        mode = Objects.requireNonNull(mode, "mode");
        activeItem = Objects.requireNonNull(activeItem, "activeItem");
        failureReason = Objects.requireNonNull(failureReason, "failureReason");
        if (!Double.isFinite(progress) || progress < 0.0 || progress > 1.0) {
            throw new IllegalArgumentException("progress must be finite and within [0.0, 1.0]");
        }
        if (target.isPresent() != hitFace.isPresent()) {
            throw new IllegalArgumentException("target and hitFace must be both present or both empty");
        }
        if (target.isPresent() && BlockFace.fromHit(target.orElseThrow()) != hitFace.orElseThrow()) {
            throw new IllegalArgumentException("hitFace must match the target normal");
        }
        if (activeItem.isPresent()) {
            ItemStackView item = activeItem.orElseThrow();
            if (item.itemId() == null || item.count() <= 0) {
                throw new IllegalArgumentException("activeItem must be a valid item stack snapshot");
            }
        }
    }
}

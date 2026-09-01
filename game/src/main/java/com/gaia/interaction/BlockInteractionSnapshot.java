package com.gaia.interaction;

import com.overlord.interaction.api.BlockFace;
import com.overlord.interaction.api.BlockHitResult;
import com.overlord.interaction.api.InteractionFailureReason;
import com.overlord.interaction.api.InteractionMode;
import com.overlord.inventory.api.ItemStack;
import com.overlord.inventory.api.ItemStackView;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

public record BlockInteractionSnapshot(
        Optional<BlockHitResult> target,
        Optional<BlockFace> hitFace,
        double progress,
        InteractionMode mode,
        Optional<ItemStackView> activeItem,
        Optional<InteractionFailureReason> failureReason,
        int crackStage,
        GameMode gameMode,
        BlockInteractionRouteDecision route,
        Optional<DetailPlacementPreview> detailPreview,
        Optional<com.overlord.assets.ResourceLocation> selectedDetailMaterial,
        OptionalInt availableDetailUnitCount)
        implements BlockInteractionViewModel {
    public BlockInteractionSnapshot {
        target = Objects.requireNonNull(target, "target");
        hitFace = Objects.requireNonNull(hitFace, "hitFace");
        mode = Objects.requireNonNull(mode, "mode");
        activeItem = Objects.requireNonNull(activeItem, "activeItem");
        failureReason = Objects.requireNonNull(failureReason, "failureReason");
        gameMode = Objects.requireNonNull(gameMode, "gameMode");
        route = Objects.requireNonNull(route, "route");
        detailPreview = Objects.requireNonNull(detailPreview, "detailPreview");
        selectedDetailMaterial = Objects.requireNonNull(
                selectedDetailMaterial, "selectedDetailMaterial");
        availableDetailUnitCount = Objects.requireNonNull(
                availableDetailUnitCount, "availableDetailUnitCount");
        if (!Double.isFinite(progress) || progress < 0 || progress > 1) {
            throw new IllegalArgumentException("progress must be finite and within [0, 1]");
        }
        if (crackStage < 0 || crackStage > 9) {
            throw new IllegalArgumentException("crackStage must be within [0, 9]");
        }
        if (target.isPresent() != hitFace.isPresent()) {
            throw new IllegalArgumentException("target and hitFace must be both present or both empty");
        }
        if (target.isPresent()
                && BlockFace.fromHit(target.orElseThrow()) != hitFace.orElseThrow()) {
            throw new IllegalArgumentException("hitFace must match target normal");
        }
        activeItem = activeItem.map(item ->
                new ItemStack(
                        Objects.requireNonNull(item.itemId(), "activeItem.itemId"),
                        item.count()));
    }

    public BlockInteractionSnapshot(
            Optional<BlockHitResult> target,
            Optional<BlockFace> hitFace,
            double progress,
            InteractionMode mode,
            Optional<ItemStackView> activeItem,
            Optional<InteractionFailureReason> failureReason,
            int crackStage,
            GameMode gameMode,
            BlockInteractionRouteDecision route,
            Optional<DetailPlacementPreview> detailPreview) {
        this(target, hitFace, progress, mode, activeItem, failureReason, crackStage,
                gameMode, route, detailPreview, Optional.empty(), OptionalInt.empty());
    }

    public BlockInteractionSnapshot(
            Optional<BlockHitResult> target,
            Optional<BlockFace> hitFace,
            double progress,
            InteractionMode mode,
            Optional<ItemStackView> activeItem,
            Optional<InteractionFailureReason> failureReason,
            int crackStage,
            GameMode gameMode) {
        this(
                target, hitFace, progress, mode, activeItem, failureReason, crackStage, gameMode,
                BlockInteractionRouteDecision.rejected("not_evaluated"), Optional.empty(),
                Optional.empty(), OptionalInt.empty());
    }
}

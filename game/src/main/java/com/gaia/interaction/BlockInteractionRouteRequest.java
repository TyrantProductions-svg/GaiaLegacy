package com.gaia.interaction;

import com.gaia.blocks.ItemCapability;
import com.overlord.assets.ResourceLocation;
import com.overlord.interaction.api.BlockHitResult;
import com.overlord.physics.SpatialQueryResult;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public record BlockInteractionRouteRequest(
        GameMode mode,
        Optional<ResourceLocation> activeItemId,
        Set<ItemCapability> activeItemCapabilities,
        SpatialQueryResult<BlockHitResult> target,
        BlockInteractionIntent intent,
        boolean pickupConsumed) {
    public BlockInteractionRouteRequest {
        Objects.requireNonNull(mode, "mode");
        activeItemId = Objects.requireNonNull(
                activeItemId, "activeItemId");
        activeItemCapabilities = Set.copyOf(
                Objects.requireNonNull(
                        activeItemCapabilities,
                        "activeItemCapabilities"));
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(intent, "intent");
    }
}

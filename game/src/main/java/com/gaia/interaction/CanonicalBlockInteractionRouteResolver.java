package com.gaia.interaction;

import com.gaia.blocks.ItemCapability;
import com.overlord.interaction.api.BlockHitResult;
import com.overlord.physics.DetailRaycastTarget;
import com.overlord.physics.SpatialQueryResult;
import java.util.Objects;

public final class CanonicalBlockInteractionRouteResolver {
    public BlockInteractionRouteDecision resolve(
            BlockInteractionRouteRequest request) {
        Objects.requireNonNull(request, "request");
        if (request.pickupConsumed()) {
            return BlockInteractionRouteDecision.rejected(
                    "pickup_consumed");
        }
        SpatialQueryResult<BlockHitResult> targetQuery = request.target();
        if (targetQuery.status()
                != SpatialQueryResult.Status.AVAILABLE) {
            return BlockInteractionRouteDecision.unavailable(
                    targetQuery.status().name().toLowerCase());
        }
        BlockHitResult hit = targetQuery.result().orElse(null);
        if (hit == null) {
            return BlockInteractionRouteDecision.rejected("no_target");
        }
        BlockInteractionIntent intent = request.intent();
        if (!intent.primaryPressed() && !intent.secondaryPressed()) {
            return BlockInteractionRouteDecision.rejected("no_input_edge");
        }

        boolean precision = request.activeItemCapabilities()
                .contains(ItemCapability.DETAIL_PRECISION);
        if (intent.primaryPressed()) {
            if (precision) {
                return BlockInteractionRouteDecision.routed(
                        BlockInteractionRoute.DETAIL_PRECISION_REMOVE);
            }
            if (hit.target() instanceof DetailRaycastTarget) {
                return BlockInteractionRouteDecision.routed(
                        BlockInteractionRoute.DETAIL_COARSE_REMOVE);
            }
            return BlockInteractionRouteDecision.routed(
                    BlockInteractionRoute.FULL_NORMAL);
        }
        if (precision) {
            return BlockInteractionRouteDecision.routed(
                    BlockInteractionRoute.DETAIL_PRECISION_PLACE);
        }
        if (hit.target() instanceof DetailRaycastTarget) {
            return BlockInteractionRouteDecision.rejected(
                    "detail_secondary_requires_precision");
        }
        return BlockInteractionRouteDecision.routed(
                BlockInteractionRoute.FULL_NORMAL);
    }
}

package com.gaia.interaction;

import java.util.Objects;
import java.util.Optional;

public record BlockInteractionRouteDecision(
        BlockInteractionRoute route,
        Optional<String> reason) {
    public BlockInteractionRouteDecision {
        Objects.requireNonNull(route, "route");
        reason = Objects.requireNonNull(reason, "reason");
    }

    public static BlockInteractionRouteDecision routed(
            BlockInteractionRoute route) {
        if (route == BlockInteractionRoute.REJECTED
                || route == BlockInteractionRoute.UNAVAILABLE) {
            throw new IllegalArgumentException(
                    "failure routes require a bounded reason");
        }
        return new BlockInteractionRouteDecision(route, Optional.empty());
    }

    public static BlockInteractionRouteDecision rejected(String reason) {
        return new BlockInteractionRouteDecision(
                BlockInteractionRoute.REJECTED,
                Optional.of(Objects.requireNonNull(reason, "reason")));
    }

    public static BlockInteractionRouteDecision unavailable(String reason) {
        return new BlockInteractionRouteDecision(
                BlockInteractionRoute.UNAVAILABLE,
                Optional.of(Objects.requireNonNull(reason, "reason")));
    }
}

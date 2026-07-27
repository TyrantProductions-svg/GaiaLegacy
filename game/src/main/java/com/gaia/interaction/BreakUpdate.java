package com.gaia.interaction;

import com.overlord.interaction.api.BlockHitResult;
import java.util.Objects;
import java.util.Optional;

public record BreakUpdate(
        Optional<BlockHitResult> target,
        long chunkRevision,
        GameMode gameMode,
        boolean primaryHeld,
        boolean blocked,
        Optional<BreakRule> rule) {
    public BreakUpdate {
        target = Objects.requireNonNull(target, "target");
        gameMode = Objects.requireNonNull(gameMode, "gameMode");
        rule = Objects.requireNonNull(rule, "rule");
        if (target.isPresent() != rule.isPresent()) {
            throw new IllegalArgumentException(
                    "target and break rule must be both present or both empty");
        }
        if (target.isPresent() && chunkRevision <= 0) {
            throw new IllegalArgumentException(
                    "loaded target requires a positive chunk revision");
        }
        if (target.isEmpty() && chunkRevision != 0) {
            throw new IllegalArgumentException(
                    "missing target requires revision zero");
        }
    }
}

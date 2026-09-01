package com.gaia.interaction;

import com.overlord.assets.ResourceLocation;
import java.util.Objects;
import java.util.Optional;

public record DetailActionDecision(
        boolean allowed,
        DetailRecoveryKind recoveryKind,
        Optional<ResourceLocation> outputItem,
        String reason) {
    public DetailActionDecision {
        Objects.requireNonNull(recoveryKind, "recoveryKind");
        outputItem = Objects.requireNonNull(outputItem, "outputItem");
        Objects.requireNonNull(reason, "reason");
        if (allowed == reason.isEmpty()) {
            // Allowed decisions have an empty reason; rejected decisions do not.
        } else {
            throw new IllegalArgumentException(
                    "allowed decisions require an empty reason");
        }
        if ((recoveryKind == DetailRecoveryKind.NONE) != outputItem.isEmpty()) {
            throw new IllegalArgumentException(
                    "recovery output must match recovery kind");
        }
    }

    public static DetailActionDecision allowedNone() {
        return new DetailActionDecision(
                true, DetailRecoveryKind.NONE, Optional.empty(), "");
    }

    public static DetailActionDecision allowed(
            DetailRecoveryKind kind, ResourceLocation outputItem) {
        if (kind == DetailRecoveryKind.NONE) {
            throw new IllegalArgumentException("NONE recovery has no output item");
        }
        return new DetailActionDecision(
                true, kind, Optional.of(outputItem), "");
    }

    public static DetailActionDecision rejected(String reason) {
        return new DetailActionDecision(
                false,
                DetailRecoveryKind.NONE,
                Optional.empty(),
                Objects.requireNonNull(reason, "reason"));
    }
}

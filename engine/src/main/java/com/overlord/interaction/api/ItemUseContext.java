package com.overlord.interaction.api;

import com.overlord.inventory.api.BodySlot;
import com.overlord.inventory.api.ItemStackView;
import java.util.Objects;
import java.util.Optional;

public record ItemUseContext(
        EntityRef actor,
        BodySlot activeBodySlot,
        Optional<ItemStackView> heldStack,
        Optional<BlockHitResult> raycastResult,
        InteractionAction action,
        long tick,
        long timestampNanos)
        implements InteractionContext {
    public ItemUseContext {
        actor = Objects.requireNonNull(actor, "actor");
        activeBodySlot = Objects.requireNonNull(activeBodySlot, "activeBodySlot");
        heldStack = Objects.requireNonNull(heldStack, "heldStack");
        raycastResult = Objects.requireNonNull(raycastResult, "raycastResult");
        action = Objects.requireNonNull(action, "action");
        if (tick < 0 || timestampNanos < 0) {
            throw new IllegalArgumentException("tick and timestampNanos must be non-negative");
        }
        heldStack.ifPresent(
                stack -> {
                    Objects.requireNonNull(stack.itemId(), "held stack itemId");
                    if (stack.count() <= 0) {
                        throw new IllegalArgumentException("held stack count must be positive");
                    }
                });
    }
}

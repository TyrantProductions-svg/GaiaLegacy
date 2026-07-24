package com.overlord.interaction.api;

import com.overlord.assets.ResourceLocation;
import java.util.Objects;

public record BlockChangeRequest(
        InteractionContext context,
        int x,
        int y,
        int z,
        ResourceLocation expectedBlock,
        ResourceLocation replacementBlock) {
    public BlockChangeRequest {
        context = Objects.requireNonNull(context, "context");
        expectedBlock =
                Objects.requireNonNull(expectedBlock, "expectedBlock");
        replacementBlock =
                Objects.requireNonNull(
                        replacementBlock, "replacementBlock");
    }
}

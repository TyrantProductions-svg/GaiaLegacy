package com.overlord.interaction.api;

import com.overlord.assets.ResourceLocation;
import java.util.Objects;

public record BlockChangedEvent(
        BlockChangeRequest request,
        ResourceLocation previousBlock,
        ResourceLocation currentBlock) {
    public BlockChangedEvent {
        request = Objects.requireNonNull(request, "request");
        previousBlock =
                Objects.requireNonNull(previousBlock, "previousBlock");
        currentBlock =
                Objects.requireNonNull(currentBlock, "currentBlock");
    }
}

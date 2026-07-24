package com.overlord.interaction.api;

import com.overlord.assets.ResourceLocation;
import java.util.Objects;

public record BeforeBlockChangedEvent(
        BlockChangeRequest request, ResourceLocation currentBlock) {
    public BeforeBlockChangedEvent {
        request = Objects.requireNonNull(request, "request");
        currentBlock =
                Objects.requireNonNull(currentBlock, "currentBlock");
    }
}

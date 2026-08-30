package com.overlord.interaction.api;

import com.overlord.assets.ResourceLocation;
import java.util.Objects;

public record FullToDetailRequest(
        InteractionContext context,
        int x,
        int y,
        int z,
        long expectedChunkRevision,
        ResourceLocation expectedFullBlock) {
    public FullToDetailRequest {
        context = Objects.requireNonNull(context, "context");
        requireRevision(expectedChunkRevision);
        expectedFullBlock =
                Objects.requireNonNull(
                        expectedFullBlock, "expectedFullBlock");
    }

    private static void requireRevision(long revision) {
        if (revision <= 0L) {
            throw new IllegalArgumentException(
                    "expectedChunkRevision must be positive");
        }
    }
}

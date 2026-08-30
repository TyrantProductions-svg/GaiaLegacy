package com.overlord.interaction.api;

import com.overlord.assets.ResourceLocation;
import com.overlord.voxel.DetailCellState;
import java.util.Objects;

public record DetailToFullRequest(
        InteractionContext context,
        int x,
        int y,
        int z,
        long expectedChunkRevision,
        DetailCellState expectedState,
        ResourceLocation replacementFullBlock) {
    public DetailToFullRequest {
        context = Objects.requireNonNull(context, "context");
        if (expectedChunkRevision <= 0L) {
            throw new IllegalArgumentException(
                    "expectedChunkRevision must be positive");
        }
        expectedState =
                Objects.requireNonNull(expectedState, "expectedState");
        replacementFullBlock =
                Objects.requireNonNull(
                        replacementFullBlock,
                        "replacementFullBlock");
    }
}

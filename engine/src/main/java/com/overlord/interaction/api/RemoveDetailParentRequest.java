package com.overlord.interaction.api;

import com.overlord.voxel.DetailCellState;
import java.util.Objects;

public record RemoveDetailParentRequest(
        InteractionContext context,
        int x,
        int y,
        int z,
        long expectedChunkRevision,
        DetailCellState expectedState) {
    public RemoveDetailParentRequest {
        context = Objects.requireNonNull(context, "context");
        if (expectedChunkRevision <= 0L) {
            throw new IllegalArgumentException(
                    "expectedChunkRevision must be positive");
        }
        expectedState = Objects.requireNonNull(expectedState, "expectedState");
    }
}

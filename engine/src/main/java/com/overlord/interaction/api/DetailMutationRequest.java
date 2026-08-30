package com.overlord.interaction.api;

import com.overlord.assets.ResourceLocation;
import com.overlord.voxel.LocalSubVoxelPosition;
import com.overlord.voxel.ParentCellState;
import java.util.Objects;
import java.util.Optional;

public record DetailMutationRequest(
        InteractionContext context,
        int x,
        int y,
        int z,
        long expectedChunkRevision,
        ParentCellState expectedState,
        LocalSubVoxelPosition position,
        Optional<ResourceLocation> replacementBlock) {
    public DetailMutationRequest {
        context = Objects.requireNonNull(context, "context");
        if (expectedChunkRevision <= 0L) {
            throw new IllegalArgumentException(
                    "expectedChunkRevision must be positive");
        }
        expectedState =
                Objects.requireNonNull(expectedState, "expectedState");
        position = Objects.requireNonNull(position, "position");
        replacementBlock =
                Objects.requireNonNull(
                        replacementBlock, "replacementBlock");
    }
}

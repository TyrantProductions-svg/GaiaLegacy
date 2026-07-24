package com.overlord.interaction.api;

import com.overlord.assets.ResourceLocation;
import com.overlord.voxel.ChunkKey;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public record BlockChangeResult(
        BlockChangeRequest request,
        Status status,
        Optional<ResourceLocation> observedBlock,
        Set<ChunkKey> dirtyChunks) {
    public BlockChangeResult {
        request = Objects.requireNonNull(request, "request");
        status = Objects.requireNonNull(status, "status");
        observedBlock =
                Objects.requireNonNull(observedBlock, "observedBlock");
        dirtyChunks = Set.copyOf(dirtyChunks);
        if (status == Status.APPLIED && dirtyChunks.isEmpty()) {
            throw new IllegalArgumentException(
                    "applied result requires dirty chunks");
        }
        if (status != Status.APPLIED && !dirtyChunks.isEmpty()) {
            throw new IllegalArgumentException(
                    "rejected result cannot contain dirty chunks");
        }
    }

    public enum Status {
        APPLIED,
        NO_CHANGE,
        CANCELLED,
        CONFLICT,
        OUT_OF_BOUNDS,
        UNKNOWN_BLOCK
    }
}

package com.overlord.interaction.api;

import com.overlord.voxel.ChunkKey;
import com.overlord.voxel.DirtyChunkRevision;
import com.overlord.voxel.ParentCellState;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public record DetailMutationResult(
        InteractionContext context,
        Status status,
        Optional<ParentCellState> oldState,
        Optional<ParentCellState> newState,
        long observedChunkRevision,
        long resultingChunkRevision,
        List<DirtyChunkRevision> dirtiedChunks) {
    public enum Status {
        APPLIED,
        NO_CHANGE,
        OUT_OF_BOUNDS,
        UNKNOWN_CHUNK,
        FAILED_CHUNK,
        STALE_CHUNK_REVISION,
        REPRESENTATION_CONFLICT,
        EXPECTED_STATE_CONFLICT,
        INVALID_BLOCK_ID,
        INVALID_COMPACTION,
        CAPACITY_EXCEEDED,
        UNLOAD_FINALIZED,
        UNKNOWN_MATERIAL
    }

    public DetailMutationResult {
        context = Objects.requireNonNull(context, "context");
        status = Objects.requireNonNull(status, "status");
        oldState = Objects.requireNonNull(oldState, "oldState");
        newState = Objects.requireNonNull(newState, "newState");
        dirtiedChunks = List.copyOf(dirtiedChunks);
        if (status == Status.APPLIED && dirtiedChunks.isEmpty()) {
            throw new IllegalArgumentException(
                    "APPLIED requires dirty revisions");
        }
        if (status != Status.APPLIED && !dirtiedChunks.isEmpty()) {
            throw new IllegalArgumentException(
                    status + " requires no dirty revisions");
        }
    }

    public Map<ChunkKey, Long> dirtyRevisions() {
        Map<ChunkKey, Long> revisions = new LinkedHashMap<>();
        for (DirtyChunkRevision dirty : dirtiedChunks) {
            revisions.put(dirty.key(), dirty.revision());
        }
        return Collections.unmodifiableMap(revisions);
    }

    public Set<ChunkKey> dirtyChunks() {
        return dirtyRevisions().keySet();
    }
}

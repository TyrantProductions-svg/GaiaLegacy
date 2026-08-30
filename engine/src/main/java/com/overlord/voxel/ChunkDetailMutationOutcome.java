package com.overlord.voxel;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public record ChunkDetailMutationOutcome(
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
        UNLOAD_FINALIZED
    }

    public ChunkDetailMutationOutcome {
        status = Objects.requireNonNull(status, "status");
        oldState = Objects.requireNonNull(oldState, "oldState");
        newState = Objects.requireNonNull(newState, "newState");
        dirtiedChunks = List.copyOf(dirtiedChunks);
        if (observedChunkRevision < 0L
                || resultingChunkRevision < 0L) {
            throw new IllegalArgumentException(
                    "Chunk revisions must be nonnegative");
        }
        if (status == Status.APPLIED) {
            if (oldState.isEmpty()
                    || newState.isEmpty()
                    || resultingChunkRevision <= observedChunkRevision
                    || dirtiedChunks.isEmpty()) {
                throw new IllegalArgumentException(
                        "APPLIED requires states, a newer revision, and dirty revisions");
            }
        } else if (!dirtiedChunks.isEmpty()) {
            throw new IllegalArgumentException(
                    status + " requires no dirty revisions");
        }
        Map<ChunkKey, Long> revisions = new LinkedHashMap<>();
        for (DirtyChunkRevision dirty : dirtiedChunks) {
            if (revisions.put(dirty.key(), dirty.revision()) != null) {
                throw new IllegalArgumentException(
                        "dirtiedChunks must not contain duplicate keys");
            }
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

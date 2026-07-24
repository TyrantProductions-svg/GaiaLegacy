package com.overlord.voxel;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public record ChunkMutationOutcome(
        Status status,
        byte observedBlock,
        List<DirtyChunkRevision> dirtiedChunks) {

    public enum Status {
        APPLIED,
        NO_CHANGE,
        CONFLICT,
        OUT_OF_BOUNDS
    }

    public ChunkMutationOutcome {
        status = Objects.requireNonNull(status, "status");
        dirtiedChunks = List.copyOf(dirtiedChunks);

        Set<ChunkKey> keys = new HashSet<>();
        for (DirtyChunkRevision dirty : dirtiedChunks) {
            if (!keys.add(dirty.key())) {
                throw new IllegalArgumentException(
                        "dirtiedChunks must not contain duplicate keys");
            }
        }
        if (status == Status.APPLIED && dirtiedChunks.isEmpty()) {
            throw new IllegalArgumentException(
                    "APPLIED requires at least one dirty revision");
        }
        if (status != Status.APPLIED && !dirtiedChunks.isEmpty()) {
            throw new IllegalArgumentException(
                    status + " requires an empty dirty revision list");
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

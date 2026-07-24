package com.overlord.interaction.api;

import com.overlord.assets.ResourceLocation;
import com.overlord.voxel.ChunkKey;
import com.overlord.voxel.DirtyChunkRevision;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public record BlockChangeResult(
        BlockChangeRequest request,
        Status status,
        Optional<ResourceLocation> observedBlock,
        List<DirtyChunkRevision> dirtiedChunks) {
    public BlockChangeResult {
        request = Objects.requireNonNull(request, "request");
        status = Objects.requireNonNull(status, "status");
        observedBlock =
                Objects.requireNonNull(observedBlock, "observedBlock");
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
                    "applied result requires dirty revisions");
        }
        if (status != Status.APPLIED && !dirtiedChunks.isEmpty()) {
            throw new IllegalArgumentException(
                    "rejected result cannot contain dirty revisions");
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

    public enum Status {
        APPLIED,
        NO_CHANGE,
        CANCELLED,
        CONFLICT,
        OUT_OF_BOUNDS,
        UNKNOWN_BLOCK
    }
}

package com.overlord.interaction.api;

import com.overlord.voxel.ChunkKey;
import com.overlord.voxel.DirtyChunkRevision;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public record ChunkDirtyEvent(
        BlockChangeRequest request,
        List<DirtyChunkRevision> dirtiedChunks) {
    public ChunkDirtyEvent {
        request = Objects.requireNonNull(request, "request");
        dirtiedChunks = List.copyOf(dirtiedChunks);

        Set<ChunkKey> keys = new HashSet<>();
        for (DirtyChunkRevision dirty : dirtiedChunks) {
            if (!keys.add(dirty.key())) {
                throw new IllegalArgumentException(
                        "dirtiedChunks must not contain duplicate keys");
            }
        }
        if (dirtiedChunks.isEmpty()) {
            throw new IllegalArgumentException(
                    "dirty event requires at least one dirty revision");
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

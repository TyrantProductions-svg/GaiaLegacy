package com.overlord.worlditem.api;

import com.overlord.voxel.ChunkCoordinatePolicy;
import com.overlord.voxel.ChunkKey;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Immutable canonical WorldItems persisted with one streamed Chunk. */
public record WorldItemHibernatePayload(
        ChunkKey chunkKey,
        List<WorldItemRestoreEntry> entries,
        long nextItemId,
        boolean itemIdsExhausted) {
    public WorldItemHibernatePayload {
        chunkKey = ChunkCoordinatePolicy.requireSafe(chunkKey);
        Objects.requireNonNull(entries, "entries");
        ArrayList<WorldItemRestoreEntry> sorted = new ArrayList<>(entries.size());
        for (WorldItemRestoreEntry entry : entries) {
            sorted.add(Objects.requireNonNull(entry, "entry"));
        }
        sorted.sort(Comparator.comparingLong(entry -> entry.runtime().item().id().value()));
        entries = List.copyOf(sorted);
        if (nextItemId < 0) {
            throw new IllegalArgumentException("nextItemId must be non-negative");
        }
    }
}

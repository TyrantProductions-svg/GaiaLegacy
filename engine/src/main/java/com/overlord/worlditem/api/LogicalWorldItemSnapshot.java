package com.overlord.worlditem.api;

import com.overlord.voxel.ChunkCoordinatePolicy;
import com.overlord.voxel.ChunkKey;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Canonical live logical world items and the stable-ID allocator high-water state. */
public record LogicalWorldItemSnapshot(
        List<WorldItemRestoreEntry> entries,
        long nextItemId,
        boolean itemIdsExhausted,
        Map<WorldItemId, ChunkKey> dormantChunkKeys,
        Completeness completeness) {
    public LogicalWorldItemSnapshot(
            List<WorldItemRestoreEntry> entries,
            long nextItemId,
            boolean itemIdsExhausted) {
        this(entries, nextItemId, itemIdsExhausted, Map.of(), Completeness.LEGACY_COMPLETE);
    }

    public LogicalWorldItemSnapshot(
            List<WorldItemRestoreEntry> entries,
            long nextItemId,
            boolean itemIdsExhausted,
            Map<WorldItemId, ChunkKey> dormantChunkKeys) {
        this(
                entries,
                nextItemId,
                itemIdsExhausted,
                dormantChunkKeys,
                dormantChunkKeys.isEmpty()
                        ? Completeness.LEGACY_COMPLETE
                        : Completeness.PAGED_PARTIAL);
    }

    public LogicalWorldItemSnapshot {
        Objects.requireNonNull(entries, "entries");
        ArrayList<WorldItemRestoreEntry> sorted = new ArrayList<>(entries);
        sorted.sort(Comparator.comparingLong(entry -> entry.runtime().item().id().value()));
        entries = List.copyOf(sorted);
        Objects.requireNonNull(dormantChunkKeys, "dormantChunkKeys");
        completeness = Objects.requireNonNull(completeness, "completeness");
        LinkedHashMap<WorldItemId, ChunkKey> sortedDormant = new LinkedHashMap<>();
        dormantChunkKeys.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(
                        Comparator.comparingLong(WorldItemId::value)))
                .forEach(entry -> sortedDormant.put(
                        Objects.requireNonNull(entry.getKey(), "dormant item id"),
                        ChunkCoordinatePolicy.requireSafe(entry.getValue())));
        dormantChunkKeys = Map.copyOf(sortedDormant);
    }

    public enum Completeness {
        LEGACY_COMPLETE,
        PAGED_PARTIAL
    }
}

package com.overlord.worlditem.api;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Canonical live logical world items and the stable-ID allocator high-water state. */
public record LogicalWorldItemSnapshot(
        List<WorldItemRestoreEntry> entries,
        long nextItemId,
        boolean itemIdsExhausted) {
    public LogicalWorldItemSnapshot {
        Objects.requireNonNull(entries, "entries");
        ArrayList<WorldItemRestoreEntry> sorted = new ArrayList<>(entries);
        sorted.sort(Comparator.comparingLong(entry -> entry.runtime().item().id().value()));
        entries = List.copyOf(sorted);
    }
}

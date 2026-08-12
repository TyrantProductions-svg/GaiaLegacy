package com.gaia.shell.world;

import com.gaia.save.format.SaveGameId;
import com.gaia.shell.save.SaveSummary;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Immutable one-page view of the current save catalog. */
public record WorldSlotsSnapshot(
        List<SaveSummary> rows,
        int pageIndex,
        int pageCount,
        Optional<SaveGameId> selectedId,
        boolean hasPreviousPage,
        boolean hasNextPage) {
    public WorldSlotsSnapshot {
        rows = List.copyOf(Objects.requireNonNull(rows, "rows"));
        selectedId = Objects.requireNonNull(selectedId, "selectedId");
        if (pageCount < 1 || pageIndex < 0 || pageIndex >= pageCount) {
            throw new IllegalArgumentException("page index must be inside page count");
        }
    }
}

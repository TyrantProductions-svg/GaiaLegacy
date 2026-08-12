package com.gaia.shell.world;

import com.gaia.save.format.SaveGameId;
import com.gaia.shell.ScreenCommand;
import com.gaia.shell.save.SaveCatalog;
import com.gaia.shell.save.SaveSummary;
import java.util.Comparator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;

/** Owner-thread paging and stable-selection authority for immutable save rows. */
public final class WorldSlotsController {
    private static final Comparator<SaveSummary> ORDER = Comparator
            .comparing(SaveSummary::modifiedTime)
            .reversed()
            .thenComparing(row -> row.id().value());

    private final SaveCatalog catalog;
    private final int pageSize;
    private List<SaveSummary> rows = List.of();
    private int pageIndex;
    private SaveGameId selectedId;

    public WorldSlotsController(SaveCatalog catalog, int pageSize) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        if (pageSize < 1) {
            throw new IllegalArgumentException("pageSize must be positive");
        }
        this.pageSize = pageSize;
        refresh();
    }

    public void refresh() {
        rows = catalog.summaries().stream().sorted(ORDER).toList();
        if (selectedId != null && rows.stream().noneMatch(row -> row.id().equals(selectedId))) {
            selectedId = null;
        }
        pageIndex = Math.min(pageIndex, pageCount() - 1);
    }

    public WorldSlotsSnapshot snapshot() {
        int from = Math.min(pageIndex * pageSize, rows.size());
        int to = Math.min(from + pageSize, rows.size());
        int pages = pageCount();
        return new WorldSlotsSnapshot(
                rows.subList(from, to),
                pageIndex,
                pages,
                Optional.ofNullable(selectedId),
                pageIndex > 0,
                pageIndex + 1 < pages);
    }

    public void nextPage() {
        pageIndex = Math.min(pageIndex + 1, pageCount() - 1);
    }

    public void previousPage() {
        pageIndex = Math.max(0, pageIndex - 1);
    }

    public void select(SaveGameId saveGameId) {
        SaveGameId requested = Objects.requireNonNull(saveGameId, "saveGameId");
        requireRow(requested);
        selectedId = requested;
    }

    public boolean hasRows() {
        return !rows.isEmpty();
    }

    public Optional<ScreenCommand> primaryCommand(SaveGameId saveGameId) {
        SaveSummary row = requireRow(saveGameId);
        return switch (row.health()) {
            case VALID -> Optional.of(new ScreenCommand.LoadWorld(row.id()));
            case RECOVERABLE_BACKUP -> Optional.of(new ScreenCommand.RecoverBackup(row.id()));
            case CORRUPT, UNSUPPORTED_VERSION -> Optional.empty();
        };
    }

    public ScreenCommand deleteCommand(SaveGameId saveGameId) {
        return new ScreenCommand.DeleteWorld(requireRow(saveGameId).id());
    }

    private SaveSummary requireRow(SaveGameId saveGameId) {
        Objects.requireNonNull(saveGameId, "saveGameId");
        return rows.stream()
                .filter(row -> row.id().equals(saveGameId))
                .findFirst()
                .orElseThrow(() -> new NoSuchElementException("unknown save-game ID"));
    }

    private int pageCount() {
        return Math.max(1, (rows.size() + pageSize - 1) / pageSize);
    }
}

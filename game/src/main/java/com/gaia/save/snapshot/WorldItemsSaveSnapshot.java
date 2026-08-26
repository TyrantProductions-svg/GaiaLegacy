package com.gaia.save.snapshot;

import com.gaia.save.format.SaveMetadataValidation;
import com.overlord.worlditem.api.LogicalWorldItemSnapshot;
import com.overlord.worlditem.api.WorldItemId;
import com.overlord.worlditem.api.WorldItemRestoreEntry;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Immutable save-owned logical world items captured at one fixed tick. */
public record WorldItemsSaveSnapshot(
        long fixedTick,
        List<WorldItemRestoreEntry> entries,
        long nextItemId,
        boolean itemIdsExhausted,
        LogicalWorldItemSnapshot.Completeness completeness) {
    public WorldItemsSaveSnapshot(
            long fixedTick,
            List<WorldItemRestoreEntry> entries,
            long nextItemId,
            boolean itemIdsExhausted) {
        this(
                fixedTick,
                entries,
                nextItemId,
                itemIdsExhausted,
                LogicalWorldItemSnapshot.Completeness.LEGACY_COMPLETE);
    }

    public WorldItemsSaveSnapshot {
        fixedTick = SaveMetadataValidation.requireNonnegativeFixedTick(fixedTick);
        completeness = Objects.requireNonNull(completeness, "completeness");
        LogicalWorldItemSnapshot detached = new LogicalWorldItemSnapshot(
                Objects.requireNonNull(entries, "entries"),
                nextItemId,
                itemIdsExhausted,
                Map.of(),
                completeness);
        entries = detached.entries();

        if (nextItemId < 0) {
            throw new IllegalArgumentException("nextItemId must be nonnegative");
        }
        if (itemIdsExhausted && nextItemId != Long.MAX_VALUE) {
            throw new IllegalArgumentException(
                    "an exhausted world-item allocator must be at Long.MAX_VALUE");
        }

        Set<WorldItemId> itemIds = new HashSet<>();
        for (WorldItemRestoreEntry entry : entries) {
            long spawnTick = entry.runtime().spawnTick();
            if (spawnTick > fixedTick) {
                throw new IllegalArgumentException(
                        "world-item spawnTick must not exceed the saved fixedTick");
            }
            WorldItemId itemId = entry.runtime().item().id();
            if (!itemIds.add(itemId)) {
                throw new IllegalArgumentException("world-item IDs must be unique");
            }
            if (!itemIdsExhausted && itemId.value() >= nextItemId) {
                throw new IllegalArgumentException(
                        "world-item ID must be below the allocator high-water value");
            }
        }
    }

    public WorldItemsSaveSnapshot(
            long fixedTick, LogicalWorldItemSnapshot snapshot) {
        this(
                fixedTick,
                requireSnapshot(snapshot).entries(),
                snapshot.nextItemId(),
                snapshot.itemIdsExhausted(),
                snapshot.completeness());
    }

    public LogicalWorldItemSnapshot logicalSnapshot() {
        return new LogicalWorldItemSnapshot(
                entries, nextItemId, itemIdsExhausted, Map.of(), completeness);
    }

    private static LogicalWorldItemSnapshot requireSnapshot(
            LogicalWorldItemSnapshot snapshot) {
        return Objects.requireNonNull(snapshot, "snapshot");
    }
}

package com.overlord.worlditem;

import com.overlord.worlditem.api.WorldItemId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;

/** Bounded deterministic index ordered by absolute expiry then stable ID. */
final class WorldItemExpiryIndex {
    private static final Comparator<Entry> ORDER = Comparator
            .comparingLong(Entry::expiresAtWorldTick)
            .thenComparingLong(entry -> entry.id().value());

    private final int capacity;
    private final TreeSet<Entry> ordered = new TreeSet<>(ORDER);
    private final Map<WorldItemId, Entry> byId = new HashMap<>();

    WorldItemExpiryIndex(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
    }

    void put(WorldItemId id, long expiresAtWorldTick) {
        Objects.requireNonNull(id, "id");
        if (expiresAtWorldTick < 0L) {
            throw new IllegalArgumentException("expiry must be non-negative");
        }
        Entry previous = byId.remove(id);
        if (previous != null) {
            ordered.remove(previous);
        } else if (byId.size() >= capacity) {
            throw new IllegalStateException("WorldItem expiry index is full");
        }
        Entry next = new Entry(expiresAtWorldTick, id);
        byId.put(id, next);
        ordered.add(next);
    }

    void remove(WorldItemId id) {
        Entry removed = byId.remove(Objects.requireNonNull(id, "id"));
        if (removed != null) {
            ordered.remove(removed);
        }
    }

    List<WorldItemId> drainDue(long worldTick) {
        List<WorldItemId> due = new ArrayList<>();
        while (!ordered.isEmpty()
                && ordered.first().expiresAtWorldTick() <= worldTick) {
            Entry entry = ordered.pollFirst();
            byId.remove(entry.id());
            due.add(entry.id());
        }
        return List.copyOf(due);
    }

    int size() {
        return byId.size();
    }

    boolean contains(WorldItemId id) {
        return byId.containsKey(id);
    }

    void clear() {
        ordered.clear();
        byId.clear();
    }

    private record Entry(long expiresAtWorldTick, WorldItemId id) {
    }
}

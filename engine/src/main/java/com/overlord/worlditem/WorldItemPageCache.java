package com.overlord.worlditem;

import com.overlord.voxel.ChunkCoordinatePolicy;
import com.overlord.voxel.ChunkKey;
import com.overlord.worlditem.api.WorldItemPageCachePolicy;
import com.overlord.worlditem.api.WorldItemPageDescriptor;
import com.overlord.worlditem.api.WorldItemPageSnapshot;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Bounded decoded-page cache; semantic ownership remains in the logical service. */
public final class WorldItemPageCache {
    public enum Admission {
        ADMITTED,
        ALL_PINNED,
        CAPACITY_EXCEEDED
    }

    public record Metrics(
            int decodedPageCount,
            long decodedPageBytes,
            int pinnedPageCount,
            int unprovedPinnedPageCount,
            int dirtyEntryCount,
            long dirtyBytes) {
    }

    public record DirtyCandidate(
            WorldItemPageDescriptor descriptor,
            WorldItemPageSnapshot page,
            long encodedBytes,
            long accessOrder) {
        public DirtyCandidate {
            Objects.requireNonNull(descriptor, "descriptor");
            Objects.requireNonNull(page, "page");
        }
    }

    private final WorldItemPageCachePolicy policy;
    private final Map<ChunkKey, Entry> entries = new LinkedHashMap<>();
    private long bytes;

    public WorldItemPageCache(WorldItemPageCachePolicy policy) {
        this.policy = Objects.requireNonNull(policy, "policy");
    }

    public Admission admitClean(
            WorldItemPageDescriptor descriptor,
            WorldItemPageSnapshot page,
            long encodedBytes,
            long accessOrder) {
        return admit(descriptor, page, encodedBytes, accessOrder, false, false);
    }

    public Admission admitDirty(
            WorldItemPageDescriptor descriptor,
            WorldItemPageSnapshot page,
            long encodedBytes,
            long accessOrder) {
        return admit(descriptor, page, encodedBytes, accessOrder, true, true);
    }

    public Admission admitUnproved(
            WorldItemPageDescriptor descriptor,
            WorldItemPageSnapshot page,
            long encodedBytes,
            long accessOrder) {
        return admit(descriptor, page, encodedBytes, accessOrder, true, true);
    }

    Admission previewClean(
            WorldItemPageDescriptor descriptor,
            WorldItemPageSnapshot page,
            long encodedBytes,
            long accessOrder) {
        return preview(descriptor, page, encodedBytes, accessOrder, false, false);
    }

    Admission previewUnproved(
            WorldItemPageDescriptor descriptor,
            WorldItemPageSnapshot page,
            long encodedBytes,
            long accessOrder) {
        return preview(descriptor, page, encodedBytes, accessOrder, true, true);
    }

    private Admission preview(
            WorldItemPageDescriptor descriptor,
            WorldItemPageSnapshot page,
            long encodedBytes,
            long accessOrder,
            boolean pinned,
            boolean dirty) {
        Snapshot before = snapshot();
        try {
            return admit(
                    descriptor, page, encodedBytes, accessOrder, pinned, dirty);
        } finally {
            restore(before);
        }
    }

    public Admission admitDirtyBatch(List<DirtyCandidate> candidates) {
        Objects.requireNonNull(candidates, "candidates");
        Map<ChunkKey, Entry> before = new LinkedHashMap<>(entries);
        long beforeBytes = bytes;
        for (DirtyCandidate candidate : candidates) {
            DirtyCandidate checked = Objects.requireNonNull(candidate, "candidate");
            Admission admission = admitDirty(
                    checked.descriptor(), checked.page(), checked.encodedBytes(),
                    checked.accessOrder());
            if (admission != Admission.ADMITTED) {
                entries.clear();
                entries.putAll(before);
                bytes = beforeBytes;
                return admission;
            }
        }
        return Admission.ADMITTED;
    }

    private Admission admit(
            WorldItemPageDescriptor descriptor,
            WorldItemPageSnapshot page,
            long encodedBytes,
            long accessOrder,
            boolean pinned,
            boolean dirty) {
        Objects.requireNonNull(descriptor, "descriptor");
        Objects.requireNonNull(page, "page");
        if (!descriptor.chunkKey().equals(page.chunkKey())
                || descriptor.pageRevision() != page.pageRevision()
                || encodedBytes < 0L
                || encodedBytes > policy.maxDecodedPageBytes()) {
            return Admission.CAPACITY_EXCEEDED;
        }
        Entry previous = entries.get(descriptor.chunkKey());
        int currentDirtyEntries = entries.values().stream()
                .filter(entry -> entry.dirty)
                .mapToInt(entry -> entry.page.entries().size()).sum();
        long currentDirtyBytes = entries.values().stream()
                .filter(entry -> entry.dirty)
                .mapToLong(entry -> entry.encodedBytes).sum();
        int candidateDirtyEntries = Math.addExact(
                currentDirtyEntries
                        - (previous != null && previous.dirty
                                ? previous.page.entries().size() : 0),
                dirty ? page.entries().size() : 0);
        long candidateDirtyBytes = Math.addExact(
                currentDirtyBytes
                        - (previous != null && previous.dirty
                                ? previous.encodedBytes : 0L),
                dirty ? encodedBytes : 0L);
        if (candidateDirtyEntries > policy.maxDirtyEntries()
                || candidateDirtyBytes > policy.maxDirtyCandidateBytes()) {
            return Admission.CAPACITY_EXCEEDED;
        }
        long candidateBytes = Math.addExact(
                bytes - (previous == null ? 0L : previous.encodedBytes),
                encodedBytes);
        int candidatePages = entries.size() + (previous == null ? 1 : 0);
        List<ChunkKey> evictions = new ArrayList<>();
        while (candidatePages > policy.maxDecodedPages()
                || candidateBytes > policy.maxDecodedPageBytes()) {
            Entry victim = entries.values().stream()
                    .filter(entry -> !entry.pinned
                            && !evictions.contains(entry.descriptor.chunkKey())
                            && (previous == null
                                    || !entry.descriptor.chunkKey().equals(
                                            previous.descriptor.chunkKey())))
                    .min(Comparator
                            .comparingLong((Entry entry) -> entry.accessOrder)
                            .thenComparing(
                                    entry -> entry.descriptor.chunkKey(),
                                    ChunkCoordinatePolicy.canonicalComparator()))
                    .orElse(null);
            if (victim == null) {
                return Admission.ALL_PINNED;
            }
            evictions.add(victim.descriptor.chunkKey());
            candidatePages--;
            candidateBytes -= victim.encodedBytes;
        }
        for (ChunkKey key : evictions) {
            Entry removed = entries.remove(key);
            bytes -= removed.encodedBytes;
        }
        if (previous != null) {
            entries.remove(previous.descriptor.chunkKey());
            bytes -= previous.encodedBytes;
        }
        entries.put(descriptor.chunkKey(), new Entry(
                descriptor, page, encodedBytes, accessOrder, pinned, dirty));
        bytes += encodedBytes;
        return Admission.ADMITTED;
    }

    public void pin(ChunkKey key) {
        Entry entry = entries.get(ChunkCoordinatePolicy.requireSafe(key));
        if (entry != null) {
            entry.pinned = true;
        }
    }

    public void unpin(ChunkKey key) {
        Entry entry = entries.get(ChunkCoordinatePolicy.requireSafe(key));
        if (entry != null) {
            entry.pinned = false;
        }
    }

    public boolean isPinned(ChunkKey key) {
        Entry entry = entries.get(ChunkCoordinatePolicy.requireSafe(key));
        return entry != null && entry.pinned;
    }

    public boolean contains(ChunkKey key) {
        return entries.containsKey(ChunkCoordinatePolicy.requireSafe(key));
    }

    public WorldItemPageSnapshot page(ChunkKey key) {
        Entry entry = entries.get(ChunkCoordinatePolicy.requireSafe(key));
        return entry == null ? null : entry.page;
    }

    public void remove(ChunkKey key) {
        Entry removed = entries.remove(ChunkCoordinatePolicy.requireSafe(key));
        if (removed != null) {
            bytes -= removed.encodedBytes;
        }
    }

    public void clear() {
        entries.clear();
        bytes = 0L;
    }

    public List<ChunkKey> cachedChunkKeys() {
        return entries.keySet().stream()
                .sorted(ChunkCoordinatePolicy.canonicalComparator())
                .toList();
    }

    public Metrics metrics() {
        return new Metrics(
                entries.size(),
                bytes,
                (int) entries.values().stream().filter(entry -> entry.pinned).count(),
                (int) entries.values().stream()
                        .filter(entry -> entry.pinned && entry.dirty).count(),
                entries.values().stream().filter(entry -> entry.dirty)
                        .mapToInt(entry -> entry.page.entries().size()).sum(),
                entries.values().stream().filter(entry -> entry.dirty)
                        .mapToLong(entry -> entry.encodedBytes).sum());
    }

    Snapshot snapshot() {
        return new Snapshot(copyEntries(entries), bytes);
    }

    void restore(Snapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        entries.clear();
        entries.putAll(copyEntries(snapshot.entries));
        bytes = snapshot.bytes;
    }

    private static Map<ChunkKey, Entry> copyEntries(Map<ChunkKey, Entry> source) {
        Map<ChunkKey, Entry> copy = new LinkedHashMap<>();
        for (Map.Entry<ChunkKey, Entry> entry : source.entrySet()) {
            copy.put(entry.getKey(), entry.getValue().copy());
        }
        return copy;
    }

    static final class Snapshot {
        private final Map<ChunkKey, Entry> entries;
        private final long bytes;

        private Snapshot(Map<ChunkKey, Entry> entries, long bytes) {
            this.entries = entries;
            this.bytes = bytes;
        }
    }

    private static final class Entry {
        private final WorldItemPageDescriptor descriptor;
        private final WorldItemPageSnapshot page;
        private final long encodedBytes;
        private final long accessOrder;
        private final boolean dirty;
        private boolean pinned;

        private Entry(
                WorldItemPageDescriptor descriptor,
                WorldItemPageSnapshot page,
                long encodedBytes,
                long accessOrder,
                boolean pinned,
                boolean dirty) {
            this.descriptor = descriptor;
            this.page = page;
            this.encodedBytes = encodedBytes;
            this.accessOrder = accessOrder;
            this.pinned = pinned;
            this.dirty = dirty;
        }

        private Entry copy() {
            return new Entry(
                    descriptor, page, encodedBytes, accessOrder, pinned, dirty);
        }
    }
}

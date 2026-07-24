package com.overlord.voxel;

import com.overlord.config.GameConfig;
import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

public final class ChunkRepository {
    private final int worldHeight;
    private final ChunkDirtyTracker dirtyTracker;
    private final ConcurrentHashMap<ChunkKey, Entry> entries =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<ChunkKey, Long> unloadedRevisions =
            new ConcurrentHashMap<>();

    public ChunkRepository() {
        this(GameConfig.Chunk.MAX_HEIGHT, new ChunkDirtyTracker());
    }

    public ChunkRepository(
            int worldHeight, ChunkDirtyTracker dirtyTracker) {
        if (worldHeight <= 0) {
            throw new IllegalArgumentException(
                    "worldHeight must be greater than zero");
        }
        this.worldHeight = worldHeight;
        this.dirtyTracker =
                Objects.requireNonNull(dirtyTracker, "dirtyTracker");
    }

    public boolean contains(ChunkKey key) {
        return entries.containsKey(Objects.requireNonNull(key, "key"));
    }

    public Set<ChunkKey> keys() {
        return Set.copyOf(entries.keySet());
    }

    public ChunkState state(ChunkKey key) {
        Entry entry = entries.get(Objects.requireNonNull(key, "key"));
        if (entry == null) {
            return ChunkState.EMPTY;
        }
        synchronized (entry) {
            return entry.state;
        }
    }

    public long revision(ChunkKey key) {
        Entry entry = entries.get(Objects.requireNonNull(key, "key"));
        if (entry == null) {
            return 0;
        }
        synchronized (entry) {
            return entry.revision;
        }
    }

    public byte getBlock(int worldX, int y, int worldZ) {
        if (y < 0 || y >= worldHeight) {
            return 0;
        }
        ChunkKey key = ChunkKey.fromWorld(worldX, worldZ);
        Entry entry = entries.get(key);
        if (entry == null) {
            return 0;
        }
        synchronized (entry) {
            return entry.chunk.getBlock(
                    ChunkKey.localCoordinate(worldX),
                    y,
                    ChunkKey.localCoordinate(worldZ));
        }
    }

    public void generate(
            ChunkKey key, Consumer<Chunk> generator) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(generator, "generator");
        Entry entry =
                entries.computeIfAbsent(
                        key, this::newEntry);
        synchronized (entry) {
            transition(
                    entry,
                    key,
                    ChunkState.EMPTY,
                    ChunkState.GENERATING);
            try {
                generator.accept(entry.chunk);
                entry.revision++;
                entry.state = ChunkState.GENERATED;
            } catch (RuntimeException | Error failure) {
                entry.failure = failure;
                entries.remove(key, entry);
                throw failure;
            }
        }
        for (ChunkKey neighbor : dirtyTracker.horizontalNeighbors(key)) {
            dirtyIfPresent(neighbor);
        }
    }

    public boolean setBlock(
            int worldX, int y, int worldZ, byte blockId) {
        if (y < 0 || y >= worldHeight) {
            return false;
        }

        ChunkKey key = ChunkKey.fromWorld(worldX, worldZ);
        int localX = ChunkKey.localCoordinate(worldX);
        int localZ = ChunkKey.localCoordinate(worldZ);
        while (true) {
            Entry entry = entries.get(key);
            if (entry == null) {
                if (blockId == 0) {
                    return false;
                }
                entry =
                        entries.computeIfAbsent(
                                key, this::newEntry);
            }

            synchronized (entry) {
                if (entries.get(key) != entry) {
                    continue;
                }
                if (entry.state == ChunkState.UNLOADING) {
                    return false;
                }
                if (entry.chunk.getBlock(localX, y, localZ) == blockId) {
                    return false;
                }
                entry.chunk.setBlock(localX, y, localZ, blockId);
                entry.revision++;
                entry.failure = null;
                entry.state = ChunkState.DIRTY;
            }
            for (ChunkKey affected :
                    dirtyTracker.affectedByBlock(key, localX, localZ)) {
                if (!affected.equals(key)) {
                    dirtyIfPresent(affected);
                }
            }
            return true;
        }
    }

    public Optional<ChunkSnapshot> snapshot(ChunkKey key) {
        Entry entry = entries.get(Objects.requireNonNull(key, "key"));
        if (entry == null) {
            return Optional.empty();
        }
        synchronized (entry) {
            if (entries.get(key) != entry
                    || entry.state == ChunkState.UNLOADING) {
                return Optional.empty();
            }
            byte[] blocks =
                    new byte[
                            Math.multiplyExact(
                                    Math.multiplyExact(
                                            GameConfig.Chunk.SIZE,
                                            worldHeight),
                                    GameConfig.Chunk.SIZE)];
            entry.chunk.copyBlocksTo(blocks);
            return Optional.of(
                    ChunkSnapshot.of(
                            key, entry.revision, worldHeight, blocks));
        }
    }

    public Set<ChunkKey> meshingCandidates() {
        Set<ChunkKey> candidates = new HashSet<>();
        for (var entryByKey : entries.entrySet()) {
            ChunkKey key = entryByKey.getKey();
            Entry entry = entryByKey.getValue();
            synchronized (entry) {
                if (entries.get(key) == entry
                        && isMeshingCandidate(entry)) {
                    candidates.add(key);
                }
            }
        }
        return Set.copyOf(candidates);
    }

    public Optional<ChunkMeshInput> claimMeshing(ChunkKey key) {
        Objects.requireNonNull(key, "key");
        Entry entry = entries.get(key);
        if (entry == null) {
            return Optional.empty();
        }

        long claimedRevision;
        synchronized (entry) {
            if (entries.get(key) != entry
                    || !isMeshingCandidate(entry)) {
                return Optional.empty();
            }
            claimedRevision = entry.revision;
            entry.state = ChunkState.MESHING;
        }

        Optional<ChunkSnapshot> center = snapshot(key);
        ChunkSnapshot north =
                snapshot(key.north())
                        .orElseGet(
                                () ->
                                        ChunkSnapshot.empty(
                                                key.north(),
                                                0,
                                                worldHeight));
        ChunkSnapshot south =
                snapshot(key.south())
                        .orElseGet(
                                () ->
                                        ChunkSnapshot.empty(
                                                key.south(),
                                                0,
                                                worldHeight));
        ChunkSnapshot west =
                snapshot(key.west())
                        .orElseGet(
                                () ->
                                        ChunkSnapshot.empty(
                                                key.west(),
                                                0,
                                                worldHeight));
        ChunkSnapshot east =
                snapshot(key.east())
                        .orElseGet(
                                () ->
                                        ChunkSnapshot.empty(
                                                key.east(),
                                                0,
                                                worldHeight));

        synchronized (entry) {
            if (entries.get(key) != entry) {
                return Optional.empty();
            }
            if (entry.state != ChunkState.MESHING
                    || entry.revision != claimedRevision
                    || center.isEmpty()
                    || center.orElseThrow().revision()
                            != claimedRevision) {
                if (entry.state == ChunkState.MESHING
                        && entry.revision == claimedRevision) {
                    entry.state = ChunkState.DIRTY;
                }
                return Optional.empty();
            }
            return Optional.of(
                    new ChunkMeshInput(
                            center.orElseThrow(),
                            north,
                            south,
                            west,
                            east));
        }
    }

    public boolean markReadyForUpload(
            ChunkKey key, long revision) {
        Objects.requireNonNull(key, "key");
        Entry entry = entries.get(key);
        if (entry == null) {
            return false;
        }
        synchronized (entry) {
            if (entries.get(key) != entry) {
                return false;
            }
            if (entry.state != ChunkState.MESHING
                    || entry.revision != revision) {
                return false;
            }
            entry.state = ChunkState.READY_FOR_UPLOAD;
            return true;
        }
    }

    public boolean markRenderable(ChunkKey key, long revision) {
        Objects.requireNonNull(key, "key");
        Entry entry = entries.get(key);
        if (entry == null) {
            return false;
        }
        synchronized (entry) {
            if (entries.get(key) != entry
                    || entry.state != ChunkState.READY_FOR_UPLOAD
                    || entry.revision != revision) {
                return false;
            }
            entry.failure = null;
            entry.state = ChunkState.RENDERABLE;
            return true;
        }
    }

    public boolean beginUnload(ChunkKey key) {
        Objects.requireNonNull(key, "key");
        Entry entry = entries.get(key);
        if (entry == null) {
            return false;
        }
        synchronized (entry) {
            if (entries.get(key) != entry
                    || entry.state == ChunkState.UNLOADING) {
                return false;
            }
            entry.failure = null;
            entry.state = ChunkState.UNLOADING;
        }
        for (ChunkKey neighbor : dirtyTracker.horizontalNeighbors(key)) {
            dirtyIfPresent(neighbor);
        }
        return true;
    }

    public boolean completeUnload(ChunkKey key) {
        Objects.requireNonNull(key, "key");
        Entry entry = entries.get(key);
        if (entry == null) {
            return false;
        }
        synchronized (entry) {
            if (entries.get(key) != entry
                    || entry.state != ChunkState.UNLOADING) {
                return false;
            }
            unloadedRevisions.merge(
                    key, entry.revision, Math::max);
            return entries.remove(key, entry);
        }
    }

    public void markMeshingFailure(
            ChunkKey key, long revision, Throwable failure) {
        markMeshingFailureIfCurrent(key, revision, failure);
    }

    boolean markMeshingFailureIfCurrent(
            ChunkKey key, long revision, Throwable failure) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(failure, "failure");
        Entry entry = entries.get(key);
        if (entry == null) {
            return false;
        }
        synchronized (entry) {
            if (entries.get(key) != entry) {
                return false;
            }
            if (entry.state == ChunkState.MESHING
                    && entry.revision == revision) {
                entry.failure = failure;
                entry.state = ChunkState.DIRTY;
                return true;
            }
            return false;
        }
    }

    public void retry(ChunkKey key) {
        Objects.requireNonNull(key, "key");
        Entry entry = entries.get(key);
        if (entry == null) {
            return;
        }
        synchronized (entry) {
            if (entries.get(key) == entry
                    && entry.state == ChunkState.DIRTY) {
                entry.failure = null;
            }
        }
    }

    public boolean isRenderable(ChunkKey key) {
        return state(key) == ChunkState.RENDERABLE;
    }

    private static boolean isMeshingCandidate(Entry entry) {
        return entry.state == ChunkState.GENERATED
                || (entry.state == ChunkState.DIRTY
                        && entry.failure == null);
    }

    private void dirtyIfPresent(ChunkKey key) {
        Entry entry = entries.get(key);
        if (entry == null) {
            return;
        }
        synchronized (entry) {
            if (entries.get(key) != entry
                    || entry.state == ChunkState.UNLOADING
                    || entry.state == ChunkState.EMPTY) {
                return;
            }
            entry.revision++;
            entry.failure = null;
            if (entry.state != ChunkState.GENERATING) {
                entry.state = ChunkState.DIRTY;
            }
        }
    }

    private static void transition(
            Entry entry,
            ChunkKey key,
            ChunkState expected,
            ChunkState requested) {
        if (entry.state != expected) {
            throw new IllegalStateException(
                    "Chunk "
                            + key
                            + " cannot transition from "
                            + entry.state
                            + " to "
                            + requested);
        }
        entry.state = requested;
    }

    private Entry newEntry(ChunkKey key) {
        return new Entry(
                worldHeight,
                unloadedRevisions.getOrDefault(key, 0L));
    }

    private static final class Entry {
        private final Chunk chunk;
        private ChunkState state = ChunkState.EMPTY;
        private long revision;
        private Throwable failure;

        private Entry(int worldHeight, long revision) {
            chunk = new Chunk(worldHeight);
            this.revision = revision;
        }
    }
}

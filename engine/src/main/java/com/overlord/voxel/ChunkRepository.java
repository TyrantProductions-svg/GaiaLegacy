package com.overlord.voxel;

import com.overlord.config.GameConfig;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

public final class ChunkRepository {
    private final int worldHeight;
    private final ChunkDirtyTracker dirtyTracker;
    private final ConcurrentHashMap<ChunkKey, Entry> entries =
            new ConcurrentHashMap<>();
    private final AtomicLong revisionSequence = new AtomicLong();
    private final AtomicLong generationAttemptSequence =
            new AtomicLong();
    private final GenerationAttempts generationAttempts =
            new GenerationAttempts();

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

    public int worldHeight() {
        return worldHeight;
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

    public ChunkGenerationTicket beginGeneration(
            ChunkKey key, ChunkGenerationMode mode) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(mode, "mode");

        synchronized (generationAttempts) {
            GenerationAttempt current =
                    generationAttempts.byKey.get(key);
            if (current != null
                    && current.status
                            == ChunkGenerationStatus.GENERATING) {
                throw new IllegalStateException(
                        "Chunk "
                                + key
                                + " already has an active generation attempt");
            }
            long baseRevision;
            if (mode == ChunkGenerationMode.INITIAL) {
                if (entries.containsKey(key)) {
                    throw new IllegalStateException(
                            "Initial generation requires an unloaded Chunk "
                                    + key);
                }
                baseRevision = 0;
            } else {
                Entry entry = entries.get(key);
                if (entry == null) {
                    throw new IllegalStateException(
                            "Rebuild generation requires a loaded Chunk "
                                    + key);
                }
                synchronized (entry) {
                    if (entries.get(key) != entry
                            || entry.state == ChunkState.EMPTY
                            || entry.state == ChunkState.UNLOADING
                            || entry.revision == 0) {
                        throw new IllegalStateException(
                                "Rebuild generation requires a stable loaded Chunk "
                                        + key);
                    }
                    baseRevision = entry.revision;
                }
            }

            ChunkGenerationTicket ticket =
                    new ChunkGenerationTicket(
                            key,
                            mode,
                            generationAttemptSequence.incrementAndGet(),
                            baseRevision);
            generationAttempts.byKey.put(
                    key, new GenerationAttempt(ticket));
            return ticket;
        }
    }

    public ChunkGenerationResult commitGeneration(
            ChunkGenerationTicket ticket,
            ChunkGenerationData data) {
        Objects.requireNonNull(ticket, "ticket");
        Objects.requireNonNull(data, "data");
        ChunkKey key =
                Objects.requireNonNull(ticket.key(), "ticket.key");
        Objects.requireNonNull(ticket.mode(), "ticket.mode");

        if (!key.equals(data.key())
                || data.worldHeight() != worldHeight) {
            return terminalConflict(ticket);
        }
        if (!isLiveAttempt(ticket)) {
            return conflictResult(key);
        }

        Chunk detached =
                Chunk.fromCanonicalBytes(
                        data.worldHeight(), data.copyBlocks());
        long committedRevision;
        Set<ChunkKey> changedEdges = Set.of();
        synchronized (generationAttempts) {
            GenerationAttempt attempt =
                    liveAttempt(ticket);
            if (attempt == null) {
                return conflictResult(key);
            }

            if (ticket.mode() == ChunkGenerationMode.INITIAL) {
                if (ticket.baseRevision() != 0) {
                    generationAttempts.byKey.remove(key, attempt);
                    return conflictResult(key);
                }

                Entry created = new Entry(detached);
                Entry resolved =
                        entries.compute(
                                key,
                                (ignored, current) -> {
                                    if (current != null) {
                                        return current;
                                    }
                                    created.revision = nextRevision();
                                    created.state = ChunkState.GENERATED;
                                    return created;
                                });
                if (resolved != created) {
                    generationAttempts.byKey.remove(key, attempt);
                    return conflictResult(key);
                }
                committedRevision = created.revision;
            } else {
                Entry entry = entries.get(key);
                if (entry == null) {
                    generationAttempts.byKey.remove(key, attempt);
                    return conflictResult(key);
                }
                synchronized (entry) {
                    if (entries.get(key) != entry
                            || entry.state == ChunkState.UNLOADING
                            || entry.revision
                                    != ticket.baseRevision()) {
                        generationAttempts.byKey.remove(key, attempt);
                        return conflictResult(key);
                    }
                    changedEdges =
                            changedHorizontalEdges(
                                    key, entry.chunk, detached);
                    entry.chunk = detached;
                    entry.revision = nextRevision();
                    entry.failure = null;
                    entry.state = ChunkState.DIRTY;
                    committedRevision = entry.revision;
                }
            }

            attempt.status = ChunkGenerationStatus.COMMITTED;
            attempt.failure = null;
        }

        if (ticket.mode() == ChunkGenerationMode.INITIAL) {
            for (ChunkKey neighbor :
                    dirtyTracker.horizontalNeighbors(key)) {
                dirtyIfPresent(neighbor);
            }
        } else {
            dirtyChangedLoadedNeighbors(changedEdges);
        }
        return new ChunkGenerationResult(
                ChunkGenerationResult.Status.COMMITTED,
                key,
                committedRevision,
                Optional.empty());
    }

    public ChunkGenerationResult failGeneration(
            ChunkGenerationTicket ticket, Throwable failure) {
        Objects.requireNonNull(ticket, "ticket");
        Objects.requireNonNull(failure, "failure");
        ChunkKey key =
                Objects.requireNonNull(ticket.key(), "ticket.key");
        Objects.requireNonNull(ticket.mode(), "ticket.mode");

        synchronized (generationAttempts) {
            GenerationAttempt attempt =
                    liveAttempt(ticket);
            if (attempt == null) {
                return conflictResult(key);
            }
            attempt.failure = failure;
            attempt.status = ChunkGenerationStatus.FAILED;
            return new ChunkGenerationResult(
                    ChunkGenerationResult.Status.FAILED,
                    key,
                    0,
                    Optional.of(failure));
        }
    }

    public ChunkGenerationStatus generationStatus(ChunkKey key) {
        Objects.requireNonNull(key, "key");
        synchronized (generationAttempts) {
            GenerationAttempt attempt =
                    generationAttempts.byKey.get(key);
            if (attempt == null || attempt.unloading) {
                return ChunkGenerationStatus.IDLE;
            }
            return attempt.status;
        }
    }

    public Optional<Throwable> generationFailure(ChunkKey key) {
        Objects.requireNonNull(key, "key");
        synchronized (generationAttempts) {
            GenerationAttempt attempt =
                    generationAttempts.byKey.get(key);
            if (attempt == null
                    || attempt.unloading
                    || attempt.status
                            != ChunkGenerationStatus.FAILED) {
                return Optional.empty();
            }
            return Optional.of(attempt.failure);
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
                entry.revision = nextRevision();
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
        ChunkMutationOutcome outcome =
                mutateBlock(
                        worldX,
                        y,
                        worldZ,
                        (byte) 0,
                        blockId,
                        false);
        return outcome.status() == ChunkMutationOutcome.Status.APPLIED;
    }

    public ChunkMutationOutcome compareAndSetBlock(
            int worldX,
            int y,
            int worldZ,
            byte expectedBlockId,
            byte replacementBlockId) {
        return mutateBlock(
                worldX,
                y,
                worldZ,
                expectedBlockId,
                replacementBlockId,
                true);
    }

    private ChunkMutationOutcome mutateBlock(
            int worldX,
            int y,
            int worldZ,
            byte expectedBlockId,
            byte replacementBlockId,
            boolean compareExpected) {
        if (y < 0 || y >= worldHeight) {
            return unchangedOutcome(
                    ChunkMutationOutcome.Status.OUT_OF_BOUNDS,
                    (byte) 0);
        }

        ChunkKey key = ChunkKey.fromWorld(worldX, worldZ);
        int localX = ChunkKey.localCoordinate(worldX);
        int localZ = ChunkKey.localCoordinate(worldZ);
        while (true) {
            Entry entry = entries.get(key);
            if (entry == null) {
                if (compareExpected && expectedBlockId != 0) {
                    return unchangedOutcome(
                            ChunkMutationOutcome.Status.CONFLICT,
                            (byte) 0);
                }
                if (replacementBlockId == 0) {
                    return unchangedOutcome(
                            ChunkMutationOutcome.Status.NO_CHANGE,
                            (byte) 0);
                }
                entry =
                        entries.computeIfAbsent(
                                key, this::newEntry);
            }

            byte observedBlock;
            long targetRevision;
            synchronized (entry) {
                if (entries.get(key) != entry) {
                    continue;
                }
                observedBlock =
                        entry.chunk.getBlock(localX, y, localZ);
                if (entry.state == ChunkState.UNLOADING) {
                    return unchangedOutcome(
                            ChunkMutationOutcome.Status.CONFLICT,
                            observedBlock);
                }
                if (compareExpected
                        && observedBlock != expectedBlockId) {
                    return unchangedOutcome(
                            ChunkMutationOutcome.Status.CONFLICT,
                            observedBlock);
                }
                if (observedBlock == replacementBlockId) {
                    return unchangedOutcome(
                            ChunkMutationOutcome.Status.NO_CHANGE,
                            observedBlock);
                }
                entry.chunk.setBlock(
                        localX, y, localZ, replacementBlockId);
                targetRevision = nextRevision();
                entry.revision = targetRevision;
                entry.failure = null;
                entry.state = ChunkState.DIRTY;
            }
            List<DirtyChunkRevision> dirtiedChunks =
                    new ArrayList<>();
            dirtiedChunks.add(
                    new DirtyChunkRevision(key, targetRevision));
            for (ChunkKey affected :
                    dirtyTracker.affectedByBlock(key, localX, localZ)) {
                if (!affected.equals(key)) {
                    OptionalLong revision =
                            dirtyIfPresent(affected);
                    if (revision.isPresent()) {
                        dirtiedChunks.add(
                                new DirtyChunkRevision(
                                        affected,
                                        revision.getAsLong()));
                    }
                }
            }
            return new ChunkMutationOutcome(
                    ChunkMutationOutcome.Status.APPLIED,
                    observedBlock,
                    dirtiedChunks);
        }
    }

    private static ChunkMutationOutcome unchangedOutcome(
            ChunkMutationOutcome.Status status, byte observedBlock) {
        return new ChunkMutationOutcome(
                status, observedBlock, List.of());
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

    public boolean isReadyForUpload(ChunkKey key, long revision) {
        Objects.requireNonNull(key, "key");
        Entry entry = entries.get(key);
        if (entry == null) {
            return false;
        }
        synchronized (entry) {
            return entries.get(key) == entry
                    && entry.state == ChunkState.READY_FOR_UPLOAD
                    && entry.revision == revision;
        }
    }

    public boolean beginUnload(ChunkKey key) {
        Objects.requireNonNull(key, "key");
        boolean generationCancelled =
                beginGenerationUnload(key);
        Entry entry = entries.get(key);
        if (entry == null) {
            return generationCancelled;
        }
        synchronized (entry) {
            if (entries.get(key) != entry
                    || entry.state == ChunkState.UNLOADING) {
                return generationCancelled;
            }
            entry.failure = null;
            entry.revision = nextRevision();
            entry.state = ChunkState.UNLOADING;
        }
        for (ChunkKey neighbor : dirtyTracker.horizontalNeighbors(key)) {
            dirtyIfPresent(neighbor);
        }
        return true;
    }

    public boolean completeUnload(ChunkKey key) {
        Objects.requireNonNull(key, "key");
        GenerationAttempt expectedAttempt =
                generationAttempt(key);
        Entry entry = entries.get(key);
        if (entry == null) {
            return clearGenerationAfterUnload(
                    key, expectedAttempt);
        }
        boolean removed;
        synchronized (entry) {
            if (entries.get(key) != entry
                    || entry.state != ChunkState.UNLOADING) {
                return false;
            }
            removed = entries.remove(key, entry);
        }
        if (removed) {
            clearGenerationAfterUnload(
                    key, expectedAttempt);
        }
        return removed;
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

    private OptionalLong dirtyIfPresent(ChunkKey key) {
        Entry entry = entries.get(key);
        if (entry == null) {
            return OptionalLong.empty();
        }
        synchronized (entry) {
            if (entries.get(key) != entry
                    || entry.state == ChunkState.UNLOADING
                    || entry.state == ChunkState.EMPTY) {
                return OptionalLong.empty();
            }
            entry.revision = nextRevision();
            entry.failure = null;
            if (entry.state != ChunkState.GENERATING) {
                entry.state = ChunkState.DIRTY;
            }
            return OptionalLong.of(entry.revision);
        }
    }

    private Set<ChunkKey> changedHorizontalEdges(
            ChunkKey key, Chunk oldChunk, Chunk replacement) {
        boolean northChanged = false;
        boolean southChanged = false;
        boolean westChanged = false;
        boolean eastChanged = false;
        int last = GameConfig.Chunk.SIZE - 1;
        for (int y = 0; y < worldHeight; y++) {
            for (int horizontal = 0;
                    horizontal < GameConfig.Chunk.SIZE;
                    horizontal++) {
                northChanged |=
                        oldChunk.getBlock(horizontal, y, 0)
                                != replacement.getBlock(horizontal, y, 0);
                southChanged |=
                        oldChunk.getBlock(horizontal, y, last)
                                != replacement.getBlock(
                                        horizontal, y, last);
                westChanged |=
                        oldChunk.getBlock(0, y, horizontal)
                                != replacement.getBlock(
                                        0, y, horizontal);
                eastChanged |=
                        oldChunk.getBlock(last, y, horizontal)
                                != replacement.getBlock(
                                        last, y, horizontal);
            }
        }

        Set<ChunkKey> changed = new HashSet<>();
        if (northChanged) {
            changed.add(key.north());
        }
        if (southChanged) {
            changed.add(key.south());
        }
        if (westChanged) {
            changed.add(key.west());
        }
        if (eastChanged) {
            changed.add(key.east());
        }
        return Set.copyOf(changed);
    }

    private void dirtyChangedLoadedNeighbors(
            Set<ChunkKey> changedEdges) {
        for (ChunkKey neighbor : changedEdges) {
            dirtyIfPresent(neighbor);
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
        return new Entry(worldHeight);
    }

    private ChunkGenerationResult terminalConflict(
            ChunkGenerationTicket ticket) {
        ChunkKey key =
                Objects.requireNonNull(ticket.key(), "ticket.key");
        synchronized (generationAttempts) {
            GenerationAttempt attempt =
                    liveAttempt(ticket);
            if (attempt != null) {
                generationAttempts.byKey.remove(key, attempt);
            }
            return conflictResult(key);
        }
    }

    private boolean isLiveAttempt(
            ChunkGenerationTicket ticket) {
        synchronized (generationAttempts) {
            return liveAttempt(ticket) != null;
        }
    }

    private GenerationAttempt liveAttempt(
            ChunkGenerationTicket ticket) {
        GenerationAttempt attempt =
                generationAttempts.byKey.get(ticket.key());
        if (attempt == null
                || attempt.unloading
                || attempt.status
                        != ChunkGenerationStatus.GENERATING
                || attempt.ticket != ticket) {
            return null;
        }
        return attempt;
    }

    private boolean beginGenerationUnload(ChunkKey key) {
        synchronized (generationAttempts) {
            GenerationAttempt attempt =
                    generationAttempts.byKey.get(key);
            if (attempt == null
                    || attempt.unloading
                    || attempt.status
                            != ChunkGenerationStatus.GENERATING) {
                return false;
            }
            attempt.unloading = true;
            return true;
        }
    }

    private GenerationAttempt generationAttempt(ChunkKey key) {
        synchronized (generationAttempts) {
            return generationAttempts.byKey.get(key);
        }
    }

    private boolean clearGenerationAfterUnload(
            ChunkKey key, GenerationAttempt expectedAttempt) {
        synchronized (generationAttempts) {
            GenerationAttempt attempt =
                    generationAttempts.byKey.get(key);
            if (attempt != expectedAttempt
                    || attempt == null
                    || (!attempt.unloading
                            && attempt.status
                                    != ChunkGenerationStatus.COMMITTED)) {
                return false;
            }
            return generationAttempts.byKey.remove(key, attempt);
        }
    }

    private ChunkGenerationResult conflictResult(ChunkKey key) {
        return new ChunkGenerationResult(
                ChunkGenerationResult.Status.CONFLICT,
                key,
                revision(key),
                Optional.empty());
    }

    private long nextRevision() {
        return revisionSequence.incrementAndGet();
    }

    private static final class GenerationAttempts {
        private final Map<ChunkKey, GenerationAttempt> byKey =
                new HashMap<>();
    }

    private static final class GenerationAttempt {
        private final ChunkGenerationTicket ticket;
        private ChunkGenerationStatus status =
                ChunkGenerationStatus.GENERATING;
        private Throwable failure;
        private boolean unloading;

        private GenerationAttempt(ChunkGenerationTicket ticket) {
            this.ticket = ticket;
        }
    }

    private static final class Entry {
        private Chunk chunk;
        private ChunkState state = ChunkState.EMPTY;
        private long revision;
        private Throwable failure;

        private Entry(int worldHeight) {
            chunk = new Chunk(worldHeight);
        }

        private Entry(Chunk chunk) {
            this.chunk = chunk;
        }
    }
}

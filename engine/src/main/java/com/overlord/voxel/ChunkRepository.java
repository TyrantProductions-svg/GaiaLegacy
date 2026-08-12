package com.overlord.voxel;

import com.overlord.config.GameConfig;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

public final class ChunkRepository {
    private final int worldHeight;
    private final ChunkDirtyTracker dirtyTracker;
    private final BiConsumer<ChunkKey, ChunkMutationOutcome>
            absentMutationPublicationProbe;
    private final RestorePublicationProbe restorePublicationProbe;
    private volatile ConcurrentHashMap<ChunkKey, Entry> entries =
            new ConcurrentHashMap<>();
    private volatile boolean restoreEligible = true;
    private final AtomicLong revisionSequence = new AtomicLong();
    private final AtomicInteger absentMutationPublications =
            new AtomicInteger();
    private final AtomicLong generationAttemptSequence =
            new AtomicLong();
    private final GenerationAttempts generationAttempts =
            new GenerationAttempts();

    public ChunkRepository() {
        this(GameConfig.Chunk.MAX_HEIGHT, new ChunkDirtyTracker());
    }

    public ChunkRepository(int worldHeight) {
        this(worldHeight, new ChunkDirtyTracker());
    }

    public ChunkRepository(
            int worldHeight, ChunkDirtyTracker dirtyTracker) {
        this(
                worldHeight,
                dirtyTracker,
                (key, outcome) -> {},
                (detached, snapshot, result) -> {});
    }

    ChunkRepository(
            int worldHeight,
            ChunkDirtyTracker dirtyTracker,
            BiConsumer<ChunkKey, ChunkMutationOutcome>
                    absentMutationPublicationProbe) {
        this(
                worldHeight,
                dirtyTracker,
                absentMutationPublicationProbe,
                (detached, snapshot, result) -> {});
    }

    ChunkRepository(
            int worldHeight,
            ChunkDirtyTracker dirtyTracker,
            BiConsumer<ChunkKey, ChunkMutationOutcome>
                    absentMutationPublicationProbe,
            RestorePublicationProbe restorePublicationProbe) {
        if (worldHeight <= 0) {
            throw new IllegalArgumentException(
                    "worldHeight must be greater than zero");
        }
        this.worldHeight = worldHeight;
        this.dirtyTracker =
                Objects.requireNonNull(dirtyTracker, "dirtyTracker");
        this.absentMutationPublicationProbe =
                Objects.requireNonNull(
                        absentMutationPublicationProbe,
                        "absentMutationPublicationProbe");
        this.restorePublicationProbe =
                Objects.requireNonNull(
                        restorePublicationProbe,
                        "restorePublicationProbe");
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
            restoreEligible = false;
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
        List<DirtyCandidate> neighborCandidates;
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

                committedRevision =
                        publishInitialGeneration(key, detached);
                if (committedRevision == 0) {
                    generationAttempts.byKey.remove(key, attempt);
                    return conflictResult(key);
                }
                neighborCandidates = null;
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
                    ChangedMeshingBoundaries changedBoundaries =
                            changedMeshingBoundaries(
                                    entry.chunk, detached);
                    neighborCandidates =
                            dirtyCandidates(
                                    changedMeshingNeighborKeys(
                                            key, changedBoundaries),
                                    key);
                    committedRevision =
                            reserveRevisions(
                                    1 + neighborCandidates.size());
                    entry.chunk = detached;
                    entry.revision = committedRevision;
                    entry.failure = null;
                    entry.state = ChunkState.DIRTY;
                }
            }

            attempt.status = ChunkGenerationStatus.COMMITTED;
            attempt.failure = null;
        }

        if (ticket.mode() == ChunkGenerationMode.REBUILD) {
            dirtyChangedLoadedNeighbors(
                    neighborCandidates, committedRevision);
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
        markRepositoryUsed();
        List<DirtyCandidate> neighborCandidates;
        long generatedRevision;
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
                neighborCandidates =
                        dirtyCandidates(
                                dirtyTracker.meshingNeighbors(key), key);
                generatedRevision =
                        reserveRevisions(
                                1 + neighborCandidates.size());
                entry.revision = generatedRevision;
                entry.state = ChunkState.GENERATED;
            } catch (RuntimeException | Error failure) {
                entry.failure = failure;
                entries.remove(key, entry);
                throw failure;
            }
        }
        dirtyReservedCandidates(
                neighborCandidates, generatedRevision);
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
            List<DirtyCandidate> affectedCandidates =
                    dirtyCandidates(
                            dirtyTracker.affectedByBlock(
                                    key, localX, localZ),
                            key);
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
                markRepositoryUsed();
                ChunkMutationOutcome outcome =
                        withLockedCandidates(
                                affectedCandidates,
                                () -> {
                                    if (entries.get(key) != null
                                            || !areCurrentDirtyCandidates(
                                                    affectedCandidates)) {
                                        return null;
                                    }
                                    absentMutationPublications.incrementAndGet();
                                    try {
                                        long targetRevision =
                                                reserveRevisions(
                                                        1
                                                                + affectedCandidates
                                                                        .size());
                                        Entry created =
                                                new Entry(worldHeight);
                                        created.chunk.setBlock(
                                                localX,
                                                y,
                                                localZ,
                                                replacementBlockId);
                                        created.revision = targetRevision;
                                        created.state = ChunkState.DIRTY;
                                        ChunkMutationOutcome preparedOutcome =
                                                prepareMutationOutcome(
                                                        key,
                                                        (byte) 0,
                                                        targetRevision,
                                                        affectedCandidates);
                                        absentMutationPublicationProbe.accept(
                                                key, preparedOutcome);
                                        if (entries.putIfAbsent(
                                                        key, created)
                                                != null) {
                                            return null;
                                        }
                                        dirtyReservedCandidates(
                                                affectedCandidates,
                                                targetRevision);
                                        return preparedOutcome;
                                    } finally {
                                        absentMutationPublications.decrementAndGet();
                                    }
                                });
                if (outcome == null) {
                    continue;
                }
                return outcome;
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
                targetRevision =
                        reserveRevisions(
                                1 + affectedCandidates.size());
                entry.chunk.setBlock(
                        localX, y, localZ, replacementBlockId);
                entry.revision = targetRevision;
                entry.failure = null;
                entry.state = ChunkState.DIRTY;
            }
            return appliedMutationOutcome(
                    key,
                    observedBlock,
                    targetRevision,
                    affectedCandidates);
        }
    }

    private ChunkMutationOutcome appliedMutationOutcome(
            ChunkKey key,
            byte observedBlock,
            long targetRevision,
            List<DirtyCandidate> affectedCandidates) {
        List<DirtyChunkRevision> dirtiedChunks = new ArrayList<>();
        dirtiedChunks.add(
                new DirtyChunkRevision(key, targetRevision));
        for (int index = 0;
                index < affectedCandidates.size();
                index++) {
            long reservedRevision = targetRevision + index + 1;
            DirtyCandidate candidate = affectedCandidates.get(index);
            if (dirtyIfCurrent(candidate, reservedRevision)) {
                dirtiedChunks.add(
                        new DirtyChunkRevision(
                                candidate.key(), reservedRevision));
            }
        }
        return new ChunkMutationOutcome(
                ChunkMutationOutcome.Status.APPLIED,
                observedBlock,
                dirtiedChunks);
    }

    private ChunkMutationOutcome prepareMutationOutcome(
            ChunkKey key,
            byte observedBlock,
            long targetRevision,
            List<DirtyCandidate> affectedCandidates) {
        List<DirtyChunkRevision> dirtiedChunks =
                new ArrayList<>(1 + affectedCandidates.size());
        dirtiedChunks.add(
                new DirtyChunkRevision(key, targetRevision));
        for (int index = 0;
                index < affectedCandidates.size();
                index++) {
            dirtiedChunks.add(
                    new DirtyChunkRevision(
                            affectedCandidates.get(index).key(),
                            targetRevision + index + 1));
        }
        return new ChunkMutationOutcome(
                ChunkMutationOutcome.Status.APPLIED,
                observedBlock,
                dirtiedChunks);
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

    public ChunkRepositorySnapshot canonicalSnapshot() {
        synchronized (generationAttempts) {
            if (hasActiveGenerationAttempts()) {
                throw new IllegalStateException(
                        "Cannot capture Chunks while generation is active");
            }
            if (absentMutationPublications.get() != 0) {
                throw new IllegalStateException(
                        "Cannot capture Chunks while absent mutation publication is active");
            }

            long revisionHighWater = revisionSequence.get();
            List<ChunkKey> keys = new ArrayList<>(entries.keySet());
            keys.sort(
                    Comparator.comparingInt(ChunkKey::x)
                            .thenComparingInt(ChunkKey::z));
            List<ChunkSnapshot> chunks = new ArrayList<>(keys.size());
            for (ChunkKey key : keys) {
                Entry entry = entries.get(key);
                if (entry == null) {
                    throw changedDuringCanonicalCapture();
                }
                synchronized (entry) {
                    if (entries.get(key) != entry
                            || entry.state == ChunkState.EMPTY
                            || entry.state == ChunkState.GENERATING
                            || entry.state == ChunkState.UNLOADING
                            || entry.revision <= 0) {
                        throw changedDuringCanonicalCapture();
                    }
                    byte[] blocks = new byte[canonicalBlockCount()];
                    entry.chunk.copyBlocksTo(blocks);
                    chunks.add(
                            ChunkSnapshot.of(
                                    key,
                                    entry.revision,
                                    worldHeight,
                                    blocks));
                }
            }

            if (revisionSequence.get() != revisionHighWater
                    || !entries.keySet().equals(new HashSet<>(keys))
                    || absentMutationPublications.get() != 0) {
                throw changedDuringCanonicalCapture();
            }
            return new ChunkRepositorySnapshot(
                    worldHeight, revisionHighWater, chunks);
        }
    }

    public ChunkRepositoryRestoreResult restoreCanonical(
            ChunkRepositorySnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        List<RestoredChunk> restoredChunks =
                validateCompleteSnapshot(snapshot);
        if (restoredChunks == null) {
            return ChunkRepositoryRestoreResult.rejected(
                    ChunkRepositoryRestoreResult.Status.INVALID_SNAPSHOT);
        }

        synchronized (generationAttempts) {
            if (hasActiveGenerationAttempts()) {
                return ChunkRepositoryRestoreResult.rejected(
                        ChunkRepositoryRestoreResult.Status.GENERATION_ACTIVE);
            }
            if (!entries.isEmpty()) {
                return ChunkRepositoryRestoreResult.rejected(
                        ChunkRepositoryRestoreResult.Status.TARGET_NOT_EMPTY);
            }
            if (!restoreEligible) {
                return ChunkRepositoryRestoreResult.rejected(
                        ChunkRepositoryRestoreResult.Status.TARGET_NOT_FRESH);
            }

            ConcurrentHashMap<ChunkKey, Entry> published =
                    new ConcurrentHashMap<>();
            for (RestoredChunk restored : restoredChunks) {
                Entry entry = new Entry(restored.chunk());
                entry.revision = restored.revision();
                entry.state = ChunkState.DIRTY;
                published.put(restored.key(), entry);
            }
            ChunkRepositoryRestoreResult result =
                    ChunkRepositoryRestoreResult.restored(
                            restoredChunks.size());
            restorePublicationProbe.beforePublication(
                    published, snapshot, result);
            revisionSequence.set(snapshot.revisionHighWater());
            entries = published;
            restoreEligible = false;
            return result;
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
        ChunkSnapshot northEast =
                snapshot(key.north().east())
                        .orElseGet(
                                () ->
                                        ChunkSnapshot.empty(
                                                key.north().east(),
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
        ChunkSnapshot southEast =
                snapshot(key.south().east())
                        .orElseGet(
                                () ->
                                        ChunkSnapshot.empty(
                                                key.south().east(),
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
        ChunkSnapshot southWest =
                snapshot(key.south().west())
                        .orElseGet(
                                () ->
                                        ChunkSnapshot.empty(
                                                key.south().west(),
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
        ChunkSnapshot northWest =
                snapshot(key.north().west())
                        .orElseGet(
                                () ->
                                        ChunkSnapshot.empty(
                                                key.north().west(),
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
                            northEast,
                            east,
                            southEast,
                            south,
                            southWest,
                            west,
                            northWest));
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
        List<DirtyCandidate> neighborCandidates;
        long unloadRevision;
        synchronized (generationAttempts) {
            GenerationAttempt attempt =
                    generationAttempts.byKey.get(key);
            boolean generationCancelled =
                    attempt != null
                            && !attempt.unloading
                            && attempt.status
                                    == ChunkGenerationStatus.GENERATING;
            Entry entry = entries.get(key);
            if (entry == null) {
                if (generationCancelled) {
                    attempt.unloading = true;
                }
                return generationCancelled;
            }
            neighborCandidates =
                    dirtyCandidates(
                            dirtyTracker.meshingNeighbors(key), key);
            synchronized (entry) {
                if (entries.get(key) != entry
                        || entry.state == ChunkState.UNLOADING) {
                    if (generationCancelled) {
                        attempt.unloading = true;
                    }
                    return generationCancelled;
                }
                unloadRevision =
                        reserveRevisions(
                                1 + neighborCandidates.size());
                if (generationCancelled) {
                    attempt.unloading = true;
                }
                entry.failure = null;
                entry.revision = unloadRevision;
                entry.state = ChunkState.UNLOADING;
            }
        }
        for (int index = 0;
                index < neighborCandidates.size();
                index++) {
            dirtyIfCurrent(
                    neighborCandidates.get(index),
                    unloadRevision + index + 1);
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

    private List<RestoredChunk> validateCompleteSnapshot(
            ChunkRepositorySnapshot snapshot) {
        if (snapshot.worldHeight() != worldHeight
                || snapshot.revisionHighWater() < 0
                || snapshot.revisionHighWater() == Long.MAX_VALUE) {
            return null;
        }

        Set<ChunkKey> keys = new HashSet<>();
        List<RestoredChunk> restored =
                new ArrayList<>(snapshot.chunks().size());
        try {
            for (ChunkSnapshot chunkSnapshot : snapshot.chunks()) {
                ChunkKey key = chunkSnapshot.key();
                long revision = chunkSnapshot.revision();
                if (chunkSnapshot.worldHeight() != worldHeight
                        || revision <= 0
                        || revision > snapshot.revisionHighWater()
                        || !keys.add(key)) {
                    return null;
                }
                byte[] blocks = chunkSnapshot.copyBlocks();
                if (blocks.length != canonicalBlockCount()) {
                    return null;
                }
                restored.add(
                        new RestoredChunk(
                                key,
                                revision,
                                Chunk.fromCanonicalBytes(
                                        worldHeight, blocks)));
            }
        } catch (RuntimeException failure) {
            return null;
        }
        return List.copyOf(restored);
    }

    private int canonicalBlockCount() {
        return Math.multiplyExact(
                Math.multiplyExact(
                        GameConfig.Chunk.SIZE, worldHeight),
                GameConfig.Chunk.SIZE);
    }

    private boolean hasActiveGenerationAttempts() {
        for (GenerationAttempt attempt : generationAttempts.byKey.values()) {
            if (attempt.status == ChunkGenerationStatus.GENERATING) {
                return true;
            }
        }
        return false;
    }

    private static IllegalStateException changedDuringCanonicalCapture() {
        return new IllegalStateException(
                "Chunk repository changed during canonical capture");
    }

    private List<DirtyCandidate> dirtyCandidates(
            Iterable<ChunkKey> keys, ChunkKey excluded) {
        List<DirtyCandidate> candidates = new ArrayList<>();
        for (ChunkKey key : keys) {
            if (key.equals(excluded)) {
                continue;
            }
            Entry entry = entries.get(key);
            if (entry != null) {
                candidates.add(new DirtyCandidate(key, entry));
            }
        }
        return List.copyOf(candidates);
    }

    private <T> T withLockedCandidates(
            List<DirtyCandidate> candidates, Supplier<T> action) {
        List<DirtyCandidate> lockOrder = new ArrayList<>(candidates);
        lockOrder.sort(
                Comparator.comparingInt(
                                (DirtyCandidate candidate) ->
                                        candidate.key().x())
                        .thenComparingInt(
                                candidate -> candidate.key().z()));
        return withLockedCandidates(lockOrder, 0, action);
    }

    private <T> T withLockedCandidates(
            List<DirtyCandidate> lockOrder,
            int index,
            Supplier<T> action) {
        if (index == lockOrder.size()) {
            return action.get();
        }
        synchronized (lockOrder.get(index).entry()) {
            return withLockedCandidates(
                    lockOrder, index + 1, action);
        }
    }

    private long withLockedCandidatesLong(
            List<DirtyCandidate> candidates, LongSupplier action) {
        List<DirtyCandidate> lockOrder = new ArrayList<>(candidates);
        lockOrder.sort(
                Comparator.comparingInt(
                                (DirtyCandidate candidate) ->
                                        candidate.key().x())
                        .thenComparingInt(
                                candidate -> candidate.key().z()));
        return withLockedCandidatesLong(lockOrder, 0, action);
    }

    private long withLockedCandidatesLong(
            List<DirtyCandidate> lockOrder,
            int index,
            LongSupplier action) {
        if (index == lockOrder.size()) {
            return action.getAsLong();
        }
        synchronized (lockOrder.get(index).entry()) {
            return withLockedCandidatesLong(
                    lockOrder, index + 1, action);
        }
    }

    private boolean areCurrentDirtyCandidates(
            List<DirtyCandidate> candidates) {
        for (DirtyCandidate candidate : candidates) {
            Entry entry = candidate.entry();
            if (entries.get(candidate.key()) != entry
                    || entry.state == ChunkState.UNLOADING
                    || entry.state == ChunkState.EMPTY) {
                return false;
            }
        }
        return true;
    }

    private void dirtyReservedCandidates(
            List<DirtyCandidate> candidates, long primaryRevision) {
        for (int index = 0; index < candidates.size(); index++) {
            dirtyIfCurrent(
                    candidates.get(index),
                    primaryRevision + index + 1);
        }
    }

    private void dirtyChangedLoadedNeighbors(
            List<DirtyCandidate> candidates, long primaryRevision) {
        dirtyReservedCandidates(candidates, primaryRevision);
    }

    private boolean dirtyIfCurrent(
            DirtyCandidate candidate, long reservedRevision) {
        Entry entry = candidate.entry();
        synchronized (entry) {
            if (entries.get(candidate.key()) != entry
                    || entry.state == ChunkState.UNLOADING
                    || entry.state == ChunkState.EMPTY
                    || entry.revision >= reservedRevision) {
                return false;
            }
            entry.revision = reservedRevision;
            entry.failure = null;
            if (entry.state != ChunkState.GENERATING) {
                entry.state = ChunkState.DIRTY;
            }
            return true;
        }
    }

    private ChangedMeshingBoundaries changedMeshingBoundaries(
            Chunk oldChunk, Chunk replacement) {
        boolean northChanged = false;
        boolean southChanged = false;
        boolean westChanged = false;
        boolean eastChanged = false;
        boolean northWestChanged = false;
        boolean northEastChanged = false;
        boolean southWestChanged = false;
        boolean southEastChanged = false;
        int last = GameConfig.Chunk.SIZE - 1;
        for (int y = 0; y < worldHeight; y++) {
            for (int horizontal = 0;
                    horizontal < GameConfig.Chunk.SIZE;
                    horizontal++) {
                boolean northCellChanged =
                        oldChunk.getBlock(horizontal, y, 0)
                                != replacement.getBlock(horizontal, y, 0);
                boolean southCellChanged =
                        oldChunk.getBlock(horizontal, y, last)
                                != replacement.getBlock(
                                        horizontal, y, last);
                boolean westCellChanged =
                        oldChunk.getBlock(0, y, horizontal)
                                != replacement.getBlock(
                                        0, y, horizontal);
                boolean eastCellChanged =
                        oldChunk.getBlock(last, y, horizontal)
                                != replacement.getBlock(
                                        last, y, horizontal);
                northChanged |= northCellChanged;
                southChanged |= southCellChanged;
                westChanged |= westCellChanged;
                eastChanged |= eastCellChanged;
                if (horizontal == 0) {
                    northWestChanged |= northCellChanged;
                    southWestChanged |= southCellChanged;
                }
                if (horizontal == last) {
                    northEastChanged |= northCellChanged;
                    southEastChanged |= southCellChanged;
                }
            }
        }

        return new ChangedMeshingBoundaries(
                northChanged,
                northEastChanged,
                eastChanged,
                southEastChanged,
                southChanged,
                southWestChanged,
                westChanged,
                northWestChanged);
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

    private long publishInitialGeneration(
            ChunkKey key, Chunk detached) {
        while (true) {
            List<DirtyCandidate> neighborCandidates =
                    dirtyCandidates(
                            dirtyTracker.meshingNeighbors(key), key);
            long result =
                    withLockedCandidatesLong(
                            neighborCandidates,
                            () -> {
                                if (!areCurrentDirtyCandidates(
                                        neighborCandidates)) {
                                    return -1L;
                                }
                                if (entries.get(key) != null) {
                                    return 0L;
                                }
                                long committedRevision =
                                        reserveRevisions(
                                                1
                                                        + neighborCandidates
                                                                .size());
                                Entry created = new Entry(detached);
                                created.revision = committedRevision;
                                created.state = ChunkState.GENERATED;
                                if (entries.putIfAbsent(key, created)
                                        != null) {
                                    return 0L;
                                }
                                dirtyReservedCandidates(
                                        neighborCandidates,
                                        committedRevision);
                                return committedRevision;
                            });
            if (result != -1L) {
                return result;
            }
        }
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

    private long reserveRevisions(int count) {
        if (count <= 0) {
            throw new IllegalArgumentException(
                    "count must be greater than zero");
        }
        while (true) {
            long current = revisionSequence.get();
            long reservedHighWater;
            try {
                reservedHighWater = Math.addExact(current, count);
            } catch (ArithmeticException failure) {
                throw new IllegalStateException(
                        "Chunk revision sequence is exhausted",
                        failure);
            }
            if (revisionSequence.compareAndSet(
                    current, reservedHighWater)) {
                return current + 1;
            }
        }
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

    private void markRepositoryUsed() {
        if (!restoreEligible) {
            return;
        }
        synchronized (generationAttempts) {
            restoreEligible = false;
        }
    }

    @FunctionalInterface
    interface RestorePublicationProbe {
        void beforePublication(
                Object detachedEntries,
                ChunkRepositorySnapshot snapshot,
                ChunkRepositoryRestoreResult result);
    }

    private static List<ChunkKey> changedMeshingNeighborKeys(
            ChunkKey key, ChangedMeshingBoundaries changed) {
        List<ChunkKey> keys = new ArrayList<>();
        if (changed.north()) {
            keys.add(key.north());
        }
        if (changed.northEast()) {
            keys.add(key.northEast());
        }
        if (changed.east()) {
            keys.add(key.east());
        }
        if (changed.southEast()) {
            keys.add(key.southEast());
        }
        if (changed.south()) {
            keys.add(key.south());
        }
        if (changed.southWest()) {
            keys.add(key.southWest());
        }
        if (changed.west()) {
            keys.add(key.west());
        }
        if (changed.northWest()) {
            keys.add(key.northWest());
        }
        return List.copyOf(keys);
    }

    private record RestoredChunk(
            ChunkKey key, long revision, Chunk chunk) {}

    private record DirtyCandidate(ChunkKey key, Entry entry) {}

    private record ChangedMeshingBoundaries(
            boolean north,
            boolean northEast,
            boolean east,
            boolean southEast,
            boolean south,
            boolean southWest,
            boolean west,
            boolean northWest) {
    }
}

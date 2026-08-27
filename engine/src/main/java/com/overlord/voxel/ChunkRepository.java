package com.overlord.voxel;

import com.overlord.config.GameConfig;
import com.overlord.voxel.ChunkStreamingTicket.BaseIdentity;
import com.overlord.voxel.ChunkStreamingTicket.SourcePreference;
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
import java.util.concurrent.atomic.AtomicBoolean;
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
    private final AtomicLong streamingRequestSequence =
            new AtomicLong();
    private final AtomicLong meshingClaimSequence =
            new AtomicLong();
    private final GenerationAttempts generationAttempts =
            new GenerationAttempts();
    private final Object streamingUnloadIssuer = new Object();
    private final Thread streamingOwnerThread = Thread.currentThread();

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

    /** Current resident Chunks whose voxel state differs from a clean publication. */
    public int modifiedResidentCount() {
        int modified = 0;
        for (Entry entry : entries.values()) {
            synchronized (entry) {
                if (entry.voxelModified) {
                    modified++;
                }
            }
        }
        return modified;
    }

    /** Exact read-only observation used to prioritize bounded unload work. */
    public boolean voxelModified(ChunkKey key) {
        ChunkKey checkedKey = ChunkCoordinatePolicy.requireSafe(key);
        Entry entry = entries.get(checkedKey);
        if (entry == null) {
            return false;
        }
        synchronized (entry) {
            return entries.get(checkedKey) == entry && entry.voxelModified;
        }
    }

    public ChunkState state(ChunkKey key) {
        ChunkKey checkedKey = ChunkCoordinatePolicy.requireSafe(key);
        Entry entry = entries.get(checkedKey);
        if (entry == null) {
            synchronized (generationAttempts) {
                StreamingRequest request = currentStreamingRequest(checkedKey);
                if (request == null) {
                    return hasUnloadingStreamingRequest(checkedKey)
                            ? ChunkState.UNLOADING
                            : ChunkState.EMPTY;
                }
                return request.ticket.sourcePreference()
                                == SourcePreference.LOAD
                        ? ChunkState.LOADING
                        : ChunkState.GENERATING;
            }
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

    public ChunkStreamingTicket request(
            ChunkKey key,
            long epoch,
            SourcePreference sourcePreference) {
        ChunkKey checkedKey = ChunkCoordinatePolicy.requireSafe(key);
        Objects.requireNonNull(sourcePreference, "sourcePreference");
        if (epoch < 0) {
            throw new IllegalArgumentException("epoch must not be negative");
        }

        synchronized (generationAttempts) {
            StreamingUnloadPreparation preparedUnload =
                    generationAttempts.streamingUnloadPreparations.get(checkedKey);
            if (preparedUnload != null && preparedUnload.finalValidated) {
                throw new IllegalStateException(
                        "A final-validated Chunk unload is pinned");
            }
            if (preparedUnload != null) {
                preparedUnload.invalidate();
            }
            generationAttempts.streamingUnloadPreparations.remove(checkedKey);
            StreamingRequests requests =
                    generationAttempts.streamingByKey.get(checkedKey);
            StreamingRequest current =
                    requests == null ? null : requests.current;
            StreamingRequest latest =
                    current != null
                            ? current
                            : latestUnloadingRequest(requests);
            if (latest != null
                    && epoch <= latest.ticket.epoch()) {
                return latest.ticket;
            }

            Entry entry = entries.get(checkedKey);
            long expectedRevision = 0;
            if (entry != null) {
                synchronized (entry) {
                    if (entries.get(checkedKey) != entry
                            || entry.state == ChunkState.EMPTY
                            || entry.revision == 0) {
                        throw new IllegalStateException(
                                "Streaming request requires a stable Chunk "
                                        + checkedKey);
                    }
                    if (entry.state == ChunkState.UNLOADING) {
                        if (latest == null
                                || latest.unloadEntry != entry) {
                            throw new IllegalStateException(
                                    "Streaming request requires a stable Chunk "
                                            + checkedKey);
                        }
                        expectedRevision = 0;
                    } else {
                        expectedRevision = entry.revision;
                    }
                }
            }

            GenerationAttempt attempt =
                    generationAttempts.byKey.get(checkedKey);
            if (attempt != null
                    && !attempt.unloading
                    && attempt.status
                            == ChunkGenerationStatus.GENERATING
                    && (current == null
                            || current.generationAttempt != attempt)) {
                throw new IllegalStateException(
                        "Chunk "
                                + checkedKey
                                + " already has an active generation attempt");
            }

            long requestId = reserveStreamingRequestId();
            ChunkStreamingTicket ticket =
                    new ChunkStreamingTicket(
                            checkedKey,
                            epoch,
                            sourcePreference,
                            expectedRevision,
                            requestId);
            GenerationAttempt replacementAttempt = null;
            if (sourcePreference == SourcePreference.GENERATE) {
                ChunkGenerationTicket generationTicket =
                        new ChunkGenerationTicket(
                                checkedKey,
                                expectedRevision == 0
                                        ? ChunkGenerationMode.INITIAL
                                        : ChunkGenerationMode.REBUILD,
                                generationAttemptSequence.incrementAndGet(),
                                expectedRevision);
                replacementAttempt =
                        new GenerationAttempt(generationTicket);
            }
            StreamingRequest replacement =
                    new StreamingRequest(ticket, replacementAttempt);

            if (current != null) {
                invalidateCurrentStreamingRequest(
                        checkedKey, requests, current);
            } else if (attempt != null
                    && !attempt.unloading
                    && attempt.status
                            != ChunkGenerationStatus.GENERATING) {
                generationAttempts.byKey.remove(checkedKey, attempt);
            }
            if (requests == null) {
                requests = new StreamingRequests();
                generationAttempts.streamingByKey.put(
                        checkedKey, requests);
            }
            requests.current = replacement;
            if (replacementAttempt != null) {
                generationAttempts.byKey.put(
                        checkedKey, replacementAttempt);
            }
            restoreEligible = false;
            return ticket;
        }
    }

    public ChunkStreamingPublication publish(
            ChunkStreamingTicket ticket,
            ChunkGenerationData data,
            BaseIdentity baseIdentity) {
        Objects.requireNonNull(ticket, "ticket");
        Objects.requireNonNull(data, "data");
        Objects.requireNonNull(baseIdentity, "baseIdentity");
        ChunkKey key = ChunkCoordinatePolicy.requireSafe(ticket.key());
        ChunkCoordinatePolicy.requireSafe(data.key());

        if (!key.equals(data.key())
                || data.worldHeight() != worldHeight
                || baseIdentity.sourcePreference()
                        != ticket.sourcePreference()
                || baseIdentity.expectedRevision()
                        != ticket.expectedRevision()) {
            return stalePublication(ticket);
        }

        Chunk detached =
                Chunk.fromCanonicalBytes(
                        data.worldHeight(), data.copyBlocks());
        long publishedRevision;
        List<DirtyCandidate> neighborCandidates = null;
        synchronized (generationAttempts) {
            StreamingRequest request = liveStreamingRequest(ticket);
            if (request == null) {
                return stalePublication(ticket);
            }

            if (ticket.sourcePreference()
                    == SourcePreference.GENERATE) {
                GenerationAttempt attempt =
                        generationAttempts.byKey.get(key);
                if (request.generationAttempt == null
                        || attempt == null
                        || attempt != request.generationAttempt
                        || attempt.unloading
                        || attempt.status
                                != ChunkGenerationStatus.GENERATING) {
                    return stalePublication(ticket);
                }
                ChunkGenerationResult result =
                        commitPreparedGeneration(
                                request.generationAttempt.ticket,
                                detached);
                if (result.status()
                        != ChunkGenerationResult.Status.COMMITTED) {
                    invalidateStreamingRequest(request);
                    return stalePublication(ticket);
                }
                publishedRevision = result.revision();
            } else if (ticket.expectedRevision() == 0) {
                publishedRevision =
                        publishInitialGeneration(
                                key, detached,
                                baseIdentity.persistedRevision());
                if (publishedRevision == 0) {
                    invalidateStreamingRequest(request);
                    return stalePublication(ticket);
                }
            } else {
                Entry entry = entries.get(key);
                if (entry == null) {
                    invalidateStreamingRequest(request);
                    return stalePublication(ticket);
                }
                synchronized (entry) {
                    if (entries.get(key) != entry
                            || entry.state == ChunkState.UNLOADING
                            || entry.revision
                                    != ticket.expectedRevision()) {
                        invalidateStreamingRequest(request);
                        return stalePublication(ticket);
                    }
                    if (!prepareEntryMutation(key, entry)) {
                        invalidateStreamingRequest(request);
                        return stalePublication(ticket);
                    }
                    ChangedMeshingBoundaries changedBoundaries =
                            changedMeshingBoundaries(
                                    entry.chunk, detached);
                    neighborCandidates =
                            dirtyCandidates(
                                    changedMeshingNeighborKeys(
                                            key, changedBoundaries),
                                    key);
                    publishedRevision =
                            reserveRevisions(
                                    1 + neighborCandidates.size());
                    entry.chunk = detached;
                    entry.revision = publishedRevision;
                    entry.persistedRevision =
                            baseIdentity.persistedRevision();
                    entry.failure = null;
                    entry.state = ChunkState.DIRTY;
                }
            }
            request.published = true;
        }

        if (neighborCandidates != null) {
            dirtyChangedLoadedNeighbors(
                    neighborCandidates, publishedRevision);
        }
        return new ChunkStreamingPublication(
                ChunkStreamingPublication.Status.PUBLISHED,
                key,
                ticket.epoch(),
                publishedRevision);
    }

    public boolean cancel(ChunkStreamingTicket ticket) {
        Objects.requireNonNull(ticket, "ticket");
        ChunkCoordinatePolicy.requireSafe(ticket.key());
        synchronized (generationAttempts) {
            StreamingRequest request = liveStreamingRequest(ticket);
            if (request == null) {
                return false;
            }
            invalidateStreamingRequest(request);
            return true;
        }
    }

    public ChunkAvailability availability(ChunkKey key) {
        ChunkKey checkedKey = ChunkCoordinatePolicy.requireSafe(key);
        Entry entry = entries.get(checkedKey);
        if (entry != null) {
            synchronized (entry) {
                if (entries.get(checkedKey) == entry
                        && entry.state != ChunkState.EMPTY
                        && entry.state != ChunkState.GENERATING
                        && entry.state != ChunkState.LOADING
                        && entry.state != ChunkState.UNLOADING) {
                    return entry.failure == null
                            ? ChunkAvailability.AVAILABLE
                            : ChunkAvailability.FAILED;
                }
            }
        }
        synchronized (generationAttempts) {
            StreamingRequests requests =
                    generationAttempts.streamingByKey.get(checkedKey);
            if (requests != null) {
                return ChunkAvailability.UNKNOWN;
            }
            GenerationAttempt attempt =
                    generationAttempts.byKey.get(checkedKey);
            return attempt != null
                            && !attempt.unloading
                            && attempt.status
                                    == ChunkGenerationStatus.FAILED
                    ? ChunkAvailability.FAILED
                    : ChunkAvailability.UNKNOWN;
        }
    }

    public ChunkGenerationTicket beginGeneration(
            ChunkKey key, ChunkGenerationMode mode) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(mode, "mode");

        synchronized (generationAttempts) {
            StreamingRequest streaming =
                    currentStreamingRequest(key);
            if (streaming != null
                    && !streaming.published) {
                throw new IllegalStateException(
                        "Chunk "
                                + key
                                + " already has an active streaming request");
            }
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
        return commitPreparedGeneration(ticket, detached);
    }

    private ChunkGenerationResult commitPreparedGeneration(
            ChunkGenerationTicket ticket, Chunk detached) {
        ChunkKey key = ticket.key();
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
                        publishInitialGeneration(key, detached, 0L);
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
                    if (!prepareEntryMutation(key, entry)) {
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
            if (attempt == null
                    || (attempt.unloading
                            && attempt.status
                                    == ChunkGenerationStatus.GENERATING)) {
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
                                        created.voxelModified = true;
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
                StreamingUnloadPreparation preparedUnload =
                        generationAttempts.streamingUnloadPreparations.get(key);
                if (preparedUnload != null
                        && preparedUnload.entry == entry
                        && preparedUnload.finalValidated) {
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
                if (!prepareEntryMutation(key, entry)) {
                    return unchangedOutcome(
                            ChunkMutationOutcome.Status.CONFLICT,
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
                entry.voxelModified = true;
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

    public ChunkUnloadPreparation prepareStreamingUnload(ChunkKey key) {
        requireStreamingOwnerThread();
        ChunkKey checkedKey = ChunkCoordinatePolicy.requireSafe(key);
        synchronized (generationAttempts) {
            if (generationAttempts.streamingUnloadPreparations.containsKey(checkedKey)) {
                return ChunkUnloadPreparation.empty(
                        ChunkUnloadPreparation.Status.ALREADY_PREPARED);
            }
            Entry entry = entries.get(checkedKey);
            if (entry == null) {
                return ChunkUnloadPreparation.empty(
                        ChunkUnloadPreparation.Status.NOT_RESIDENT);
            }
            synchronized (entry) {
                if (entries.get(checkedKey) != entry
                        || entry.state == ChunkState.EMPTY
                        || entry.state == ChunkState.GENERATING
                        || entry.state == ChunkState.LOADING
                        || entry.state == ChunkState.UNLOADING
                        || entry.revision <= 0) {
                    return ChunkUnloadPreparation.empty(
                            ChunkUnloadPreparation.Status.NOT_RESIDENT);
                }
                byte[] blocks = new byte[canonicalBlockCount()];
                entry.chunk.copyBlocksTo(blocks);
                ChunkSnapshot capture = ChunkSnapshot.of(
                        checkedKey, entry.revision, worldHeight, blocks);
                ChunkUnloadTicket ticket = new ChunkUnloadTicket(
                        streamingUnloadIssuer,
                        Thread.currentThread(),
                        checkedKey);
                StreamingUnloadPreparation prepared =
                        new StreamingUnloadPreparation(
                                ticket,
                                entry,
                                entry.revision,
                                entry.persistedRevision,
                                entry.state,
                                entry.failure);
                generationAttempts.streamingUnloadPreparations.put(
                        checkedKey, prepared);
                return ChunkUnloadPreparation.prepared(
                        ticket,
                        capture,
                        prepared.persistedRevision,
                        entry.voxelModified,
                        prepared.freshness::get);
            }
        }
    }

    public ChunkUnloadResult validateStreamingUnload(
            ChunkUnloadTicket ticket) {
        return inspectStreamingUnload(ticket, false, false);
    }

    /**
     * Records the exact durable revision produced for a prepared capture.
     * This does not validate or commit eviction and never changes voxel state,
     * gameplay revision, or lifecycle state.
     */
    public ChunkUnloadResult acknowledgeStreamingPersistence(
            ChunkUnloadTicket ticket, long durableRevision) {
        Objects.requireNonNull(ticket, "ticket");
        requireStreamingOwnerThread();
        ticket.requireOwnerThread();
        if (!ticket.belongsTo(streamingUnloadIssuer)) {
            return new ChunkUnloadResult(ChunkUnloadResult.Status.FOREIGN);
        }
        synchronized (generationAttempts) {
            ChunkKey key = ticket.key();
            StreamingUnloadPreparation prepared =
                    generationAttempts.streamingUnloadPreparations.get(key);
            if (prepared == null || prepared.ticket != ticket
                    || durableRevision != prepared.revision) {
                return new ChunkUnloadResult(ChunkUnloadResult.Status.STALE);
            }
            Entry entry = prepared.entry;
            synchronized (entry) {
                if (entries.get(key) != entry
                        || entry.persistedRevision != prepared.persistedRevision
                        || durableRevision < prepared.persistedRevision) {
                    return new ChunkUnloadResult(ChunkUnloadResult.Status.STALE);
                }
                entry.persistedRevision = durableRevision;
                prepared.persistedRevision = durableRevision;
                return new ChunkUnloadResult(ChunkUnloadResult.Status.VALID);
            }
        }
    }

    public ChunkUnloadResult cancelStreamingUnload(
            ChunkUnloadTicket ticket) {
        return inspectStreamingUnload(ticket, true, false);
    }

    public ChunkUnloadResult commitStreamingUnload(
            ChunkUnloadTicket ticket) {
        return inspectStreamingUnload(ticket, true, true);
    }

    private ChunkUnloadResult inspectStreamingUnload(
            ChunkUnloadTicket ticket, boolean consume, boolean commit) {
        Objects.requireNonNull(ticket, "ticket");
        requireStreamingOwnerThread();
        ticket.requireOwnerThread();
        if (!ticket.belongsTo(streamingUnloadIssuer)) {
            return new ChunkUnloadResult(ChunkUnloadResult.Status.FOREIGN);
        }
        synchronized (generationAttempts) {
            ChunkKey key = ticket.key();
            StreamingUnloadPreparation prepared =
                    generationAttempts.streamingUnloadPreparations.get(key);
            if (prepared == null || prepared.ticket != ticket) {
                return new ChunkUnloadResult(ChunkUnloadResult.Status.STALE);
            }
            Entry entry = prepared.entry;
            synchronized (entry) {
                boolean live = entries.get(key) == entry
                        && entry.revision == prepared.revision
                        && entry.persistedRevision
                                == prepared.persistedRevision
                        && entry.state == prepared.state
                        && entry.failure == prepared.failure;
                if (!live) {
                    prepared.invalidate();
                    generationAttempts.streamingUnloadPreparations.remove(key, prepared);
                    return new ChunkUnloadResult(
                            ChunkUnloadResult.Status.STALE);
                }
                if (!consume) {
                    prepared.finalValidated = true;
                    return new ChunkUnloadResult(
                            ChunkUnloadResult.Status.VALID);
                }
                generationAttempts.streamingUnloadPreparations.remove(key, prepared);
                prepared.invalidate();
                if (!commit) {
                    return new ChunkUnloadResult(
                            ChunkUnloadResult.Status.CANCELED);
                }
                if (!entries.remove(key, entry)) {
                    throw new IllegalStateException(
                            "Validated pinned Chunk could not be removed");
                }
                return new ChunkUnloadResult(
                        ChunkUnloadResult.Status.COMMITTED);
            }
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
        return restoreCanonical(snapshot, Map.of());
    }

    /**
     * Fresh-target restore with exact durable identities for resident Chunks
     * that were decoded from the current streamed authority.
     */
    public ChunkRepositoryRestoreResult restoreCanonical(
            ChunkRepositorySnapshot snapshot,
            Map<ChunkKey, Long> persistedRevisions) {
        Objects.requireNonNull(snapshot, "snapshot");
        List<RestoredChunk> restoredChunks =
                validateCompleteSnapshot(snapshot);
        if (restoredChunks == null) {
            return ChunkRepositoryRestoreResult.rejected(
                    ChunkRepositoryRestoreResult.Status.INVALID_SNAPSHOT);
        }
        final Map<ChunkKey, Long> checkedPersisted;
        try {
            checkedPersisted = Map.copyOf(Objects.requireNonNull(
                    persistedRevisions, "persistedRevisions"));
            Map<ChunkKey, RestoredChunk> restoredByKey = restoredChunks.stream()
                    .collect(java.util.stream.Collectors.toUnmodifiableMap(
                            RestoredChunk::key, value -> value));
            for (var binding : checkedPersisted.entrySet()) {
                ChunkKey key = ChunkCoordinatePolicy.requireSafe(binding.getKey());
                long revision = Objects.requireNonNull(
                        binding.getValue(), "persisted revision");
                RestoredChunk restored = restoredByKey.get(key);
                if (restored == null || revision <= 0L
                        || revision > restored.revision()) {
                    return ChunkRepositoryRestoreResult.rejected(
                            ChunkRepositoryRestoreResult.Status.INVALID_SNAPSHOT);
                }
            }
        } catch (RuntimeException invalid) {
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
                entry.persistedRevision = checkedPersisted.getOrDefault(
                        restored.key(), 0L);
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
        return claimMeshingCapability(key).map(ChunkMeshingClaim::input);
    }

    Optional<ChunkMeshingClaim> claimMeshingCapability(ChunkKey key) {
        Objects.requireNonNull(key, "key");
        Entry entry = entries.get(key);
        if (entry == null) {
            return Optional.empty();
        }

        long claimedRevision;
        long claimedId;
        synchronized (entry) {
            if (entries.get(key) != entry
                    || !isMeshingCandidate(entry)
                    || !prepareEntryMutation(key, entry)) {
                return Optional.empty();
            }
            claimedRevision = entry.revision;
            claimedId = nextMeshingClaimId();
            entry.state = ChunkState.MESHING;
            entry.meshingClaimId = claimedId;
            entry.meshingClaimQueued = true;
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
                    || entry.meshingClaimId != claimedId
                    || !entry.meshingClaimQueued
                    || center.isEmpty()
                    || center.orElseThrow().revision()
                            != claimedRevision) {
                if (entry.state == ChunkState.MESHING
                        && entry.revision == claimedRevision) {
                    if (prepareEntryMutation(key, entry)) {
                        entry.state = ChunkState.DIRTY;
                        entry.meshingClaimId = 0L;
                        entry.meshingClaimQueued = false;
                    }
                }
                return Optional.empty();
            }
            return Optional.of(
                    new ChunkMeshingClaim(
                            claimedId,
                            key,
                            claimedRevision,
                            new ChunkMeshInput(
                                    center.orElseThrow(),
                                    north,
                                    northEast,
                                    east,
                                    southEast,
                                    south,
                                    southWest,
                                    west,
                                    northWest)));
        }
    }

    /**
     * Releases one exact not-yet-started meshing claim selected by its owner.
     * The key and revision bind the resident incarnation; stale/replayed calls
     * fail without changing repository state.
     */
    boolean releaseQueuedMeshingClaim(
            ChunkKey key, long revision, long claimId) {
        requireStreamingOwnerThread();
        if (claimId <= 0L) {
            return false;
        }
        ChunkKey checked = ChunkCoordinatePolicy.requireSafe(key);
        Entry entry = entries.get(checked);
        if (entry == null) {
            return false;
        }
        synchronized (entry) {
            if (entries.get(checked) != entry
                    || entry.state != ChunkState.MESHING
                    || entry.revision != revision
                    || entry.meshingClaimId != claimId
                    || !entry.meshingClaimQueued
                    || !prepareEntryMutation(checked, entry)) {
                return false;
            }
            entry.state = ChunkState.DIRTY;
            entry.meshingClaimId = 0L;
            entry.meshingClaimQueued = false;
            return true;
        }
    }

    boolean markMeshingClaimActive(
            ChunkKey key, long revision, long claimId) {
        if (claimId <= 0L) {
            return false;
        }
        ChunkKey checked = ChunkCoordinatePolicy.requireSafe(key);
        Entry entry = entries.get(checked);
        if (entry == null) {
            return false;
        }
        synchronized (entry) {
            if (entries.get(checked) != entry
                    || entry.state != ChunkState.MESHING
                    || entry.revision != revision
                    || entry.meshingClaimId != claimId
                    || !entry.meshingClaimQueued) {
                return false;
            }
            entry.meshingClaimQueued = false;
            return true;
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
                    || entry.revision != revision
                    || !prepareEntryMutation(key, entry)) {
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
                    || entry.revision != revision
                    || !prepareEntryMutation(key, entry)) {
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
        ChunkKey checkedKey = ChunkCoordinatePolicy.requireSafe(key);
        List<DirtyCandidate> neighborCandidates;
        long unloadRevision;
        synchronized (generationAttempts) {
            if (generationAttempts.streamingUnloadPreparations.containsKey(checkedKey)) {
                return false;
            }
            StreamingRequests requests =
                    generationAttempts.streamingByKey.get(checkedKey);
            StreamingRequest streaming =
                    requests == null ? null : requests.current;
            GenerationAttempt attempt =
                    generationAttempts.byKey.get(checkedKey);
            boolean generationCancelled =
                    attempt != null
                            && !attempt.unloading
                            && attempt.status
                                    == ChunkGenerationStatus.GENERATING;
            boolean streamingInvalidated =
                    streaming != null;
            Entry entry = entries.get(checkedKey);
            if (entry == null) {
                if (generationCancelled) {
                    attempt.unloading = true;
                }
                if (streamingInvalidated) {
                    beginStreamingUnload(
                            checkedKey,
                            requests,
                            streaming,
                            null);
                }
                return generationCancelled || streamingInvalidated;
            }
            neighborCandidates =
                    dirtyCandidates(
                            dirtyTracker.meshingNeighbors(checkedKey),
                            checkedKey);
            synchronized (entry) {
                if (entries.get(checkedKey) != entry
                        || entry.state == ChunkState.UNLOADING) {
                    if (generationCancelled) {
                        attempt.unloading = true;
                    }
                    if (streamingInvalidated) {
                        beginStreamingUnload(
                                checkedKey,
                                requests,
                                streaming,
                                null);
                    }
                    return generationCancelled || streamingInvalidated;
                }
                unloadRevision =
                        reserveRevisions(
                                1 + neighborCandidates.size());
                if (attempt != null && !attempt.unloading) {
                    attempt.unloading = true;
                }
                if (streamingInvalidated) {
                    beginStreamingUnload(
                            checkedKey,
                            requests,
                            streaming,
                            entry);
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
        ChunkKey checkedKey = ChunkCoordinatePolicy.requireSafe(key);
        List<StreamingRequest> expectedStreaming =
                unloadingStreamingRequests(checkedKey);
        GenerationAttempt expectedAttempt =
                unloadingGenerationAttempt(checkedKey);
        Entry entry = entries.get(checkedKey);
        if (entry == null) {
            boolean streamingCleared =
                    clearStreamingAfterKeyUnload(
                            checkedKey, expectedStreaming);
            return clearGenerationAfterUnload(
                            checkedKey, expectedAttempt)
                    || streamingCleared;
        }
        boolean removed;
        synchronized (entry) {
            if (entries.get(checkedKey) != entry
                    || entry.state != ChunkState.UNLOADING) {
                return false;
            }
            removed = entries.remove(checkedKey, entry);
        }
        if (removed) {
            clearGenerationAfterUnload(
                    checkedKey, expectedAttempt);
            clearStreamingAfterKeyUnload(
                    checkedKey, expectedStreaming);
        }
        return removed;
    }

    public boolean completeUnload(ChunkStreamingTicket ticket) {
        Objects.requireNonNull(ticket, "ticket");
        ChunkKey key = ChunkCoordinatePolicy.requireSafe(ticket.key());
        synchronized (generationAttempts) {
            StreamingRequests requests =
                    generationAttempts.streamingByKey.get(key);
            StreamingRequest expectedStreaming =
                    unloadingStreamingRequest(requests, ticket);
            if (expectedStreaming == null
                    || expectedStreaming.ticket != ticket) {
                return false;
            }
            Entry expectedEntry = expectedStreaming.unloadEntry;
            if (expectedEntry != null) {
                synchronized (expectedEntry) {
                    if (entries.get(key) != expectedEntry
                            || expectedEntry.state
                                    != ChunkState.UNLOADING
                            || !entries.remove(key, expectedEntry)) {
                        return false;
                    }
                }
            }
            GenerationAttempt expectedAttempt =
                    expectedStreaming.unloadGenerationAttempt;
            if (expectedAttempt != null
                    && generationAttempts.byKey.get(key)
                            == expectedAttempt) {
                generationAttempts.byKey.remove(
                        key, expectedAttempt);
            }
            requests.unloading.remove(expectedStreaming);
            removeEmptyStreamingRequests(key, requests);
            return true;
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
                    && entry.revision == revision
                    && prepareEntryMutation(key, entry)) {
                entry.failure = failure;
                entry.state = ChunkState.DIRTY;
                return true;
            }
            return false;
        }
    }

    public boolean retry(ChunkKey key) {
        Objects.requireNonNull(key, "key");
        Entry entry = entries.get(key);
        if (entry == null) {
            return false;
        }
        synchronized (entry) {
            if (entries.get(key) == entry
                    && entry.state == ChunkState.DIRTY
                    && prepareEntryMutation(key, entry)) {
                boolean retried = entry.failure != null;
                entry.failure = null;
                return retried;
            }
            return false;
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
        for (StreamingRequests requests :
                generationAttempts.streamingByKey.values()) {
            if (requests.current != null
                    && !requests.current.published) {
                return true;
            }
            for (StreamingRequest request : requests.unloading) {
                if (!request.published) {
                    return true;
                }
            }
        }
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
                    || entry.revision >= reservedRevision
                    || !prepareEntryMutation(candidate.key(), entry)) {
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

    private boolean prepareEntryMutation(ChunkKey key, Entry entry) {
        StreamingUnloadPreparation prepared =
                generationAttempts.streamingUnloadPreparations.get(key);
        if (prepared == null || prepared.entry != entry) {
            return true;
        }
        if (prepared.finalValidated) {
            return false;
        }
        prepared.invalidate();
        return true;
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
            ChunkKey key, Chunk detached, long persistedRevision) {
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
                                created.persistedRevision = persistedRevision;
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

    private long nextMeshingClaimId() {
        long next = meshingClaimSequence.incrementAndGet();
        if (next <= 0L) {
            throw new IllegalStateException("meshing claim id space exhausted");
        }
        return next;
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

    private GenerationAttempt unloadingGenerationAttempt(
            ChunkKey key) {
        synchronized (generationAttempts) {
            GenerationAttempt attempt =
                    generationAttempts.byKey.get(key);
            return attempt != null && attempt.unloading
                    ? attempt
                    : null;
        }
    }

    private StreamingRequest liveStreamingRequest(
            ChunkStreamingTicket ticket) {
        StreamingRequest request = currentStreamingRequest(ticket.key());
        if (request == null
                || request.ticket != ticket
                || request.published) {
            return null;
        }
        return request;
    }

    private void invalidateStreamingRequest(
            StreamingRequest request) {
        ChunkKey key = request.ticket.key();
        StreamingRequests requests =
                generationAttempts.streamingByKey.get(key);
        if (requests == null || requests.current != request) {
            return;
        }
        invalidateCurrentStreamingRequest(key, requests, request);
        removeEmptyStreamingRequests(key, requests);
    }

    private void invalidateCurrentStreamingRequest(
            ChunkKey key,
            StreamingRequests requests,
            StreamingRequest request) {
        if (requests.current != request) {
            return;
        }
        requests.current = null;
        GenerationAttempt attempt =
                generationAttempts.byKey.get(key);
        if (attempt != null
                && attempt == request.generationAttempt) {
            generationAttempts.byKey.remove(key, attempt);
        }
    }

    private List<StreamingRequest> unloadingStreamingRequests(
            ChunkKey key) {
        synchronized (generationAttempts) {
            StreamingRequests requests =
                    generationAttempts.streamingByKey.get(key);
            return requests == null
                    ? List.of()
                    : List.copyOf(requests.unloading);
        }
    }

    private boolean clearStreamingAfterKeyUnload(
            ChunkKey key,
            List<StreamingRequest> expectedRequests) {
        synchronized (generationAttempts) {
            StreamingRequests requests =
                    generationAttempts.streamingByKey.get(key);
            if (requests == null || expectedRequests.isEmpty()) {
                return false;
            }
            boolean removed = false;
            for (StreamingRequest expected : expectedRequests) {
                if (requests.unloading.remove(expected)) {
                    clearBoundGenerationAttempt(key, expected);
                    removed = true;
                }
            }
            removeEmptyStreamingRequests(key, requests);
            return removed;
        }
    }

    private void beginStreamingUnload(
            ChunkKey key,
            StreamingRequests requests,
            StreamingRequest request,
            Entry entry) {
        if (requests.current != request) {
            return;
        }
        request.unloadEntry = entry;
        request.unloadGenerationAttempt = request.generationAttempt;
        requests.current = null;
        requests.unloading.add(request);
    }

    private void clearBoundGenerationAttempt(
            ChunkKey key, StreamingRequest request) {
        GenerationAttempt expected = request.unloadGenerationAttempt;
        if (expected != null
                && generationAttempts.byKey.get(key) == expected) {
            generationAttempts.byKey.remove(key, expected);
        }
    }

    private StreamingRequest currentStreamingRequest(ChunkKey key) {
        StreamingRequests requests =
                generationAttempts.streamingByKey.get(key);
        return requests == null ? null : requests.current;
    }

    private boolean hasUnloadingStreamingRequest(ChunkKey key) {
        StreamingRequests requests =
                generationAttempts.streamingByKey.get(key);
        return requests != null && !requests.unloading.isEmpty();
    }

    private static StreamingRequest latestUnloadingRequest(
            StreamingRequests requests) {
        if (requests == null || requests.unloading.isEmpty()) {
            return null;
        }
        return requests.unloading.get(requests.unloading.size() - 1);
    }

    private static StreamingRequest unloadingStreamingRequest(
            StreamingRequests requests,
            ChunkStreamingTicket ticket) {
        if (requests == null) {
            return null;
        }
        for (StreamingRequest request : requests.unloading) {
            if (request.ticket == ticket) {
                return request;
            }
        }
        return null;
    }

    private void removeEmptyStreamingRequests(
            ChunkKey key, StreamingRequests requests) {
        if (requests.current == null
                && requests.unloading.isEmpty()) {
            generationAttempts.streamingByKey.remove(key, requests);
        }
    }

    private long reserveStreamingRequestId() {
        while (true) {
            long current = streamingRequestSequence.get();
            if (current == Long.MAX_VALUE) {
                throw new IllegalStateException(
                        "Chunk streaming request sequence is exhausted");
            }
            long next = current + 1;
            if (streamingRequestSequence.compareAndSet(current, next)) {
                return next;
            }
        }
    }

    private ChunkStreamingPublication stalePublication(
            ChunkStreamingTicket ticket) {
        return new ChunkStreamingPublication(
                ChunkStreamingPublication.Status.STALE,
                ticket.key(),
                ticket.epoch(),
                revision(ticket.key()));
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
        private final Map<ChunkKey, StreamingRequests> streamingByKey =
                new HashMap<>();
        private final Map<ChunkKey, StreamingUnloadPreparation>
                streamingUnloadPreparations = new ConcurrentHashMap<>();
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

    private static final class StreamingRequests {
        private StreamingRequest current;
        private final List<StreamingRequest> unloading =
                new ArrayList<>();
    }

    private static final class StreamingRequest {
        private final ChunkStreamingTicket ticket;
        private final GenerationAttempt generationAttempt;
        private boolean published;
        private Entry unloadEntry;
        private GenerationAttempt unloadGenerationAttempt;

        private StreamingRequest(
                ChunkStreamingTicket ticket,
                GenerationAttempt generationAttempt) {
            this.ticket = ticket;
            this.generationAttempt = generationAttempt;
        }
    }

    private static final class Entry {
        private Chunk chunk;
        private ChunkState state = ChunkState.EMPTY;
        private long revision;
        private long persistedRevision;
        private Throwable failure;
        private boolean voxelModified;
        private long meshingClaimId;
        private boolean meshingClaimQueued;

        private Entry(int worldHeight) {
            chunk = new Chunk(worldHeight);
        }

        private Entry(Chunk chunk) {
            this.chunk = chunk;
        }
    }

    private static final class StreamingUnloadPreparation {
        private final ChunkUnloadTicket ticket;
        private final Entry entry;
        private final long revision;
        private long persistedRevision;
        private final ChunkState state;
        private final Throwable failure;
        private final AtomicBoolean freshness = new AtomicBoolean(true);
        private boolean finalValidated;

        private StreamingUnloadPreparation(
                ChunkUnloadTicket ticket,
                Entry entry,
                long revision,
                long persistedRevision,
                ChunkState state,
                Throwable failure) {
            this.ticket = ticket;
            this.entry = entry;
            this.revision = revision;
            this.persistedRevision = persistedRevision;
            this.state = state;
            this.failure = failure;
        }

        private void invalidate() {
            freshness.set(false);
        }
    }

    private void requireStreamingOwnerThread() {
        if (Thread.currentThread() != streamingOwnerThread) {
            throw new IllegalStateException(
                    "Chunk streaming unload must run on its repository owner thread");
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

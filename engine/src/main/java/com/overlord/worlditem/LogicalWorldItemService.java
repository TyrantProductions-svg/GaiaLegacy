package com.overlord.worlditem;

import com.overlord.core.thread.MainThreadGuard;
import com.overlord.core.transaction.ReservationTerminalState;
import com.overlord.inventory.api.ItemStack;
import com.overlord.voxel.ChunkCoordinatePolicy;
import com.overlord.voxel.ChunkKey;
import com.overlord.worlditem.api.LogicalWorldItemSnapshot;
import com.overlord.worlditem.api.WorldItemActivationResult;
import com.overlord.worlditem.api.WorldItemActivationTicket;
import com.overlord.worlditem.api.WorldItemHibernatePayload;
import com.overlord.worlditem.api.WorldItemHibernateResult;
import com.overlord.worlditem.api.WorldItemHibernateTicket;
import com.overlord.worlditem.api.WorldItemId;
import com.overlord.worlditem.api.WorldItemMotionUpdate;
import com.overlord.worlditem.api.WorldItemMotionUpdateResult;
import com.overlord.worlditem.api.WorldItemPhysicalSnapshot;
import com.overlord.worlditem.api.WorldItemPhysicalState;
import com.overlord.worlditem.api.WorldItemReservation;
import com.overlord.worlditem.api.WorldItemReservationAudit;
import com.overlord.worlditem.api.WorldItemReservationAuditSnapshot;
import com.overlord.worlditem.api.WorldItemReservationId;
import com.overlord.worlditem.api.WorldItemReservationResult;
import com.overlord.worlditem.api.WorldItemRestoreEntry;
import com.overlord.worlditem.api.WorldItemRestoreResult;
import com.overlord.worlditem.api.WorldItemRuntimeSnapshot;
import com.overlord.worlditem.api.WorldItemRuntimeAccess;
import com.overlord.worlditem.api.SaveIdentity;
import com.overlord.worlditem.api.WorldItemDurabilityVerifier;
import com.overlord.worlditem.api.WorldItemDurablePageProof;
import com.overlord.worlditem.api.WorldItemDurableProof;
import com.overlord.worlditem.api.WorldItemLiveMetadata;
import com.overlord.worlditem.api.WorldItemLiveState;
import com.overlord.worlditem.api.WorldItemPageCachePolicy;
import com.overlord.worlditem.api.WorldItemPageDescriptor;
import com.overlord.worlditem.api.WorldItemPageMutation;
import com.overlord.worlditem.api.WorldItemPageReadView;
import com.overlord.worlditem.api.WorldItemPageSnapshot;
import com.overlord.worlditem.api.WorldItemPagingCheckpoint;
import com.overlord.worlditem.api.WorldItemPagingMetrics;
import com.overlord.worlditem.api.WorldItemPersistencePlan;
import com.overlord.worlditem.api.WorldItemPersistenceTicket;
import com.overlord.worlditem.api.WorldItemService;
import com.overlord.worlditem.api.WorldItemSnapshot;
import com.overlord.worlditem.api.WorldItemSpawnCommitResult;
import com.overlord.worlditem.api.WorldItemSpawnRequest;
import com.overlord.worlditem.api.WorldItemSpawnReservation;
import com.overlord.worlditem.api.WorldItemSpawnReservationAudit;
import com.overlord.worlditem.api.WorldItemSpawnReservationAuditSnapshot;
import com.overlord.worlditem.api.WorldItemSpawnReservationId;
import com.overlord.worlditem.api.WorldItemSpawnReservations;
import com.overlord.worlditem.api.WorldItemSpawnReserveResult;
import com.overlord.worlditem.api.WorldItemSpawnResult;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.function.Function;
import java.util.function.ToLongFunction;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Main-thread logical backend; Phase 11 may attach PhysicsBody presentation later. */
public final class LogicalWorldItemService
        implements WorldItemService, WorldItemSpawnReservations, WorldItemRuntimeAccess,
                WorldItemReservationAudit, WorldItemSpawnReservationAudit, AutoCloseable {
    private static final RestorePublicationProbe NOOP_RESTORE_PUBLICATION_PROBE =
            (detached, snapshot, success) -> { };

    private final MainThreadGuard mainThreadGuard;
    private final int capacity;
    private final long pickupDelayTicks;
    private final RestorePublicationProbe restorePublicationProbe;
    private final SaveIdentity pagingSaveIdentity;
    private final WorldItemPageCachePolicy pagingPolicy;
    private final WorldItemDurabilityVerifier durabilityVerifier;
    private final Function<WorldItemPageSnapshot, WorldItemPageDescriptor> descriptorFactory;
    private final ToLongFunction<WorldItemPageSnapshot> pageByteSizer;
    private final boolean strictWorldClock;
    private final Map<WorldItemId, WorldItemLiveMetadata> liveMetadata =
            new LinkedHashMap<>();
    private final WorldItemExpiryIndex expiryIndex;
    private final WorldItemPageCache pageCache;
    private final Map<WorldItemPersistenceTicket, PersistencePreparation>
            persistenceTickets = new IdentityHashMap<>();
    private final Map<WorldItemId, WorldItemPersistenceTicket> persistenceIdOwners =
            new HashMap<>();
    private final Map<ChunkKey, WorldItemPersistenceTicket> persistencePageOwners =
            new HashMap<>();
    private final Map<ChunkKey, WorldItemPageDescriptor> pageDescriptors =
            new LinkedHashMap<>();
    private final LinkedHashMap<ChunkKey, CleanupIntent> cleanupIntents =
            new LinkedHashMap<>();
    private long droppedCleanupIntents;
    private long cleanupWrittenBytes;
    private long currentWorldTick;
    private final AtomicLong detachedWorldTick = new AtomicLong();
    private long checkpointRevision;
    private long checkpointWorldTick = -1L;
    private long cacheAccessOrder;
    private LiveState liveState = LiveState.fresh();
    private final Object pagingTicketAuthority = new Object();
    private final Map<WorldItemHibernateTicket, HibernatePreparation> hibernateTickets =
            new IdentityHashMap<>();
    private final Map<WorldItemActivationTicket, ActivationPreparation> activationTickets =
            new IdentityHashMap<>();
    private long pagingEpoch;
    private final AtomicLong detachedPagingEpoch = new AtomicLong();
    private final Map<WorldItemReservationId, ExtractionState> extractionReservations =
            new HashMap<>();
    private final Map<WorldItemId, WorldItemReservationId> activeExtractions =
            new HashMap<>();
    private final Map<WorldItemSpawnReservationId, SpawnState> spawnReservations =
            new HashMap<>();
    private long nextExtractionReservationId;
    private long nextSpawnReservationId;
    private boolean extractionIdsExhausted;
    private boolean spawnIdsExhausted;
    private boolean projectionCallbackActive;
    private boolean closed;

    public LogicalWorldItemService(
            MainThreadGuard mainThreadGuard, int capacity, long pickupDelayTicks) {
        this(mainThreadGuard, capacity, pickupDelayTicks,
                NOOP_RESTORE_PUBLICATION_PROBE, null,
                new WorldItemPageCachePolicy(
                        Math.min(capacity, 1_024), 32, 16L * 1_024L * 1_024L,
                        64, Math.min(capacity, 1_024), 16L * 1_024L * 1_024L,
                        64, 64L * 1_024L),
                (ticket, plan, proof) -> {
                    throw new IllegalArgumentException(
                            "legacy service has no durability verifier");
                },
                LogicalWorldItemService::fallbackDescriptor,
                LogicalWorldItemService::estimatedPageBytes,
                false);
    }

    public LogicalWorldItemService(
            MainThreadGuard mainThreadGuard,
            int capacity,
            long pickupDelayTicks,
            SaveIdentity saveIdentity,
            WorldItemPageCachePolicy pagingPolicy,
            WorldItemDurabilityVerifier durabilityVerifier,
            Function<WorldItemPageSnapshot, WorldItemPageDescriptor> descriptorFactory) {
        this(mainThreadGuard, capacity, pickupDelayTicks, saveIdentity, pagingPolicy,
                durabilityVerifier, descriptorFactory,
                LogicalWorldItemService::estimatedPageBytes);
    }

    public LogicalWorldItemService(
            MainThreadGuard mainThreadGuard,
            int capacity,
            long pickupDelayTicks,
            SaveIdentity saveIdentity,
            WorldItemPageCachePolicy pagingPolicy,
            WorldItemDurabilityVerifier durabilityVerifier,
            Function<WorldItemPageSnapshot, WorldItemPageDescriptor> descriptorFactory,
            ToLongFunction<WorldItemPageSnapshot> pageByteSizer) {
        this(mainThreadGuard, capacity, pickupDelayTicks,
                NOOP_RESTORE_PUBLICATION_PROBE, saveIdentity, pagingPolicy,
                durabilityVerifier, descriptorFactory, pageByteSizer, true);
    }

    LogicalWorldItemService(
            MainThreadGuard mainThreadGuard,
            int capacity,
            long pickupDelayTicks,
            RestorePublicationProbe restorePublicationProbe) {
        this(mainThreadGuard, capacity, pickupDelayTicks, restorePublicationProbe,
                null,
                new WorldItemPageCachePolicy(
                        Math.min(capacity, 1_024), 32, 16L * 1_024L * 1_024L,
                        64, Math.min(capacity, 1_024), 16L * 1_024L * 1_024L,
                        64, 64L * 1_024L),
                (ticket, plan, proof) -> {
                    throw new IllegalArgumentException(
                            "legacy service has no durability verifier");
                },
                LogicalWorldItemService::fallbackDescriptor,
                LogicalWorldItemService::estimatedPageBytes,
                false);
    }

    private LogicalWorldItemService(
            MainThreadGuard mainThreadGuard,
            int capacity,
            long pickupDelayTicks,
            RestorePublicationProbe restorePublicationProbe,
            SaveIdentity saveIdentity,
            WorldItemPageCachePolicy pagingPolicy,
            WorldItemDurabilityVerifier durabilityVerifier,
            Function<WorldItemPageSnapshot, WorldItemPageDescriptor> descriptorFactory,
            ToLongFunction<WorldItemPageSnapshot> pageByteSizer,
            boolean strictWorldClock) {
        this.mainThreadGuard = Objects.requireNonNull(mainThreadGuard, "mainThreadGuard");
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        if (pickupDelayTicks < 0) {
            throw new IllegalArgumentException("pickupDelayTicks must be non-negative");
        }
        this.capacity = capacity;
        this.pickupDelayTicks = pickupDelayTicks;
        this.restorePublicationProbe = Objects.requireNonNull(
                restorePublicationProbe, "restorePublicationProbe");
        this.pagingSaveIdentity = saveIdentity;
        this.pagingPolicy = Objects.requireNonNull(pagingPolicy, "pagingPolicy");
        if (pagingPolicy.maxLiveMetadata() < capacity) {
            throw new IllegalArgumentException(
                    "paging metadata bound must cover logical capacity");
        }
        this.durabilityVerifier = Objects.requireNonNull(
                durabilityVerifier, "durabilityVerifier");
        this.descriptorFactory = Objects.requireNonNull(
                descriptorFactory, "descriptorFactory");
        this.pageByteSizer = Objects.requireNonNull(pageByteSizer, "pageByteSizer");
        this.pageCache = new WorldItemPageCache(pagingPolicy);
        this.expiryIndex = new WorldItemExpiryIndex(pagingPolicy.maxLiveMetadata());
        this.strictWorldClock = strictWorldClock;
    }

    @Override
    public WorldItemSpawnResult spawn(WorldItemSpawnRequest request) {
        assertMutationAllowed("world item spawn");
        Objects.requireNonNull(request, "request");
        WorldItemSpawnReserveResult held = reserveSpawn(request);
        if (held.status() == WorldItemSpawnReserveResult.Status.REJECTED) {
            return new WorldItemSpawnResult(
                    request,
                    WorldItemSpawnResult.Status.REJECTED,
                    Optional.empty(),
                    Optional.of(request.stack()));
        }
        WorldItemSpawnCommitResult committed =
                commitSpawn(held.reservation().orElseThrow().id());
        if (committed.status() != WorldItemSpawnCommitResult.Status.COMMITTED) {
            throw new IllegalStateException("fresh spawn reservation did not commit");
        }
        return new WorldItemSpawnResult(
                request,
                WorldItemSpawnResult.Status.SPAWNED,
                committed.item(),
                Optional.empty());
    }

    @Override
    public Optional<WorldItemSnapshot> snapshot(WorldItemId itemId) {
        assertMainThread("world item snapshot");
        ItemState state = liveState.items.get(Objects.requireNonNull(itemId, "itemId"));
        return state == null ? Optional.empty() : Optional.of(state.item);
    }

    public Optional<WorldItemRuntimeSnapshot> runtimeSnapshot(WorldItemId itemId) {
        assertMainThread("world item runtime snapshot");
        ItemState state = liveState.items.get(Objects.requireNonNull(itemId, "itemId"));
        return state == null ? Optional.empty() : Optional.of(state.runtimeSnapshot());
    }

    @Override
    public List<WorldItemPhysicalSnapshot> physicalSnapshots() {
        assertMainThread("world item physical snapshots");
        return liveState.items.values().stream()
                .map(state -> state.physicalSnapshot(
                        activeExtractions.containsKey(state.item.id())))
                .sorted(Comparator.comparingLong(snapshot -> snapshot.id().value()))
                .toList();
    }

    @Override
    public Optional<WorldItemPhysicalSnapshot> physicalSnapshot(WorldItemId itemId) {
        assertMainThread("world item physical snapshot");
        Objects.requireNonNull(itemId, "itemId");
        ItemState state = liveState.items.get(itemId);
        return state == null
                ? Optional.empty()
                : Optional.of(state.physicalSnapshot(
                        activeExtractions.containsKey(itemId)));
    }

    @Override
    public boolean motionPinnedForPersistence(WorldItemId itemId) {
        assertMainThread("world item persistence motion pin observation");
        Objects.requireNonNull(itemId, "itemId");
        WorldItemLiveMetadata metadata = liveMetadata.get(itemId);
        return metadata != null && metadata.state() == WorldItemLiveState.PENDING;
    }

    @Override
    public WorldItemMotionUpdateResult updateMotion(WorldItemMotionUpdate update) {
        assertMutationAllowed("world item motion update");
        Objects.requireNonNull(update, "update");
        ItemState state = liveState.items.get(update.itemId());
        if (state == null) {
            return new WorldItemMotionUpdateResult(
                    WorldItemMotionUpdateResult.Status.UNKNOWN_ITEM,
                    Optional.empty());
        }

        WorldItemPhysicalSnapshot current = state.physicalSnapshot(
                activeExtractions.containsKey(update.itemId()));
        if (update.expectedRevision() != state.item.revision()) {
            return new WorldItemMotionUpdateResult(
                    WorldItemMotionUpdateResult.Status.STALE_REVISION,
                    Optional.of(current));
        }
        if (!finite(update.positionX())
                || !finite(update.positionY())
                || !finite(update.positionZ())
                || !finite(update.velocityX())
                || !finite(update.velocityY())
                || !finite(update.velocityZ())) {
            return new WorldItemMotionUpdateResult(
                    WorldItemMotionUpdateResult.Status.INVALID_MOTION,
                    Optional.of(current));
        }
        OptionalLong advancedRevision = nextRevision(state.item.revision());
        if (advancedRevision.isEmpty()) {
            return new WorldItemMotionUpdateResult(
                    WorldItemMotionUpdateResult.Status.REVISION_EXHAUSTED,
                    Optional.of(current));
        }

        WorldItemSnapshot next = new WorldItemSnapshot(
                state.item.id(),
                state.item.stack(),
                update.positionX(),
                update.positionY(),
                update.positionZ(),
                update.velocityX(),
                update.velocityY(),
                update.velocityZ(),
                advancedRevision.getAsLong());
        ChunkKey nextChunkKey;
        try {
            nextChunkKey = chunkKeyOf(next);
        } catch (IllegalArgumentException outsideStreamedEnvelope) {
            return new WorldItemMotionUpdateResult(
                    WorldItemMotionUpdateResult.Status.INVALID_MOTION,
                    Optional.of(current));
        }
        state.item = next;
        state.physicalState = update.state();
        WorldItemLiveMetadata metadata = liveMetadata.get(update.itemId());
        if (metadata != null && metadata.state() == WorldItemLiveState.ACTIVE) {
            liveMetadata.put(update.itemId(), metadata.withState(
                    WorldItemLiveState.ACTIVE,
                    nextChunkKey,
                    metadata.intendedPageRevision(),
                    metadata.durableProof()));
        }
        advancePagingEpoch();
        return new WorldItemMotionUpdateResult(
                WorldItemMotionUpdateResult.Status.APPLIED,
                Optional.of(state.physicalSnapshot(
                        activeExtractions.containsKey(update.itemId()))));
    }

    public List<WorldItemSnapshot> snapshots() {
        assertMainThread("world item snapshots");
        List<WorldItemSnapshot> snapshots = new ArrayList<>();
        for (ItemState state : liveState.items.values()) {
            snapshots.add(state.item);
        }
        return List.copyOf(snapshots);
    }

    public LogicalWorldItemSnapshot canonicalSnapshot() {
        assertMainThread("world item canonical snapshot");
        if (hasPendingSpawnReservations()) {
            throw new IllegalStateException(
                    "world item canonical snapshot cannot include a pending spawn reservation");
        }
        if (hasPendingExtractionReservations()) {
            throw new IllegalStateException(
                    "world item canonical snapshot cannot include a pending extraction reservation");
        }

        List<WorldItemRestoreEntry> entries = new ArrayList<>(liveState.itemLocations.size());
        Map<WorldItemId, ChunkKey> dormantChunkKeys = new LinkedHashMap<>();
        for (ItemState state : liveState.items.values()) {
            entries.add(new WorldItemRestoreEntry(
                    state.runtimeSnapshot(), state.physicalState));
        }
        for (Map.Entry<ChunkKey, Map<WorldItemId, ItemState>> bucket
                : liveState.dormantByChunk.entrySet()) {
            for (ItemState state : bucket.getValue().values()) {
                entries.add(new WorldItemRestoreEntry(
                        state.runtimeSnapshot(), state.physicalState));
                dormantChunkKeys.put(state.item.id(), bucket.getKey());
            }
        }
        return new LogicalWorldItemSnapshot(
                entries,
                liveState.nextItemId,
                liveState.itemIdsExhausted,
                dormantChunkKeys,
                pagingStateIsPresent()
                        ? LogicalWorldItemSnapshot.Completeness.PAGED_PARTIAL
                        : LogicalWorldItemSnapshot.Completeness.LEGACY_COMPLETE);
    }

    private boolean pagingStateIsPresent() {
        return pagingSaveIdentity != null
                && (checkpointRevision != 0L
                        || !pageDescriptors.isEmpty()
                        || !persistenceTickets.isEmpty()
                        || !hibernateTickets.isEmpty()
                        || !cleanupIntents.isEmpty());
    }

    public WorldItemRestoreResult restoreCanonical(LogicalWorldItemSnapshot snapshot) {
        return restoreCanonical(snapshot, currentWorldTick);
    }

    public WorldItemRestoreResult restoreCanonical(
            LogicalWorldItemSnapshot snapshot, long authoritativeWorldTick) {
        assertMutationAllowed("world item canonical restore");
        Objects.requireNonNull(snapshot, "snapshot");
        if (authoritativeWorldTick < 0L) {
            throw new IllegalArgumentException(
                    "authoritativeWorldTick must not be negative");
        }
        if (snapshot.completeness()
                == LogicalWorldItemSnapshot.Completeness.PAGED_PARTIAL) {
            throw new IllegalArgumentException(
                    "paged WorldItem state requires the streamed restore boundary");
        }

        RestoreValidation validation = validateRestore(
                snapshot, authoritativeWorldTick);
        if (validation.status != WorldItemRestoreResult.Status.RESTORED) {
            return restoreResult(validation.status, 0);
        }
        if (!isFreshTarget()) {
            return restoreResult(WorldItemRestoreResult.Status.TARGET_NOT_FRESH, 0);
        }

        LiveState detached = validation.liveState;
        Map<WorldItemId, WorldItemLiveMetadata> detachedMetadata =
                legacyMetadata(detached);
        WorldItemRestoreResult success = restoreResult(
                WorldItemRestoreResult.Status.RESTORED,
                detached.itemLocations.size());
        restorePublicationProbe.beforePublication(detached, snapshot, success);
        liveState = detached;
        liveMetadata.clear();
        liveMetadata.putAll(detachedMetadata);
        expiryIndex.clear();
        detachedMetadata.values().forEach(metadata ->
                expiryIndex.put(metadata.id(), metadata.expiresAtWorldTick()));
        setCurrentWorldTick(authoritativeWorldTick);
        advancePagingEpoch();
        return success;
    }

    private static Map<WorldItemId, WorldItemLiveMetadata> legacyMetadata(
            LiveState state) {
        Map<WorldItemId, WorldItemLiveMetadata> result = new LinkedHashMap<>();
        for (ItemState item : state.items.values()) {
            result.put(item.item.id(), new WorldItemLiveMetadata(
                    item.item.id(),
                    chunkKeyOf(item.item),
                    0L,
                    item.expiresAtWorldTick,
                    WorldItemLiveState.ACTIVE,
                    Optional.empty()));
        }
        for (Map.Entry<ChunkKey, Map<WorldItemId, ItemState>> bucket
                : state.dormantByChunk.entrySet()) {
            for (ItemState item : bucket.getValue().values()) {
                result.put(item.item.id(), new WorldItemLiveMetadata(
                        item.item.id(),
                        bucket.getKey(),
                        0L,
                        item.expiresAtWorldTick,
                        WorldItemLiveState.DECODED_DORMANT,
                        Optional.empty()));
            }
        }
        return result;
    }

    /** Advances the sole authoritative simulation clock and expires due IDs exactly. */
    public List<WorldItemId> deliverWorldTick(long worldTick) {
        return deliverWorldTick(worldTick, () -> {});
    }

    /**
     * Advances the authoritative clock and publishes semantic expiry around one
     * detached projection transaction.
     */
    public List<WorldItemId> deliverWorldTick(
            long worldTick, Runnable projectionPublication) {
        assertMutationAllowed("world item world tick");
        Objects.requireNonNull(projectionPublication, "projectionPublication");
        if (worldTick < currentWorldTick) {
            throw new IllegalArgumentException("world tick cannot move backwards");
        }
        long previousWorldTick = currentWorldTick;
        long previousPagingEpoch = pagingEpoch;
        LiveState previousLiveState = liveState;
        Map<WorldItemId, WorldItemLiveMetadata> liveMetadataBefore =
                new LinkedHashMap<>(liveMetadata);
        setCurrentWorldTick(worldTick);
        List<WorldItemId> due = expiryIndex.drainDue(worldTick);
        Map<WorldItemId, WorldItemLiveMetadata> removedMetadata =
                new LinkedHashMap<>();
        for (WorldItemId id : due) {
            WorldItemLiveMetadata removed = liveMetadata.remove(id);
            if (removed == null) {
                throw new IllegalStateException("expiry index and metadata diverged");
            }
            removedMetadata.put(id, removed);
        }
        if (due.isEmpty()) {
            return List.of();
        }
        Map<WorldItemReservationId, ExtractionState> extractionReservationsBefore =
                new HashMap<>(extractionReservations);
        Map<WorldItemId, WorldItemReservationId> activeExtractionsBefore =
                new HashMap<>(activeExtractions);
        Map<WorldItemSpawnReservationId, SpawnState> spawnReservationsBefore =
                new HashMap<>(spawnReservations);
        liveState = liveState.without(due);
        for (WorldItemId id : due) {
            removeCanonicalItem(id);
        }
        advancePagingEpoch();
        try {
            runProjectionCallback(projectionPublication);
        } catch (RuntimeException | Error failure) {
            liveState = previousLiveState;
            liveMetadata.clear();
            liveMetadata.putAll(liveMetadataBefore);
            for (WorldItemLiveMetadata metadata : removedMetadata.values()) {
                expiryIndex.put(metadata.id(), metadata.expiresAtWorldTick());
            }
            setCurrentWorldTick(previousWorldTick);
            setPagingEpoch(previousPagingEpoch);
            extractionReservations.clear();
            extractionReservations.putAll(extractionReservationsBefore);
            activeExtractions.clear();
            activeExtractions.putAll(activeExtractionsBefore);
            spawnReservations.clear();
            spawnReservations.putAll(spawnReservationsBefore);
            throw failure;
        }
        for (WorldItemId id : due) {
            WorldItemLiveMetadata removed = removedMetadata.get(id);
            stalePagingTicketsFor(id);
            removed.durableProof().ifPresent(proof -> enqueueCleanup(proof.chunkKey()));
            if (removed.durableProof().isEmpty()
                    && !pageDescriptors.containsKey(removed.intendedChunkKey())
                    && liveMetadata.values().stream().noneMatch(metadata ->
                            metadata.intendedChunkKey().equals(
                                    removed.intendedChunkKey()))) {
                pageCache.remove(removed.intendedChunkKey());
            }
        }
        return List.copyOf(due);
    }

    public long currentWorldTick() {
        assertMainThread("world item world tick observation");
        return currentWorldTick;
    }

    public List<WorldItemLiveMetadata> liveMetadata() {
        assertMainThread("world item live metadata observation");
        return liveMetadata.values().stream()
                .sorted(Comparator.comparingLong(value -> value.id().value()))
                .toList();
    }

    public WorldItemPagingMetrics pagingMetrics() {
        assertMainThread("world item paging metrics");
        int active = 0;
        int dormant = 0;
        int evicted = 0;
        int pending = 0;
        for (WorldItemLiveMetadata metadata : liveMetadata.values()) {
            switch (metadata.state()) {
                case ACTIVE -> active++;
                case DECODED_DORMANT -> dormant++;
                case EVICTED_UNEXPIRED -> evicted++;
                case PENDING -> pending++;
            }
        }
        WorldItemPageCache.Metrics cache = pageCache.metrics();
        long cleanupBytes = (long) cleanupIntents.size() * 64L;
        int zeroLiveDescriptors = (int) pageDescriptors.values().stream()
                .filter(descriptor -> descriptor.expectedLiveCountAtCheckpointTick() == 0)
                .count();
        int tombstones = (int) cleanupIntents.values().stream()
                .filter(intent -> intent.replacement() == null)
                .count();
        long droppedCleanupBytes = droppedCleanupIntents > Long.MAX_VALUE / 64L
                ? Long.MAX_VALUE
                : droppedCleanupIntents * 64L;
        return new WorldItemPagingMetrics(
                liveMetadata.size(), expiryIndex.size(), active, dormant, evicted, pending,
                cache.decodedPageCount(), cache.decodedPageBytes(), cache.pinnedPageCount(),
                cache.dirtyEntryCount(), cache.dirtyBytes(),
                zeroLiveDescriptors, cache.unprovedPinnedPageCount(),
                cleanupIntents.size(), cleanupBytes, tombstones, cleanupWrittenBytes,
                droppedCleanupIntents, droppedCleanupBytes,
                persistenceTickets.size(), activationTickets.size(), pageDescriptors.size(),
                projectionCallbackActive ? 1 : 0);
    }

    /** Exact immutable active-ID/revision observation for one canonical Chunk. */
    public Map<WorldItemId, Long> activeRevisionsInChunk(ChunkKey chunkKey) {
        assertMainThread("world item active revision observation");
        ChunkKey checkedKey = ChunkCoordinatePolicy.requireSafe(chunkKey);
        Map<WorldItemId, Long> revisions = new LinkedHashMap<>();
        for (ItemState state : activeItemsInChunk(checkedKey)) {
            revisions.put(state.item.id(), state.item.revision());
        }
        return Collections.unmodifiableMap(revisions);
    }

    /** Captures an immutable, side-effect-free persistence payload for one active Chunk. */
    public WorldItemHibernateResult prepareHibernate(
            ChunkKey chunkKey, Map<WorldItemId, Long> expectedRevisions) {
        assertMutationAllowed("world item hibernation prepare");
        ChunkKey checkedKey = ChunkCoordinatePolicy.requireSafe(chunkKey);
        Objects.requireNonNull(expectedRevisions, "expectedRevisions");
        if (outstandingPagingOperations() >= pagingPolicy.maxPagingTickets()) {
            return hibernateResult(WorldItemHibernateResult.Status.TICKET_LIMIT);
        }

        List<ItemState> selected = activeItemsInChunk(checkedKey);
        if (hasPendingSpawnInChunk(checkedKey)
                || selected.stream().anyMatch(
                        state -> activeExtractions.containsKey(state.item.id()))) {
            return hibernateResult(WorldItemHibernateResult.Status.RESERVED);
        }
        if (expectedRevisions.size() != selected.size()) {
            return hibernateResult(WorldItemHibernateResult.Status.INVALID_REQUEST);
        }
        if (selected.isEmpty()) {
            return hibernateResult(WorldItemHibernateResult.Status.INVALID_REQUEST);
        }

        List<WorldItemRestoreEntry> entries = new ArrayList<>(selected.size());
        for (ItemState state : selected) {
            Long expected = expectedRevisions.get(state.item.id());
            if (expected == null || expected.longValue() != state.item.revision()) {
                return hibernateResult(WorldItemHibernateResult.Status.STALE_REVISION);
            }
            entries.add(new WorldItemRestoreEntry(
                    state.runtimeSnapshot(), state.physicalState));
        }
        for (WorldItemId expectedId : expectedRevisions.keySet()) {
            Objects.requireNonNull(expectedId, "expected item id");
            ItemState state = liveState.items.get(expectedId);
            if (state == null || !checkedKey.equals(chunkKeyOf(state.item))) {
                return hibernateResult(WorldItemHibernateResult.Status.WRONG_CHUNK);
            }
        }

        WorldItemHibernatePayload payload = new WorldItemHibernatePayload(
                checkedKey,
                entries,
                liveState.nextItemId,
                liveState.itemIdsExhausted);
        WorldItemHibernateTicket ticket =
                WorldItemHibernateTicket.issuedBy(pagingTicketAuthority);
        if (pagingSaveIdentity == null) {
            hibernateTickets.put(ticket, new HibernatePreparation(
                    pagingEpoch, checkedKey, payload, null));
            return new WorldItemHibernateResult(
                    WorldItemHibernateResult.Status.PREPARED,
                    Optional.of(ticket), Optional.of(payload));
        }
        if (selected.stream().anyMatch(state ->
                persistenceIdOwners.containsKey(state.item.id()))) {
            return hibernateResult(WorldItemHibernateResult.Status.RESERVED);
        }
        Set<ChunkKey> touchedKeys = new HashSet<>();
        touchedKeys.add(checkedKey);
        Map<ChunkKey, Set<WorldItemId>> movedFrom = new LinkedHashMap<>();
        for (ItemState state : selected) {
            WorldItemLiveMetadata metadata = liveMetadata.get(state.item.id());
            if (metadata != null && metadata.durableProof().isPresent()) {
                ChunkKey oldKey = metadata.durableProof().orElseThrow().chunkKey();
                if (!oldKey.equals(checkedKey)) {
                    movedFrom.computeIfAbsent(oldKey, ignored -> new HashSet<>())
                            .add(state.item.id());
                    touchedKeys.add(oldKey);
                }
            }
        }
        if (touchedKeys.stream().anyMatch(persistencePageOwners::containsKey)) {
            return hibernateResult(WorldItemHibernateResult.Status.RESERVED);
        }

        List<WorldItemPageMutation> mutations = new ArrayList<>();
        Map<ChunkKey, WorldItemPageSnapshot> candidatePages = new LinkedHashMap<>();
        Map<ChunkKey, WorldItemPageDescriptor> candidateDescriptors =
                new LinkedHashMap<>();
        Map<ChunkKey, WorldItemPageDescriptor> intendedDescriptors =
                recountedDescriptors();

        for (Map.Entry<ChunkKey, Set<WorldItemId>> move : movedFrom.entrySet()) {
            ChunkKey sourceKey = move.getKey();
            WorldItemPageDescriptor old = pageDescriptors.get(sourceKey);
            WorldItemPageSnapshot oldPage = pageCache.page(sourceKey);
            if (old == null || oldPage == null) {
                return hibernateResult(WorldItemHibernateResult.Status.PAGE_NOT_RESIDENT);
            }
            List<WorldItemRestoreEntry> survivors = livePageEntries(
                    oldPage, move.getValue());
            if (survivors.isEmpty()) {
                mutations.add(new WorldItemPageMutation.Remove(old));
                intendedDescriptors.remove(sourceKey);
            } else {
                WorldItemPageSnapshot rewrite = new WorldItemPageSnapshot(
                        sourceKey, Math.addExact(old.pageRevision(), 1L), survivors);
                WorldItemPageDescriptor rewritten = requireExactDescriptor(rewrite);
                mutations.add(new WorldItemPageMutation.Upsert(
                        rewrite, Optional.of(old)));
                candidatePages.put(sourceKey, rewrite);
                candidateDescriptors.put(sourceKey, rewritten);
                intendedDescriptors.put(sourceKey, rewritten);
            }
        }

        List<WorldItemRestoreEntry> destinationEntries = new ArrayList<>();
        WorldItemPageSnapshot residentDestination = pageCache.page(checkedKey);
        WorldItemPageDescriptor expectedDestination = pageDescriptors.get(checkedKey);
        if (expectedDestination != null && residentDestination == null) {
            return hibernateResult(WorldItemHibernateResult.Status.PAGE_NOT_RESIDENT);
        }
        if (residentDestination != null) {
            Set<WorldItemId> selectedIds = selected.stream()
                    .map(state -> state.item.id()).collect(java.util.stream.Collectors.toSet());
            destinationEntries.addAll(livePageEntries(
                    residentDestination, selectedIds));
        }
        destinationEntries.addAll(entries);
        destinationEntries.sort(Comparator.comparingLong(
                entry -> entry.runtime().item().id().value()));
        long pageRevision = expectedDestination == null
                ? 1L
                : Math.addExact(expectedDestination.pageRevision(), 1L);
        WorldItemPageSnapshot page = new WorldItemPageSnapshot(
                checkedKey, pageRevision, destinationEntries);
        WorldItemPageDescriptor descriptor = requireExactDescriptor(page);
        Optional<WorldItemPageDescriptor> expectedPrevious =
                Optional.ofNullable(expectedDestination);
        if (expectedDestination == null
                && intendedDescriptors.size()
                        >= WorldItemPagingCheckpoint.MAX_PAGE_DESCRIPTORS) {
            WorldItemPageDescriptor stale = intendedDescriptors.values().stream()
                    .filter(value -> value.expectedLiveCountAtCheckpointTick() == 0)
                    .filter(value -> !touchedKeys.contains(value.chunkKey()))
                    .filter(value -> !persistencePageOwners.containsKey(value.chunkKey()))
                    .min(Comparator.comparing(
                            WorldItemPageDescriptor::chunkKey,
                            ChunkCoordinatePolicy.canonicalComparator()))
                    .orElse(null);
            if (stale == null
                    || mutations.size() + 2 > WorldItemPersistencePlan.MAX_PAGES) {
                return hibernateResult(WorldItemHibernateResult.Status.INVALID_REQUEST);
            }
            mutations.add(new WorldItemPageMutation.Remove(
                    pageDescriptors.get(stale.chunkKey())));
            intendedDescriptors.remove(stale.chunkKey());
            touchedKeys.add(stale.chunkKey());
        }
        mutations.add(new WorldItemPageMutation.Upsert(page, expectedPrevious));
        candidatePages.put(checkedKey, page);
        candidateDescriptors.put(checkedKey, descriptor);
        intendedDescriptors.put(checkedKey, descriptor);
        if (mutations.size() > WorldItemPersistencePlan.MAX_PAGES) {
            return hibernateResult(WorldItemHibernateResult.Status.INVALID_REQUEST);
        }
        long intendedRevision = Math.addExact(checkpointRevision, 1L);
        int durableSurvivors = intendedDescriptors.values().stream()
                .mapToInt(WorldItemPageDescriptor::expectedLiveCountAtCheckpointTick)
                .sum();
        WorldItemPagingCheckpoint intended = new WorldItemPagingCheckpoint(
                requirePagingSaveIdentity(), intendedRevision, currentWorldTick,
                liveState.nextItemId, liveState.itemIdsExhausted,
                durableSurvivors, new ArrayList<>(intendedDescriptors.values()));
        String digest = transactionDigest(intended, mutations);
        WorldItemPersistenceTicket persistenceTicket =
                WorldItemPersistenceTicket.issuedBy(pagingTicketAuthority);
        AtomicBoolean detachedFreshness = new AtomicBoolean(true);
        long preparedEpoch = pagingEpoch;
        WorldItemPersistencePlan plan = new WorldItemPersistencePlan(
                checkpointRevision, intended, mutations, digest,
                () -> detachedFreshness.get()
                        && detachedPagingEpoch.get() == preparedEpoch);
        Map<WorldItemId, WorldItemLiveMetadata> before = new LinkedHashMap<>();
        for (ItemState state : selected) {
            WorldItemLiveMetadata metadata = liveMetadata.get(state.item.id());
            if (metadata != null) {
                before.put(state.item.id(), metadata);
                liveMetadata.put(state.item.id(), metadata.withState(
                        WorldItemLiveState.PENDING, checkedKey, pageRevision,
                        metadata.durableProof()));
            }
        }
        List<WorldItemPageCache.DirtyCandidate> cacheCandidates = new ArrayList<>();
        for (Map.Entry<ChunkKey, WorldItemPageSnapshot> candidate
                 : candidatePages.entrySet()) {
            cacheCandidates.add(new WorldItemPageCache.DirtyCandidate(
                    candidateDescriptors.get(candidate.getKey()),
                    candidate.getValue(), pageBytes(candidate.getValue()),
                    ++cacheAccessOrder));
        }
        WorldItemPageCache.Admission admission =
                pageCache.admitDirtyBatch(cacheCandidates);
        if (admission != WorldItemPageCache.Admission.ADMITTED) {
            restoreMetadataMap(before);
            return hibernateResult(
                    admission == WorldItemPageCache.Admission.ALL_PINNED
                            ? WorldItemHibernateResult.Status.ALL_PINNED
                            : WorldItemHibernateResult.Status.DIRTY_LIMIT);
        }
        hibernateTickets.put(ticket, new HibernatePreparation(
                pagingEpoch, checkedKey, payload, persistenceTicket));
        persistenceTickets.put(persistenceTicket, new PersistencePreparation(
                PersistenceKind.HIBERNATE, pagingEpoch, plan, detachedFreshness,
                ticket, before,
                page, descriptor, candidatePages, candidateDescriptors,
                List.copyOf(selected.stream()
                        .map(state -> state.item.id()).toList()),
                List.of(), touchedKeys));
        for (WorldItemId id : before.keySet()) {
            persistenceIdOwners.put(id, persistenceTicket);
        }
        for (ChunkKey key : touchedKeys) {
            persistencePageOwners.put(key, persistenceTicket);
        }
        return new WorldItemHibernateResult(
                WorldItemHibernateResult.Status.PREPARED,
                Optional.of(ticket),
                Optional.of(payload),
                Optional.of(persistenceTicket),
                Optional.of(plan));
    }

    /** Moves the exact prepared active items to the service-owned dormant bucket. */
    public WorldItemHibernateResult commitHibernate(WorldItemHibernateTicket ticket) {
        return commitHibernate(ticket, () -> {});
    }

    /** Moves prepared items around one detached projection publication transaction. */
    public WorldItemHibernateResult commitHibernate(
            WorldItemHibernateTicket ticket, Runnable projectionPublication) {
        assertMutationAllowed("world item hibernation commit");
        Objects.requireNonNull(ticket, "ticket");
        Objects.requireNonNull(projectionPublication, "projectionPublication");
        if (!ticket.belongsTo(pagingTicketAuthority)) {
            return hibernateResult(WorldItemHibernateResult.Status.FOREIGN_TICKET);
        }
        HibernatePreparation prepared = hibernateTickets.get(ticket);
        if (prepared == null) {
            return hibernateResult(WorldItemHibernateResult.Status.STALE_TICKET);
        }
        if (prepared.epoch != pagingEpoch
                || hasPendingSpawnInChunk(prepared.chunkKey)) {
            hibernateTickets.remove(ticket);
            return hibernateResult(WorldItemHibernateResult.Status.STALE_TICKET);
        }
        for (WorldItemRestoreEntry entry : prepared.payload.entries()) {
            WorldItemSnapshot expected = entry.runtime().item();
            ItemState current = liveState.items.get(expected.id());
            if (current == null
                    || activeExtractions.containsKey(expected.id())
                    || !expected.equals(current.item)
                    || current.physicalState != entry.physicalState()
                    || !prepared.chunkKey.equals(chunkKeyOf(current.item))) {
                hibernateTickets.remove(ticket);
                return hibernateResult(WorldItemHibernateResult.Status.STALE_TICKET);
            }
        }

        LiveState before = liveState;
        long epochBefore = pagingEpoch;
        Map<WorldItemId, WorldItemLiveMetadata> metadataBefore =
                new LinkedHashMap<>();
        liveState = liveState.hibernate(prepared.chunkKey, prepared.payload.entries());
        for (WorldItemRestoreEntry entry : prepared.payload.entries()) {
            WorldItemLiveMetadata metadata = liveMetadata.get(entry.runtime().item().id());
            if (metadata != null) {
                metadataBefore.put(metadata.id(), metadata);
                liveMetadata.put(metadata.id(), metadata.withState(
                        WorldItemLiveState.DECODED_DORMANT,
                        prepared.chunkKey,
                        metadata.intendedPageRevision(),
                        metadata.durableProof()));
            }
        }
        advancePagingEpoch();
        try {
            runProjectionCallback(projectionPublication);
        } catch (RuntimeException | Error failure) {
            liveState = before;
            liveMetadata.putAll(metadataBefore);
            setPagingEpoch(epochBefore);
            throw failure;
        }
        hibernateTickets.remove(ticket);
        if (prepared.persistenceTicket != null) {
            PersistencePreparation persistence =
                    persistenceTickets.remove(prepared.persistenceTicket);
            if (persistence != null) {
                releasePersistenceOwnership(persistence, prepared.persistenceTicket);
            }
        }
        return hibernateResult(WorldItemHibernateResult.Status.COMMITTED);
    }

    /**
     * Atomically publishes one durable hibernation and its detached projection
     * removal. The linked capabilities remain retryable until publication succeeds.
     */
    public WorldItemHibernateResult commitLinkedHibernate(
            WorldItemHibernateTicket hibernateTicket,
            WorldItemPersistenceTicket persistenceTicket,
            WorldItemDurableProof proof,
            Runnable projectionPublication) {
        assertMutationAllowed("linked world item hibernation commit");
        Objects.requireNonNull(hibernateTicket, "hibernateTicket");
        Objects.requireNonNull(persistenceTicket, "persistenceTicket");
        Objects.requireNonNull(proof, "proof");
        Objects.requireNonNull(projectionPublication, "projectionPublication");
        if (!hibernateTicket.belongsTo(pagingTicketAuthority)
                || !persistenceTicket.belongsTo(pagingTicketAuthority)) {
            return hibernateResult(WorldItemHibernateResult.Status.FOREIGN_TICKET);
        }
        HibernatePreparation hibernate = hibernateTickets.get(hibernateTicket);
        PersistencePreparation persistence =
                persistenceTickets.get(persistenceTicket);
        if (hibernate == null || persistence == null) {
            return hibernateResult(WorldItemHibernateResult.Status.STALE_TICKET);
        }
        if (hibernate.persistenceTicket != persistenceTicket
                || persistence.hibernateTicket != hibernateTicket
                || persistence.kind != PersistenceKind.HIBERNATE) {
            return hibernateResult(WorldItemHibernateResult.Status.STALE_TICKET);
        }
        if (!linkedHibernateStillCurrent(hibernate, persistence)) {
            return hibernateResult(WorldItemHibernateResult.Status.STALE_TICKET);
        }
        durabilityVerifier.verify(persistenceTicket, persistence.plan, proof);
        return publishLinkedHibernatePersistence(
                hibernateTicket,
                persistenceTicket,
                persistence,
                projectionPublication);
    }

    private boolean linkedHibernateStillCurrent(
            HibernatePreparation hibernate,
            PersistencePreparation persistence) {
        if (hibernate.epoch != pagingEpoch
                || persistence.epoch != pagingEpoch
                || hibernate.epoch != persistence.epoch
                || hasPendingSpawnInChunk(hibernate.chunkKey)
                || !persistence.plan.stillCurrent().getAsBoolean()
                || !capturedItemsStillExact(persistence)
                || persistence.plan.expectedCheckpointRevision()
                        != checkpointRevision) {
            return false;
        }
        for (WorldItemRestoreEntry entry : hibernate.payload.entries()) {
            WorldItemSnapshot expected = entry.runtime().item();
            ItemState current = liveState.items.get(expected.id());
            if (current == null
                    || activeExtractions.containsKey(expected.id())
                    || !expected.equals(current.item)
                    || current.physicalState != entry.physicalState()
                    || !hibernate.chunkKey.equals(chunkKeyOf(current.item))) {
                return false;
            }
        }
        return true;
    }

    private WorldItemHibernateResult publishLinkedHibernatePersistence(
            WorldItemHibernateTicket hibernateTicket,
            WorldItemPersistenceTicket persistenceTicket,
            PersistencePreparation prepared,
            Runnable projectionPublication) {
        long committedEpoch = Math.addExact(pagingEpoch, 1L);
        LinkedHibernateState before = new LinkedHibernateState(
                liveState,
                new LinkedHashMap<>(liveMetadata),
                checkpointRevision,
                checkpointWorldTick,
                new LinkedHashMap<>(pageDescriptors),
                pageCache.snapshot(),
                cacheAccessOrder,
                new HashMap<>(extractionReservations),
                new HashMap<>(activeExtractions),
                new HashMap<>(spawnReservations));
        try {
            checkpointRevision =
                    prepared.plan.intendedCheckpoint().checkpointRevision();
            checkpointWorldTick = prepared.plan.intendedCheckpoint().worldTick();
            pageDescriptors.clear();
            for (WorldItemPageDescriptor descriptor
                    : prepared.plan.intendedCheckpoint().pages()) {
                pageDescriptors.put(descriptor.chunkKey(), descriptor);
            }
            for (Map.Entry<ChunkKey, WorldItemPageSnapshot> published
                    : prepared.publishedPages.entrySet()) {
                WorldItemPageDescriptor descriptor =
                        prepared.publishedDescriptors.get(published.getKey());
                WorldItemDurablePageProof pageProof = new WorldItemDurablePageProof(
                        descriptor.chunkKey(),
                        descriptor.pageRevision(),
                        descriptor.pageHash());
                for (WorldItemRestoreEntry entry : published.getValue().entries()) {
                    WorldItemId id = entry.runtime().item().id();
                    WorldItemLiveMetadata metadata = liveMetadata.get(id);
                    if (metadata != null) {
                        WorldItemLiveState state = prepared.itemIds.contains(id)
                                ? WorldItemLiveState.EVICTED_UNEXPIRED
                                : metadata.state();
                        liveMetadata.put(id, metadata.withState(
                                state,
                                published.getKey(),
                                descriptor.pageRevision(),
                                Optional.of(pageProof)));
                    }
                }
                WorldItemPageCache.Admission admission = pageCache.admitClean(
                        descriptor,
                        published.getValue(),
                        pageBytes(published.getValue()),
                        ++cacheAccessOrder);
                if (admission != WorldItemPageCache.Admission.ADMITTED) {
                    throw new IllegalStateException(
                            "prepared durable WorldItem page no longer fits cache");
                }
                pageCache.unpin(published.getKey());
            }
            liveState = liveState.without(prepared.itemIds);
            for (WorldItemId id : prepared.itemIds) {
                removeCanonicalItem(id);
            }
            runProjectionCallback(projectionPublication);
        } catch (RuntimeException | Error failure) {
            restoreLinkedHibernateState(before);
            throw failure;
        }

        hibernateTickets.remove(hibernateTicket);
        persistenceTickets.remove(persistenceTicket);
        releasePersistenceOwnership(prepared, persistenceTicket);
        setPagingEpoch(committedEpoch);
        return hibernateResult(WorldItemHibernateResult.Status.COMMITTED);
    }

    private void restoreLinkedHibernateState(LinkedHibernateState before) {
        liveState = before.liveState;
        liveMetadata.clear();
        liveMetadata.putAll(before.liveMetadata);
        checkpointRevision = before.checkpointRevision;
        checkpointWorldTick = before.checkpointWorldTick;
        pageDescriptors.clear();
        pageDescriptors.putAll(before.pageDescriptors);
        pageCache.restore(before.pageCache);
        cacheAccessOrder = before.cacheAccessOrder;
        extractionReservations.clear();
        extractionReservations.putAll(before.extractionReservations);
        activeExtractions.clear();
        activeExtractions.putAll(before.activeExtractions);
        spawnReservations.clear();
        spawnReservations.putAll(before.spawnReservations);
    }

    /** Cancels a prepared hibernation without changing canonical item state. */
    public WorldItemHibernateResult cancelHibernate(WorldItemHibernateTicket ticket) {
        assertMutationAllowed("world item hibernation cancel");
        Objects.requireNonNull(ticket, "ticket");
        if (!ticket.belongsTo(pagingTicketAuthority)) {
            return hibernateResult(WorldItemHibernateResult.Status.FOREIGN_TICKET);
        }
        HibernatePreparation removed = hibernateTickets.remove(ticket);
        if (removed == null) {
            return hibernateResult(WorldItemHibernateResult.Status.STALE_TICKET);
        }
        if (removed.persistenceTicket != null) {
            PersistencePreparation persistence =
                    persistenceTickets.remove(removed.persistenceTicket);
            restorePreparedMetadata(persistence);
            pageCache.pin(removed.chunkKey);
            if (persistence != null) {
                releasePersistenceOwnership(persistence, removed.persistenceTicket);
            }
        }
        return hibernateResult(WorldItemHibernateResult.Status.CANCELED);
    }

    /** Publishes a prepared page transition only after the trusted backend proves durability. */
    public WorldItemHibernateResult commitPersistence(
            WorldItemPersistenceTicket ticket, WorldItemDurableProof proof) {
        assertMutationAllowed("world item persistence commit");
        Objects.requireNonNull(ticket, "ticket");
        Objects.requireNonNull(proof, "proof");
        if (!ticket.belongsTo(pagingTicketAuthority)) {
            return hibernateResult(WorldItemHibernateResult.Status.FOREIGN_TICKET);
        }
        PersistencePreparation prepared = persistenceTickets.get(ticket);
        if (prepared == null) {
            return hibernateResult(WorldItemHibernateResult.Status.STALE_TICKET);
        }
        if (!prepared.plan.stillCurrent().getAsBoolean()
                || prepared.epoch != pagingEpoch
                || !capturedItemsStillExact(prepared)
                || prepared.plan.expectedCheckpointRevision() != checkpointRevision
                || prepared.plan.intendedCheckpoint().worldTick()
                        != currentWorldTick) {
            discardPersistence(prepared, ticket, true);
            return hibernateResult(WorldItemHibernateResult.Status.STALE_TICKET);
        }
        durabilityVerifier.verify(ticket, prepared.plan, proof);

        checkpointRevision = prepared.plan.intendedCheckpoint().checkpointRevision();
        checkpointWorldTick = prepared.plan.intendedCheckpoint().worldTick();
        pageDescriptors.clear();
        for (WorldItemPageDescriptor descriptor
                : prepared.plan.intendedCheckpoint().pages()) {
            pageDescriptors.put(descriptor.chunkKey(), descriptor);
        }
        if (prepared.kind == PersistenceKind.HIBERNATE) {
            for (Map.Entry<ChunkKey, WorldItemPageSnapshot> published
                    : prepared.publishedPages.entrySet()) {
                WorldItemPageDescriptor publishedDescriptor =
                        prepared.publishedDescriptors.get(published.getKey());
                WorldItemDurablePageProof pageProof = new WorldItemDurablePageProof(
                        publishedDescriptor.chunkKey(),
                        publishedDescriptor.pageRevision(),
                        publishedDescriptor.pageHash());
                for (WorldItemRestoreEntry entry : published.getValue().entries()) {
                    WorldItemId id = entry.runtime().item().id();
                    WorldItemLiveMetadata metadata = liveMetadata.get(id);
                    if (metadata != null) {
                        WorldItemLiveState state = prepared.itemIds.contains(id)
                                ? WorldItemLiveState.EVICTED_UNEXPIRED
                                : metadata.state();
                        liveMetadata.put(id, metadata.withState(
                                state, published.getKey(),
                                publishedDescriptor.pageRevision(), Optional.of(pageProof)));
                    }
                }
                pageCache.admitClean(
                        publishedDescriptor, published.getValue(),
                        pageBytes(published.getValue()), ++cacheAccessOrder);
                pageCache.unpin(published.getKey());
            }
            for (WorldItemId id : prepared.itemIds) {
                removeCanonicalItem(id);
            }
            if (prepared.hibernateTicket != null) {
                hibernateTickets.remove(prepared.hibernateTicket);
            }
        } else {
            for (Map.Entry<ChunkKey, WorldItemPageSnapshot> published
                    : prepared.publishedPages.entrySet()) {
                WorldItemPageDescriptor publishedDescriptor =
                        prepared.publishedDescriptors.get(published.getKey());
                WorldItemDurablePageProof pageProof = new WorldItemDurablePageProof(
                        publishedDescriptor.chunkKey(),
                        publishedDescriptor.pageRevision(),
                        publishedDescriptor.pageHash());
                for (WorldItemRestoreEntry entry : published.getValue().entries()) {
                    WorldItemId id = entry.runtime().item().id();
                    WorldItemLiveMetadata metadata = liveMetadata.get(id);
                    if (metadata != null) {
                        liveMetadata.put(id, metadata.withState(
                                metadata.state(), published.getKey(),
                                publishedDescriptor.pageRevision(), Optional.of(pageProof)));
                    }
                }
                pageCache.admitClean(
                        publishedDescriptor, published.getValue(),
                        pageBytes(published.getValue()), ++cacheAccessOrder);
            }
            for (ChunkKey key : prepared.cleanupKeys) {
                cleanupIntents.remove(key);
                cleanupWrittenBytes = saturatingAdd(cleanupWrittenBytes, 64L);
                if (!pageDescriptors.containsKey(key)) {
                    pageCache.remove(key);
                } else {
                    pageCache.unpin(key);
                }
            }
        }
        persistenceTickets.remove(ticket);
        releasePersistenceOwnership(prepared, ticket);
        advancePagingEpoch();
        return hibernateResult(WorldItemHibernateResult.Status.COMMITTED);
    }

    public WorldItemHibernateResult cancelPersistence(
            WorldItemPersistenceTicket ticket) {
        assertMutationAllowed("world item persistence cancel");
        Objects.requireNonNull(ticket, "ticket");
        if (!ticket.belongsTo(pagingTicketAuthority)) {
            return hibernateResult(WorldItemHibernateResult.Status.FOREIGN_TICKET);
        }
        PersistencePreparation prepared = persistenceTickets.remove(ticket);
        if (prepared == null) {
            return hibernateResult(WorldItemHibernateResult.Status.STALE_TICKET);
        }
        restorePreparedMetadata(prepared);
        if (prepared.descriptor != null) {
            pageCache.pin(prepared.descriptor.chunkKey());
        }
        if (prepared.hibernateTicket != null) {
            hibernateTickets.remove(prepared.hibernateTicket);
        }
        releasePersistenceOwnership(prepared, ticket);
        return hibernateResult(WorldItemHibernateResult.Status.CANCELED);
    }

    /** Captures one bounded save checkpoint without evicting active gameplay state. */
    public WorldItemHibernateResult prepareSavePersistence() {
        assertMutationAllowed("world item save persistence prepare");
        if (pagingSaveIdentity == null
                || hasPendingSpawnReservations()
                || hasPendingExtractionReservations()
                || outstandingPagingOperations() >= pagingPolicy.maxPagingTickets()) {
            return hibernateResult(WorldItemHibernateResult.Status.RESERVED);
        }

        Set<ChunkKey> touchedKeys = new HashSet<>(cleanupIntents.keySet());
        for (WorldItemLiveMetadata metadata : liveMetadata.values()) {
            if (metadata.state() == WorldItemLiveState.ACTIVE
                    || metadata.state() == WorldItemLiveState.DECODED_DORMANT
                    || metadata.durableProof().isEmpty()) {
                touchedKeys.add(metadata.intendedChunkKey());
                metadata.durableProof().ifPresent(
                        proof -> touchedKeys.add(proof.chunkKey()));
            }
        }
        if (touchedKeys.size() > WorldItemPersistencePlan.MAX_PAGES
                || touchedKeys.stream().anyMatch(persistencePageOwners::containsKey)) {
            return hibernateResult(WorldItemHibernateResult.Status.INVALID_REQUEST);
        }

        Map<ChunkKey, WorldItemPageDescriptor> intended = recountedDescriptors();
        List<WorldItemPageMutation> mutations = new ArrayList<>();
        Map<ChunkKey, WorldItemPageSnapshot> publishedPages = new LinkedHashMap<>();
        Map<ChunkKey, WorldItemPageDescriptor> publishedDescriptors =
                new LinkedHashMap<>();
        Set<WorldItemId> capturedIds = new HashSet<>();
        List<ChunkKey> cleanupKeys = new ArrayList<>();

        List<ChunkKey> orderedKeys = touchedKeys.stream()
                .sorted(ChunkCoordinatePolicy.canonicalComparator())
                .toList();
        for (ChunkKey key : orderedKeys) {
            WorldItemPageDescriptor previous = pageDescriptors.get(key);
            WorldItemPageSnapshot resident = pageCache.page(key);
            if (previous != null && resident == null) {
                return hibernateResult(WorldItemHibernateResult.Status.PAGE_NOT_RESIDENT);
            }

            Map<WorldItemId, WorldItemRestoreEntry> entries = new LinkedHashMap<>();
            if (resident != null) {
                for (WorldItemRestoreEntry entry : resident.entries()) {
                    WorldItemId id = entry.runtime().item().id();
                    WorldItemLiveMetadata metadata = liveMetadata.get(id);
                    if (metadata != null
                            && entry.runtime().expiresAtWorldTick() > currentWorldTick
                            && currentItemState(id) == null
                            && metadata.durableProof().isPresent()
                            && metadata.durableProof().orElseThrow().chunkKey().equals(key)) {
                        entries.put(id, entry);
                    }
                }
            }
            for (WorldItemLiveMetadata metadata : liveMetadata.values()) {
                ItemState state = currentItemState(metadata.id());
                if (state == null || !chunkKeyOf(state.item).equals(key)) {
                    continue;
                }
                entries.put(metadata.id(), new WorldItemRestoreEntry(
                        state.runtimeSnapshot(), state.physicalState));
                capturedIds.add(metadata.id());
            }

            if (entries.isEmpty()) {
                if (previous != null) {
                    mutations.add(new WorldItemPageMutation.Remove(previous));
                    intended.remove(key);
                }
            } else {
                long revision = previous == null
                        ? 1L
                        : Math.addExact(previous.pageRevision(), 1L);
                List<WorldItemRestoreEntry> sortedEntries = entries.values().stream()
                        .sorted(Comparator.comparingLong(
                                entry -> entry.runtime().item().id().value()))
                        .toList();
                WorldItemPageSnapshot page = new WorldItemPageSnapshot(
                        key, revision, sortedEntries);
                WorldItemPageDescriptor descriptor = requireExactDescriptor(page);
                mutations.add(new WorldItemPageMutation.Upsert(
                        page, Optional.ofNullable(previous)));
                intended.put(key, descriptor);
                publishedPages.put(key, page);
                publishedDescriptors.put(key, descriptor);
            }
            if (cleanupIntents.containsKey(key)) {
                cleanupKeys.add(key);
            }
        }

        boolean checkpointAlreadyExact = checkpointRevision != 0L
                && checkpointWorldTick == currentWorldTick
                && mutations.isEmpty()
                && cleanupIntents.isEmpty()
                && liveMetadata.values().stream()
                        .allMatch(metadata -> metadata.durableProof().isPresent());
        if (checkpointAlreadyExact) {
            return hibernateResult(WorldItemHibernateResult.Status.INVALID_REQUEST);
        }

        long intendedRevision = Math.addExact(checkpointRevision, 1L);
        WorldItemPagingCheckpoint checkpoint = new WorldItemPagingCheckpoint(
                requirePagingSaveIdentity(), intendedRevision, currentWorldTick,
                liveState.nextItemId, liveState.itemIdsExhausted,
                liveMetadata.size(), new ArrayList<>(intended.values()));
        WorldItemPersistenceTicket ticket =
                WorldItemPersistenceTicket.issuedBy(pagingTicketAuthority);
        String digest = transactionDigest(checkpoint, mutations);
        AtomicBoolean detachedFreshness = new AtomicBoolean(true);
        long preparedEpoch = pagingEpoch;
        WorldItemPersistencePlan plan = new WorldItemPersistencePlan(
                checkpointRevision, checkpoint, mutations, digest,
                () -> detachedFreshness.get()
                        && detachedPagingEpoch.get() == preparedEpoch
                        && detachedWorldTick.get() == checkpoint.worldTick());

        long preparedBytes = 0L;
        for (WorldItemPageSnapshot page : publishedPages.values()) {
            preparedBytes = Math.addExact(preparedBytes, pageBytes(page));
        }
        if (preparedBytes > pagingPolicy.maxDirtyCandidateBytes()) {
            return hibernateResult(WorldItemHibernateResult.Status.DIRTY_LIMIT);
        }
        PersistencePreparation preparation = new PersistencePreparation(
                PersistenceKind.SAVE, pagingEpoch, plan, detachedFreshness,
                null, Map.of(),
                null, null, publishedPages, publishedDescriptors,
                new ArrayList<>(capturedIds), cleanupKeys, touchedKeys);
        persistenceTickets.put(ticket, preparation);
        for (WorldItemId id : capturedIds) {
            persistenceIdOwners.put(id, ticket);
        }
        for (ChunkKey key : touchedKeys) {
            persistencePageOwners.put(key, ticket);
        }
        return new WorldItemHibernateResult(
                WorldItemHibernateResult.Status.PERSISTENCE_PREPARED,
                Optional.empty(), Optional.empty(), Optional.of(ticket), Optional.of(plan));
    }

    public boolean savePersistenceReady() {
        assertMainThread("world item save persistence readiness");
        return checkpointRevision != 0L
                && checkpointWorldTick == currentWorldTick
                && persistenceTickets.isEmpty()
                && cleanupIntents.isEmpty()
                && liveMetadata.values().stream()
                        .allMatch(metadata -> metadata.durableProof().isPresent());
    }

    /** Prepares at most one bounded cleanup batch; semantic expiry has already happened. */
    public Optional<WorldItemHibernateResult> prepareCleanupPersistence() {
        assertMutationAllowed("world item cleanup persistence prepare");
        if (cleanupIntents.isEmpty()
                || outstandingPagingOperations() >= pagingPolicy.maxPagingTickets()) {
            return Optional.empty();
        }
        List<ChunkKey> keys = cleanupIntents.keySet().stream()
                .filter(key -> !persistencePageOwners.containsKey(key))
                .limit(WorldItemPersistencePlan.MAX_PAGES).toList();
        if (keys.isEmpty()) {
            return Optional.empty();
        }
        List<WorldItemPageMutation> mutations = new ArrayList<>();
        Map<ChunkKey, WorldItemPageDescriptor> intended =
                recountedDescriptors();
        Map<ChunkKey, WorldItemPageSnapshot> publishedPages = new LinkedHashMap<>();
        Map<ChunkKey, WorldItemPageDescriptor> publishedDescriptors =
                new LinkedHashMap<>();
        List<ChunkKey> preparedKeys = new ArrayList<>();
        for (ChunkKey key : keys) {
            WorldItemPageDescriptor current = pageDescriptors.get(key);
            if (current == null) {
                continue;
            }
            CleanupIntent intent = cleanupIntents.get(key);
            long survivors = liveMetadata.values().stream()
                    .filter(metadata -> metadata.durableProof().isPresent())
                    .filter(metadata -> metadata.durableProof().orElseThrow()
                            .chunkKey().equals(key))
                    .count();
            if (survivors == 0L) {
                mutations.add(new WorldItemPageMutation.Remove(current));
                intended.remove(key);
                preparedKeys.add(key);
                continue;
            }
            WorldItemPageSnapshot source = pageCache.page(key);
            if (source == null) {
                source = intent.replacement;
            }
            if (source == null) {
                intended.put(key, current);
                continue;
            }
            List<WorldItemRestoreEntry> currentEntries =
                    currentCleanupEntries(key, source);
            WorldItemPageSnapshot replacement = new WorldItemPageSnapshot(
                    key, Math.addExact(current.pageRevision(), 1L), currentEntries);
            WorldItemPageDescriptor replacementDescriptor =
                    requireExactDescriptor(replacement);
            mutations.add(new WorldItemPageMutation.Upsert(
                    replacement, Optional.of(current)));
            intended.put(key, replacementDescriptor);
            publishedPages.put(key, replacement);
            publishedDescriptors.put(key, replacementDescriptor);
            preparedKeys.add(key);
        }
        if (mutations.isEmpty()) {
            return Optional.empty();
        }
        long nextRevision = Math.addExact(checkpointRevision, 1L);
        int survivorCount = intended.values().stream()
                .mapToInt(WorldItemPageDescriptor::expectedLiveCountAtCheckpointTick)
                .sum();
        WorldItemPagingCheckpoint checkpoint = new WorldItemPagingCheckpoint(
                requirePagingSaveIdentity(), nextRevision, currentWorldTick,
                liveState.nextItemId, liveState.itemIdsExhausted,
                survivorCount, new ArrayList<>(intended.values()));
        WorldItemPersistenceTicket ticket =
                WorldItemPersistenceTicket.issuedBy(pagingTicketAuthority);
        String digest = transactionDigest(checkpoint, mutations);
        AtomicBoolean detachedFreshness = new AtomicBoolean(true);
        long preparedEpoch = pagingEpoch;
        WorldItemPersistencePlan plan = new WorldItemPersistencePlan(
                checkpointRevision, checkpoint, mutations, digest,
                () -> detachedFreshness.get()
                        && detachedPagingEpoch.get() == preparedEpoch
                        && detachedWorldTick.get() == checkpoint.worldTick());
        PersistencePreparation preparation = new PersistencePreparation(
                PersistenceKind.CLEANUP, pagingEpoch, plan, detachedFreshness,
                null, Map.of(),
                null, null, publishedPages, publishedDescriptors, List.of(),
                preparedKeys, new HashSet<>(preparedKeys));
        persistenceTickets.put(ticket, preparation);
        for (ChunkKey key : preparedKeys) {
            persistencePageOwners.put(key, ticket);
        }
        return Optional.of(new WorldItemHibernateResult(
                WorldItemHibernateResult.Status.PERSISTENCE_PREPARED,
                Optional.empty(),
                Optional.empty(),
                Optional.of(ticket), Optional.of(plan)));
    }

    /**
     * Recaptures one exact durable page for a pending cleanup whose decoded source was
     * evicted. The immutable read view is validated before the page is pinned, and the
     * ordinary bounded cleanup path remains the only plan publisher.
     */
    public Optional<WorldItemHibernateResult> prepareCleanupPersistence(
            WorldItemPageReadView view, WorldItemPageDescriptor descriptor) {
        assertMutationAllowed("world item cleanup page recapture");
        Objects.requireNonNull(view, "view");
        Objects.requireNonNull(descriptor, "descriptor");
        ChunkKey key = descriptor.chunkKey();
        if (checkpointRevision == 0L
                || outstandingPagingOperations() >= pagingPolicy.maxPagingTickets()
                || !cleanupIntents.containsKey(key)
                || persistencePageOwners.containsKey(key)
                || view.checkpoint().checkpointRevision() != checkpointRevision
                || !view.checkpoint().saveIdentity().equals(requirePagingSaveIdentity())
                || !view.checkpoint().pages().contains(descriptor)
                || !descriptor.equals(pageDescriptors.get(key))) {
            return Optional.empty();
        }
        WorldItemPageSnapshot page;
        try {
            page = view.read(descriptor);
        } catch (RuntimeException failure) {
            return Optional.empty();
        }
        WorldItemPageDescriptor actual;
        try {
            actual = requireExactDescriptor(page);
        } catch (RuntimeException failure) {
            return Optional.empty();
        }
        if (!descriptor.chunkKey().equals(actual.chunkKey())
                || descriptor.pageRevision() != actual.pageRevision()
                || !descriptor.pageHash().equals(actual.pageHash())
                || descriptor.encodedEntryCount() != actual.encodedEntryCount()) {
            return Optional.empty();
        }
        WorldItemPageCache.Admission admission = pageCache.admitClean(
                descriptor, page, pageBytes(page), ++cacheAccessOrder);
        if (admission != WorldItemPageCache.Admission.ADMITTED) {
            return Optional.empty();
        }
        pageCache.pin(key);
        return prepareCleanupPersistence();
    }

    /** Restores one complete validated bounded paging checkpoint into a fresh target. */
    public boolean restorePagingState(
            WorldItemPagingCheckpoint checkpoint,
            List<WorldItemLiveMetadata> metadata,
            List<WorldItemPageSnapshot> pages) {
        assertMutationAllowed("world item paging restore");
        Objects.requireNonNull(checkpoint, "checkpoint");
        Objects.requireNonNull(metadata, "metadata");
        Objects.requireNonNull(pages, "pages");
        if (pagingSaveIdentity == null
                || !pagingSaveIdentity.equals(checkpoint.saveIdentity())
                || !isFreshTarget()
                || !liveMetadata.isEmpty()
                || metadata.size() > pagingPolicy.maxLiveMetadata()
                || metadata.size() != checkpoint.totalLiveItemCount()) {
            return false;
        }
        Map<ChunkKey, WorldItemPageDescriptor> descriptors = new LinkedHashMap<>();
        for (WorldItemPageDescriptor descriptor : checkpoint.pages()) {
            descriptors.put(descriptor.chunkKey(), descriptor);
        }
        Map<ChunkKey, WorldItemPageSnapshot> pageMap = new LinkedHashMap<>();
        Set<ChunkKey> completeEncodedPages = new HashSet<>();
        Set<WorldItemId> pageIds = new HashSet<>();
        Map<WorldItemId, ChunkKey> pageOwners = new HashMap<>();
        for (WorldItemPageSnapshot page : pages) {
            WorldItemPageDescriptor descriptor = descriptors.get(page.chunkKey());
            boolean completeEncodedPage = descriptor != null
                    && descriptor.encodedEntryCount() == page.entries().size();
            boolean liveOnlyPage = descriptor != null
                    && descriptor.expectedLiveCountAtCheckpointTick()
                            == page.entries().size();
            if (descriptor == null
                    || descriptor.pageRevision() != page.pageRevision()
                    || (!completeEncodedPage && !liveOnlyPage)
                    || pageMap.putIfAbsent(page.chunkKey(), page) != null) {
                return false;
            }
            if (completeEncodedPage) {
                completeEncodedPages.add(page.chunkKey());
            }
            int liveEntryCount = 0;
            for (WorldItemRestoreEntry entry : page.entries()) {
                if (entry.runtime().expiresAtWorldTick() <= checkpoint.worldTick()) {
                    continue;
                }
                liveEntryCount++;
                WorldItemId id = entry.runtime().item().id();
                if (!pageIds.add(id)) {
                    return false;
                }
                pageOwners.put(id, page.chunkKey());
            }
            if (liveEntryCount != descriptor.expectedLiveCountAtCheckpointTick()) {
                return false;
            }
        }
        Map<WorldItemId, WorldItemLiveMetadata> checked = new LinkedHashMap<>();
        for (WorldItemLiveMetadata row : metadata) {
            if (checked.putIfAbsent(row.id(), row) != null
                    || row.expiresAtWorldTick() <= checkpoint.worldTick()
                    || (!checkpoint.itemIdsExhausted()
                            && row.id().value() >= checkpoint.nextItemId())
                    || (checkpoint.itemIdsExhausted()
                            && checkpoint.nextItemId() != Long.MAX_VALUE)) {
                return false;
            }
            WorldItemPageDescriptor descriptor = descriptors.get(row.intendedChunkKey());
            if (descriptor == null || row.durableProof().isEmpty()
                    || !row.intendedChunkKey().equals(pageOwners.get(row.id()))
                    || row.durableProof().orElseThrow().pageRevision()
                            != descriptor.pageRevision()
                    || !row.durableProof().orElseThrow().pageHash()
                            .equals(descriptor.pageHash())) {
                return false;
            }
        }
        if (!pageIds.equals(checked.keySet())) {
            return false;
        }

        Map<WorldItemId, ItemState> active = new LinkedHashMap<>();
        Map<ChunkKey, Map<WorldItemId, ItemState>> dormant = new LinkedHashMap<>();
        Map<WorldItemId, ItemLocation> locations = new LinkedHashMap<>();
        for (WorldItemPageSnapshot page : pages) {
            for (WorldItemRestoreEntry entry : page.entries()) {
                WorldItemLiveMetadata row = checked.get(entry.runtime().item().id());
                if (row == null) {
                    if (entry.runtime().expiresAtWorldTick() <= checkpoint.worldTick()) {
                        continue;
                    }
                    return false;
                }
                if (entry.runtime().expiresAtWorldTick() != row.expiresAtWorldTick()) {
                    return false;
                }
                if (row.state() == WorldItemLiveState.DECODED_DORMANT) {
                    ItemState state = itemState(entry);
                    dormant.computeIfAbsent(page.chunkKey(), ignored -> new LinkedHashMap<>())
                            .put(row.id(), state);
                    locations.put(row.id(), ItemLocation.dormant(page.chunkKey()));
                } else if (row.state() == WorldItemLiveState.ACTIVE) {
                    ItemState state = itemState(entry);
                    active.put(row.id(), state);
                    locations.put(row.id(), ItemLocation.active());
                }
            }
        }
        liveState = LiveState.restored(
                active, dormant, locations,
                checkpoint.nextItemId(), checkpoint.itemIdsExhausted());
        liveMetadata.clear();
        liveMetadata.putAll(checked);
        expiryIndex.clear();
        checked.values().forEach(row -> expiryIndex.put(row.id(), row.expiresAtWorldTick()));
        pageDescriptors.clear();
        pageDescriptors.putAll(descriptors);
        checkpointRevision = checkpoint.checkpointRevision();
        checkpointWorldTick = checkpoint.worldTick();
        setCurrentWorldTick(checkpoint.worldTick());
        for (WorldItemPageSnapshot page : pages) {
            WorldItemPageDescriptor descriptor = descriptors.get(page.chunkKey());
            if (completeEncodedPages.contains(page.chunkKey())) {
                pageCache.admitClean(
                        descriptor, page, pageBytes(page), ++cacheAccessOrder);
            }
        }
        return true;
    }

    /** Validates a pinned immutable read view before publishing any canonical DTO. */
    public WorldItemActivationResult prepareActivate(
            WorldItemPageReadView view, WorldItemPageDescriptor descriptor) {
        assertMutationAllowed("world item paged activation prepare");
        Objects.requireNonNull(view, "view");
        Objects.requireNonNull(descriptor, "descriptor");
        if (outstandingPagingOperations() >= pagingPolicy.maxPagingTickets()) {
            return activationResult(WorldItemActivationResult.Status.CAPACITY_EXCEEDED);
        }
        if (pagingSaveIdentity == null
                || checkpointRevision == 0L
                || !view.checkpoint().saveIdentity().equals(pagingSaveIdentity)) {
            return activationResult(WorldItemActivationResult.Status.INVALID_VIEW);
        }
        if ((!liveMetadata.isEmpty()
                        || checkpointRevision != 0L
                        || !pageDescriptors.isEmpty())
                && (view.checkpoint().checkpointRevision() != checkpointRevision
                        || !descriptor.equals(pageDescriptors.get(descriptor.chunkKey()))
                        || !view.checkpoint().pages().contains(descriptor))) {
            return activationResult(WorldItemActivationResult.Status.METADATA_MISMATCH);
        }
        WorldItemPageSnapshot page;
        try {
            page = view.read(descriptor);
        } catch (RuntimeException failure) {
            return activationResult(WorldItemActivationResult.Status.INVALID_VIEW);
        }
        if (!descriptor.chunkKey().equals(page.chunkKey())
                || descriptor.pageRevision() != page.pageRevision()) {
            return activationResult(WorldItemActivationResult.Status.INVALID_PAYLOAD);
        }
        if (descriptor.encodedEntryCount() != page.entries().size()) {
            return activationResult(WorldItemActivationResult.Status.INVALID_PAYLOAD);
        }
        Set<WorldItemId> ids = new HashSet<>();
        for (WorldItemRestoreEntry entry : page.entries()) {
            if (!ids.add(entry.runtime().item().id())) {
                return activationResult(WorldItemActivationResult.Status.DUPLICATE_ID);
            }
        }
        List<WorldItemRestoreEntry> liveEntries = new ArrayList<>();
        boolean foundExpired = false;
        for (WorldItemRestoreEntry entry : page.entries()) {
            WorldItemRuntimeSnapshot runtime = entry.runtime();
            if (runtime.expiresAtWorldTick() <= currentWorldTick) {
                foundExpired = true;
                continue;
            }
            liveEntries.add(entry);
            WorldItemLiveMetadata row = liveMetadata.get(runtime.item().id());
            if (row == null) {
                return activationResult(WorldItemActivationResult.Status.MISSING_METADATA);
            }
            if (!row.intendedChunkKey().equals(descriptor.chunkKey())
                    || row.intendedPageRevision() != descriptor.pageRevision()
                    || row.expiresAtWorldTick() != runtime.expiresAtWorldTick()
                    || row.durableProof().isEmpty()
                    || !row.durableProof().orElseThrow().pageHash()
                            .equals(descriptor.pageHash())) {
                return activationResult(WorldItemActivationResult.Status.METADATA_MISMATCH);
            }
            if (liveState.items.containsKey(row.id())) {
                return activationResult(WorldItemActivationResult.Status.COLLISION);
            }
        }
        if (liveEntries.isEmpty()) {
            enqueueCleanup(descriptor.chunkKey());
            return activationResult(WorldItemActivationResult.Status.EXPIRED);
        }
        WorldItemPageSnapshot cachePage = page;
        WorldItemPageDescriptor cacheDescriptor = descriptor;
        boolean cacheUnproved = false;
        WorldItemPageCache.Admission admission;
        if (foundExpired) {
            WorldItemPageSnapshot filtered = new WorldItemPageSnapshot(
                    page.chunkKey(), Math.addExact(page.pageRevision(), 1L), liveEntries);
            WorldItemPageDescriptor filteredDescriptor = requireExactDescriptor(filtered);
            cachePage = filtered;
            cacheDescriptor = filteredDescriptor;
            cacheUnproved = true;
            admission = pageCache.previewUnproved(
                    filteredDescriptor, filtered, pageBytes(filtered),
                    Math.addExact(cacheAccessOrder, 1L));
        } else {
            admission = pageCache.previewClean(
                    descriptor, page, pageBytes(page),
                    Math.addExact(cacheAccessOrder, 1L));
        }
        if (admission == WorldItemPageCache.Admission.ALL_PINNED) {
            return activationResult(WorldItemActivationResult.Status.ALL_PINNED);
        }
        if (admission != WorldItemPageCache.Admission.ADMITTED) {
            return activationResult(WorldItemActivationResult.Status.CAPACITY_EXCEEDED);
        }
        WorldItemHibernatePayload payload = new WorldItemHibernatePayload(
                descriptor.chunkKey(), liveEntries,
                view.checkpoint().nextItemId(), view.checkpoint().itemIdsExhausted());
        WorldItemActivationTicket ticket =
                WorldItemActivationTicket.issuedBy(pagingTicketAuthority);
        Map<WorldItemId, WorldItemLiveMetadata> before = new LinkedHashMap<>();
        for (WorldItemRestoreEntry entry : liveEntries) {
            WorldItemId id = entry.runtime().item().id();
            before.put(id, liveMetadata.get(id));
        }
        activationTickets.put(ticket, new ActivationPreparation(
                pagingEpoch, descriptor.chunkKey(), payload,
                descriptor, before, true,
                cachePage, cacheDescriptor, cacheUnproved));
        return new WorldItemActivationResult(
                WorldItemActivationResult.Status.PREPARED, Optional.of(ticket));
    }

    /** Validates one persisted Chunk payload without publishing active state. */
    public WorldItemActivationResult prepareActivate(
            ChunkKey chunkKey, WorldItemHibernatePayload payload) {
        assertMutationAllowed("world item activation prepare");
        ChunkKey checkedKey = ChunkCoordinatePolicy.requireSafe(chunkKey);
        Objects.requireNonNull(payload, "payload");
        if (outstandingPagingOperations() >= pagingPolicy.maxPagingTickets()) {
            return activationResult(WorldItemActivationResult.Status.CAPACITY_EXCEEDED);
        }
        ActivationValidation validation = validateActivation(checkedKey, payload);
        if (validation.status != WorldItemActivationResult.Status.PREPARED) {
            return activationResult(validation.status);
        }
        if (payload.entries().stream().anyMatch(
                entry -> !liveMetadata.containsKey(entry.runtime().item().id()))) {
            return activationResult(WorldItemActivationResult.Status.MISSING_METADATA);
        }

        WorldItemActivationTicket ticket =
                WorldItemActivationTicket.issuedBy(pagingTicketAuthority);
        activationTickets.put(ticket, new ActivationPreparation(
                pagingEpoch, checkedKey, payload,
                null, Map.of(), false, null, null, false));
        return new WorldItemActivationResult(
                WorldItemActivationResult.Status.PREPARED,
                Optional.of(ticket));
    }

    /** Atomically moves the exact validated dormant payload into active state. */
    public WorldItemActivationResult commitActivate(WorldItemActivationTicket ticket) {
        WorldItemActivationResult result = beginActivate(ticket);
        if (result.status() == WorldItemActivationResult.Status.COMMITTED) {
            activationTickets.remove(ticket);
        }
        return result;
    }

    private WorldItemActivationResult beginActivate(WorldItemActivationTicket ticket) {
        assertMutationAllowed("world item activation commit");
        Objects.requireNonNull(ticket, "ticket");
        if (!ticket.belongsTo(pagingTicketAuthority)) {
            return activationResult(WorldItemActivationResult.Status.FOREIGN_TICKET);
        }
        ActivationPreparation prepared = activationTickets.get(ticket);
        if (prepared == null || prepared.terminal != null) {
            return activationResult(WorldItemActivationResult.Status.STALE_TICKET);
        }
        if (prepared.epoch != pagingEpoch) {
            activationTickets.remove(ticket);
            return activationResult(WorldItemActivationResult.Status.STALE_TICKET);
        }
        ActivationValidation validation = validateActivation(
                prepared.chunkKey, prepared.payload);
        if (validation.status != WorldItemActivationResult.Status.PREPARED) {
            activationTickets.remove(ticket);
            return activationResult(validation.status);
        }

        try {
            if (prepared.paged) {
                WorldItemPageCache.Admission admission =
                        publishActivationCache(prepared);
                if (admission != WorldItemPageCache.Admission.ADMITTED) {
                    activationTickets.remove(ticket);
                    return activationResult(
                            admission == WorldItemPageCache.Admission.ALL_PINNED
                                    ? WorldItemActivationResult.Status.ALL_PINNED
                                    : WorldItemActivationResult.Status.CAPACITY_EXCEEDED);
                }
            }
            LiveState before = liveState;
            LiveState after = liveState.activate(prepared.chunkKey, prepared.payload);
            prepared.before = before;
            prepared.after = after;
            prepared.terminal = ActivationTerminal.COMMITTED;
            liveState = after;
            if (prepared.paged) {
                for (WorldItemRestoreEntry entry : prepared.payload.entries()) {
                    WorldItemId id = entry.runtime().item().id();
                    WorldItemLiveMetadata metadata = liveMetadata.get(id);
                    if (metadata == null) {
                        throw new IllegalStateException(
                                "validated activation lost its live metadata");
                    }
                    liveMetadata.put(id, metadata.withState(
                            WorldItemLiveState.ACTIVE,
                            prepared.chunkKey,
                            metadata.intendedPageRevision(),
                            metadata.durableProof()));
                }
            }
            advancePagingEpoch();
            prepared.committedEpoch = pagingEpoch;
            return activationResult(WorldItemActivationResult.Status.COMMITTED);
        } catch (RuntimeException | Error failure) {
            restoreActivationCache(prepared);
            activationTickets.remove(ticket);
            throw failure;
        }
    }

    /**
     * Publishes logical activation around an owner-thread projection callback.
     * The callback observes active logical state; failure restores the exact
     * dormant aggregate before propagating the original exception or error.
     */
    public WorldItemActivationResult commitActivate(
            WorldItemActivationTicket ticket, Runnable projectionPublication) {
        assertMutationAllowed("world item activation transaction");
        Objects.requireNonNull(projectionPublication, "projectionPublication");
        WorldItemActivationResult committed = beginActivate(ticket);
        if (committed.status() != WorldItemActivationResult.Status.COMMITTED) {
            return committed;
        }
        try {
            runProjectionCallback(projectionPublication);
            activationTickets.remove(ticket);
            return committed;
        } catch (RuntimeException | Error failure) {
            WorldItemActivationResult rolledBack = rollbackActivate(ticket);
            if (rolledBack.status() != WorldItemActivationResult.Status.ROLLED_BACK) {
                failure.addSuppressed(new IllegalStateException(
                        "logical activation rollback failed closed: "
                                + rolledBack.status()));
            }
            throw failure;
        }
    }

    /** Cancels a prepared activation without changing canonical item state. */
    public WorldItemActivationResult cancelActivate(WorldItemActivationTicket ticket) {
        assertMutationAllowed("world item activation cancel");
        Objects.requireNonNull(ticket, "ticket");
        if (!ticket.belongsTo(pagingTicketAuthority)) {
            return activationResult(WorldItemActivationResult.Status.FOREIGN_TICKET);
        }
        ActivationPreparation prepared = activationTickets.get(ticket);
        if (prepared == null || prepared.terminal != null) {
            return activationResult(WorldItemActivationResult.Status.STALE_TICKET);
        }
        prepared.terminal = ActivationTerminal.CANCELED;
        activationTickets.remove(ticket);
        return activationResult(WorldItemActivationResult.Status.CANCELED);
    }

    /** Restores the exact dormant aggregate after owner-thread projection publication fails. */
    public WorldItemActivationResult rollbackActivate(WorldItemActivationTicket ticket) {
        assertMutationAllowed("world item activation rollback");
        Objects.requireNonNull(ticket, "ticket");
        if (!ticket.belongsTo(pagingTicketAuthority)) {
            return activationResult(WorldItemActivationResult.Status.FOREIGN_TICKET);
        }
        ActivationPreparation prepared = activationTickets.get(ticket);
        if (prepared == null
                || prepared.terminal != ActivationTerminal.COMMITTED
                || liveState != prepared.after
                || pagingEpoch != prepared.committedEpoch) {
            return activationResult(WorldItemActivationResult.Status.STALE_TICKET);
        }
        liveState = prepared.before;
        if (prepared.paged) {
            for (Map.Entry<WorldItemId, WorldItemLiveMetadata> entry
                    : prepared.metadataBefore.entrySet()) {
                liveMetadata.put(entry.getKey(), entry.getValue());
            }
        }
        restoreActivationCache(prepared);
        setPagingEpoch(prepared.epoch);
        prepared.terminal = ActivationTerminal.ROLLED_BACK;
        activationTickets.remove(ticket);
        return activationResult(WorldItemActivationResult.Status.ROLLED_BACK);
    }

    private WorldItemPageCache.Admission publishActivationCache(
            ActivationPreparation prepared) {
        prepared.cacheBeforeCommit = pageCache.snapshot();
        prepared.cleanupBeforeCommit = new LinkedHashMap<>(cleanupIntents);
        prepared.droppedCleanupIntentsBeforeCommit = droppedCleanupIntents;
        prepared.cacheAccessOrderBeforeCommit = cacheAccessOrder;
        long nextAccessOrder = Math.addExact(cacheAccessOrder, 1L);
        WorldItemPageCache.Admission admission = prepared.cacheUnproved
                ? pageCache.admitUnproved(
                        prepared.cacheDescriptor,
                        prepared.cachePage,
                        pageBytes(prepared.cachePage),
                        nextAccessOrder)
                : pageCache.admitClean(
                        prepared.cacheDescriptor,
                        prepared.cachePage,
                        pageBytes(prepared.cachePage),
                        nextAccessOrder);
        if (admission != WorldItemPageCache.Admission.ADMITTED) {
            return admission;
        }
        prepared.cachePublished = true;
        cacheAccessOrder = nextAccessOrder;
        if (prepared.cacheUnproved) {
            enqueueCleanup(
                    prepared.chunkKey,
                    prepared.cachePage,
                    prepared.cacheDescriptor);
        }
        return admission;
    }

    private void restoreActivationCache(ActivationPreparation prepared) {
        if (!prepared.cachePublished) {
            return;
        }
        pageCache.restore(prepared.cacheBeforeCommit);
        cleanupIntents.clear();
        cleanupIntents.putAll(prepared.cleanupBeforeCommit);
        droppedCleanupIntents = prepared.droppedCleanupIntentsBeforeCommit;
        cacheAccessOrder = prepared.cacheAccessOrderBeforeCommit;
        prepared.cachePublished = false;
    }

    @Override
    public WorldItemSpawnReserveResult reserveSpawn(WorldItemSpawnRequest request) {
        assertMutationAllowed("world item spawn reservation");
        Objects.requireNonNull(request, "request");
        if (strictWorldClock && request.tick() != currentWorldTick) {
            return new WorldItemSpawnReserveResult(
                    request,
                    WorldItemSpawnReserveResult.Status.REJECTED,
                    Optional.empty(),
                    Optional.of(request.stack()));
        }
        if (!strictWorldClock && request.tick() > currentWorldTick) {
            setCurrentWorldTick(request.tick());
        }
        ChunkKey metadataKey;
        try {
            metadataKey = strictWorldClock
                    ? chunkKeyOf(request)
                    : tryChunkKeyOf(request);
        } catch (IllegalArgumentException outsideStreamedEnvelope) {
            return new WorldItemSpawnReserveResult(
                    request,
                    WorldItemSpawnReserveResult.Status.REJECTED,
                    Optional.empty(),
                    Optional.of(request.stack()));
        }
        if (occupiedCapacity() >= capacity
                || liveMetadata.size() >= pagingPolicy.maxLiveMetadata()) {
            return new WorldItemSpawnReserveResult(
                    request,
                    WorldItemSpawnReserveResult.Status.REJECTED,
                    Optional.empty(),
                    Optional.of(request.stack()));
        }
        WorldItemSpawnReservation reservation = new WorldItemSpawnReservation(
                nextSpawnReservationId(), nextItemId(), request,
                saturatedPickupTick(request.tick()));
        WorldItemSpawnReserveResult result = new WorldItemSpawnReserveResult(
                request,
                WorldItemSpawnReserveResult.Status.RESERVED,
                Optional.of(reservation),
                Optional.empty());
        spawnReservations.put(reservation.id(), new SpawnState(reservation));
        if (metadataKey != null) {
            long expiry = WorldItemRuntimeSnapshot.saturatingExpiry(request.tick());
            WorldItemLiveMetadata metadata = new WorldItemLiveMetadata(
                    reservation.itemId(), metadataKey, 0L, expiry,
                    WorldItemLiveState.PENDING, Optional.empty());
            liveMetadata.put(reservation.itemId(), metadata);
            expiryIndex.put(reservation.itemId(), expiry);
        }
        advancePagingEpoch();
        return result;
    }

    @Override
    public WorldItemSpawnCommitResult commitSpawn(
            WorldItemSpawnReservationId reservationId) {
        assertMutationAllowed("world item spawn reservation commit");
        return completeSpawn(reservationId, SpawnTerminal.COMMITTED);
    }

    @Override
    public WorldItemSpawnCommitResult rollbackSpawn(
            WorldItemSpawnReservationId reservationId) {
        assertMutationAllowed("world item spawn reservation rollback");
        return completeSpawn(reservationId, SpawnTerminal.ROLLED_BACK);
    }

    @Override
    public Optional<WorldItemSpawnReservationAuditSnapshot> spawnReservationAudit(
            WorldItemSpawnReservationId reservationId) {
        assertMainThread("world item spawn reservation audit");
        Objects.requireNonNull(reservationId, "reservationId");
        SpawnState state = spawnReservations.get(reservationId);
        if (state == null) {
            return Optional.empty();
        }
        ReservationTerminalState terminalState = state.terminal == null
                ? ReservationTerminalState.PENDING
                : state.terminal == SpawnTerminal.COMMITTED
                        ? ReservationTerminalState.COMMITTED
                        : ReservationTerminalState.ROLLED_BACK;
        return Optional.of(new WorldItemSpawnReservationAuditSnapshot(
                state.reservation,
                terminalState,
                terminalState == ReservationTerminalState.COMMITTED
                        ? Optional.of(state.committedRuntime)
                        : Optional.empty()));
    }

    private WorldItemSpawnCommitResult completeSpawn(
            WorldItemSpawnReservationId reservationId, SpawnTerminal requested) {
        Objects.requireNonNull(reservationId, "reservationId");
        SpawnState state = spawnReservations.get(reservationId);
        if (state == null) {
            return spawnResult(
                    WorldItemSpawnCommitResult.Status.UNKNOWN_RESERVATION,
                    Optional.empty(), Optional.empty());
        }
        if (state.terminal == requested) {
            return spawnResult(
                    requested == SpawnTerminal.COMMITTED
                            ? WorldItemSpawnCommitResult.Status.ALREADY_COMMITTED
                            : WorldItemSpawnCommitResult.Status.ALREADY_ROLLED_BACK,
                    Optional.of(state.reservation),
                    requested == SpawnTerminal.COMMITTED
                            ? Optional.of(state.committedRuntime)
                            : Optional.empty());
        }
        if (state.terminal != null) {
            return spawnResult(
                    WorldItemSpawnCommitResult.Status.TERMINAL_CONFLICT,
                    Optional.of(state.reservation),
                    state.terminal == SpawnTerminal.COMMITTED
                            ? Optional.of(state.committedRuntime)
                            : Optional.empty());
        }
        state.terminal = requested;
        advancePagingEpoch();
        if (requested == SpawnTerminal.ROLLED_BACK) {
            liveMetadata.remove(state.reservation.itemId());
            expiryIndex.remove(state.reservation.itemId());
            return spawnResult(
                    WorldItemSpawnCommitResult.Status.ROLLED_BACK,
                    Optional.of(state.reservation), Optional.empty());
        }
        WorldItemSpawnRequest request = state.reservation.request();
        WorldItemSnapshot item = new WorldItemSnapshot(
                state.reservation.itemId(),
                request.stack(),
                request.positionX(), request.positionY(), request.positionZ(),
                request.velocityX(), request.velocityY(), request.velocityZ(),
                0);
        WorldItemRuntimeSnapshot committedRuntime = new WorldItemRuntimeSnapshot(
                item,
                request.source(),
                request.tick(),
                state.reservation.pickupAvailableTick());
        state.committedRuntime = committedRuntime;
        liveState.items.put(item.id(), new ItemState(
                item, request.source(), request.tick(),
                state.reservation.pickupAvailableTick()));
        liveState.itemLocations.put(item.id(), ItemLocation.active());
        WorldItemLiveMetadata pending = liveMetadata.get(item.id());
        if (pending != null) {
            liveMetadata.put(item.id(), pending.withState(
                    WorldItemLiveState.ACTIVE,
                    chunkKeyOf(item),
                    0L,
                    Optional.empty()));
        }
        return spawnResult(
                WorldItemSpawnCommitResult.Status.COMMITTED,
                Optional.of(state.reservation), Optional.of(committedRuntime));
    }

    @Override
    public WorldItemReservationResult reserve(WorldItemId itemId, int count) {
        assertMutationAllowed("world item extraction reservation");
        Objects.requireNonNull(itemId, "itemId");
        ItemState state = liveState.items.get(itemId);
        if (state == null) {
            return extractionResult(
                    WorldItemReservationResult.Status.UNKNOWN_ITEM,
                    Optional.empty(), Optional.empty(), Optional.empty());
        }
        WorldItemSnapshot item = state.item;
        if (count <= 0 || count > item.stack().count()) {
            return extractionResult(
                    WorldItemReservationResult.Status.INVALID_COUNT,
                    Optional.empty(), Optional.of(item), Optional.empty());
        }
        if (activeExtractions.containsKey(itemId)) {
            return extractionResult(
                    WorldItemReservationResult.Status.UNAVAILABLE,
                    Optional.empty(), Optional.of(item), Optional.empty());
        }
        WorldItemReservation reservation = new WorldItemReservation(
                nextExtractionReservationId(),
                itemId,
                new ItemStack(item.stack().itemId(), count));
        extractionReservations.put(
                reservation.id(), new ExtractionState(reservation));
        activeExtractions.put(itemId, reservation.id());
        advancePagingEpoch();
        if (count == item.stack().count()) {
            return extractionResult(
                    WorldItemReservationResult.Status.RESERVED,
                    Optional.of(reservation), Optional.of(item), Optional.empty());
        }
        return extractionResult(
                WorldItemReservationResult.Status.PARTIALLY_RESERVED,
                Optional.of(reservation), Optional.of(item),
                Optional.of(new ItemStack(
                        item.stack().itemId(), item.stack().count() - count)));
    }

    @Override
    public WorldItemReservationResult commit(WorldItemReservationId reservationId) {
        assertMutationAllowed("world item extraction reservation commit");
        return completeExtraction(reservationId, ExtractionTerminal.COMMITTED);
    }

    @Override
    public WorldItemReservationResult rollback(WorldItemReservationId reservationId) {
        assertMutationAllowed("world item extraction reservation rollback");
        return completeExtraction(reservationId, ExtractionTerminal.ROLLED_BACK);
    }

    @Override
    public Optional<WorldItemReservationAuditSnapshot> reservationAudit(
            WorldItemReservationId reservationId) {
        assertMainThread("world item extraction reservation audit");
        Objects.requireNonNull(reservationId, "reservationId");
        ExtractionState reservation = extractionReservations.get(reservationId);
        if (reservation == null) {
            return Optional.empty();
        }
        ReservationTerminalState state = reservation.terminal == null
                ? ReservationTerminalState.PENDING
                : reservation.terminal == ExtractionTerminal.COMMITTED
                        ? ReservationTerminalState.COMMITTED
                        : ReservationTerminalState.ROLLED_BACK;
        return Optional.of(new WorldItemReservationAuditSnapshot(
                reservation.reservation, state));
    }

    private WorldItemReservationResult completeExtraction(
            WorldItemReservationId reservationId, ExtractionTerminal requested) {
        Objects.requireNonNull(reservationId, "reservationId");
        ExtractionState state = extractionReservations.get(reservationId);
        if (state == null) {
            return extractionResult(
                    WorldItemReservationResult.Status.UNKNOWN_RESERVATION,
                    Optional.empty(), Optional.empty(), Optional.empty());
        }
        if (state.terminal == requested) {
            return extractionTerminal(
                    state,
                    requested == ExtractionTerminal.COMMITTED
                            ? WorldItemReservationResult.Status.ALREADY_COMMITTED
                            : WorldItemReservationResult.Status.ALREADY_ROLLED_BACK);
        }
        if (state.terminal != null) {
            return extractionTerminal(
                    state, WorldItemReservationResult.Status.TERMINAL_CONFLICT);
        }

        ItemState current = null;
        int remainder = 0;
        long advancedRevision = -1;
        if (requested == ExtractionTerminal.COMMITTED) {
            current = liveState.items.get(state.reservation.itemId());
            if (current == null
                    || current.item.stack().count()
                            < state.reservation.reserved().count()) {
                throw new IllegalStateException(
                        "world item extraction reservation guarantee broken");
            }
            remainder = current.item.stack().count()
                    - state.reservation.reserved().count();
            if (remainder > 0) {
                OptionalLong next = nextRevision(current.item.revision());
                if (next.isEmpty()) {
                    return extractionResult(
                            WorldItemReservationResult.Status.REVISION_EXHAUSTED,
                            Optional.of(state.reservation),
                            Optional.of(current.item),
                            Optional.of(new ItemStack(
                                    current.item.stack().itemId(), remainder)));
                }
                advancedRevision = next.getAsLong();
            }
        }

        state.terminal = requested;
        activeExtractions.remove(state.reservation.itemId());
        advancePagingEpoch();
        if (requested == ExtractionTerminal.ROLLED_BACK) {
            return extractionTerminal(
                    state, WorldItemReservationResult.Status.ROLLED_BACK);
        }
        applyExtraction(current, remainder, advancedRevision);
        return extractionTerminal(
                state, WorldItemReservationResult.Status.COMMITTED);
    }

    private void applyExtraction(
            ItemState current, int remainder, long advancedRevision) {
        if (remainder == 0) {
            WorldItemLiveMetadata metadata = liveMetadata.get(current.item.id());
            if (metadata != null) {
                metadata.durableProof().ifPresent(
                        proof -> enqueueCleanup(proof.chunkKey()));
            }
            liveState.items.remove(current.item.id());
            liveState.itemLocations.remove(current.item.id());
            liveMetadata.remove(current.item.id());
            expiryIndex.remove(current.item.id());
            return;
        }
        current.item = new WorldItemSnapshot(
                current.item.id(),
                new ItemStack(current.item.stack().itemId(), remainder),
                current.item.positionX(), current.item.positionY(), current.item.positionZ(),
                current.item.velocityX(), current.item.velocityY(), current.item.velocityZ(),
                advancedRevision);
    }

    private WorldItemReservationResult extractionTerminal(
            ExtractionState state, WorldItemReservationResult.Status status) {
        return extractionResult(
                status,
                Optional.of(state.reservation),
                itemSnapshot(state.reservation.itemId()),
                Optional.empty());
    }

    private Optional<WorldItemSnapshot> itemSnapshot(WorldItemId itemId) {
        ItemState state = liveState.items.get(itemId);
        return state == null ? Optional.empty() : Optional.of(state.item);
    }

    private List<ItemState> activeItemsInChunk(ChunkKey chunkKey) {
        return liveState.items.values().stream()
                .filter(state -> chunkKey.equals(chunkKeyOf(state.item)))
                .sorted(Comparator.comparingLong(state -> state.item.id().value()))
                .toList();
    }

    private boolean hasPendingSpawnInChunk(ChunkKey chunkKey) {
        for (SpawnState state : spawnReservations.values()) {
            if (state.terminal == null
                    && chunkKey.equals(chunkKeyOf(state.reservation.request()))) {
                return true;
            }
        }
        return false;
    }

    private ActivationValidation validateActivation(
            ChunkKey requestedKey, WorldItemHibernatePayload payload) {
        if (!requestedKey.equals(payload.chunkKey())) {
            return ActivationValidation.failed(WorldItemActivationResult.Status.WRONG_CHUNK);
        }
        if (payload.nextItemId() < 0
                || (payload.itemIdsExhausted()
                        && payload.nextItemId() != Long.MAX_VALUE)) {
            return ActivationValidation.failed(
                    WorldItemActivationResult.Status.INVALID_ALLOCATOR);
        }
        if (hasPendingSpawnInChunk(requestedKey)) {
            return ActivationValidation.failed(WorldItemActivationResult.Status.RESERVED);
        }

        Set<WorldItemId> payloadIds = new HashSet<>();
        Map<WorldItemId, ItemState> existingBucket =
                liveState.dormantByChunk.getOrDefault(requestedKey, Map.of());
        for (WorldItemRestoreEntry entry : payload.entries()) {
            WorldItemRuntimeSnapshot runtime = entry.runtime();
            WorldItemSnapshot item = runtime.item();
            if (!payloadIds.add(item.id())) {
                return ActivationValidation.failed(
                        WorldItemActivationResult.Status.DUPLICATE_ID);
            }
            if (!validRuntime(runtime)
                    || !requestedKey.equals(chunkKeyOf(item))) {
                return ActivationValidation.failed(
                        WorldItemActivationResult.Status.WRONG_CHUNK);
            }
            if ((!payload.itemIdsExhausted()
                            && item.id().value() >= payload.nextItemId())
                    || (payload.itemIdsExhausted()
                            && payload.nextItemId() != Long.MAX_VALUE)) {
                return ActivationValidation.failed(
                        WorldItemActivationResult.Status.INVALID_ALLOCATOR);
            }
            if (activeExtractions.containsKey(item.id())) {
                return ActivationValidation.failed(WorldItemActivationResult.Status.RESERVED);
            }
            ItemLocation location = liveState.itemLocations.get(item.id());
            if (location != null && !location.dormant) {
                return ActivationValidation.failed(
                        WorldItemActivationResult.Status.DUPLICATE_ID);
            }
            if (location != null && !requestedKey.equals(location.chunkKey)) {
                return ActivationValidation.failed(WorldItemActivationResult.Status.WRONG_CHUNK);
            }
            ItemState dormant = existingBucket.get(item.id());
            if (location != null
                    && (dormant == null
                            || !item.equals(dormant.item)
                            || entry.physicalState() != dormant.physicalState
                            || !runtime.equals(dormant.runtimeSnapshot()))) {
                return ActivationValidation.failed(
                        WorldItemActivationResult.Status.STALE_REVISION);
            }
            if (location == null
                    && !liveMetadata.containsKey(item.id())
                    && (liveState.itemIdsExhausted
                            || item.id().value() < liveState.nextItemId)) {
                return ActivationValidation.failed(
                        WorldItemActivationResult.Status.INVALID_ALLOCATOR);
            }
        }
        if (liveState.dormantByChunk.containsKey(requestedKey)
                && !existingBucket.keySet().equals(payloadIds)) {
            return ActivationValidation.failed(
                    WorldItemActivationResult.Status.INVALID_PAYLOAD);
        }
        int activeAfter = strictWorldClock
                ? occupiedCapacity()
                : Math.addExact(occupiedCapacity(), payload.entries().size());
        if (activeAfter > capacity) {
            return ActivationValidation.failed(
                    WorldItemActivationResult.Status.CAPACITY_EXCEEDED);
        }
        return ActivationValidation.prepared();
    }

    private static ChunkKey chunkKeyOf(WorldItemSnapshot item) {
        return chunkKeyOf(item.positionX(), item.positionZ());
    }

    private static ChunkKey chunkKeyOf(WorldItemSpawnRequest request) {
        return chunkKeyOf(request.positionX(), request.positionZ());
    }

    private static ChunkKey tryChunkKeyOf(WorldItemSpawnRequest request) {
        try {
            return chunkKeyOf(request);
        } catch (IllegalArgumentException outsideStreamedEnvelope) {
            return null;
        }
    }

    private static ChunkKey chunkKeyOf(double positionX, double positionZ) {
        double floorX = Math.floor(positionX);
        double floorZ = Math.floor(positionZ);
        if (!Double.isFinite(floorX)
                || !Double.isFinite(floorZ)
                || floorX < Integer.MIN_VALUE
                || floorX > Integer.MAX_VALUE
                || floorZ < Integer.MIN_VALUE
                || floorZ > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                    "world item position is outside the logical coordinate envelope");
        }
        return ChunkCoordinatePolicy.requireSafe(ChunkKey.fromWorld(
                (int) floorX, (int) floorZ));
    }

    private int occupiedCapacity() {
        if (strictWorldClock) {
            return liveMetadata.size();
        }
        int pending = 0;
        for (SpawnState state : spawnReservations.values()) {
            if (state.terminal == null) {
                pending++;
            }
        }
        return Math.addExact(liveState.items.size(), pending);
    }

    private RestoreValidation validateRestore(
            LogicalWorldItemSnapshot snapshot, long authoritativeWorldTick) {
        if (snapshot.dormantChunkKeys().size() > snapshot.entries().size()) {
            return RestoreValidation.failed(
                    WorldItemRestoreResult.Status.CAPACITY_EXCEEDED);
        }
        if (snapshot.nextItemId() < 0
                || (snapshot.itemIdsExhausted()
                        && snapshot.nextItemId() != Long.MAX_VALUE)) {
            return RestoreValidation.failed(
                    WorldItemRestoreResult.Status.INVALID_SNAPSHOT);
        }

        Map<WorldItemId, ItemState> restoredItems = new LinkedHashMap<>();
        Map<ChunkKey, Map<WorldItemId, ItemState>> restoredDormant =
                new LinkedHashMap<>();
        Map<WorldItemId, ItemLocation> restoredLocations = new LinkedHashMap<>();
        Set<WorldItemId> seenIds = new HashSet<>();
        for (WorldItemRestoreEntry entry : snapshot.entries()) {
            WorldItemRuntimeSnapshot runtime = entry.runtime();
            WorldItemSnapshot item = runtime.item();
            if (!validRuntime(runtime)
                    || (!snapshot.itemIdsExhausted()
                            && item.id().value() >= snapshot.nextItemId())
                    || !seenIds.add(item.id())) {
                return RestoreValidation.failed(
                        WorldItemRestoreResult.Status.INVALID_SNAPSHOT);
            }
            if (runtime.expiresAtWorldTick() <= authoritativeWorldTick) {
                continue;
            }
            ItemState restored = new ItemState(
                    item,
                    runtime.source(),
                    runtime.spawnTick(),
                    runtime.pickupAvailableTick(),
                    runtime.expiresAtWorldTick(),
                    entry.physicalState());
            ChunkKey dormantKey = snapshot.dormantChunkKeys().get(item.id());
            if (dormantKey == null) {
                restoredItems.put(item.id(), restored);
                restoredLocations.put(item.id(), ItemLocation.active());
            } else {
                if (!dormantKey.equals(chunkKeyOf(item))) {
                    return RestoreValidation.failed(
                            WorldItemRestoreResult.Status.INVALID_SNAPSHOT);
                }
                restoredDormant.computeIfAbsent(
                                dormantKey, ignored -> new LinkedHashMap<>())
                        .put(item.id(), restored);
                restoredLocations.put(item.id(), ItemLocation.dormant(dormantKey));
            }
        }
        if (!seenIds.containsAll(snapshot.dormantChunkKeys().keySet())) {
            return RestoreValidation.failed(WorldItemRestoreResult.Status.INVALID_SNAPSHOT);
        }
        if (restoredItems.size() > capacity
                || restoredLocations.size() > pagingPolicy.maxLiveMetadata()) {
            return RestoreValidation.failed(
                    WorldItemRestoreResult.Status.CAPACITY_EXCEEDED);
        }
        return RestoreValidation.restored(
                restoredItems,
                restoredDormant,
                restoredLocations,
                snapshot.nextItemId(),
                snapshot.itemIdsExhausted());
    }

    private static boolean validRuntime(WorldItemRuntimeSnapshot runtime) {
        WorldItemSnapshot item = runtime.item();
        return item.revision() >= 0
                && finite(item.positionX())
                && finite(item.positionY())
                && finite(item.positionZ())
                && finite(item.velocityX())
                && finite(item.velocityY())
                && finite(item.velocityZ())
                && runtime.spawnTick() >= 0
                && runtime.pickupAvailableTick() >= runtime.spawnTick();
    }

    private boolean isFreshTarget() {
        return liveState.restoreEligible
                && liveState.items.isEmpty()
                && liveState.dormantByChunk.isEmpty()
                && liveState.itemLocations.isEmpty()
                && extractionReservations.isEmpty()
                && activeExtractions.isEmpty()
                && spawnReservations.isEmpty()
                && liveState.nextItemId == 0
                && nextExtractionReservationId == 0
                && nextSpawnReservationId == 0
                && !liveState.itemIdsExhausted
                && !extractionIdsExhausted
                && !spawnIdsExhausted;
    }

    private int outstandingPagingOperations() {
        int standaloneHibernate = (int) hibernateTickets.values().stream()
                .filter(prepared -> prepared.persistenceTicket == null)
                .count();
        return Math.addExact(
                Math.addExact(persistenceTickets.size(), activationTickets.size()),
                standaloneHibernate);
    }

    private SaveIdentity requirePagingSaveIdentity() {
        if (pagingSaveIdentity == null) {
            throw new IllegalStateException("paged persistence is not configured");
        }
        return pagingSaveIdentity;
    }

    private Map<ChunkKey, WorldItemPageDescriptor> recountedDescriptors() {
        Map<ChunkKey, WorldItemPageDescriptor> result = new LinkedHashMap<>();
        for (WorldItemPageDescriptor descriptor : pageDescriptors.values()) {
            int survivors = (int) liveMetadata.values().stream()
                    .filter(metadata -> metadata.durableProof().isPresent())
                    .filter(metadata -> {
                        WorldItemDurablePageProof proof =
                                metadata.durableProof().orElseThrow();
                        return proof.chunkKey().equals(descriptor.chunkKey())
                                && proof.pageRevision() == descriptor.pageRevision()
                                && proof.pageHash().equals(descriptor.pageHash());
                    })
                    .count();
            result.put(descriptor.chunkKey(), new WorldItemPageDescriptor(
                    descriptor.chunkKey(), descriptor.pageRevision(),
                    descriptor.pageHash(), descriptor.encodedEntryCount(), survivors));
        }
        return result;
    }

    private List<WorldItemRestoreEntry> livePageEntries(
            WorldItemPageSnapshot page, Set<WorldItemId> excluded) {
        return page.entries().stream()
                .filter(entry -> !excluded.contains(entry.runtime().item().id()))
                .filter(entry -> liveMetadata.containsKey(entry.runtime().item().id()))
                .filter(entry -> entry.runtime().expiresAtWorldTick() > currentWorldTick)
                .sorted(Comparator.comparingLong(
                        entry -> entry.runtime().item().id().value()))
                .toList();
    }

    private List<WorldItemRestoreEntry> currentCleanupEntries(
            ChunkKey key, WorldItemPageSnapshot source) {
        List<WorldItemRestoreEntry> entries = new ArrayList<>();
        for (WorldItemRestoreEntry encoded : source.entries()) {
            WorldItemId id = encoded.runtime().item().id();
            WorldItemLiveMetadata metadata = liveMetadata.get(id);
            if (metadata == null
                    || metadata.expiresAtWorldTick() <= currentWorldTick
                    || !metadata.intendedChunkKey().equals(key)
                    || metadata.durableProof().isEmpty()
                    || !metadata.durableProof().orElseThrow().chunkKey().equals(key)) {
                continue;
            }
            ItemState current = currentItemState(id);
            entries.add(current == null
                    ? encoded
                    : new WorldItemRestoreEntry(
                            current.runtimeSnapshot(), current.physicalState));
        }
        entries.sort(Comparator.comparingLong(
                entry -> entry.runtime().item().id().value()));
        return List.copyOf(entries);
    }

    private ItemState currentItemState(WorldItemId id) {
        ItemState active = liveState.items.get(id);
        if (active != null) {
            return active;
        }
        ItemLocation location = liveState.itemLocations.get(id);
        if (location == null || !location.dormant) {
            return null;
        }
        return liveState.dormantByChunk
                .getOrDefault(location.chunkKey, Map.of()).get(id);
    }

    private WorldItemPageDescriptor requireExactDescriptor(WorldItemPageSnapshot page) {
        WorldItemPageDescriptor descriptor = descriptorFactory.apply(page);
        if (!descriptor.chunkKey().equals(page.chunkKey())
                || descriptor.pageRevision() != page.pageRevision()
                || descriptor.encodedEntryCount() != page.entries().size()
                || descriptor.expectedLiveCountAtCheckpointTick()
                        != page.entries().size()) {
            throw new IllegalArgumentException(
                    "descriptor factory did not describe the exact live page");
        }
        return descriptor;
    }

    private boolean capturedItemsStillExact(PersistencePreparation prepared) {
        if (prepared.kind == PersistenceKind.CLEANUP) {
            return true;
        }
        Map<WorldItemId, WorldItemRestoreEntry> captured = new HashMap<>();
        for (WorldItemPageSnapshot candidate : prepared.publishedPages.values()) {
            for (WorldItemRestoreEntry entry : candidate.entries()) {
                if (prepared.itemIds.contains(entry.runtime().item().id())) {
                    captured.put(entry.runtime().item().id(), entry);
                }
            }
        }
        for (WorldItemId id : prepared.itemIds) {
            ItemState current = liveState.items.get(id);
            WorldItemRestoreEntry expected = captured.get(id);
            if (current == null || expected == null
                    || !expected.runtime().equals(current.runtimeSnapshot())
                    || expected.physicalState() != current.physicalState) {
                return false;
            }
        }
        return true;
    }

    private void restoreMetadataMap(
            Map<WorldItemId, WorldItemLiveMetadata> before) {
        for (Map.Entry<WorldItemId, WorldItemLiveMetadata> entry : before.entrySet()) {
            if (liveMetadata.containsKey(entry.getKey())) {
                liveMetadata.put(entry.getKey(), entry.getValue());
            }
        }
    }

    private void releasePersistenceOwnership(
            PersistencePreparation prepared, WorldItemPersistenceTicket ticket) {
        prepared.detachedFreshness.set(false);
        for (WorldItemId id : prepared.itemIds) {
            persistenceIdOwners.remove(id, ticket);
        }
        for (ChunkKey key : prepared.touchedKeys) {
            persistencePageOwners.remove(key, ticket);
        }
    }

    private void removeCanonicalItem(WorldItemId id) {
        liveState.items.remove(id);
        ItemLocation location = liveState.itemLocations.remove(id);
        if (location != null && location.dormant) {
            Map<WorldItemId, ItemState> bucket =
                    liveState.dormantByChunk.get(location.chunkKey);
            if (bucket != null) {
                bucket.remove(id);
                if (bucket.isEmpty()) {
                    liveState.dormantByChunk.remove(location.chunkKey);
                }
            }
        }
        WorldItemReservationId extraction = activeExtractions.remove(id);
        if (extraction != null) {
            extractionReservations.remove(extraction);
        }
        spawnReservations.entrySet().removeIf(entry ->
                entry.getValue().terminal == null
                        && entry.getValue().reservation.itemId().equals(id));
    }

    private void stalePagingTicketsFor(WorldItemId id) {
        List<Map.Entry<WorldItemPersistenceTicket, PersistencePreparation>> stale =
                persistenceTickets.entrySet().stream()
                        .filter(entry -> entry.getValue().itemIds.contains(id))
                        .toList();
        for (Map.Entry<WorldItemPersistenceTicket, PersistencePreparation> entry
                 : stale) {
            PersistencePreparation prepared = entry.getValue();
            restorePreparedMetadata(prepared);
            persistenceTickets.remove(entry.getKey());
            if (prepared.hibernateTicket != null) {
                hibernateTickets.remove(prepared.hibernateTicket);
            }
            releasePersistenceOwnership(prepared, entry.getKey());
            for (ChunkKey key : prepared.publishedPages.keySet()) {
                if (!pageDescriptors.containsKey(key)) {
                    pageCache.remove(key);
                }
            }
        }
        hibernateTickets.entrySet().removeIf(entry -> entry.getValue().payload.entries()
                .stream().anyMatch(value -> value.runtime().item().id().equals(id)));
        activationTickets.entrySet().removeIf(entry -> entry.getValue().payload.entries()
                .stream().anyMatch(value -> value.runtime().item().id().equals(id)));
    }

    private void enqueueCleanup(ChunkKey key) {
        enqueueCleanup(key, null, null);
    }

    private void enqueueCleanup(
            ChunkKey key,
            WorldItemPageSnapshot replacement,
            WorldItemPageDescriptor replacementDescriptor) {
        ChunkKey checked = ChunkCoordinatePolicy.requireSafe(key);
        CleanupIntent existing = cleanupIntents.get(checked);
        if (existing != null) {
            if (replacement != null) {
                cleanupIntents.put(checked, new CleanupIntent(
                        checked, replacement, replacementDescriptor));
            }
            return;
        }
        if (cleanupIntents.size() >= pagingPolicy.maxCleanupIntents()
                || Math.multiplyExact(cleanupIntents.size() + 1L, 64L)
                        > pagingPolicy.maxCleanupIntentBytes()) {
            droppedCleanupIntents++;
            return;
        }
        cleanupIntents.put(checked, new CleanupIntent(
                checked, replacement, replacementDescriptor));
        pageCache.pin(checked);
    }

    private void restorePreparedMetadata(PersistencePreparation prepared) {
        if (prepared == null) {
            return;
        }
        for (Map.Entry<WorldItemId, WorldItemLiveMetadata> entry
                : prepared.metadataBefore.entrySet()) {
            if (liveMetadata.containsKey(entry.getKey())
                    && persistenceIdOwners.get(entry.getKey()) != null) {
                WorldItemLiveMetadata before = entry.getValue();
                ItemState active = liveState.items.get(entry.getKey());
                ChunkKey actual = active == null
                        ? before.intendedChunkKey()
                        : chunkKeyOf(active.item);
                liveMetadata.put(entry.getKey(), before.withState(
                        active == null ? before.state() : WorldItemLiveState.ACTIVE,
                        actual,
                        before.intendedPageRevision(),
                        before.durableProof()));
            }
        }
    }

    private void discardPersistence(
            PersistencePreparation prepared,
            WorldItemPersistenceTicket ticket,
            boolean retainPinned) {
        persistenceTickets.remove(ticket);
        restorePreparedMetadata(prepared);
        if (prepared.hibernateTicket != null) {
            hibernateTickets.remove(prepared.hibernateTicket);
        }
        if (retainPinned && prepared.descriptor != null) {
            pageCache.pin(prepared.descriptor.chunkKey());
        }
        releasePersistenceOwnership(prepared, ticket);
    }

    private static ItemState itemState(WorldItemRestoreEntry entry) {
        WorldItemRuntimeSnapshot runtime = entry.runtime();
        return new ItemState(
                runtime.item(), runtime.source(), runtime.spawnTick(),
                runtime.pickupAvailableTick(), runtime.expiresAtWorldTick(),
                entry.physicalState());
    }

    private static long estimatedPageBytes(WorldItemPageSnapshot page) {
        return Math.addExact(64L, Math.multiplyExact(page.entries().size(), 128L));
    }

    private long pageBytes(WorldItemPageSnapshot page) {
        long encodedBytes = pageByteSizer.applyAsLong(Objects.requireNonNull(page, "page"));
        if (encodedBytes < 0L) {
            throw new IllegalArgumentException("encoded page bytes must be non-negative");
        }
        return encodedBytes;
    }

    private static WorldItemPageDescriptor fallbackDescriptor(
            WorldItemPageSnapshot page) {
        return new WorldItemPageDescriptor(
                page.chunkKey(), page.pageRevision(), sha256(page.toString()),
                page.entries().size(), page.entries().size());
    }

    private static String transactionDigest(
            WorldItemPagingCheckpoint checkpoint,
            List<WorldItemPageMutation> mutations) {
        return sha256(checkpoint + "|" + mutations);
    }

    private static String sha256(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(64);
            for (byte next : bytes) {
                result.append(String.format("%02x", next & 0xff));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private boolean hasPendingSpawnReservations() {
        for (SpawnState state : spawnReservations.values()) {
            if (state.terminal == null) {
                return true;
            }
        }
        return false;
    }

    private boolean hasPendingExtractionReservations() {
        if (!activeExtractions.isEmpty()) {
            return true;
        }
        for (ExtractionState state : extractionReservations.values()) {
            if (state.terminal == null) {
                return true;
            }
        }
        return false;
    }

    private long saturatedPickupTick(long spawnTick) {
        return spawnTick > Long.MAX_VALUE - pickupDelayTicks
                ? Long.MAX_VALUE
                : spawnTick + pickupDelayTicks;
    }

    private WorldItemId nextItemId() {
        if (liveState.itemIdsExhausted) {
            throw new IllegalStateException("world item ID sequence exhausted");
        }
        WorldItemId id = new WorldItemId(liveState.nextItemId);
        if (liveState.nextItemId == Long.MAX_VALUE) {
            liveState.itemIdsExhausted = true;
        } else {
            liveState.nextItemId++;
        }
        return id;
    }

    private WorldItemReservationId nextExtractionReservationId() {
        if (extractionIdsExhausted) {
            throw new IllegalStateException(
                    "world item extraction reservation ID sequence exhausted");
        }
        WorldItemReservationId id =
                new WorldItemReservationId(nextExtractionReservationId);
        if (nextExtractionReservationId == Long.MAX_VALUE) {
            extractionIdsExhausted = true;
        } else {
            nextExtractionReservationId++;
        }
        return id;
    }

    private WorldItemSpawnReservationId nextSpawnReservationId() {
        if (spawnIdsExhausted) {
            throw new IllegalStateException(
                    "world item spawn reservation ID sequence exhausted");
        }
        WorldItemSpawnReservationId id =
                new WorldItemSpawnReservationId(nextSpawnReservationId);
        if (nextSpawnReservationId == Long.MAX_VALUE) {
            spawnIdsExhausted = true;
        } else {
            nextSpawnReservationId++;
        }
        return id;
    }

    private static WorldItemSpawnCommitResult spawnResult(
            WorldItemSpawnCommitResult.Status status,
            Optional<WorldItemSpawnReservation> reservation,
            Optional<WorldItemRuntimeSnapshot> runtime) {
        return new WorldItemSpawnCommitResult(status, reservation, runtime);
    }

    private static WorldItemReservationResult extractionResult(
            WorldItemReservationResult.Status status,
            Optional<WorldItemReservation> reservation,
            Optional<WorldItemSnapshot> item,
            Optional<ItemStack> remainder) {
        return new WorldItemReservationResult(status, reservation, item, remainder);
    }

    private static WorldItemRestoreResult restoreResult(
            WorldItemRestoreResult.Status status, int restoredCount) {
        return new WorldItemRestoreResult(status, restoredCount);
    }

    private static WorldItemHibernateResult hibernateResult(
            WorldItemHibernateResult.Status status) {
        return new WorldItemHibernateResult(status, Optional.empty(), Optional.empty());
    }

    private static WorldItemActivationResult activationResult(
            WorldItemActivationResult.Status status) {
        return new WorldItemActivationResult(status, Optional.empty());
    }

    private void assertMainThread(String operation) {
        mainThreadGuard.assertMainThread(operation);
    }

    private void assertMutationAllowed(String operation) {
        if (projectionCallbackActive) {
            throw new IllegalStateException(
                    "WorldItem mutation is forbidden during projection callback: "
                            + operation);
        }
        assertMainThread(operation);
        if (closed) {
            throw new IllegalStateException("LogicalWorldItemService is closed: " + operation);
        }
    }

    @Override
    public void close() {
        assertMainThread("world item service close");
        if (closed) {
            return;
        }
        if (projectionCallbackActive) {
            throw new IllegalStateException(
                    "WorldItem service cannot close during projection callback");
        }
        closed = true;
        persistenceTickets.values().forEach(
                prepared -> prepared.detachedFreshness.set(false));
        liveState = LiveState.restored(
                Map.of(), Map.of(), Map.of(),
                liveState.nextItemId, liveState.itemIdsExhausted);
        liveMetadata.clear();
        expiryIndex.clear();
        pageCache.clear();
        pageDescriptors.clear();
        cleanupIntents.clear();
        hibernateTickets.clear();
        persistenceTickets.clear();
        activationTickets.clear();
        persistenceIdOwners.clear();
        persistencePageOwners.clear();
        extractionReservations.clear();
        activeExtractions.clear();
        spawnReservations.clear();
        droppedCleanupIntents = 0L;
        cleanupWrittenBytes = 0L;
    }

    private static long saturatingAdd(long left, long right) {
        return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
    }

    private void advancePagingEpoch() {
        setPagingEpoch(Math.addExact(pagingEpoch, 1L));
    }

    private void setPagingEpoch(long value) {
        pagingEpoch = value;
        detachedPagingEpoch.set(value);
    }

    private void setCurrentWorldTick(long value) {
        currentWorldTick = value;
        detachedWorldTick.set(value);
    }

    private void runProjectionCallback(Runnable callback) {
        if (projectionCallbackActive) {
            throw new IllegalStateException(
                    "WorldItem projection callbacks are not reentrant");
        }
        projectionCallbackActive = true;
        try {
            callback.run();
        } finally {
            projectionCallbackActive = false;
        }
    }

    private static boolean finite(double value) {
        return Double.isFinite(value);
    }

    private static OptionalLong nextRevision(long revision) {
        return revision == Long.MAX_VALUE
                ? OptionalLong.empty()
                : OptionalLong.of(revision + 1);
    }

    private static final class ItemState {
        private WorldItemSnapshot item;
        private WorldItemPhysicalState physicalState = WorldItemPhysicalState.ACTIVE;
        private final Optional<com.overlord.interaction.api.EntityRef> source;
        private final long spawnTick;
        private final long pickupAvailableTick;
        private final long expiresAtWorldTick;

        private ItemState(
                WorldItemSnapshot item,
                Optional<com.overlord.interaction.api.EntityRef> source,
                long spawnTick,
                long pickupAvailableTick) {
            this(item, source, spawnTick, pickupAvailableTick,
                    WorldItemRuntimeSnapshot.saturatingExpiry(spawnTick),
                    WorldItemPhysicalState.ACTIVE);
        }

        private ItemState(
                WorldItemSnapshot item,
                Optional<com.overlord.interaction.api.EntityRef> source,
                long spawnTick,
                long pickupAvailableTick,
                WorldItemPhysicalState physicalState) {
            this(item, source, spawnTick, pickupAvailableTick,
                    WorldItemRuntimeSnapshot.saturatingExpiry(spawnTick), physicalState);
        }

        private ItemState(
                WorldItemSnapshot item,
                Optional<com.overlord.interaction.api.EntityRef> source,
                long spawnTick,
                long pickupAvailableTick,
                long expiresAtWorldTick,
                WorldItemPhysicalState physicalState) {
            this.item = item;
            this.source = source;
            this.spawnTick = spawnTick;
            this.pickupAvailableTick = pickupAvailableTick;
            this.expiresAtWorldTick = expiresAtWorldTick;
            this.physicalState = physicalState;
        }

        private WorldItemRuntimeSnapshot runtimeSnapshot() {
            return new WorldItemRuntimeSnapshot(
                    item, source, spawnTick, pickupAvailableTick, expiresAtWorldTick);
        }

        private WorldItemPhysicalSnapshot physicalSnapshot(
                boolean extractionReserved) {
            return new WorldItemPhysicalSnapshot(
                    runtimeSnapshot(), physicalState, extractionReserved);
        }
    }

    private static final class SpawnState {
        private final WorldItemSpawnReservation reservation;
        private SpawnTerminal terminal;
        private WorldItemRuntimeSnapshot committedRuntime;

        private SpawnState(WorldItemSpawnReservation reservation) {
            this.reservation = reservation;
        }
    }

    private static final class ExtractionState {
        private final WorldItemReservation reservation;
        private ExtractionTerminal terminal;

        private ExtractionState(WorldItemReservation reservation) {
            this.reservation = reservation;
        }
    }

    private enum SpawnTerminal {
        COMMITTED,
        ROLLED_BACK
    }

    private enum ExtractionTerminal {
        COMMITTED,
        ROLLED_BACK
    }

    private enum ActivationTerminal {
        COMMITTED,
        CANCELED,
        ROLLED_BACK
    }

    private record HibernatePreparation(
            long epoch,
            ChunkKey chunkKey,
            WorldItemHibernatePayload payload,
            WorldItemPersistenceTicket persistenceTicket) {
    }

    private record LinkedHibernateState(
            LiveState liveState,
            Map<WorldItemId, WorldItemLiveMetadata> liveMetadata,
            long checkpointRevision,
            long checkpointWorldTick,
            Map<ChunkKey, WorldItemPageDescriptor> pageDescriptors,
            WorldItemPageCache.Snapshot pageCache,
            long cacheAccessOrder,
            Map<WorldItemReservationId, ExtractionState> extractionReservations,
            Map<WorldItemId, WorldItemReservationId> activeExtractions,
            Map<WorldItemSpawnReservationId, SpawnState> spawnReservations) {
    }

    private enum PersistenceKind {
        HIBERNATE,
        SAVE,
        CLEANUP
    }

    private record CleanupIntent(
            ChunkKey chunkKey,
            WorldItemPageSnapshot replacement,
            WorldItemPageDescriptor replacementDescriptor) {
        private CleanupIntent {
            if ((replacement == null) != (replacementDescriptor == null)) {
                throw new IllegalArgumentException(
                        "cleanup replacement and descriptor must be paired");
            }
        }
    }

    private record PersistencePreparation(
            PersistenceKind kind,
            long epoch,
            WorldItemPersistencePlan plan,
            AtomicBoolean detachedFreshness,
            WorldItemHibernateTicket hibernateTicket,
            Map<WorldItemId, WorldItemLiveMetadata> metadataBefore,
            WorldItemPageSnapshot page,
            WorldItemPageDescriptor descriptor,
            Map<ChunkKey, WorldItemPageSnapshot> publishedPages,
            Map<ChunkKey, WorldItemPageDescriptor> publishedDescriptors,
            List<WorldItemId> itemIds,
            List<ChunkKey> cleanupKeys,
            Set<ChunkKey> touchedKeys) {
        private PersistencePreparation {
            Objects.requireNonNull(detachedFreshness, "detachedFreshness");
            metadataBefore = Map.copyOf(metadataBefore);
            publishedPages = Map.copyOf(publishedPages);
            publishedDescriptors = Map.copyOf(publishedDescriptors);
            itemIds = List.copyOf(itemIds);
            cleanupKeys = List.copyOf(cleanupKeys);
            touchedKeys = Set.copyOf(touchedKeys);
        }
    }

    private static final class ActivationPreparation {
        private final long epoch;
        private final ChunkKey chunkKey;
        private final WorldItemHibernatePayload payload;
        private final WorldItemPageDescriptor descriptor;
        private final Map<WorldItemId, WorldItemLiveMetadata> metadataBefore;
        private final boolean paged;
        private final WorldItemPageSnapshot cachePage;
        private final WorldItemPageDescriptor cacheDescriptor;
        private final boolean cacheUnproved;
        private ActivationTerminal terminal;
        private LiveState before;
        private LiveState after;
        private long committedEpoch;
        private WorldItemPageCache.Snapshot cacheBeforeCommit;
        private Map<ChunkKey, CleanupIntent> cleanupBeforeCommit;
        private long droppedCleanupIntentsBeforeCommit;
        private long cacheAccessOrderBeforeCommit;
        private boolean cachePublished;

        private ActivationPreparation(
                long epoch,
                ChunkKey chunkKey,
                WorldItemHibernatePayload payload,
                WorldItemPageDescriptor descriptor,
                Map<WorldItemId, WorldItemLiveMetadata> metadataBefore,
                boolean paged,
                WorldItemPageSnapshot cachePage,
                WorldItemPageDescriptor cacheDescriptor,
                boolean cacheUnproved) {
            this.epoch = epoch;
            this.chunkKey = chunkKey;
            this.payload = payload;
            this.descriptor = descriptor;
            this.metadataBefore = Map.copyOf(metadataBefore);
            this.paged = paged;
            this.cachePage = cachePage;
            this.cacheDescriptor = cacheDescriptor;
            this.cacheUnproved = cacheUnproved;
        }
    }

    private record ActivationValidation(WorldItemActivationResult.Status status) {
        private static ActivationValidation failed(
                WorldItemActivationResult.Status status) {
            return new ActivationValidation(status);
        }

        private static ActivationValidation prepared() {
            return new ActivationValidation(WorldItemActivationResult.Status.PREPARED);
        }
    }

    private record ItemLocation(boolean dormant, ChunkKey chunkKey) {
        private static ItemLocation active() {
            return new ItemLocation(false, null);
        }

        private static ItemLocation dormant(ChunkKey chunkKey) {
            return new ItemLocation(true, ChunkCoordinatePolicy.requireSafe(chunkKey));
        }
    }

    private record RestoreValidation(
            WorldItemRestoreResult.Status status,
            LiveState liveState) {
        private static RestoreValidation failed(WorldItemRestoreResult.Status status) {
            return new RestoreValidation(status, null);
        }

        private static RestoreValidation restored(
                Map<WorldItemId, ItemState> items,
                Map<ChunkKey, Map<WorldItemId, ItemState>> dormantByChunk,
                Map<WorldItemId, ItemLocation> itemLocations,
                long nextItemId,
                boolean itemIdsExhausted) {
            return new RestoreValidation(
                    WorldItemRestoreResult.Status.RESTORED,
                    LiveState.restored(
                            items,
                            dormantByChunk,
                            itemLocations,
                            nextItemId,
                            itemIdsExhausted));
        }
    }

    private static final class LiveState {
        private final Map<WorldItemId, ItemState> items;
        private final Map<ChunkKey, Map<WorldItemId, ItemState>> dormantByChunk;
        private final Map<WorldItemId, ItemLocation> itemLocations;
        private long nextItemId;
        private boolean itemIdsExhausted;
        private final boolean restoreEligible;

        private LiveState(
                Map<WorldItemId, ItemState> items,
                Map<ChunkKey, Map<WorldItemId, ItemState>> dormantByChunk,
                Map<WorldItemId, ItemLocation> itemLocations,
                long nextItemId,
                boolean itemIdsExhausted,
                boolean restoreEligible) {
            this.items = items;
            this.dormantByChunk = dormantByChunk;
            this.itemLocations = itemLocations;
            this.nextItemId = nextItemId;
            this.itemIdsExhausted = itemIdsExhausted;
            this.restoreEligible = restoreEligible;
        }

        private static LiveState fresh() {
            return new LiveState(
                    new LinkedHashMap<>(),
                    new LinkedHashMap<>(),
                    new LinkedHashMap<>(),
                    0,
                    false,
                    true);
        }

        private static LiveState restored(
                Map<WorldItemId, ItemState> items,
                Map<ChunkKey, Map<WorldItemId, ItemState>> dormantByChunk,
                Map<WorldItemId, ItemLocation> itemLocations,
                long nextItemId,
                boolean itemIdsExhausted) {
            return new LiveState(
                    new LinkedHashMap<>(items),
                    copyBuckets(dormantByChunk),
                    new LinkedHashMap<>(itemLocations),
                    nextItemId,
                    itemIdsExhausted,
                    false);
        }

        private LiveState hibernate(
                ChunkKey chunkKey, List<WorldItemRestoreEntry> entries) {
            Map<WorldItemId, ItemState> nextActive = new LinkedHashMap<>(items);
            Map<ChunkKey, Map<WorldItemId, ItemState>> nextDormant =
                    copyBuckets(dormantByChunk);
            Map<WorldItemId, ItemLocation> nextLocations =
                    new LinkedHashMap<>(itemLocations);
            Map<WorldItemId, ItemState> bucket = entries.isEmpty()
                    ? null
                    : nextDormant.computeIfAbsent(
                            chunkKey, ignored -> new LinkedHashMap<>());
            for (WorldItemRestoreEntry entry : entries) {
                WorldItemId id = entry.runtime().item().id();
                ItemState moved = nextActive.remove(id);
                if (moved == null || bucket == null
                        || bucket.putIfAbsent(id, moved) != null) {
                    throw new IllegalStateException(
                            "validated hibernation aggregate changed before publication");
                }
                nextLocations.put(id, ItemLocation.dormant(chunkKey));
            }
            return new LiveState(
                    nextActive,
                    nextDormant,
                    nextLocations,
                    nextItemId,
                    itemIdsExhausted,
                    false);
        }

        private LiveState activate(
                ChunkKey chunkKey, WorldItemHibernatePayload payload) {
            Map<WorldItemId, ItemState> nextActive = new LinkedHashMap<>(items);
            Map<ChunkKey, Map<WorldItemId, ItemState>> nextDormant =
                    copyBuckets(dormantByChunk);
            Map<WorldItemId, ItemLocation> nextLocations =
                    new LinkedHashMap<>(itemLocations);
            Map<WorldItemId, ItemState> bucket = nextDormant.get(chunkKey);
            for (WorldItemRestoreEntry entry : payload.entries()) {
                WorldItemId id = entry.runtime().item().id();
                ItemState moved;
                if (bucket != null && bucket.containsKey(id)) {
                    moved = bucket.remove(id);
                } else {
                    WorldItemRuntimeSnapshot runtime = entry.runtime();
                    moved = new ItemState(
                            runtime.item(),
                            runtime.source(),
                            runtime.spawnTick(),
                            runtime.pickupAvailableTick(),
                            runtime.expiresAtWorldTick(),
                            entry.physicalState());
                }
                if (nextActive.putIfAbsent(id, moved) != null) {
                    throw new IllegalStateException(
                            "validated activation aggregate changed before publication");
                }
                nextLocations.put(id, ItemLocation.active());
            }
            if (bucket != null && bucket.isEmpty()) {
                nextDormant.remove(chunkKey);
            }
            long mergedNext = Math.max(nextItemId, payload.nextItemId());
            boolean mergedExhausted = itemIdsExhausted || payload.itemIdsExhausted();
            if (mergedExhausted) {
                mergedNext = Long.MAX_VALUE;
            }
            return new LiveState(
                    nextActive,
                    nextDormant,
                    nextLocations,
                    mergedNext,
                    mergedExhausted,
                    false);
        }

        private LiveState without(List<WorldItemId> removedIds) {
            Map<WorldItemId, ItemState> nextActive = new LinkedHashMap<>(items);
            Map<ChunkKey, Map<WorldItemId, ItemState>> nextDormant =
                    copyBuckets(dormantByChunk);
            Map<WorldItemId, ItemLocation> nextLocations =
                    new LinkedHashMap<>(itemLocations);
            for (WorldItemId id : removedIds) {
                nextActive.remove(id);
                ItemLocation location = nextLocations.remove(id);
                if (location != null && location.dormant) {
                    Map<WorldItemId, ItemState> bucket =
                            nextDormant.get(location.chunkKey);
                    if (bucket != null) {
                        bucket.remove(id);
                        if (bucket.isEmpty()) {
                            nextDormant.remove(location.chunkKey);
                        }
                    }
                }
            }
            return new LiveState(
                    nextActive,
                    nextDormant,
                    nextLocations,
                    nextItemId,
                    itemIdsExhausted,
                    false);
        }

        private static Map<ChunkKey, Map<WorldItemId, ItemState>> copyBuckets(
                Map<ChunkKey, Map<WorldItemId, ItemState>> source) {
            Map<ChunkKey, Map<WorldItemId, ItemState>> copy = new LinkedHashMap<>();
            for (Map.Entry<ChunkKey, Map<WorldItemId, ItemState>> entry
                    : source.entrySet()) {
                copy.put(entry.getKey(), new LinkedHashMap<>(entry.getValue()));
            }
            return copy;
        }
    }

    @FunctionalInterface
    interface RestorePublicationProbe {
        void beforePublication(
                Object detachedAggregate,
                LogicalWorldItemSnapshot validatedSnapshot,
                WorldItemRestoreResult success);
    }
}

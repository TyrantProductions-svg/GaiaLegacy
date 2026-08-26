package com.gaia.worlditem;

import com.overlord.core.thread.MainThreadGuard;
import com.overlord.config.GameConfig;
import com.overlord.physics.Aabb;
import com.overlord.physics.CollisionWorld;
import com.overlord.physics.MassProperties;
import com.overlord.physics.PhysicsBody;
import com.overlord.physics.PhysicsWorld;
import com.overlord.physics.SimulationOrigin;
import com.overlord.physics.SpatialQueryResult;
import com.overlord.physics.SweepResult;
import com.overlord.voxel.ChunkKey;
import com.overlord.voxel.ChunkRepository;
import com.overlord.worlditem.LogicalWorldItemService;
import com.overlord.worlditem.api.WorldItemActivationResult;
import com.overlord.worlditem.api.WorldItemActivationTicket;
import com.overlord.worlditem.api.WorldItemHibernateResult;
import com.overlord.worlditem.api.WorldItemHibernateTicket;
import com.overlord.worlditem.api.WorldItemDurableProof;
import com.overlord.worlditem.api.WorldItemId;
import com.overlord.worlditem.api.WorldItemMotionUpdate;
import com.overlord.worlditem.api.WorldItemMotionUpdateResult;
import com.overlord.worlditem.api.WorldItemPhysicalSnapshot;
import com.overlord.worlditem.api.WorldItemPhysicalState;
import com.overlord.worlditem.api.WorldItemPersistenceTicket;
import com.overlord.worlditem.api.WorldItemRuntimeAccess;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.joml.Vector3f;

/**
 * Main-thread, stable-ID projection of logical world items into PhysicsWorld.
 *
 * <p>The system owns the deterministic physical projection and its fixed-step
 * gravity, static-voxel collision, support, sleep, bounds, and Chunk-freeze
 * policy. Pickup and rendering remain outside this class.</p>
 */
public final class PhysicalWorldItemSystem implements AutoCloseable {
    private static final float FIXED_STEP_SECONDS = 1.0f / 60.0f;
    @FunctionalInterface
    public interface ProjectionFactory {
        PhysicsBody create(WorldItemPhysicalSnapshot snapshot);
    }

    enum ProjectionConstructionStage {
        AFTER_BODY_CREATION,
        AFTER_PROJECTION_CONSTRUCTION,
        AFTER_ROLLBACK_TRACKING,
        AFTER_REGISTRATION,
        AFTER_MAP_INSERTION
    }

    @FunctionalInterface
    interface ProjectionConstructionObserver {
        void reached(ProjectionConstructionStage stage);
    }

    enum ProjectionRemovalStage {
        AFTER_BODY_REMOVAL,
        AFTER_MAP_REMOVAL
    }

    @FunctionalInterface
    interface ProjectionRemovalObserver {
        void reached(ProjectionRemovalStage stage, WorldItemId id);
    }

    private static final ProjectionConstructionObserver NO_CONSTRUCTION_OBSERVER = stage -> {
    };
    private static final ProjectionRemovalObserver NO_REMOVAL_OBSERVER = (stage, id) -> {
    };

    private final WorldItemRuntimeAccess runtimeAccess;
    private final PhysicsWorld physicsWorld;
    private final ChunkRepository chunks;
    private final MainThreadGuard mainThreadGuard;
    private final WorldItemPhysicsConfig config;
    private final ProjectionFactory projectionFactory;
    private final ProjectionConstructionObserver constructionObserver;
    private final ProjectionRemovalObserver removalObserver;
    private final Map<WorldItemId, Projection> projections = new LinkedHashMap<>();
    private final Map<WorldItemId, RecoveryBlock> recoveryBlocks = new LinkedHashMap<>();
    private final Set<WorldItemId> pendingLostIds = new HashSet<>();
    private final Set<WorldItemId> unreportedLostIds = new HashSet<>();

    private long created;
    private long rebuilt;
    private long destroyed;
    private long appliedWrites;
    private long staleRejections;
    private long lost;
    private long capacitySkipped;
    private long recoveryFailures;
    private List<WorldItemId> capacitySkippedIds = List.of();
    private boolean prepared;
    private boolean closed;
    private SimulationOrigin simulationOrigin = new SimulationOrigin(new ChunkKey(0, 0));
    private boolean originAware;

    public PhysicalWorldItemSystem(
            WorldItemRuntimeAccess runtimeAccess,
            PhysicsWorld physicsWorld,
            MainThreadGuard mainThreadGuard,
            WorldItemPhysicsConfig config) {
        this(
                runtimeAccess,
                physicsWorld,
                null,
                mainThreadGuard,
                config,
                snapshot -> createDefaultBody(snapshot, config),
                NO_CONSTRUCTION_OBSERVER,
                NO_REMOVAL_OBSERVER);
    }

    public PhysicalWorldItemSystem(
            WorldItemRuntimeAccess runtimeAccess,
            PhysicsWorld physicsWorld,
            ChunkRepository chunks,
            MainThreadGuard mainThreadGuard,
            WorldItemPhysicsConfig config) {
        this(
                runtimeAccess,
                physicsWorld,
                chunks,
                mainThreadGuard,
                config,
                snapshot -> createDefaultBody(snapshot, config),
                NO_CONSTRUCTION_OBSERVER,
                NO_REMOVAL_OBSERVER);
    }

    public PhysicalWorldItemSystem(
            WorldItemRuntimeAccess runtimeAccess,
            PhysicsWorld physicsWorld,
            MainThreadGuard mainThreadGuard,
            WorldItemPhysicsConfig config,
            ChunkRepository chunks) {
        this(
                runtimeAccess,
                physicsWorld,
                chunks,
                mainThreadGuard,
                config);
    }

    public PhysicalWorldItemSystem(
            WorldItemRuntimeAccess runtimeAccess,
            PhysicsWorld physicsWorld,
            MainThreadGuard mainThreadGuard,
            WorldItemPhysicsConfig config,
            ProjectionFactory projectionFactory) {
        this(
                runtimeAccess,
                physicsWorld,
                null,
                mainThreadGuard,
                config,
                projectionFactory,
                NO_CONSTRUCTION_OBSERVER,
                NO_REMOVAL_OBSERVER);
    }

    PhysicalWorldItemSystem(
            WorldItemRuntimeAccess runtimeAccess,
            PhysicsWorld physicsWorld,
            MainThreadGuard mainThreadGuard,
            WorldItemPhysicsConfig config,
            ProjectionFactory projectionFactory,
            ProjectionConstructionObserver constructionObserver) {
        this(
                runtimeAccess,
                physicsWorld,
                null,
                mainThreadGuard,
                config,
                projectionFactory,
                constructionObserver,
                NO_REMOVAL_OBSERVER);
    }

    PhysicalWorldItemSystem(
            WorldItemRuntimeAccess runtimeAccess,
            PhysicsWorld physicsWorld,
            ChunkRepository chunks,
            MainThreadGuard mainThreadGuard,
            WorldItemPhysicsConfig config,
            ProjectionFactory projectionFactory,
            ProjectionConstructionObserver constructionObserver) {
        this(
                runtimeAccess,
                physicsWorld,
                chunks,
                mainThreadGuard,
                config,
                projectionFactory,
                constructionObserver,
                NO_REMOVAL_OBSERVER);
    }

    PhysicalWorldItemSystem(
            WorldItemRuntimeAccess runtimeAccess,
            PhysicsWorld physicsWorld,
            MainThreadGuard mainThreadGuard,
            WorldItemPhysicsConfig config,
            ProjectionFactory projectionFactory,
            ProjectionConstructionObserver constructionObserver,
            ProjectionRemovalObserver removalObserver) {
        this(
                runtimeAccess,
                physicsWorld,
                null,
                mainThreadGuard,
                config,
                projectionFactory,
                constructionObserver,
                removalObserver);
    }

    private PhysicalWorldItemSystem(
            WorldItemRuntimeAccess runtimeAccess,
            PhysicsWorld physicsWorld,
            ChunkRepository chunks,
            MainThreadGuard mainThreadGuard,
            WorldItemPhysicsConfig config,
            ProjectionFactory projectionFactory,
            ProjectionConstructionObserver constructionObserver,
            ProjectionRemovalObserver removalObserver) {
        this.runtimeAccess = Objects.requireNonNull(runtimeAccess, "runtimeAccess");
        this.physicsWorld = Objects.requireNonNull(physicsWorld, "physicsWorld");
        this.chunks = chunks;
        this.mainThreadGuard = Objects.requireNonNull(mainThreadGuard, "mainThreadGuard");
        this.config = Objects.requireNonNull(config, "config");
        this.projectionFactory = Objects.requireNonNull(projectionFactory, "projectionFactory");
        this.constructionObserver = Objects.requireNonNull(
                constructionObserver, "constructionObserver");
        this.removalObserver = Objects.requireNonNull(
                removalObserver, "removalObserver");
    }

    public void prepareStep(long tick) {
        assertMainThread("physical world-item prepare");
        ensureOpen();
        if (tick < 0) {
            throw new IllegalArgumentException("tick must be non-negative");
        }
        prepared = false;
        List<WorldItemPhysicalSnapshot> snapshots = orderedSnapshots();
        Map<WorldItemId, WorldItemPhysicalSnapshot> byId = indexSnapshots(snapshots);
        snapshots = List.copyOf(byId.values());
        pendingLostIds.removeIf(id -> !byId.containsKey(id));
        recoveryBlocks.keySet().removeIf(id -> !byId.containsKey(id));
        for (WorldItemId id : new ArrayList<>(projections.keySet())) {
            if (!byId.containsKey(id)) {
                removeProjection(id);
                pendingLostIds.remove(id);
            }
        }

        List<ProjectionConstruction> createdThisPass = new ArrayList<>();
        List<WorldItemId> rebuiltLostIds = new ArrayList<>();
        Map<WorldItemId, RecoveryBlock> recoveryBlocksBefore =
                new LinkedHashMap<>(recoveryBlocks);
        long recoveryFailuresBefore = recoveryFailures;
        boolean physicsPreparationStarted = false;
        try {
            for (WorldItemPhysicalSnapshot snapshot : snapshots) {
                Projection projection = projections.get(snapshot.id());
                if (projection != null && !physicsWorld.containsBody(projection.body)) {
                    if (projections.remove(snapshot.id(), projection)) {
                        pendingLostIds.add(snapshot.id());
                        unreportedLostIds.add(snapshot.id());
                    }
                    projection = null;
                }
                if (projection != null) {
                    if (projection.revision != snapshot.runtime().item().revision()) {
                        synchronize(projection, snapshot);
                        projection.sleepSteps = 0;
                        projection.state = WorldItemPhysicalState.ACTIVE;
                        projection.body.setSleeping(false);
                    } else if (!projection.runtime.equals(snapshot)) {
                        projection.refreshMetadata(snapshot);
                    }
                }
            }

            if (chunks != null) {
                for (WorldItemPhysicalSnapshot snapshot : snapshots) {
                    Projection projection = projections.get(snapshot.id());
                    if (projection != null && !collisionDataAvailable(projection.body, true)) {
                        freezeProjection(projection);
                    } else if (projection == null
                            && !collisionDataAvailable(snapshot, true)) {
                        freezeUnprojected(snapshot);
                    }
                }
            }

            int available = config.maxProjections() - projections.size();
            List<WorldItemPhysicalSnapshot> admissionCandidates = snapshots.stream()
                    .filter(snapshot -> !projections.containsKey(snapshot.id()))
                    .filter(snapshot -> chunks == null
                            || collisionDataAvailable(snapshot, true))
                    .filter(snapshot -> !recoveryBlocked(snapshot))
                    .toList();
            int admittedCount = Math.min(available, admissionCandidates.size());
            List<WorldItemPhysicalSnapshot> admitted = new ArrayList<>(
                    admissionCandidates.subList(0, admittedCount));
            List<WorldItemId> skipped = new ArrayList<>(admissionCandidates.subList(admittedCount,
                            admissionCandidates.size()).stream()
                    .map(WorldItemPhysicalSnapshot::id)
                    .toList());
            skipped.sort(Comparator.comparingLong(WorldItemId::value));
            List<WorldItemId> nextCapacitySkippedIds = List.copyOf(skipped);
            long nextCapacitySkipped = Math.addExact(capacitySkipped, skipped.size());

            // Capacity admission is finalized from one stable-ID-sorted set
            // before any new body is registered.
            for (int index = 0; index < admitted.size(); index++) {
                WorldItemPhysicalSnapshot snapshot = admitted.get(index);
                snapshot = activateIfReloaded(snapshot);
                if (snapshot == null) {
                    admitted.remove(index--);
                    continue;
                }
                admitted.set(index, snapshot);
                createProjection(snapshot, createdThisPass);
                created++;
                if (pendingLostIds.contains(snapshot.id())) {
                    rebuiltLostIds.add(snapshot.id());
                }
            }
            physicsPreparationStarted = true;
            for (Projection projection : orderedProjections()) {
                preparePhysics(projection);
            }
            capacitySkippedIds = nextCapacitySkippedIds;
            capacitySkipped = nextCapacitySkipped;
            lost = Math.addExact(lost, unreportedLostIds.size());
            unreportedLostIds.clear();
            for (WorldItemId rebuiltId : rebuiltLostIds) {
                if (projections.containsKey(rebuiltId)
                        && pendingLostIds.remove(rebuiltId)) {
                    rebuilt++;
                }
            }
            prepared = true;
        } catch (RuntimeException | Error failure) {
            recoveryBlocks.clear();
            recoveryBlocks.putAll(recoveryBlocksBefore);
            recoveryFailures = recoveryFailuresBefore;
            rollbackConstructions(createdThisPass);
            if (physicsPreparationStarted) {
                discardAllProjections();
            }
            prepared = false;
            throw failure;
        }
    }

    /** Rebuilds the physical projection of freshly restored logical state. */
    public void reconcileRestoredCanonicalState(long tick) {
        assertMainThread("physical world-item restore reconciliation");
        ensureOpen();
        if (tick < 0) {
            throw new IllegalArgumentException("tick must be non-negative");
        }
        if (prepared) {
            throw new IllegalStateException(
                    "cannot reconcile restored projections during a prepared step");
        }

        runProjectionTransaction(
                () -> reconcileRestoredCanonicalStateTransaction(tick));
    }

    private void reconcileRestoredCanonicalStateTransaction(long tick) {
        List<WorldItemPhysicalSnapshot> snapshots = orderedSnapshots();
        snapshots = List.copyOf(indexSnapshots(snapshots).values());
        List<WorldItemPhysicalSnapshot> candidates = snapshots.stream()
                .filter(snapshot -> chunks == null
                        || collisionDataAvailable(snapshot, false))
                .toList();
        int admittedCount = Math.min(config.maxProjections(), candidates.size());
        List<WorldItemPhysicalSnapshot> admitted =
                candidates.subList(0, admittedCount);
        Map<WorldItemId, WorldItemPhysicalSnapshot> desired =
                indexSnapshots(admitted);
        List<WorldItemId> skipped = candidates.subList(
                        admittedCount, candidates.size()).stream()
                .map(WorldItemPhysicalSnapshot::id)
                .toList();

        List<Projection> replacements = new ArrayList<>();
        List<Projection> registeredReplacements = new ArrayList<>();
        long nextCreated = created;
        for (WorldItemPhysicalSnapshot snapshot : admitted) {
            Projection existing = projections.get(snapshot.id());
            if (existing == null
                    || !existing.runtime.equals(snapshot)
                    || !physicsWorld.containsBody(existing.body)) {
                nextCreated = Math.addExact(nextCreated, 1L);
            }
        }
        long nextCapacitySkipped = Math.addExact(capacitySkipped, skipped.size());

        try {
            for (WorldItemPhysicalSnapshot snapshot : admitted) {
                Projection existing = projections.get(snapshot.id());
                if (existing != null
                        && existing.runtime.equals(snapshot)
                        && physicsWorld.containsBody(existing.body)) {
                    continue;
                }

                PhysicsBody body = Objects.requireNonNull(
                        projectionFactory.create(snapshot),
                        "projection factory result");
                body.teleport(new Vector3f(
                        localX(snapshot.runtime().item().positionX()),
                        finiteFloat(
                                snapshot.runtime().item().positionY(),
                                "positionY"),
                        localZ(snapshot.runtime().item().positionZ())));
                constructionObserver.reached(
                        ProjectionConstructionStage.AFTER_BODY_CREATION);
                Projection replacement = new Projection(snapshot, body);
                constructionObserver.reached(
                        ProjectionConstructionStage.AFTER_PROJECTION_CONSTRUCTION);
                replacements.add(replacement);
                constructionObserver.reached(
                        ProjectionConstructionStage.AFTER_ROLLBACK_TRACKING);
                if (!recoverRestoredProjection(replacement)) {
                    throw new IllegalStateException(
                            "restored projection overlap recovery failed for "
                                    + snapshot.id());
                }
            }

            Set<PhysicsBody> detachedBodies = java.util.Collections.newSetFromMap(
                    new java.util.IdentityHashMap<>());
            for (Projection replacement : replacements) {
                if (!detachedBodies.add(replacement.body)
                        || physicsWorld.containsBody(replacement.body)) {
                    throw new IllegalStateException(
                            "restored projection body was already registered for "
                                    + replacement.id);
                }
            }
            for (Projection replacement : replacements) {
                if (!physicsWorld.addBody(replacement.body)) {
                    throw new IllegalStateException(
                            "restored projection body was already registered for "
                                    + replacement.id);
                }
                registeredReplacements.add(replacement);
            }
            for (Projection replacement : replacements) {
                constructionObserver.reached(
                        ProjectionConstructionStage.AFTER_REGISTRATION);
            }

            Set<WorldItemId> replacementIds = replacements.stream()
                    .map(projection -> projection.id)
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
            for (WorldItemId id : new ArrayList<>(projections.keySet())) {
                if (!desired.containsKey(id) || replacementIds.contains(id)) {
                    removeProjection(id);
                }
            }
            for (Projection replacement : replacements) {
                Projection previous = projections.putIfAbsent(
                        replacement.id, replacement);
                if (previous != null) {
                    throw new IllegalStateException(
                            "restored projection already exists for "
                                    + replacement.id);
                }
            }
            for (Projection replacement : replacements) {
                constructionObserver.reached(
                        ProjectionConstructionStage.AFTER_MAP_INSERTION);
            }

            created = nextCreated;
            capacitySkippedIds = List.copyOf(skipped);
            capacitySkipped = nextCapacitySkipped;
        } catch (RuntimeException | Error failure) {
            for (int index = replacements.size() - 1; index >= 0; index--) {
                Projection replacement = replacements.get(index);
                projections.remove(replacement.id, replacement);
                if (registeredReplacements.contains(replacement)) {
                    physicsWorld.removeBody(replacement.body);
                }
            }
            throw failure;
        }
    }

    /** Removes projections whose canonical lifecycle ended at this authoritative tick. */
    public void removeExpiredProjections(List<WorldItemId> expiredIds) {
        assertMainThread("physical world-item expiry reconciliation");
        ensureOpen();
        if (prepared) {
            throw new IllegalStateException(
                    "cannot reconcile expired projections during a prepared step");
        }
        List<WorldItemId> ordered = Objects.requireNonNull(expiredIds, "expiredIds")
                .stream()
                .map(id -> Objects.requireNonNull(id, "expired WorldItem ID"))
                .distinct()
                .sorted(Comparator.comparingLong(WorldItemId::value))
                .toList();
        for (WorldItemId id : ordered) {
            if (runtimeAccess.physicalSnapshot(id).isPresent()) {
                throw new IllegalArgumentException(
                        "cannot remove a projection for a live canonical WorldItem");
            }
        }
        runProjectionTransaction(() -> {
            for (WorldItemId id : ordered) {
                removeProjection(id);
                recoveryBlocks.remove(id);
                pendingLostIds.remove(id);
                unreportedLostIds.remove(id);
            }
        });
    }

    /** Commits logical hibernation and projection removal as one rollback-safe transaction. */
    public WorldItemHibernateResult commitHibernate(
            LogicalWorldItemService logical,
            WorldItemHibernateTicket ticket) {
        assertMainThread("physical world-item hibernation commit");
        ensureOpen();
        requireLogicalAuthority(logical);
        if (prepared) {
            throw new IllegalStateException(
                    "cannot hibernate world items during a prepared physics step");
        }

        return logical.commitHibernate(
                ticket,
                () -> runProjectionTransaction(this::removeNonCanonicalProjections));
    }

    /** Commits one durable linked hibernation across logical and physical ownership. */
    public WorldItemHibernateResult commitLinkedHibernate(
            LogicalWorldItemService logical,
            WorldItemHibernateTicket hibernateTicket,
            WorldItemPersistenceTicket persistenceTicket,
            WorldItemDurableProof proof) {
        assertMainThread("physical linked world-item hibernation commit");
        ensureOpen();
        requireLogicalAuthority(logical);
        if (prepared) {
            throw new IllegalStateException(
                    "cannot hibernate world items during a prepared physics step");
        }

        return logical.commitLinkedHibernate(
                hibernateTicket,
                persistenceTicket,
                proof,
                () -> runProjectionTransaction(this::removeNonCanonicalProjections));
    }

    private void removeNonCanonicalProjections() {
        Set<WorldItemId> activeIds = runtimeAccess.physicalSnapshots().stream()
                .map(WorldItemPhysicalSnapshot::id)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        for (WorldItemId id : new ArrayList<>(projections.keySet())) {
            if (!activeIds.contains(id)) {
                removeProjection(id);
                recoveryBlocks.remove(id);
                pendingLostIds.remove(id);
                unreportedLostIds.remove(id);
            }
        }
    }

    /** Commits activation and rolls it back exactly if projection construction fails. */
    public WorldItemActivationResult commitActivate(
            LogicalWorldItemService logical,
            WorldItemActivationTicket ticket,
            long tick) {
        assertMainThread("physical world-item activation commit");
        ensureOpen();
        requireLogicalAuthority(logical);
        if (prepared) {
            throw new IllegalStateException(
                    "cannot activate world items during a prepared physics step");
        }

        return logical.commitActivate(
                ticket,
                () -> reconcileRestoredCanonicalState(tick));
    }

    /** Expires canonical items and their projections as one owner-thread transaction. */
    public List<WorldItemId> deliverWorldTick(
            LogicalWorldItemService logical, long worldTick) {
        assertMainThread("physical world-item expiry transaction");
        ensureOpen();
        requireLogicalAuthority(logical);
        if (prepared) {
            throw new IllegalStateException(
                    "cannot expire world items during a prepared physics step");
        }
        return logical.deliverWorldTick(
                worldTick,
                () -> runProjectionTransaction(this::removeNonCanonicalProjections));
    }

    /** Runs one complete deterministic world-item fixed step at 1/60 second. */
    public void step(long tick) {
        assertMainThread("physical world-item step");
        prepareStep(tick);
        try {
            physicsWorld.step(FIXED_STEP_SECONDS);
        } catch (RuntimeException | Error failure) {
            abortPreparedStep();
            throw failure;
        }
        finishStep();
    }

    /** Discards physical scratch after a failed shared physics step. */
    public void abortStep() {
        assertMainThread("physical world-item abort");
        ensureOpen();
        abortPreparedStep();
    }

    public void finishStep() {
        assertMainThread("physical world-item finish");
        ensureOpen();
        if (!prepared) {
            return;
        }
        try {
            for (Projection projection : orderedProjections()) {
                if (runtimeAccess.motionPinnedForPersistence(projection.id)) {
                    runtimeAccess.physicalSnapshot(projection.id)
                            .ifPresent(snapshot -> synchronize(projection, snapshot));
                    continue;
                }
                Vector3f position = projection.body.position(new Vector3f());
                Vector3f velocity = projection.body.linearVelocity(new Vector3f());
                if (!finite(position) || !finite(velocity)) {
                    removeProjection(projection.id);
                    continue;
                }
                clampTerminalVelocity(velocity);
                projection.body.setLinearVelocity(velocity);
                if (!recoverOrDisable(projection)) {
                    removeProjection(projection.id);
                    continue;
                }
                position = projection.body.position(position);
                boolean supported = applyGroundProbe(projection, position, velocity);
                position = projection.body.position(position);
                velocity = projection.body.linearVelocity(velocity);
                projection.state = updateSleepState(
                        projection, supported, position, velocity);
                if (sameMotion(projection, position, velocity)) {
                    continue;
                }

                WorldItemMotionUpdateResult result = runtimeAccess.updateMotion(
                        new WorldItemMotionUpdate(
                                projection.id,
                                projection.revision,
                                globalX(position.x),
                                position.y,
                                globalZ(position.z),
                                velocity.x,
                                velocity.y,
                                velocity.z,
                                projection.state));
                if (result.status() == WorldItemMotionUpdateResult.Status.APPLIED) {
                    WorldItemPhysicalSnapshot snapshot = result.snapshot().orElseThrow();
                    projection.adopt(snapshot, position);
                    appliedWrites++;
                } else if (result.status()
                        == WorldItemMotionUpdateResult.Status.REVISION_EXHAUSTED) {
                    synchronize(projection, result.snapshot().orElseThrow());
                } else {
                    if (result.status() == WorldItemMotionUpdateResult.Status.STALE_REVISION) {
                        staleRejections++;
                    }
                    removeProjection(projection.id);
                }
            }
        } catch (RuntimeException | Error failure) {
            discardAllProjections();
            throw failure;
        } finally {
            prepared = false;
        }
    }

    public List<WorldItemPresentationSnapshot> presentationSnapshots() {
        assertMainThread("physical world-item presentation snapshot");
        if (closed) {
            return List.of();
        }
        return orderedProjections().stream()
                .map(Projection::presentation)
                .toList();
    }

    /** Prepares a bounded projection-only rebase without mutating canonical item state. */
    public PreparedOriginRebase prepareOriginRebase(
            SimulationOrigin oldOrigin, SimulationOrigin newOrigin) {
        assertMainThread("physical world-item origin rebase");
        ensureOpen();
        Objects.requireNonNull(oldOrigin, "oldOrigin");
        Objects.requireNonNull(newOrigin, "newOrigin");
        if (!simulationOrigin.equals(oldOrigin)) {
            throw new IllegalStateException("old origin does not match the committed origin");
        }
        float offsetX = preciseOffset(oldOrigin.worldOriginX(), newOrigin.worldOriginX(), "x");
        float offsetZ = preciseOffset(oldOrigin.worldOriginZ(), newOrigin.worldOriginZ(), "z");
        List<Projection> preparedProjections = orderedProjections();
        List<PreparedProjectionRebase> replacements =
                new ArrayList<>(preparedProjections.size());
        for (Projection projection : preparedProjections) {
            PhysicsBody.PreparedRebase preparedBody = projection.body.prepareRebase(
                    new Vector3f(offsetX, 0.0f, offsetZ));
            double previousX = projection.previousX + offsetX;
            double currentX = projection.currentX + offsetX;
            double previousZ = projection.previousZ + offsetZ;
            double currentZ = projection.currentZ + offsetZ;
            if (!Double.isFinite(previousX)
                    || !Double.isFinite(currentX)
                    || !Double.isFinite(previousZ)
                    || !Double.isFinite(currentZ)) {
                throw new IllegalArgumentException("rebased WorldItem projection must remain finite");
            }
            replacements.add(new PreparedProjectionRebase(
                    projection, preparedBody, previousX, currentX, previousZ, currentZ));
        }
        return () -> {
            for (int index = 0; index < replacements.size(); index++) {
                PreparedProjectionRebase replacement = replacements.get(index);
                replacement.body.commit();
                replacement.projection.previousX = replacement.previousX;
                replacement.projection.currentX = replacement.currentX;
                replacement.projection.previousZ = replacement.previousZ;
                replacement.projection.currentZ = replacement.currentZ;
            }
            simulationOrigin = newOrigin;
            originAware = chunks != null;
        };
    }

    public WorldItemPhysicsMetrics metrics() {
        assertMainThread("physical world-item metrics");
        int active = 0;
        int grounded = 0;
        int sleeping = 0;
        int frozen = 0;
        for (WorldItemPhysicalSnapshot snapshot : runtimeAccess.physicalSnapshots()) {
            switch (snapshot.state()) {
                case ACTIVE -> active++;
                case GROUNDED -> grounded++;
                case SLEEPING -> sleeping++;
                case FROZEN_UNLOADED -> frozen++;
            }
        }
        return new WorldItemPhysicsMetrics(
                projections.size(), created, rebuilt, destroyed,
                appliedWrites, staleRejections, lost, capacitySkipped,
                capacitySkippedIds, recoveryFailures, orderedRecoveryBlockedIds(),
                active, grounded, sleeping, frozen);
    }

    @Override
    public void close() {
        assertMainThread("physical world-item shutdown");
        if (closed) {
            return;
        }
        closed = true;
        prepared = false;
        pendingLostIds.clear();
        unreportedLostIds.clear();
        recoveryBlocks.clear();
        Throwable failure = null;
        for (WorldItemId id : new ArrayList<>(projections.keySet())) {
            try {
                removeProjection(id);
            } catch (RuntimeException | Error cleanupFailure) {
                failure = appendFailure(failure, cleanupFailure);
            }
        }
        projections.clear();
        if (failure != null) {
            rethrow(failure);
        }
    }

    static PhysicsBody createDefaultBody(
            WorldItemPhysicalSnapshot snapshot, float edgeLength) {
        Objects.requireNonNull(snapshot, "snapshot");
        float half = edgeLength * 0.5f;
        if (!Float.isFinite(half) || half <= 0) {
            throw new IllegalArgumentException("edgeLength must be finite and positive");
        }
        PhysicsBody body = new PhysicsBody(
                new Aabb(-half, -half, -half, half, half, half),
                MassProperties.dynamic(1.0f));
        body.teleport(new Vector3f(
                finiteFloat(snapshot.runtime().item().positionX(), "positionX"),
                finiteFloat(snapshot.runtime().item().positionY(), "positionY"),
                finiteFloat(snapshot.runtime().item().positionZ(), "positionZ")));
        body.setLinearVelocity(new Vector3f(
                finiteFloat(snapshot.runtime().item().velocityX(), "velocityX"),
                finiteFloat(snapshot.runtime().item().velocityY(), "velocityY"),
                finiteFloat(snapshot.runtime().item().velocityZ(), "velocityZ")));
        return body;
    }

    static PhysicsBody createDefaultBody(
            WorldItemPhysicalSnapshot snapshot,
            WorldItemPhysicsConfig config) {
        PhysicsBody body = createDefaultBody(snapshot, config.edgeLength());
        body.setGravityScale(1.0f);
        body.setMaximumFallSpeed(config.maximumFallSpeed());
        body.setRestitution(config.restitution());
        // World-item friction is support-only and is applied after the ground
        // probe. Generic contact friction would also damp wall and ceiling
        // tangents while the item is airborne.
        body.setFriction(0.0f);
        return body;
    }

    static PhysicsBody createDefaultBody(WorldItemPhysicalSnapshot snapshot) {
        return createDefaultBody(
                snapshot, GameConfig.Interaction.WORLD_ITEM_EDGE_LENGTH);
    }

    private void preparePhysics(Projection projection) {
        if (chunks != null && !collisionDataAvailable(projection.body, true)) {
            freezeProjection(projection);
            return;
        }
        Vector3f position = projection.body.position(new Vector3f());
        Vector3f velocity = projection.body.linearVelocity(new Vector3f());
        if (!finite(position) || !finite(velocity)) {
            return;
        }

        if (projection.state == WorldItemPhysicalState.SLEEPING) {
            var canonical = projection.runtime.runtime().item();
            boolean explicitVelocity =
                    Math.abs(canonical.velocityX() - velocity.x) > 1.0e-6
                            || Math.abs(canonical.velocityY() - velocity.y) > 1.0e-6
                            || Math.abs(canonical.velocityZ() - velocity.z) > 1.0e-6;
            boolean speedWake =
                    velocity.lengthSquared()
                            > config.sleepSpeedThreshold()
                                    * config.sleepSpeedThreshold();
            boolean supported = hasGroundSupport(projection);
            if (explicitVelocity || speedWake || !supported) {
                projection.sleepSteps = 0;
                projection.state = WorldItemPhysicalState.ACTIVE;
                projection.body.setSleeping(false);
            } else {
                projection.body.setSleeping(true);
            }
        } else {
            if (projection.state == WorldItemPhysicalState.ACTIVE) {
                projection.sleepSteps = 0;
            }
            projection.body.setSleeping(false);
        }

        clampTerminalVelocity(velocity);
        projection.body.setLinearVelocity(velocity);
        if (chunks != null) {
            if (!recoverOrDisable(projection)) {
                removeProjection(projection.id);
            }
        }
    }

    private boolean recoverOrDisable(Projection projection) {
        if (recoverPosition(projection)) {
            recoveryBlocks.remove(projection.id);
            return true;
        }
        recoveryBlocks.put(projection.id, recoveryBlock(projection.runtime));
        recoveryFailures = Math.addExact(recoveryFailures, 1);
        return false;
    }

    private boolean recoverRestoredProjection(Projection projection) {
        if (!recoverPosition(projection)) {
            return false;
        }
        Vector3f position = projection.body.position(new Vector3f());
        Aabb bounds = projection.body.collider().translated(position);
        SpatialQueryResult<Boolean> overlap = overlapsQuery(
                physicsWorld.collisionWorld(), bounds);
        if (!collisionBoundsRepresentable(bounds)
                || (chunks != null
                        && !collisionDataAvailable(projection.body, false))
                || overlap.status() != SpatialQueryResult.Status.AVAILABLE
                || overlap.result().orElseThrow()) {
            return false;
        }
        projection.previousX = position.x;
        projection.previousY = position.y;
        projection.previousZ = position.z;
        projection.currentX = position.x;
        projection.currentY = position.y;
        projection.currentZ = position.z;
        return true;
    }

    private boolean recoverPosition(Projection projection) {
        Vector3f position = projection.body.position(new Vector3f());
        float half = config.edgeLength() * 0.5f;
        float topCenter = Math.max(half, config.worldHeight() - half);
        boolean outsideBounds = position.y < half || position.y > topCenter;
        CollisionWorld collisions = physicsWorld.collisionWorld();
        Aabb worldBounds = projection.body.collider().translated(position);
        if (!collisionBoundsRepresentable(worldBounds)) {
            return false;
        }
        SpatialQueryResult<Boolean> overlapQuery = overlapsQuery(collisions, worldBounds);
        if (overlapQuery.status() != SpatialQueryResult.Status.AVAILABLE) {
            return false;
        }
        boolean overlaps = !outsideBounds && overlapQuery.result().orElseThrow();
        if (!outsideBounds && !overlaps) {
            return true;
        }

        if (overlaps) {
            SpatialQueryResult<Vector3f> recovered = depenetrateQuery(
                    collisions,
                    projection.body.collider(),
                    position,
                    config.depenetrationIterations());
            if (recovered.status() != SpatialQueryResult.Status.AVAILABLE) {
                return false;
            }
            if (recovered.result().isPresent()) {
                Vector3f candidate = recovered.result().orElseThrow();
                Aabb candidateBounds =
                        projection.body.collider().translated(candidate);
                SpatialQueryResult<Boolean> candidateOverlap =
                        overlapsQuery(collisions, candidateBounds);
                if (candidate.y >= half
                        && candidate.y <= topCenter
                        && collisionBoundsRepresentable(candidateBounds)
                        && candidateOverlap.status() == SpatialQueryResult.Status.AVAILABLE
                        && !candidateOverlap.result().orElseThrow()) {
                    projection.body.setPosition(candidate);
                    return true;
                }
            }
        }

        float start = Math.min(topCenter, Math.max(half, position.y));
        int maximumSteps = (int) Math.floor((topCenter - start) / 0.25f);
        for (int step = 0; step <= maximumSteps; step++) {
            float candidateY = start + step * 0.25f;
            Vector3f candidate = new Vector3f(position.x, candidateY, position.z);
            Aabb candidateBounds = projection.body.collider().translated(candidate);
            if (!collisionBoundsRepresentable(candidateBounds)) {
                continue;
            }
            SpatialQueryResult<Boolean> candidateOverlap =
                    overlapsQuery(collisions, candidateBounds);
            if (candidateOverlap.status() != SpatialQueryResult.Status.AVAILABLE) {
                return false;
            }
            if (!candidateOverlap.result().orElseThrow()) {
                projection.body.setPosition(candidate);
                return true;
            }
        }
        return false;
    }

    private boolean applyGroundProbe(
            Projection projection, Vector3f position, Vector3f velocity) {
        float probeDistance = config.groundProbeDistance();
        float settleSpeed = Math.max(0.5f, config.sleepSpeedThreshold());
        if (probeDistance == 0 || velocity.y > settleSpeed) {
            return false;
        }
        SpatialQueryResult<SweepResult> contact = sweepQuery(
                physicsWorld.collisionWorld(),
                projection.body.collider(),
                position,
                new Vector3f(0.0f, -probeDistance, 0.0f));
        if (contact.status() != SpatialQueryResult.Status.AVAILABLE
                || contact.result().isEmpty()
                || contact.result().orElseThrow().normalY() <= 0) {
            return false;
        }

        SweepResult hit = contact.result().orElseThrow();
        float safeDistance = probeDistance * hit.fraction();
        if (Float.isFinite(safeDistance) && safeDistance > 0) {
            projection.body.setPosition(new Vector3f(
                    position.x,
                    position.y - safeDistance,
                    position.z));
        }
        if (Math.abs(velocity.y) <= settleSpeed) {
            velocity.y = 0;
        }
        float frictionScale = 1.0f - config.friction();
        velocity.x *= frictionScale;
        velocity.z *= frictionScale;
        projection.body.setLinearVelocity(velocity);
        return true;
    }

    private boolean hasGroundSupport(Projection projection) {
        if (config.groundProbeDistance() == 0) {
            return false;
        }
        Vector3f position = projection.body.position(new Vector3f());
        SpatialQueryResult<SweepResult> contact = sweepQuery(
                        physicsWorld.collisionWorld(),
                        projection.body.collider(),
                        position,
                        new Vector3f(0.0f, -config.groundProbeDistance(), 0.0f));
        return contact.status() == SpatialQueryResult.Status.AVAILABLE
                && contact.result().map(SweepResult::normalY).orElse(0.0f) > 0;
    }

    private SpatialQueryResult<SweepResult> sweepQuery(
            CollisionWorld collisions,
            Aabb collider,
            Vector3f position,
            Vector3f displacement) {
        return originAware
                ? collisions.sweep(simulationOrigin, collider, position, displacement)
                : SpatialQueryResult.available(
                        collisions.sweep(collider, position, displacement));
    }

    private SpatialQueryResult<Boolean> overlapsQuery(
            CollisionWorld collisions, Aabb bounds) {
        return originAware
                ? collisions.overlapsSolid(simulationOrigin, bounds)
                : SpatialQueryResult.available(
                        Optional.of(collisions.overlapsSolid(bounds)));
    }

    private SpatialQueryResult<Vector3f> depenetrateQuery(
            CollisionWorld collisions,
            Aabb collider,
            Vector3f position,
            int iterations) {
        return originAware
                ? collisions.depenetrate(simulationOrigin, collider, position, iterations)
                : SpatialQueryResult.available(
                        collisions.depenetrate(collider, position, iterations));
    }

    private WorldItemPhysicalState updateSleepState(
            Projection projection,
            boolean supported,
            Vector3f position,
            Vector3f velocity) {
        if (!supported) {
            projection.sleepSteps = 0;
            projection.body.setSleeping(false);
            return WorldItemPhysicalState.ACTIVE;
        }

        float threshold = config.sleepSpeedThreshold();
        if (velocity.lengthSquared() <= threshold * threshold) {
            projection.sleepSteps = Math.min(
                    config.sleepStableSteps(), projection.sleepSteps + 1);
            if (projection.sleepSteps >= config.sleepStableSteps()) {
                projection.body.setSleeping(true);
                return WorldItemPhysicalState.SLEEPING;
            }
        } else {
            projection.sleepSteps = 0;
        }
        projection.body.setSleeping(false);
        return WorldItemPhysicalState.GROUNDED;
    }

    private void clampTerminalVelocity(Vector3f velocity) {
        if (velocity.y < config.maximumFallSpeed()) {
            velocity.y = config.maximumFallSpeed();
        }
    }

    private boolean collisionDataAvailable(
            WorldItemPhysicalSnapshot snapshot, boolean includeSweep) {
        double half = config.edgeLength() * 0.5;
        var item = snapshot.runtime().item();
        double minX = item.positionX() - half;
        double maxX = item.positionX() + half;
        double minZ = item.positionZ() - half;
        double maxZ = item.positionZ() + half;
        if (includeSweep) {
            Vector3f velocity = new Vector3f(
                    finiteFloat(item.velocityX(), "velocityX"),
                    finiteFloat(item.velocityY(), "velocityY"),
                    finiteFloat(item.velocityZ(), "velocityZ"));
            velocity.y += GameConfig.Physics.GRAVITY * FIXED_STEP_SECONDS;
            clampTerminalVelocity(velocity);
            double displacementX = velocity.x * FIXED_STEP_SECONDS;
            double displacementZ = velocity.z * FIXED_STEP_SECONDS;
            minX += Math.min(0.0, displacementX);
            maxX += Math.max(0.0, displacementX);
            minZ += Math.min(0.0, displacementZ);
            maxZ += Math.max(0.0, displacementZ);
        }
        return collisionDataAvailable(minX, maxX, minZ, maxZ);
    }

    private boolean recoveryBlocked(WorldItemPhysicalSnapshot snapshot) {
        RecoveryBlock blocked = recoveryBlocks.get(snapshot.id());
        return blocked != null && blocked.equals(recoveryBlock(snapshot));
    }

    private RecoveryBlock recoveryBlock(WorldItemPhysicalSnapshot snapshot) {
        var item = snapshot.runtime().item();
        return new RecoveryBlock(item.revision(), collisionRevisions(snapshot));
    }

    private Map<ChunkKey, Long> collisionRevisions(
            WorldItemPhysicalSnapshot snapshot) {
        if (chunks == null) {
            return Map.of();
        }
        double half = config.edgeLength() * 0.5;
        var item = snapshot.runtime().item();
        double minX = item.positionX() - half;
        double maxX = item.positionX() + half;
        double minZ = item.positionZ() - half;
        double maxZ = item.positionZ() + half;
        if (!floorRepresentable(minX)
                || !floorRepresentable(maxX)
                || !floorRepresentable(minZ)
                || !floorRepresentable(maxZ)) {
            return Map.of();
        }
        ChunkKey minimum = ChunkKey.fromWorld(
                (int) Math.floor(minX), (int) Math.floor(minZ));
        ChunkKey maximum = ChunkKey.fromWorld(
                (int) Math.floor(exclusiveMaximum(minX, maxX)),
                (int) Math.floor(exclusiveMaximum(minZ, maxZ)));
        Map<ChunkKey, Long> revisions = new LinkedHashMap<>();
        for (long chunkX = minimum.x(); chunkX <= maximum.x(); chunkX++) {
            for (long chunkZ = minimum.z(); chunkZ <= maximum.z(); chunkZ++) {
                ChunkKey key = new ChunkKey((int) chunkX, (int) chunkZ);
                revisions.put(key, chunks.revision(key));
            }
        }
        return revisions;
    }

    private List<WorldItemId> orderedRecoveryBlockedIds() {
        return recoveryBlocks.keySet().stream()
                .sorted(Comparator.comparingLong(WorldItemId::value))
                .toList();
    }

    private boolean collisionDataAvailable(PhysicsBody body, boolean includeSweep) {
        Vector3f position = body.position(new Vector3f());
        Aabb bounds = body.collider().translated(position);
        if (includeSweep) {
            Vector3f velocity = body.linearVelocity(new Vector3f());
            velocity.y += GameConfig.Physics.GRAVITY * FIXED_STEP_SECONDS;
            clampTerminalVelocity(velocity);
            bounds = bounds.sweptBounds(new Vector3f(velocity).mul(FIXED_STEP_SECONDS));
        }
        return collisionDataAvailable(
                bounds.minX() + simulationOrigin.worldOriginX(),
                bounds.maxX() + simulationOrigin.worldOriginX(),
                bounds.minZ() + simulationOrigin.worldOriginZ(),
                bounds.maxZ() + simulationOrigin.worldOriginZ());
    }

    private boolean collisionDataAvailable(
            double minX, double maxX, double minZ, double maxZ) {
        if (chunks == null
                || !floorRepresentable(minX)
                || !floorRepresentable(maxX)
                || !floorRepresentable(minZ)
                || !floorRepresentable(maxZ)) {
            return false;
        }
        ChunkKey minimum = ChunkKey.fromWorld(
                (int) Math.floor(minX), (int) Math.floor(minZ));
        ChunkKey maximum = ChunkKey.fromWorld(
                (int) Math.floor(exclusiveMaximum(minX, maxX)),
                (int) Math.floor(exclusiveMaximum(minZ, maxZ)));
        for (long chunkX = minimum.x(); chunkX <= maximum.x(); chunkX++) {
            for (long chunkZ = minimum.z(); chunkZ <= maximum.z(); chunkZ++) {
                if (!chunks.contains(new ChunkKey((int) chunkX, (int) chunkZ))) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean floorRepresentable(double value) {
        double floor = Math.floor(value);
        return Double.isFinite(floor)
                && floor >= Integer.MIN_VALUE
                && floor <= Integer.MAX_VALUE;
    }

    private static boolean collisionBoundsRepresentable(Aabb bounds) {
        return floorRepresentable(bounds.minX())
                && floorRepresentable(bounds.maxX())
                && floorRepresentable(bounds.minY())
                && floorRepresentable(bounds.maxY())
                && floorRepresentable(bounds.minZ())
                && floorRepresentable(bounds.maxZ());
    }

    private void freezeProjection(Projection projection) {
        if (runtimeAccess.motionPinnedForPersistence(projection.id)) {
            runtimeAccess.physicalSnapshot(projection.id)
                    .ifPresent(snapshot -> synchronize(projection, snapshot));
            return;
        }
        Vector3f position = projection.body.position(new Vector3f());
        Vector3f velocity = projection.body.linearVelocity(new Vector3f());
        if (!finite(position) || !finite(velocity)) {
            return;
        }
        WorldItemMotionUpdateResult result = runtimeAccess.updateMotion(
                new WorldItemMotionUpdate(
                        projection.id,
                        projection.revision,
                        globalX(position.x),
                        position.y,
                        globalZ(position.z),
                        velocity.x,
                        velocity.y,
                        velocity.z,
                        WorldItemPhysicalState.FROZEN_UNLOADED));
        if (result.status() == WorldItemMotionUpdateResult.Status.APPLIED) {
            appliedWrites++;
            removeProjection(projection.id);
        } else if (result.status() == WorldItemMotionUpdateResult.Status.STALE_REVISION) {
            staleRejections++;
            removeProjection(projection.id);
        } else {
            result.snapshot().ifPresent(snapshot -> synchronize(projection, snapshot));
            removeProjection(projection.id);
        }
    }

    private void freezeUnprojected(WorldItemPhysicalSnapshot snapshot) {
        if (runtimeAccess.motionPinnedForPersistence(snapshot.id())) {
            return;
        }
        if (snapshot.state() == WorldItemPhysicalState.FROZEN_UNLOADED) {
            return;
        }
        var item = snapshot.runtime().item();
        WorldItemMotionUpdateResult result = runtimeAccess.updateMotion(
                new WorldItemMotionUpdate(
                        snapshot.id(),
                        item.revision(),
                        item.positionX(),
                        item.positionY(),
                        item.positionZ(),
                        item.velocityX(),
                        item.velocityY(),
                        item.velocityZ(),
                        WorldItemPhysicalState.FROZEN_UNLOADED));
        if (result.status() == WorldItemMotionUpdateResult.Status.APPLIED) {
            appliedWrites++;
        } else if (result.status() == WorldItemMotionUpdateResult.Status.STALE_REVISION) {
            staleRejections++;
        }
    }

    private WorldItemPhysicalSnapshot activateIfReloaded(
            WorldItemPhysicalSnapshot snapshot) {
        if (snapshot.state() != WorldItemPhysicalState.FROZEN_UNLOADED) {
            return snapshot;
        }
        var item = snapshot.runtime().item();
        WorldItemMotionUpdateResult result = runtimeAccess.updateMotion(
                new WorldItemMotionUpdate(
                        snapshot.id(),
                        item.revision(),
                        item.positionX(),
                        item.positionY(),
                        item.positionZ(),
                        item.velocityX(),
                        item.velocityY(),
                        item.velocityZ(),
                        WorldItemPhysicalState.ACTIVE));
        if (result.status() == WorldItemMotionUpdateResult.Status.APPLIED) {
            appliedWrites++;
            return result.snapshot().orElseThrow();
        }
        if (result.status() == WorldItemMotionUpdateResult.Status.STALE_REVISION) {
            return runtimeAccess.physicalSnapshot(snapshot.id()).orElse(null);
        }
        return null;
    }

    private Projection createProjection(
            WorldItemPhysicalSnapshot snapshot,
            List<ProjectionConstruction> rollbackOwnership) {
        PhysicsBody body = Objects.requireNonNull(
                projectionFactory.create(snapshot), "projection factory result");
        body.teleport(new Vector3f(
                localX(snapshot.runtime().item().positionX()),
                finiteFloat(snapshot.runtime().item().positionY(), "positionY"),
                localZ(snapshot.runtime().item().positionZ())));
        constructionObserver.reached(ProjectionConstructionStage.AFTER_BODY_CREATION);
        Projection projection = new Projection(snapshot, body);
        constructionObserver.reached(ProjectionConstructionStage.AFTER_PROJECTION_CONSTRUCTION);

        ProjectionConstruction construction = new ProjectionConstruction(projection);
        rollbackOwnership.add(construction);
        constructionObserver.reached(ProjectionConstructionStage.AFTER_ROLLBACK_TRACKING);
        if (!physicsWorld.addBody(body)) {
            throw new IllegalStateException(
                    "projection body was already registered for " + snapshot.id());
        }
        construction.registered = true;
        constructionObserver.reached(ProjectionConstructionStage.AFTER_REGISTRATION);

        Projection previous = projections.putIfAbsent(snapshot.id(), projection);
        if (previous != null) {
            throw new IllegalStateException(
                    "projection already exists for " + snapshot.id());
        }
        construction.mapped = true;
        constructionObserver.reached(ProjectionConstructionStage.AFTER_MAP_INSERTION);
        return projection;
    }

    private void rollbackConstructions(List<ProjectionConstruction> constructions) {
        for (int index = constructions.size() - 1; index >= 0; index--) {
            ProjectionConstruction construction = constructions.get(index);
            if (construction.mapped) {
                projections.remove(
                        construction.projection.id,
                        construction.projection);
                construction.mapped = false;
            }
            if (construction.registered) {
                if (physicsWorld.removeBody(construction.projection.body)) {
                    destroyed++;
                }
                construction.registered = false;
            }
        }
    }

    private void abortPreparedStep() {
        if (!prepared) {
            return;
        }
        discardAllProjections();
        prepared = false;
    }

    private void discardAllProjections() {
        for (WorldItemId id : new ArrayList<>(projections.keySet())) {
            removeProjection(id);
        }
    }

    private void synchronize(Projection projection, WorldItemPhysicalSnapshot snapshot) {
        Vector3f candidatePosition = new Vector3f(
                localX(snapshot.runtime().item().positionX()),
                finiteFloat(snapshot.runtime().item().positionY(), "positionY"),
                localZ(snapshot.runtime().item().positionZ()));
        Vector3f candidateVelocity = new Vector3f(
                finiteFloat(snapshot.runtime().item().velocityX(), "velocityX"),
                finiteFloat(snapshot.runtime().item().velocityY(), "velocityY"),
                finiteFloat(snapshot.runtime().item().velocityZ(), "velocityZ"));

        projection.body.teleport(candidatePosition);
        projection.body.setLinearVelocity(candidateVelocity);
        projection.runtime = snapshot;
        projection.state = snapshot.state();
        projection.revision = snapshot.runtime().item().revision();
        projection.sleepSteps = projection.state == WorldItemPhysicalState.SLEEPING
                ? config.sleepStableSteps()
                : 0;
        projection.body.setSleeping(projection.state == WorldItemPhysicalState.SLEEPING);
        projection.previousX = localX(snapshot.runtime().item().positionX());
        projection.previousY = snapshot.runtime().item().positionY();
        projection.previousZ = localZ(snapshot.runtime().item().positionZ());
        projection.currentX = projection.previousX;
        projection.currentY = projection.previousY;
        projection.currentZ = projection.previousZ;
    }

    private void removeProjection(WorldItemId id) {
        Projection projection = projections.get(id);
        if (projection != null) {
            if (physicsWorld.removeBody(projection.body)) {
                destroyed++;
            }
            removalObserver.reached(ProjectionRemovalStage.AFTER_BODY_REMOVAL, id);
            projections.remove(id, projection);
            removalObserver.reached(ProjectionRemovalStage.AFTER_MAP_REMOVAL, id);
        }
    }

    private static double exclusiveMaximum(double minimum, double maximum) {
        return maximum > minimum ? Math.nextDown(maximum) : maximum;
    }

    private void runProjectionTransaction(Runnable publication) {
        ProjectionStateSnapshot before = captureProjectionState();
        try {
            publication.run();
        } catch (RuntimeException | Error failure) {
            try {
                restoreProjectionState(before);
            } catch (RuntimeException | Error rollbackFailure) {
                appendFailure(failure, rollbackFailure);
            }
            rethrow(failure);
        }
    }

    private ProjectionStateSnapshot captureProjectionState() {
        return new ProjectionStateSnapshot(
                new LinkedHashMap<>(projections),
                physicsWorld.bodies(),
                new LinkedHashMap<>(recoveryBlocks),
                Set.copyOf(pendingLostIds),
                Set.copyOf(unreportedLostIds),
                created,
                rebuilt,
                destroyed,
                appliedWrites,
                staleRejections,
                lost,
                capacitySkipped,
                recoveryFailures,
                capacitySkippedIds,
                prepared);
    }

    private void restoreProjectionState(ProjectionStateSnapshot before) {
        for (PhysicsBody body : physicsWorld.bodies()) {
            if (!physicsWorld.removeBody(body)) {
                throw new IllegalStateException(
                        "failed to detach projection body during rollback");
            }
        }
        for (PhysicsBody body : before.physicsBodies) {
            if (!physicsWorld.addBody(body)) {
                throw new IllegalStateException(
                        "failed to restore projection body during rollback");
            }
        }
        projections.clear();
        projections.putAll(before.projections);
        recoveryBlocks.clear();
        recoveryBlocks.putAll(before.recoveryBlocks);
        pendingLostIds.clear();
        pendingLostIds.addAll(before.pendingLostIds);
        unreportedLostIds.clear();
        unreportedLostIds.addAll(before.unreportedLostIds);
        created = before.created;
        rebuilt = before.rebuilt;
        destroyed = before.destroyed;
        appliedWrites = before.appliedWrites;
        staleRejections = before.staleRejections;
        lost = before.lost;
        capacitySkipped = before.capacitySkipped;
        recoveryFailures = before.recoveryFailures;
        capacitySkippedIds = before.capacitySkippedIds;
        prepared = before.prepared;
    }

    private List<WorldItemPhysicalSnapshot> orderedSnapshots() {
        List<WorldItemPhysicalSnapshot> snapshots =
                new ArrayList<>(runtimeAccess.physicalSnapshots());
        snapshots.sort(Comparator.comparingLong(snapshot -> snapshot.id().value()));
        return List.copyOf(snapshots);
    }

    private static Map<WorldItemId, WorldItemPhysicalSnapshot> indexSnapshots(
            List<WorldItemPhysicalSnapshot> snapshots) {
        Map<WorldItemId, WorldItemPhysicalSnapshot> byId = new LinkedHashMap<>();
        for (WorldItemPhysicalSnapshot snapshot : snapshots) {
            Objects.requireNonNull(snapshot, "physical snapshot");
            WorldItemPhysicalSnapshot previous = byId.putIfAbsent(snapshot.id(), snapshot);
            if (previous != null && !previous.equals(snapshot)) {
                throw new IllegalArgumentException(
                        "conflicting physical snapshots for world-item id: "
                                + snapshot.id());
            }
        }
        return byId;
    }

    private List<Projection> orderedProjections() {
        return projections.values().stream()
                .sorted(Comparator.comparingLong(projection -> projection.id.value()))
                .toList();
    }

    private static boolean sameMotion(
            Projection projection, Vector3f position, Vector3f velocity) {
        return projection.runtime.state() == projection.state
                && close(projection.currentX, position.x)
                && close(projection.currentY, position.y)
                && close(projection.currentZ, position.z)
                && close(projection.runtime.runtime().item().velocityX(), velocity.x)
                && close(projection.runtime.runtime().item().velocityY(), velocity.y)
                && close(projection.runtime.runtime().item().velocityZ(), velocity.z);
    }

    private static boolean close(double expected, float actual) {
        return Math.abs(expected - actual) <= 1.0e-6;
    }

    private static boolean finite(Vector3f value) {
        return Float.isFinite(value.x)
                && Float.isFinite(value.y)
                && Float.isFinite(value.z);
    }

    private static float finiteFloat(double value, String name) {
        float converted = (float) value;
        if (!Double.isFinite(value) || !Float.isFinite(converted)) {
            throw new IllegalArgumentException(name + " cannot be represented by PhysicsBody");
        }
        return converted;
    }

    private float localX(double globalX) {
        return finiteFloat(globalX - simulationOrigin.worldOriginX(), "local positionX");
    }

    private float localZ(double globalZ) {
        return finiteFloat(globalZ - simulationOrigin.worldOriginZ(), "local positionZ");
    }

    private double globalX(float localX) {
        return simulationOrigin.worldOriginX() + (double) localX;
    }

    private double globalZ(float localZ) {
        return simulationOrigin.worldOriginZ() + (double) localZ;
    }

    private static float preciseOffset(long oldOrigin, long newOrigin, String axis) {
        long difference = Math.subtractExact(oldOrigin, newOrigin);
        float converted = difference;
        if (!Float.isFinite(converted) || (long) converted != difference) {
            throw new IllegalArgumentException(axis + " origin delta is not precisely representable");
        }
        return converted;
    }

    private void assertMainThread(String operation) {
        mainThreadGuard.assertMainThread(operation);
    }

    private void requireLogicalAuthority(LogicalWorldItemService logical) {
        Objects.requireNonNull(logical, "logical");
        if (runtimeAccess != logical) {
            throw new IllegalArgumentException(
                    "paging must use this projection system's logical authority");
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("physical world-item system is closed");
        }
    }

    private static Throwable appendFailure(Throwable primary, Throwable next) {
        if (primary == null) {
            return next;
        }
        if (primary != next) {
            primary.addSuppressed(next);
        }
        return primary;
    }

    private static void rethrow(Throwable failure) {
        if (failure instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
        throw (Error) failure;
    }

    private static final class ProjectionConstruction {
        private final Projection projection;
        private boolean registered;
        private boolean mapped;

        private ProjectionConstruction(Projection projection) {
            this.projection = projection;
        }
    }

    private record ProjectionStateSnapshot(
            Map<WorldItemId, Projection> projections,
            List<PhysicsBody> physicsBodies,
            Map<WorldItemId, RecoveryBlock> recoveryBlocks,
            Set<WorldItemId> pendingLostIds,
            Set<WorldItemId> unreportedLostIds,
            long created,
            long rebuilt,
            long destroyed,
            long appliedWrites,
            long staleRejections,
            long lost,
            long capacitySkipped,
            long recoveryFailures,
            List<WorldItemId> capacitySkippedIds,
            boolean prepared) {
        private ProjectionStateSnapshot {
            projections = new LinkedHashMap<>(projections);
            physicsBodies = List.copyOf(physicsBodies);
            recoveryBlocks = new LinkedHashMap<>(recoveryBlocks);
            pendingLostIds = Set.copyOf(pendingLostIds);
            unreportedLostIds = Set.copyOf(unreportedLostIds);
            capacitySkippedIds = List.copyOf(capacitySkippedIds);
        }
    }

    private record RecoveryBlock(
            long itemRevision, Map<ChunkKey, Long> collisionRevisions) {
        private RecoveryBlock {
            collisionRevisions = Map.copyOf(collisionRevisions);
        }
    }

    private record PreparedProjectionRebase(
            Projection projection,
            PhysicsBody.PreparedRebase body,
            double previousX,
            double currentX,
            double previousZ,
            double currentZ) {}

    private final class Projection {
        private final WorldItemId id;
        private final PhysicsBody body;
        private WorldItemPhysicalSnapshot runtime;
        private WorldItemPhysicalState state;
        private long revision;
        private int sleepSteps;
        private double previousX;
        private double previousY;
        private double previousZ;
        private double currentX;
        private double currentY;
        private double currentZ;

        private Projection(WorldItemPhysicalSnapshot snapshot, PhysicsBody body) {
            this.id = snapshot.id();
            this.body = body;
            this.runtime = snapshot;
            this.state = snapshot.state();
            this.revision = snapshot.runtime().item().revision();
            this.sleepSteps = snapshot.state() == WorldItemPhysicalState.SLEEPING
                    ? config.sleepStableSteps()
                    : 0;
            this.body.setSleeping(snapshot.state() == WorldItemPhysicalState.SLEEPING);
            this.previousX = localX(snapshot.runtime().item().positionX());
            this.previousY = snapshot.runtime().item().positionY();
            this.previousZ = localZ(snapshot.runtime().item().positionZ());
            this.currentX = previousX;
            this.currentY = previousY;
            this.currentZ = previousZ;
        }

        private void adopt(WorldItemPhysicalSnapshot snapshot, Vector3f position) {
            previousX = currentX;
            previousY = currentY;
            previousZ = currentZ;
            currentX = position.x;
            currentY = position.y;
            currentZ = position.z;
            runtime = snapshot;
            state = snapshot.state();
            revision = snapshot.runtime().item().revision();
            if (snapshot.state() == WorldItemPhysicalState.SLEEPING) {
                sleepSteps = config.sleepStableSteps();
            } else if (snapshot.state() == WorldItemPhysicalState.ACTIVE) {
                sleepSteps = 0;
            }
            body.setSleeping(snapshot.state() == WorldItemPhysicalState.SLEEPING);
        }

        private void refreshMetadata(WorldItemPhysicalSnapshot snapshot) {
            runtime = snapshot;
            state = snapshot.state();
            if (state != WorldItemPhysicalState.SLEEPING) {
                sleepSteps = 0;
                body.setSleeping(false);
            }
        }

        private WorldItemPresentationSnapshot presentation() {
            return new WorldItemPresentationSnapshot(
                    runtime,
                    previousX,
                    previousY,
                    previousZ,
                    currentX,
                    currentY,
                    currentZ);
        }
    }

    @FunctionalInterface
    public interface PreparedOriginRebase {
        void commit();
    }
}

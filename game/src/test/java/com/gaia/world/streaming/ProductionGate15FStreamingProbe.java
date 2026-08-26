package com.gaia.world.streaming;

import com.gaia.assets.GaiaAssetCatalog;
import com.gaia.save.archive.SaveArchiveReader;
import com.gaia.save.archive.SaveArchiveWriter;
import com.gaia.save.codec.ChunkSectionCodec;
import com.gaia.save.codec.InventorySectionCodec;
import com.gaia.save.codec.PlayerSectionCodec;
import com.gaia.save.codec.SaveSnapshotCodec;
import com.gaia.save.codec.WorldItemsSectionCodec;
import com.gaia.save.format.SaveGameId;
import com.gaia.save.format.SaveSectionId;
import com.gaia.save.snapshot.SaveGameSnapshot;
import com.gaia.save.session.SaveCoordinator;
import com.gaia.save.store.JdkSaveFileOperations;
import com.gaia.save.store.SaveFileOperations;
import com.gaia.save.streaming.Phase14MigrationResult;
import com.gaia.save.streaming.Phase14SaveMigrator;
import com.gaia.save.streaming.StreamedChunkCodec;
import com.gaia.save.streaming.StreamedChunkIndex;
import com.gaia.save.streaming.StreamedChunkIndexCodec;
import com.gaia.save.streaming.StreamedChunkStore;
import com.gaia.save.streaming.StreamedSessionSaveTarget;
import com.gaia.save.streaming.StreamedWorldItemPageBackend;
import com.gaia.session.GameSession;
import com.gaia.session.GameSessionFactory;
import com.gaia.session.GameSessionFrame;
import com.gaia.session.GameSessionSaveResult;
import com.gaia.session.GameSessionState;
import com.gaia.session.SessionSaveCaptureResult;
import com.gaia.session.streaming.SimulationOriginCoordinator;
import com.gaia.world.GaiaWorldGenerator;
import com.gaia.world.generation.GenerationContext;
import com.gaia.world.generation.GenerationBlockPalette;
import com.gaia.world.generation.DeterministicCoordinateSampler;
import com.gaia.world.generation.WorldGenerationConfig;
import com.gaia.world.generation.WorldGenerationResult;
import com.gaia.worlditem.PhysicalWorldItemSystem;
import com.overlord.assets.ResourceLocation;
import com.overlord.core.input.InputManager;
import com.overlord.core.input.MouseDelta;
import com.overlord.core.thread.MainThreadGuard;
import com.overlord.config.GameConfig;
import com.overlord.physics.PlayerController;
import com.overlord.physics.Aabb;
import com.overlord.physics.BlockCollisionShapeResolver;
import com.overlord.physics.BlockRaycast;
import com.overlord.physics.PhysicsWorld;
import com.overlord.physics.SpatialQueryResult;
import com.overlord.physics.SimulationOrigin;
import com.overlord.renderer.Camera;
import com.overlord.voxel.ChunkGenerationData;
import com.overlord.voxel.ChunkKey;
import com.overlord.voxel.ChunkRepositorySnapshot;
import com.overlord.voxel.ChunkSnapshot;
import com.overlord.voxel.GlobalPosition;
import com.overlord.voxel.World;
import com.overlord.worlditem.LogicalWorldItemService;
import com.overlord.worlditem.api.WorldItemId;
import com.overlord.worlditem.api.SaveIdentity;
import com.overlord.worlditem.api.WorldItemActivationResult;
import com.overlord.worlditem.api.WorldItemDurableProof;
import com.overlord.worlditem.api.WorldItemPageDescriptor;
import com.overlord.worlditem.api.WorldItemPageReadView;
import com.overlord.worlditem.api.WorldItemPageSnapshot;
import com.overlord.worlditem.api.WorldItemPagingCheckpoint;
import com.overlord.worlditem.api.WorldItemPersistencePlan;
import com.overlord.worlditem.api.WorldItemSnapshot;
import com.overlord.worlditem.api.WorldItemSpawnRequest;
import com.overlord.inventory.api.ItemStack;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.joml.Vector3f;

/**
 * Bounded test-source driver for Gate 15F. Every observation is captured from a
 * real headless production session and its session-owned streamed authorities.
 */
public final class ProductionGate15FStreamingProbe implements Gate15FStreamingProbe {
    private static final Object LOCK = new Object();
    private static volatile RunResult cached;
    private static volatile AssertionError cachedFailure;
    private static final MouseDelta NO_LOOK = new MouseDelta(0.0, 0.0);
    private static final Duration SETTLE_DEADLINE = Duration.ofMinutes(10);
    private static final Duration EXACT_KEY_DEADLINE = Duration.ofMinutes(10);
    private static final ChunkKey UNTOUCHED = new ChunkKey(-5, -10);
    private static final ChunkKey MODIFIED = new ChunkKey(80, -10);
    private static final ChunkKey RESTART_EVICTION_TARGET = new ChunkKey(-120, 10);

    @Override
    public Gate15FSoakObservation runStructuralSoak() {
        return result().soak();
    }

    @Override
    public Gate15FMeasurementObservation runMeasurement() {
        return result().measurement();
    }

    private static RunResult result() {
        RunResult value = cached;
        if (value != null) return value;
        AssertionError failure = cachedFailure;
        if (failure != null) throw failure;
        synchronized (LOCK) {
            if (cached == null && cachedFailure == null) {
                try {
                    cached = execute();
                } catch (AssertionError rejected) {
                    cachedFailure = rejected;
                    throw rejected;
                }
            }
            if (cachedFailure != null) throw cachedFailure;
            return cached;
        }
    }

    private static RunResult execute() {
        try {
            Fixture fixture = Fixture.create();
            List<ChunkKey> route = route();
            List<SimulationOrigin> origins = new ArrayList<>();
            List<Gate15FEpochObservation> epochs = new ArrayList<>();
            List<Gate15FPipelineCounterSample> counters = new ArrayList<>();
            List<Gate15FRetainedStateObservation> retained = new ArrayList<>();
            List<Gate15FLocalTransformObservation> transforms = new ArrayList<>();
            ChunkSnapshot untouchedBefore;
            ChunkSnapshot untouchedAfter;
            ChunkSnapshot modifiedBefore;
            ChunkSnapshot modifiedAfter;
            boolean untouchedAbsentFromDurableIndex;
            Gate15FCanonicalBlockQuery canonicalQuery;
            SaveGameSnapshot publishedSnapshot;
            SaveIdentity beforeIdentity;
            long beforeTick;
            RestartItemPreparation restartItem;
            long transition = 0L;
            boolean modifiedDurablyEvicted = false;

            try (GameSession session = fixture.factory().restore(fixture.restoreSnapshot())) {
                SessionHarness harness = new SessionHarness(session);
                harness.driveReady();
                harness.sample(transition, Gate15FLifecycleState.SETTLED,
                        origins, epochs, counters, retained, transforms);

                for (int index = 1; index < route.size(); index++) {
                    ChunkKey key = route.get(index);
                    transition++;
                    harness.moveTo(key);
                    harness.sample(transition, Gate15FLifecycleState.TRANSITIONING,
                            origins, epochs, counters, retained, transforms);
                    if (key.equals(UNTOUCHED) && harness.untouchedBefore == null) {
                        harness.untouchedBefore = harness.waitResidentStable(
                                key, "untouched-before-unload");
                    }
                    if (key.equals(MODIFIED) && harness.modifiedBefore == null) {
                        ChunkSnapshot beforeMutation = harness.waitResidentStable(
                                key, "modified-before-mutation");
                        int x = key.worldOriginX() + 3;
                        int y = 4;
                        int z = key.worldOriginZ() + 3;
                        byte existing = harness.world.getBlock(x, y, z);
                        byte replacement = existing == 1 ? (byte) 2 : (byte) 1;
                        if (!harness.world.setBlock(x, y, z, replacement)) {
                            throw new AssertionError("focused RED: real distant block mutation was rejected "
                                    + key + " existing=" + existing + " replacement=" + replacement);
                        }
                        harness.frame();
                        harness.modifiedBefore = harness.waitMutation(
                                key, beforeMutation.revision(), x, y, z, replacement);
                    } else if (key.equals(MODIFIED)
                            && modifiedDurablyEvicted
                            && harness.modifiedAfter == null) {
                        harness.modifiedAfter = harness.waitResidentStable(
                                key, "modified-after-first-durable-reload");
                    }
                    if (key.equals(new ChunkKey(120, -10))
                            && !modifiedDurablyEvicted) {
                        harness.waitEvicted(MODIFIED);
                        modifiedDurablyEvicted = true;
                    }
                    if (key.equals(new ChunkKey(-120, 10))) {
                        harness.waitEvicted(UNTOUCHED);
                    }
                }

                untouchedBefore = harness.untouchedBefore;
                modifiedBefore = harness.modifiedBefore;
                modifiedAfter = harness.modifiedAfter;
                if (untouchedBefore == null || modifiedBefore == null
                        || modifiedAfter == null) {
                    throw new AssertionError("probe checkpoints were not captured from the route");
                }

                untouchedAbsentFromDurableIndex = currentIndex(harness.store)
                        .entry(UNTOUCHED).isEmpty();
                transition++;
                harness.moveTo(UNTOUCHED);
                harness.sample(transition, Gate15FLifecycleState.TRANSITIONING,
                        origins, epochs, counters, retained, transforms);
                untouchedAfter = harness.waitResidentStable(
                        UNTOUCHED, "untouched-after-production-reload");

                transition++;
                harness.moveTo(MODIFIED);
                harness.sample(transition, Gate15FLifecycleState.TRANSITIONING,
                        origins, epochs, counters, retained, transforms);
                harness.waitResidentStable(MODIFIED, "modified-current-resident");

                if (modifiedAfter.revision() <= modifiedBefore.revision()
                        || !java.util.Arrays.equals(
                                modifiedBefore.copyBlocks(), modifiedAfter.copyBlocks())) {
                    throw new AssertionError("focused RED: modified distant Chunk did not reload exact bytes with a newer publication revision; key="
                            + MODIFIED + " beforeRevision=" + modifiedBefore.revision()
                            + " afterRevision=" + modifiedAfter.revision()
                            + " beforeHash=" + sha256(modifiedBefore.copyBlocks())
                            + " afterHash=" + sha256(modifiedAfter.copyBlocks())
                            + " metrics=" + harness.last.streamingMetrics());
                }
                canonicalQuery = harness.canonicalSpatialQuery();
                restartItem = harness.prepareRestartWorldItem(MODIFIED);
                GameSessionSaveResult saved = new SaveCoordinator(id -> {
                    throw new AssertionError("session-owned streamed target was not used for " + id);
                }).save(session, fixture.restoreSnapshot().metadata()
                        .createdAt().plusSeconds(3_600));
                if (saved.status() != GameSessionSaveResult.Status.SUCCESS) {
                    throw new AssertionError("real streamed Save & Quit publication failed: "
                            + saved.status() + " diagnostics="
                            + saved.diagnostics().stream()
                                    .map(value -> value.code() + ": " + value.message())
                                    .toList());
                }
                var committed = saved.committedManifest().orElseThrow();
                beforeIdentity = new SaveIdentity(UUID.fromString(
                        committed.saveGameId().value()));
                beforeTick = committed.fixedTick();
                publishedSnapshot = fixture.reopenSnapshot();
            }

            SaveIdentity afterIdentity;
            long afterTick;
            ChunkSnapshot modifiedAfterRestart;
            ChunkSnapshot modifiedAfterSecondReload;
            WorldItemSnapshot worldItemAfterRestart;
            long expiryAfterRestart;
            Gate15FWorldItemLifecycleObservation lifecycle;
            try (GameSession restarted = fixture.factory().restore(publishedSnapshot)) {
                SessionHarness restartHarness = new SessionHarness(restarted);
                restartHarness.driveReady();
                restartHarness.moveTo(MODIFIED);
                modifiedAfterRestart = restartHarness.waitResidentStable(
                        MODIFIED, "modified-after-process-restart");
                worldItemAfterRestart = restartHarness.activateWorldItem(
                        MODIFIED, restartItem.snapshot().id());
                expiryAfterRestart = restartHarness.worldItems
                        .physicalSnapshot(restartItem.snapshot().id()).orElseThrow()
                        .runtime().expiresAtWorldTick();
                SessionSaveCaptureResult restoredCapture =
                        restartHarness.waitCapturable();
                if (restoredCapture.status()
                        != SessionSaveCaptureResult.Status.CAPTURED) {
                    throw new AssertionError(
                            "fresh restart did not converge to a capturable revision: "
                                    + restoredCapture.status());
                }
                SaveGameSnapshot restoredSnapshot =
                        restoredCapture.snapshot().orElseThrow();
                afterIdentity = saveIdentity(restoredSnapshot);
                afterTick = restoredSnapshot.fixedTick();
                restartHarness.moveTo(RESTART_EVICTION_TARGET);
                restartHarness.waitEvicted(MODIFIED);
                restartHarness.moveTo(MODIFIED);
                modifiedAfterSecondReload = restartHarness.waitResidentStable(
                        MODIFIED, "modified-after-second-unload-reload");
                lifecycle = restartHarness.completeRestartWorldItemLifecycle(
                        MODIFIED, restartItem.snapshot());
            }

            Gate15FSoakObservation soak = new Gate15FSoakObservation(
                    route,
                    origins,
                    epochs,
                    counters,
                    List.of(new Gate15FUntouchedChunkObservation(
                            UNTOUCHED,
                            sha256(untouchedBefore.copyBlocks()),
                            sha256(untouchedAfter.copyBlocks()),
                            untouchedAbsentFromDurableIndex)),
                    List.of(new Gate15FModifiedChunkObservation(
                            modifiedBefore,
                            modifiedBefore.copyBlocks(),
                            modifiedAfter,
                            modifiedAfter.copyBlocks())),
                    List.of(lifecycle),
                    new Gate15FRestartObservation(
                            beforeIdentity, beforeTick, afterIdentity, afterTick,
                            modifiedAfterRestart, modifiedAfterSecondReload,
                            restartItem.snapshot(), worldItemAfterRestart,
                            restartItem.expiresAtWorldTick(), expiryAfterRestart),
                    transforms,
                    List.of(canonicalQuery),
                    retained);
            ChunkStreamingMetrics latencyMetrics = epochs.get(epochs.size() - 1).metrics();
            Gate15FMeasurementObservation measurement = new Gate15FMeasurementObservation(
                    List.of(
                            new Gate15FArchiveObservation(
                                    "save-root", countFiles(fixture.root())),
                            new Gate15FArchiveObservation(
                                    "world-root", countFiles(fixture.root().resolve(fixture.id().value())))),
                    epochs,
                    counters,
                    origins,
                    List.of(
                            new Gate15FLatencyObservation("load", latencyMetrics.loadLatencyNanos()),
                            new Gate15FLatencyObservation("generation", latencyMetrics.generationLatencyNanos()),
                            new Gate15FLatencyObservation("mesh", latencyMetrics.meshLatencyNanos()),
                            new Gate15FLatencyObservation("save", latencyMetrics.saveLatencyNanos()),
                            new Gate15FLatencyObservation("restore", latencyMetrics.restoreLatencyNanos())));
            return new RunResult(soak, measurement);
        } catch (RuntimeException | Error failure) {
            throw new AssertionError(
                    "Gate 15F production probe failed: " + failure.getMessage(),
                    failure);
        } catch (Exception failure) {
            throw new IllegalStateException("Gate 15F production probe failed", failure);
        }
    }

    private static SaveIdentity saveIdentity(SaveGameSnapshot snapshot) {
        return new SaveIdentity(UUID.fromString(snapshot.metadata().saveGameId().value()));
    }

    private static long countFiles(Path root) {
        try (var paths = Files.walk(root)) {
            return paths.filter(Files::isRegularFile).count();
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("could not count real archive files under " + root, failure);
        }
    }

    private static List<ChunkKey> route() {
        List<ChunkKey> keys = new ArrayList<>();
        keys.add(new ChunkKey(0, 0));
        keys.add(UNTOUCHED);
        keys.add(new ChunkKey(-10, -10));
        for (int x = -9; x <= 120; x++) keys.add(new ChunkKey(x, -10));
        for (int x = 119; x >= -120; x--) keys.add(new ChunkKey(x, -10));
        for (int z = -9; z <= 10; z++) keys.add(new ChunkKey(-120, z));
        for (int x = -119; x <= 0; x++) keys.add(new ChunkKey(x, 10));
        for (int z = 9; z >= 0; z--) keys.add(new ChunkKey(0, z));
        return List.copyOf(keys);
    }

    private static final class SessionHarness {
        private final GameSession session;
        private final Object runtime;
        private final World world;
        private final PlayerController player;
        private final SimulationOriginCoordinator origin;
        private final ChunkStreamingPipeline pipeline;
        private final LogicalWorldItemService worldItems;
        private final PhysicalWorldItemSystem physicalWorldItems;
        private final StreamedWorldItemPageBackend pageBackend;
        private final PhysicsWorld physicsWorld;
        private final StreamedChunkStore store;
        private final Camera camera;
        private GameSessionFrame last;
        private ChunkSnapshot untouchedBefore;
        private ChunkSnapshot modifiedBefore;
        private ChunkSnapshot modifiedAfter;
        private int observedPhysicalDescriptorCount = -1;
        private int observedPhysicalDependencies;

        private SessionHarness(GameSession session) {
            this.session = session;
            runtime = runtime(session);
            world = field(runtime, "world", World.class);
            player = field(runtime, "playerController", PlayerController.class);
            origin = field(runtime, "originCoordinator", SimulationOriginCoordinator.class);
            pipeline = field(runtime, "streamingPipeline", ChunkStreamingPipeline.class);
            worldItems = field(runtime, "worldItems", LogicalWorldItemService.class);
            physicalWorldItems = field(
                    runtime, "physicalWorldItems", PhysicalWorldItemSystem.class);
            pageBackend = field(
                    runtime, "streamedWorldItems", StreamedWorldItemPageBackend.class);
            physicsWorld = field(runtime, "physicsWorld", PhysicsWorld.class);
            store = field(runtime, "streamedChunkStore", StreamedChunkStore.class);
            Object environment = field(runtime, "environment", Object.class);
            camera = invoke(environment, "camera", Camera.class);
        }

        private void driveReady() {
            for (int poll = 0; poll < 200_000 && session.state() == GameSessionState.LOADING; poll++) {
                session.pollLoad();
                if (session.state() == GameSessionState.LOADING) {
                    java.util.concurrent.locks.LockSupport.parkNanos(1_000_000L);
                }
            }
            if (session.state() != GameSessionState.READY) {
                throw new AssertionError("production session did not become READY: " + session.state());
            }
            last = session.capturePaused();
            if (!idle(last.streamingMetrics())) {
                throw new AssertionError("paused READY baseline admitted streaming work: "
                        + last.streamingMetrics());
            }
        }

        private void moveTo(ChunkKey key) {
            GlobalPosition desired = new GlobalPosition(key, 0.5, 4.0, 0.5);
            Vector3f local = origin.simulationOrigin().toLocal(desired);
            player.teleport(local);
            last = frame();
            if (!last.streamingMetrics().playerGlobalPosition().chunkKey().equals(key)) {
                throw new AssertionError("production global/local conversion missed target; target="
                        + desired + " observed=" + last.streamingMetrics().playerGlobalPosition()
                        + " origin=" + origin.simulationOrigin() + " local=" + local);
            }
        }

        private GameSessionFrame frame() {
            return session.advancePlaying(0.0, NO_LOOK, true);
        }

        private void sample(long transition, Gate15FLifecycleState state,
                List<SimulationOrigin> origins,
                List<Gate15FEpochObservation> epochs,
                List<Gate15FPipelineCounterSample> counters,
                List<Gate15FRetainedStateObservation> retained,
                List<Gate15FLocalTransformObservation> transforms) {
            ChunkStreamingMetrics metrics = last.streamingMetrics();
            origins.add(metrics.simulationOrigin());
            Set<WorldItemId> survivors = new LinkedHashSet<>();
            worldItems.liveMetadata().forEach(row -> survivors.add(row.id()));
            Set<WorldItemId> expiry = expiryIds(worldItems);
            int descriptorCount = metrics.worldItems().physicalDescriptorCount();
            if (descriptorCount != observedPhysicalDescriptorCount) {
                observedPhysicalDependencies = physicalDependencies(store);
                observedPhysicalDescriptorCount = descriptorCount;
            }
            int dependencies = observedPhysicalDependencies;
            epochs.add(new Gate15FEpochObservation(
                    transition, state, metrics, survivors, expiry, dependencies));
            counters.add(new Gate15FPipelineCounterSample(
                    transition, metrics.canceled(), metrics.staleResults()));
            retained.add(new Gate15FRetainedStateObservation(
                    transition,
                    state,
                    metrics.residentChunks(),
                    pipeline.retainedWorkCount(),
                    metrics.worldItems().liveMetadataCount(),
                    metrics.worldItems().decodedPageCount(),
                    metrics.worldItems().physicalDescriptorCount()));
            Vector3f render = camera.getPosition();
            Vector3f physics = player.body().position(new Vector3f());
            transforms.add(new Gate15FLocalTransformObservation(
                    render.x, render.y, render.z, physics.x, physics.y, physics.z));
        }

        private ChunkSnapshot requireResident(ChunkKey key, String stage) {
            return world.chunks().snapshot(key).orElseThrow(() -> new AssertionError(
                    stage + " was not resident; metrics=" + last.streamingMetrics()
                            + " requested=" + pipeline.requestedKeys()
                            + " retainedWork=" + pipeline.retainedWorkCount()));
        }

        private ChunkSnapshot waitResidentStable(ChunkKey key, String stage) {
            long deadline = System.nanoTime() + EXACT_KEY_DEADLINE.toNanos();
            ChunkSnapshot prior = null;
            while (System.nanoTime() < deadline) {
                last = frame();
                failOnDiagnostics(stage);
                Optional<ChunkSnapshot> current = world.chunks().snapshot(key);
                if (current.isPresent()) {
                    ChunkSnapshot snapshot = current.orElseThrow();
                    if (prior != null
                            && prior.revision() == snapshot.revision()
                            && java.util.Arrays.equals(
                                    prior.copyBlocks(), snapshot.copyBlocks())) {
                        return snapshot;
                    }
                    prior = snapshot;
                }
                java.util.concurrent.locks.LockSupport.parkNanos(1_000_000L);
            }
            throw exactWaitFailure(stage, key);
        }

        private ChunkSnapshot waitMutation(
                ChunkKey key, long priorRevision,
                int x, int y, int z, byte expected) {
            long deadline = System.nanoTime() + EXACT_KEY_DEADLINE.toNanos();
            while (System.nanoTime() < deadline) {
                last = frame();
                failOnDiagnostics("modified-mutation");
                Optional<ChunkSnapshot> current = world.chunks().snapshot(key);
                if (current.isPresent()
                        && current.orElseThrow().revision() > priorRevision
                        && world.getBlock(x, y, z) == expected) {
                    return current.orElseThrow();
                }
                java.util.concurrent.locks.LockSupport.parkNanos(1_000_000L);
            }
            throw exactWaitFailure("modified-mutation", key);
        }

        private void waitEvicted(ChunkKey first, ChunkKey second) {
            long deadline = System.nanoTime() + EXACT_KEY_DEADLINE.toNanos();
            while (System.nanoTime() < deadline) {
                last = frame();
                failOnDiagnostics("probe-eviction");
                if (world.chunks().snapshot(first).isEmpty()
                        && world.chunks().snapshot(second).isEmpty()) {
                    return;
                }
                java.util.concurrent.locks.LockSupport.parkNanos(1_000_000L);
            }
            throw new AssertionError("probe-eviction did not complete; first="
                    + world.chunks().snapshot(first).isPresent()
                    + " second=" + world.chunks().snapshot(second).isPresent()
                     + " metrics=" + last.streamingMetrics()
                     + " backendStale=" + pageBackend.staleMetrics()
                     + " requested=" + pipeline.requestedKeys()
                     + " diagnostics=" + pipeline.diagnostics());
        }

        private void waitEvicted(ChunkKey key) {
            long deadline = System.nanoTime() + EXACT_KEY_DEADLINE.toNanos();
            while (System.nanoTime() < deadline) {
                last = frame();
                failOnDiagnostics("probe-single-eviction");
                if (world.chunks().snapshot(key).isEmpty()) {
                    return;
                }
                java.util.concurrent.locks.LockSupport.parkNanos(1_000_000L);
            }
            throw new AssertionError("probe-single-eviction did not complete; key="
                    + key + " resident=" + world.chunks().snapshot(key).isPresent()
                    + " metrics=" + last.streamingMetrics()
                    + " backendStale=" + pageBackend.staleMetrics()
                    + " requested=" + pipeline.requestedKeys()
                    + " diagnostics=" + pipeline.diagnostics());
        }

        private void failOnDiagnostics(String stage) {
            List<ChunkStreamingDiagnostic> diagnostics = pipeline.diagnostics();
            if (!diagnostics.isEmpty()) {
                throw new AssertionError(stage + " diagnostic failure: " + diagnostics
                        + " metrics=" + last.streamingMetrics());
            }
        }

        private SessionSaveCaptureResult waitCapturable() {
            long deadline = System.nanoTime() + SETTLE_DEADLINE.toNanos();
            while (System.nanoTime() < deadline) {
                last = frame();
                failOnDiagnostics("save-capture");
                SessionSaveCaptureResult capture = session.captureSave();
                if (capture.status() == SessionSaveCaptureResult.Status.CAPTURED) {
                    return capture;
                }
                java.util.concurrent.locks.LockSupport.parkNanos(1_000_000L);
            }
            throw new AssertionError("real session did not become capturable; metrics="
                    + last.streamingMetrics() + " requested=" + pipeline.requestedKeys()
                    + " diagnostics=" + pipeline.diagnostics());
        }

        private Gate15FCanonicalBlockQuery canonicalSpatialQuery() {
            int globalX = MODIFIED.worldOriginX() + 3;
            int globalY = 4;
            int globalZ = MODIFIED.worldOriginZ() + 3;
            GlobalPosition requested = new GlobalPosition(MODIFIED, 3.0, globalY, 3.0);
            SimulationOrigin committed = origin.simulationOrigin();
            Vector3f rayStart = committed.toLocal(
                    new GlobalPosition(MODIFIED, 2.25, globalY + 0.5, 3.5));
            var ray = new BlockRaycast(
                    world, BlockCollisionShapeResolver.fullCubesForNonAir())
                    .cast(committed, rayStart, new Vector3f(1, 0, 0), 8.0f);
            if (ray.status() != SpatialQueryResult.Status.AVAILABLE
                    || ray.result().isEmpty()) {
                throw new AssertionError("canonical raycast unavailable: " + ray);
            }
            var rayHit = ray.result().orElseThrow();
            GlobalPosition rayGlobal = globalBlock(
                    rayHit.blockX(), rayHit.blockY(), rayHit.blockZ());

            Vector3f sweepStart = committed.toLocal(
                    new GlobalPosition(MODIFIED, 2.125, globalY + 0.125, 3.125));
            var sweep = physicsWorld.collisionWorld().sweep(
                    committed,
                    new Aabb(0, 0, 0, 0.5f, 0.5f, 0.5f),
                    sweepStart,
                    new Vector3f(2, 0, 0));
            if (sweep.status() != SpatialQueryResult.Status.AVAILABLE
                    || sweep.result().isEmpty()) {
                throw new AssertionError("canonical collision sweep unavailable: " + sweep);
            }
            var collision = sweep.result().orElseThrow();
            GlobalPosition collisionGlobal = globalBlock(
                    collision.blockX(), collision.blockY(), collision.blockZ());
            if (!requested.equals(rayGlobal) || !requested.equals(collisionGlobal)) {
                throw new AssertionError("canonical spatial mismatch requested=" + requested
                        + " ray=" + rayGlobal + " collision=" + collisionGlobal
                        + " block=" + world.getBlock(globalX, globalY, globalZ));
            }
            return new Gate15FCanonicalBlockQuery(
                    requested, rayGlobal, collisionGlobal);
        }

        private RestartItemPreparation prepareRestartWorldItem(ChunkKey key) {
            long tick = worldItems.currentWorldTick();
            WorldItemSnapshot before = worldItems.spawn(new WorldItemSpawnRequest(
                    new ItemStack(ResourceLocation.parse("gaia:dirt"), 2),
                    key.worldOriginX() + 0.5,
                    groundItemSpawnY(key),
                    key.worldOriginZ() + 0.5,
                    0.0, 0.0, 0.0,
                    Optional.empty(), tick)).item().orElseThrow();
            physicalWorldItems.reconcileRestoredCanonicalState(tick);
            long expiresAt = worldItems.physicalSnapshot(before.id()).orElseThrow()
                    .runtime().expiresAtWorldTick();
            var hibernate = worldItems.prepareHibernate(
                    key, java.util.Map.of(before.id(), before.revision()));
            if (hibernate.status()
                    != com.overlord.worlditem.api.WorldItemHibernateResult.Status.PREPARED) {
                throw new AssertionError("real hibernate was not prepared: " + hibernate.status());
            }
            WorldItemDurableProof proof = persist(
                    pageBackend, hibernate.persistencePlan().orElseThrow());
            var committed = physicalWorldItems.commitLinkedHibernate(
                    worldItems,
                    hibernate.ticket().orElseThrow(),
                    hibernate.persistenceTicket().orElseThrow(),
                    proof);
            if (committed.status()
                    != com.overlord.worlditem.api.WorldItemHibernateResult.Status.COMMITTED) {
                throw new AssertionError("real hibernate did not commit: " + committed.status());
            }
            return new RestartItemPreparation(before, expiresAt);
        }

        private WorldItemSnapshot activateWorldItem(ChunkKey key, WorldItemId id) {
            WorldItemPageDescriptor descriptor = descriptorFor(key);
            try (WorldItemPageReadView view = pageBackend.openReadView()) {
                WorldItemActivationResult activation = worldItems.prepareActivate(view, descriptor);
                if (activation.status() != WorldItemActivationResult.Status.PREPARED) {
                    throw new AssertionError("real activation was not prepared: "
                            + activation.status());
                }
                var activated = physicalWorldItems.commitActivate(
                        worldItems, activation.ticket().orElseThrow(),
                        worldItems.currentWorldTick());
                if (activated.status() != WorldItemActivationResult.Status.COMMITTED) {
                    throw new AssertionError("real activation did not commit: "
                            + activated.status());
                }
                return worldItems.snapshot(id).orElseThrow();
            }
        }

        private WorldItemPageDescriptor descriptorFor(ChunkKey key) {
            try (WorldItemPageReadView view = pageBackend.openReadView()) {
                return view.checkpoint().pages().stream()
                        .filter(value -> value.chunkKey().equals(key))
                        .findFirst().orElseThrow();
            }
        }

        private Gate15FWorldItemLifecycleObservation completeRestartWorldItemLifecycle(
                ChunkKey key, WorldItemSnapshot before) {
            WorldItemPageDescriptor descriptor = descriptorFor(key);
            WorldItemSnapshot afterActivate = activateWorldItem(key, before.id());

            long expiresAt = worldItems.physicalSnapshot(before.id()).orElseThrow()
                    .runtime().expiresAtWorldTick();
            List<WorldItemId> expired = physicalWorldItems.deliverWorldTick(
                    worldItems, expiresAt);
            if (!expired.equals(List.of(before.id()))) {
                throw new AssertionError("exact tick expiry mismatch: " + expired);
            }
            List<WorldItemSnapshot> afterExpiry = worldItems.snapshots();

            var cleanup = worldItems.prepareCleanupPersistence().orElseThrow();
            try {
                worldItems.commitPersistence(
                        cleanup.persistenceTicket().orElseThrow(), new ForeignProof());
                throw new AssertionError("foreign cleanup proof was accepted");
            } catch (IllegalArgumentException | IllegalStateException expected) {
                // The injected storage/proof failure must leave semantic death intact.
            }
            List<WorldItemSnapshot> afterCleanupFailure = worldItems.snapshots();
            worldItems.cancelPersistence(cleanup.persistenceTicket().orElseThrow());

            AtomicInteger reads = new AtomicInteger();
            WorldItemActivationResult revisit;
            try (WorldItemPageReadView raw = pageBackend.openReadView()) {
                CountingReadView counted = new CountingReadView(raw, reads);
                revisit = worldItems.prepareActivate(counted, descriptor);
            }
            if (revisit.status() != WorldItemActivationResult.Status.EXPIRED) {
                throw new AssertionError("expired page revisit did not converge: "
                        + revisit.status());
            }
            return new Gate15FWorldItemLifecycleObservation(
                    before,
                    afterActivate,
                    afterExpiry,
                    afterCleanupFailure,
                    worldItems.snapshots(),
                    reads.get());
        }

        private double groundItemSpawnY(ChunkKey key) {
            int x = key.worldOriginX();
            int z = key.worldOriginZ();
            for (int y = world.chunks().worldHeight() - 2; y >= 0; y--) {
                if (world.getBlock(x, y, z) != 0) {
                    return y + 1.5;
                }
            }
            return 1.5;
        }

        private static GlobalPosition globalBlock(int x, int y, int z) {
            ChunkKey key = ChunkKey.fromWorld(x, z);
            return new GlobalPosition(
                    key,
                    Math.floorMod(x, GameConfig.Chunk.SIZE),
                    y,
                    Math.floorMod(z, GameConfig.Chunk.SIZE));
        }

        private AssertionError exactWaitFailure(String stage, ChunkKey key) {
            return new AssertionError(stage + " timed out for " + key
                    + "; metrics=" + last.streamingMetrics()
                    + " requested=" + pipeline.requestedKeys()
                    + " retainedWork=" + pipeline.retainedWorkCount()
                    + " diagnostics=" + pipeline.diagnostics());
        }

        private static boolean idle(ChunkStreamingMetrics metrics) {
            return metrics.loadGenerationWork().accepted() == 0
                    && metrics.meshWork().accepted() == 0
                    && metrics.saveWork().accepted() == 0;
        }
    }

    @SuppressWarnings("unchecked")
    private static Set<WorldItemId> expiryIds(LogicalWorldItemService service) {
        Object index = field(service, "expiryIndex", Object.class);
        java.util.Map<WorldItemId, ?> byId = field(index, "byId", java.util.Map.class);
        return Set.copyOf(byId.keySet());
    }

    private static int physicalDependencies(StreamedChunkStore store) {
        StreamedChunkIndex index = currentIndex(store);
        return index.globalExtension(SaveSectionId.WORLD_ITEM_CHECKPOINT)
                .flatMap(value -> value.dependency())
                .map(value -> value.referenceCount())
                .orElse(0);
    }

    private static StreamedChunkIndex currentIndex(StreamedChunkStore store) {
        return invoke(store, "readCurrentIndex", StreamedChunkIndex.class);
    }

    private static WorldItemDurableProof persist(
            StreamedWorldItemPageBackend backend,
            WorldItemPersistencePlan plan) {
        try {
            Method method = StreamedWorldItemPageBackend.class.getDeclaredMethod(
                    "persist", WorldItemPersistencePlan.class);
            method.setAccessible(true);
            return (WorldItemDurableProof) method.invoke(backend, plan);
        } catch (java.lang.reflect.InvocationTargetException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            if (cause instanceof Error error) throw error;
            throw new IllegalStateException(cause);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("could not invoke real page persistence", failure);
        }
    }

    private static final class ForeignProof implements WorldItemDurableProof {}

    private static final class CountingReadView implements WorldItemPageReadView {
        private final WorldItemPageReadView delegate;
        private final AtomicInteger reads;

        private CountingReadView(WorldItemPageReadView delegate, AtomicInteger reads) {
            this.delegate = delegate;
            this.reads = reads;
        }

        @Override public long indexSequence() { return delegate.indexSequence(); }
        @Override public String checkpointDigest() { return delegate.checkpointDigest(); }
        @Override public WorldItemPagingCheckpoint checkpoint() { return delegate.checkpoint(); }
        @Override public WorldItemPageSnapshot read(WorldItemPageDescriptor descriptor) {
            reads.incrementAndGet();
            return delegate.read(descriptor);
        }
        @Override public void close() {}
    }

    private static Object runtime(GameSession session) {
        return field(session, "runtime", Object.class);
    }

    private static <T> T field(Object target, String name, Class<T> type) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return type.cast(field.get(target));
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("missing production observation field "
                    + target.getClass().getName() + "." + name, failure);
        }
    }

    private static <T> T invoke(Object target, String name, Class<T> type) {
        try {
            Method method = target.getClass().getDeclaredMethod(name);
            method.setAccessible(true);
            return type.cast(method.invoke(target));
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("missing production observation method "
                    + target.getClass().getName() + "." + name + "()", failure);
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private record RunResult(
            Gate15FSoakObservation soak,
            Gate15FMeasurementObservation measurement) {}

    private record RestartItemPreparation(
            WorldItemSnapshot snapshot, long expiresAtWorldTick) {}

    private record Fixture(
            Path root,
            SaveGameId id,
            SaveFileOperations files,
            SaveArchiveReader reader,
            Phase14MigrationResult.PublishedMigration migration,
            SaveGameSnapshot restoreSnapshot,
            GameSessionFactory factory) {
        private static Fixture create() throws Exception {
            Path root = Files.createTempDirectory("gaia-gate15f-");
            SaveGameSnapshot snapshot = productionSnapshot();
            SaveGameId id = snapshot.metadata().saveGameId();
            Path world = Files.createDirectories(root.resolve(id.value()));
            SaveSnapshotCodec codec = new SaveSnapshotCodec(
                    new ChunkSectionCodec(), new PlayerSectionCodec(),
                    new InventorySectionCodec(), new WorldItemsSectionCodec());
            SaveArchiveReader reader = new SaveArchiveReader(codec);
            SaveArchiveWriter writer = new SaveArchiveWriter();
            Instant modified = snapshot.metadata().createdAt().plusSeconds(120);
            writer.write(world.resolve("current.glsave"), codec.encode(snapshot, modified));
            writer.write(world.resolve("backup.glsave"),
                    codec.encode(snapshot, modified.minusSeconds(60)));
            SaveFileOperations files = new JdkSaveFileOperations();
            Phase14MigrationResult result = new Phase14SaveMigrator(
                    root, reader, new StreamedChunkCodec(),
                    new StreamedChunkIndexCodec(), files).migrate(id);
            if (result.status() != Phase14MigrationResult.Status.MIGRATED
                    && result.status() != Phase14MigrationResult.Status.NOT_REQUIRED) {
                throw new AssertionError("focused RED: migration seed failed: status="
                        + result.status() + " diagnostics=" + result.diagnostics());
            }
            Phase14MigrationResult.PublishedMigration migration =
                    Phase14SaveMigrator.readPublished(root, id, reader, files).orElseThrow();
            SaveGameSnapshot restored = StreamedSessionSaveTarget.restoreSnapshot(
                    root, id, migration, files).orElseThrow();
            return new Fixture(root, id, files, reader, migration, restored,
                    productionFactory(root, reader, files));
        }

        private static GameSessionFactory productionFactory(
                Path root, SaveArchiveReader reader, SaveFileOperations files) throws Exception {
            Class<?> owner = GameSessionFactory.class;
            Object environment = constructNested(owner, "HeadlessProductionEnvironment");
            Object hooks = constructNested(owner, "ProductionInstrumentation");
            Class<?> holder = Class.forName(owner.getName() + "$ProductionTestAssetsHolder");
            Field assetsField = holder.getDeclaredField("ASSETS");
            assetsField.setAccessible(true);
            Object assets = assetsField.get(null);
            GaiaAssetCatalog catalog = invoke(assets, "catalog", GaiaAssetCatalog.class);
            Object uiAssets = invoke(assets, "uiAssets", Object.class);
            MainThreadGuard guard = MainThreadGuard.captureCurrentThread();
            InputManager input = new InputManager(guard);
            Function<SaveGameId, GameSessionFactory.StreamingBackends> backends = saveId -> {
                StreamedChunkStore store = new StreamedChunkStore(
                        root, saveId, new StreamedChunkCodec(),
                        new StreamedChunkIndexCodec(), files);
                StreamedWorldItemPageBackend pages = new StreamedWorldItemPageBackend(store);
                StreamedSessionSaveTarget target = new StreamedSessionSaveTarget(
                        root, saveId, reader, files, store, pages);
                return new GameSessionFactory.StreamingBackends(
                        store, pages, Optional.of(target));
            };
            Constructor<?> constructor = java.util.Arrays.stream(owner.getDeclaredConstructors())
                    .filter(value -> value.getParameterCount() == 7)
                    .filter(value -> value.getParameterTypes()[6] == Function.class)
                    .findFirst().orElseThrow();
            constructor.setAccessible(true);
            return (GameSessionFactory) constructor.newInstance(
                    environment, input, guard, catalog, uiAssets, hooks, backends);
        }

        private SaveGameSnapshot reopenSnapshot() {
            Phase14MigrationResult.PublishedMigration published =
                    Phase14SaveMigrator.readPublished(root, id, reader, files).orElseThrow();
            return StreamedSessionSaveTarget.restoreSnapshot(
                    root, id, published, files).orElseThrow();
        }

        private static GaiaAssetCatalog productionCatalog() {
            try {
                Class<?> owner = GameSessionFactory.class;
                Class<?> holder = Class.forName(
                        owner.getName() + "$ProductionTestAssetsHolder");
                Field assetsField = holder.getDeclaredField("ASSETS");
                assetsField.setAccessible(true);
                Object assets = assetsField.get(null);
                return invoke(assets, "catalog", GaiaAssetCatalog.class);
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException("could not open production test catalog", failure);
            }
        }

        private static Object constructNested(Class<?> owner, String simpleName) throws Exception {
            Class<?> type = Class.forName(owner.getName() + "$" + simpleName);
            Constructor<?> constructor = type.getDeclaredConstructor();
            constructor.setAccessible(true);
            return constructor.newInstance();
        }

        private static SaveGameSnapshot productionSnapshot() throws Exception {
            Class<?> test = Class.forName(
                    "com.gaia.session.ChunkStreamingSessionIntegrationTest");
            Method method = test.getDeclaredMethod("productionSnapshot");
            method.setAccessible(true);
            SaveGameSnapshot base = (SaveGameSnapshot) method.invoke(null);
            int height = GameConfig.Chunk.MAX_HEIGHT;
            List<ChunkSnapshot> chunks = new ArrayList<>(25);
            long revision = 0L;
            for (int x = -2; x <= 2; x++) {
                for (int z = -2; z <= 2; z++) {
                    chunks.add(ChunkSnapshot.of(
                            new ChunkKey(x, z), ++revision, height,
                            new byte[16 * height * 16]));
                }
            }
            SaveGameSnapshot.StaticMetadata metadata = base.metadata();
            WorldGenerationConfig generationConfig = visualConfig(
                    metadata.worldSeed(), metadata.chunkRadius());
            String generatorVersion =
                    "gaia-v" + generationConfig.algorithmVersion();
            String generatorFingerprint = sha256(
                    generationConfig.canonicalFingerprintInput()
                            .getBytes(StandardCharsets.UTF_8));
            SaveGameSnapshot snapshot = new SaveGameSnapshot(
                    new SaveGameSnapshot.StaticMetadata(
                            metadata.formatVersion(),
                            metadata.gameVersion(),
                            metadata.saveGameId(),
                            metadata.displayName(),
                            metadata.createdAt(),
                            metadata.worldSeed(),
                            generatorVersion,
                            generatorFingerprint,
                            metadata.chunkRadius(),
                            height,
                            metadata.summary()),
                    base.fixedTick(),
                    new ChunkRepositorySnapshot(
                            height,
                            revision,
                            chunks),
                    base.player(),
                    base.inventory(),
                    base.worldItems());
            if (!snapshot.metadata().generatorVersion().equals(generatorVersion)
                    || !snapshot.metadata().generatorConfigFingerprint()
                            .equals(generatorFingerprint)) {
                throw new AssertionError(
                        "Task12 fixture generator identity is not derived exactly");
            }
            return snapshot;
        }

        private static WorldGenerationConfig visualConfig(
                long seed, int chunkRadius) {
            WorldGenerationConfig template =
                    WorldGenerationConfig.visualRevisionCandidate();
            return new WorldGenerationConfig(
                    seed,
                    template.algorithmVersion(),
                    chunkRadius,
                    template.biome(),
                    template.height(),
                    template.cave(),
                    template.surface(),
                    template.decoration(),
                    template.spawn());
        }
    }
}

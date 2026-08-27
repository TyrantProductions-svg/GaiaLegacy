package com.gaia.session;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.gaia.ui.HudDebugSnapshot;
import com.gaia.ui.HudFrameCoordinator;
import com.gaia.ui.HudPresenter;
import com.gaia.ui.widget.DebugHud;
import com.gaia.save.snapshot.SaveGameSnapshot;
import com.gaia.save.snapshot.PlayerSaveSnapshot;
import com.gaia.save.format.SaveGameId;
import com.gaia.save.store.JdkSaveFileOperations;
import com.gaia.save.streaming.StreamedChunkCodec;
import com.gaia.save.streaming.StreamedChunkIndexCodec;
import com.gaia.save.streaming.StreamedChunkStore;
import com.gaia.world.streaming.ChunkStreamingPipeline;
import com.gaia.world.streaming.ChunkDesiredSets;
import com.gaia.world.streaming.ChunkStreamingDecision;
import com.gaia.world.streaming.ChunkStreamingMetrics;
import com.gaia.world.streaming.ChunkStreamingMetricsRecorder;
import com.gaia.world.streaming.ChunkStreamingPolicy;
import com.gaia.world.streaming.ChunkWorkResult;
import com.gaia.worlditem.PhysicalWorldItemSystem;
import com.gaia.worlditem.WorldItemPhysicsConfig;
import com.overlord.assets.ResourceLocation;
import com.overlord.config.GameConfig;
import com.overlord.core.input.MouseDelta;
import com.overlord.core.input.InputSnapshot;
import com.overlord.core.thread.MainThreadGuard;
import com.overlord.interaction.api.EntityRef;
import com.overlord.inventory.api.ItemStack;
import com.overlord.physics.BlockCollisionShapeResolver;
import com.overlord.physics.CollisionWorld;
import com.overlord.physics.PhysicsWorld;
import com.overlord.physics.SimulationOrigin;
import com.overlord.renderer.ChunkRenderBackend;
import com.overlord.renderer.ChunkRenderObject;
import com.overlord.voxel.ChunkKey;
import com.overlord.voxel.ChunkAvailability;
import com.overlord.voxel.ChunkDirtyTracker;
import com.overlord.voxel.ChunkRepository;
import com.overlord.voxel.ChunkRepositorySnapshot;
import com.overlord.voxel.ChunkSnapshot;
import com.overlord.voxel.ChunkGenerationData;
import com.overlord.voxel.ChunkMeshData;
import com.overlord.voxel.ChunkMeshManager;
import com.overlord.voxel.ChunkStreamingTicket;
import com.overlord.voxel.GlobalPosition;
import com.overlord.voxel.World;
import com.overlord.worlditem.LogicalWorldItemService;
import com.overlord.worlditem.api.SaveIdentity;
import com.overlord.worlditem.api.WorldItemHibernateResult;
import com.overlord.worlditem.api.WorldItemPageCachePolicy;
import com.overlord.worlditem.api.WorldItemPageDescriptor;
import com.overlord.worlditem.api.WorldItemPageSnapshot;
import com.overlord.worlditem.api.WorldItemSpawnRequest;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ChunkStreamingSessionIntegrationTest {
    private static final String RUNTIME =
            "com.gaia.session.GameSessionFactory$ProductionSessionRuntime";
    private static final String METRICS =
            "com.gaia.world.streaming.ChunkStreamingMetrics";

    @Test
    void productionRuntimeOwnsOneConstructorInjectedStreamingAuthorityGraph() {
        Class<?> runtime = requireClass(RUNTIME);
        Map<String, Integer> expected = new LinkedHashMap<>();
        expected.put("com.gaia.world.streaming.ChunkStreamingController", 1);
        expected.put("com.gaia.world.streaming.ChunkStreamingPipeline", 1);
        expected.put("com.gaia.save.streaming.StreamedChunkStore", 1);
        expected.put("com.gaia.save.streaming.StreamedWorldItemPageBackend", 1);
        expected.put("com.gaia.session.streaming.SimulationOriginCoordinator", 1);
        expected.put("com.overlord.voxel.ChunkMeshManager", 1);
        expected.put("com.gaia.world.streaming.ChunkStreamingMetricsRecorder", 1);
        expected.put("com.overlord.voxel.World", 1);
        expected.put("com.overlord.worlditem.LogicalWorldItemService", 1);

        Map<String, Long> fieldCounts = Arrays.stream(runtime.getDeclaredFields())
                .collect(java.util.stream.Collectors.groupingBy(
                        field -> field.getType().getName(),
                        LinkedHashMap::new,
                        java.util.stream.Collectors.counting()));
        Set<String> constructorTypes = Arrays.stream(runtime.getDeclaredConstructors())
                .flatMap(constructor -> Arrays.stream(constructor.getParameterTypes()))
                .map(Class::getName)
                .collect(java.util.stream.Collectors.toSet());

        assertAll(expected.entrySet().stream().map(entry -> () -> {
            assertEquals(entry.getValue().longValue(),
                    fieldCounts.getOrDefault(entry.getKey(), 0L),
                    entry.getKey() + " must have exactly one runtime owner field");
            assertTrue(constructorTypes.contains(entry.getKey()),
                    entry.getKey() + " must enter through constructor injection");
        }));
    }

    @Test
    void sessionSaveBoundaryExposesPreparedDirtyChunkCaptures() {
        assertTrue(Arrays.stream(GameSession.class.getMethods()).anyMatch(method ->
                        method.getName().equals("preparedDirtyChunks")
                                && method.getReturnType().equals(List.class)),
                "streamed Save & Quit must carry owner-prepared dirty Chunks");
        assertTrue(Arrays.stream(GameSession.class.getMethods()).anyMatch(method ->
                        method.getName().equals("commitDirtyChunkPersistence")
                                && method.getParameterCount() == 0),
                "durable save success must acknowledge prepared resident Chunks");
    }

    @Test
    void actualProductionFrameObservesAfterMutationAndCapturesMetricsLast()
            throws Exception {
        var access = GameSessionFactory.productionSessionTestAccess();
        Method trace = requireMethod(access.getClass(), "lastPlayingFrameTrace");
        GameSession session = access.factory().restore(productionSnapshot());
        try {
            driveToReady(session);
            session.advancePlaying(1.0 / 60.0, new MouseDelta(0.0, 0.0), true);

            assertEquals(List.of(
                            "fixed-step-mutation",
                            "observe-player-global-position",
                            "compute-desired-decision",
                            "apply-streaming-decision",
                            "drain-owner-publications",
                            "pump-owner-mesh-work",
                            "capture-immutable-streaming-metrics"),
                    invokeList(trace, access));
        } finally {
            session.close();
        }
    }

    @Test
    void sparseStreamedCheckpointRegeneratesSimulationNeighborhoodBeforePlayerRestore() {
        SaveGameSnapshot sparse = sparseProductionSnapshot();
        var access = GameSessionFactory.productionSessionTestAccess();

        GameSession session = access.factory().restore(sparse);
        try {
            driveToReady(session);
            assertEquals(GameSessionState.READY, session.state());
        } finally {
            session.close();
        }
    }

    @Test
    void delayedRadiusTwoChunkBlocksReadyButRadiusFourAndFiveAreNotRequired()
            throws Exception {
        var access = GameSessionFactory.productionSessionTestAccess();
        GameSession session = access.factory().restore(sparseProductionSnapshot());
        try {
            Object runtime = declaredField(session, "runtime");
            World world = (World) declaredField(runtime, "world");
            ChunkMeshManager meshes = (ChunkMeshManager) declaredField(
                    runtime, "chunkMeshes");
            @SuppressWarnings("unchecked")
            Set<ChunkKey> readiness = Set.copyOf(
                    (Set<ChunkKey>) declaredField(runtime, "meshReadiness"));
            ChunkKey delayed = new ChunkKey(2, 2);
            assertEquals(25, readiness.size(),
                    "initial readiness is exactly the radius-2 safety square");
            assertTrue(readiness.contains(delayed));
            assertEquals(25, world.chunks().keys().size(),
                    "radius-4 render and radius-5 preload are not startup barriers");
            var delayedClaim = world.chunks().claimMeshing(delayed).orElseThrow();
            Set<ChunkKey> otherReadiness = new java.util.HashSet<>(readiness);
            otherReadiness.remove(delayed);

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15L);
            while (!meshes.allRenderable(otherReadiness)
                    && System.nanoTime() < deadline) {
                session.pollLoad();
                assertEquals(GameSessionState.LOADING, session.state());
                Thread.yield();
            }

            assertTrue(meshes.allRenderable(otherReadiness));
            assertFalse(meshes.allRenderable(readiness));
            assertEquals(GameSessionState.LOADING, session.state());
            assertEquals(0, access.readyPublicationCount());
            assertThrows(IllegalStateException.class, () -> session.advancePlaying(
                    1.0 / 60.0, MouseDelta.ZERO, true));

            world.chunks().markMeshingFailure(
                    delayed,
                    delayedClaim.center().revision(),
                    new IllegalStateException("release delayed readiness fixture"));
            assertTrue(meshes.retry(delayed));
            driveToReady(session);

            assertEquals(GameSessionState.READY, session.state());
            assertEquals(1, access.readyPublicationCount());
        } finally {
            session.close();
        }
    }

    @Test
    void streamedRestartPublishesOnlyBoundedSimulationNeighborhood() {
        SaveGameSnapshot full = productionSnapshot();
        int worldHeight = GameConfig.Chunk.MAX_HEIGHT;
        List<ChunkSnapshot> historical = new ArrayList<>();
        long revision = 1L;
        for (int x = -2; x <= 2; x++) {
            for (int z = -2; z <= 2; z++) {
                historical.add(ChunkSnapshot.empty(
                        new ChunkKey(x, z), revision++, worldHeight));
            }
        }
        for (int index = 0; index < 40; index++) {
            historical.add(ChunkSnapshot.empty(
                    new ChunkKey(1_000 + index, -1_000 - index),
                    revision++,
                    worldHeight));
        }
        SaveGameSnapshot historicalSnapshot = new SaveGameSnapshot(
                new SaveGameSnapshot.StaticMetadata(
                        full.metadata().formatVersion(),
                        full.metadata().gameVersion(),
                        full.metadata().saveGameId(),
                        full.metadata().displayName(),
                        full.metadata().createdAt(),
                        full.metadata().worldSeed(),
                        full.metadata().generatorVersion(),
                        full.metadata().generatorConfigFingerprint(),
                        full.metadata().chunkRadius(),
                        worldHeight,
                        full.metadata().summary()),
                full.fixedTick(),
                new ChunkRepositorySnapshot(worldHeight, 65L, historical),
                new PlayerSaveSnapshot(
                        full.player().owner(),
                        0.5,
                        200.0,
                        0.5,
                        full.player().velocityX(),
                        full.player().velocityY(),
                        full.player().velocityZ(),
                        full.player().yaw(),
                        full.player().pitch(),
                        full.player().gameMode(),
                        full.player().noclip()),
                full.inventory(),
                full.worldItems());
        var access = GameSessionFactory.productionSessionTestAccess();

        GameSession session = access.factory().restore(historicalSnapshot);
        try {
            driveToReady(session);
            ChunkRepositorySnapshot resident = session.captureSave()
                    .snapshot().orElseThrow().chunks();
            assertEquals(25, resident.chunks().size());
            assertTrue(resident.chunks().stream().noneMatch(chunk ->
                    Math.abs(chunk.key().x()) > 2 || Math.abs(chunk.key().z()) > 2));
            assertEquals(65L, resident.revisionHighWater());
        } finally {
            session.close();
        }
    }

    @Test
    void allOriginParticipantsInitializeTogetherBeforeReadyPublication()
            throws Exception {
        var access = GameSessionFactory.productionSessionTestAccess();
        Method trace = requireMethod(access.getClass(), "originInitializationTrace");
        GameSession session = access.factory().restore(productionSnapshot());
        try {
            driveToReady(session);

            assertEquals(List.of(
                            "player",
                            "physics",
                            "camera",
                            "world-items",
                            "transient-blocks",
                            "particles",
                            "chunk-renders",
                            "publish-simulation-and-render-origin",
                            "publish-ready"),
                    invokeList(trace, access));
        } finally {
            session.close();
        }
    }

    @Test
    void frameAndHudCarryOneImmutableMetricsValueWithoutPolicyAuthority() {
        Class<?> metrics = requireClass(METRICS);
        assertTrue(metrics.isRecord(), "streaming metrics must be an immutable record value");
        Set<String> componentNames = Arrays.stream(metrics.getRecordComponents())
                .map(RecordComponent::getName)
                .collect(java.util.stream.Collectors.toSet());
        assertTrue(componentNames.contains("streamingTerrain"));
        assertTrue(componentNames.containsAll(Set.of(
                "residentChunks",
                "unloadPendingChunks",
                "loadGenerationWork",
                "meshWork",
                "saveWork",
                "canceled",
                "staleResults",
                "diagnosticCodes")),
                "metrics must expose bounded queue, lifecycle, and diagnostic observations");
        assertTrue(componentNames.containsAll(Set.of(
                        "publicationsThisFrame",
                        "uploadsThisFrame",
                        "bytesUploadedThisFrame",
                        "destructionsThisFrame",
                        "modifiedPersistedChunks",
                        "modifiedResidentChunks",
                        "loadLatencyNanos",
                        "generationLatencyNanos",
                        "meshLatencyNanos",
                        "saveLatencyNanos",
                        "restoreLatencyNanos",
                        "blockedUnknownDirections")),
                "the immutable value must expose the complete truthful bounded design");
        assertEquals(List.class, Arrays.stream(metrics.getRecordComponents())
                .filter(component -> component.getName().equals("diagnosticCodes"))
                .findFirst().orElseThrow().getType(),
                "diagnostics must be copied into one immutable list value");
        assertFalse(componentNames.stream().anyMatch(name ->
                        name.toLowerCase(java.util.Locale.ROOT).contains("percent")
                                || name.toLowerCase(java.util.Locale.ROOT).contains("progress")),
                "truthful streaming state must not expose a fabricated percentage");
        assertTrue(componentNames.contains("modifiedPersistedChunks"),
                "persisted modified count must come from the streamed store authority");
        assertTrue(componentNames.contains("modifiedResidentChunks"),
                "resident modified count must come from the repository authority");
        assertTrue(componentNames.stream().anyMatch(name ->
                        name.toLowerCase(java.util.Locale.ROOT).contains("upload")),
                "metrics must publish a truthful per-frame upload delta where available");
        assertTrue(componentNames.stream().anyMatch(name ->
                        name.toLowerCase(java.util.Locale.ROOT).contains("destruction")),
                "metrics must publish a truthful per-frame destruction delta where available");
        assertTrue(componentNames.stream().anyMatch(name ->
                        name.toLowerCase(java.util.Locale.ROOT).contains("latency")),
                "latency observations must be bounded scalar values, not a history");
        assertTrue(componentNames.stream().anyMatch(name -> {
            String lower = name.toLowerCase(java.util.Locale.ROOT);
            return lower.contains("unknown") || lower.contains("blocked");
        }), "bounded UNKNOWN blocking direction/code observations are required");

        assertEquals(1, recordComponentCount(GameSessionFrame.class, METRICS));
        assertEquals(1, recordComponentCount(HudDebugSnapshot.class, METRICS));

        Set<String> forbiddenPrefixes = Set.of(
                "java.nio.file.",
                "java.util.concurrent.",
                "com.gaia.world.streaming.ChunkStreamingController",
                "com.gaia.world.streaming.ChunkStreamingPipeline",
                "com.gaia.save.streaming.StreamedChunkStore");
        for (Class<?> hudType : List.of(
                HudDebugSnapshot.class,
                HudFrameCoordinator.class,
                HudPresenter.class,
                DebugHud.class)) {
            for (Field field : hudType.getDeclaredFields()) {
                assertFalse(forbiddenPrefixes.stream().anyMatch(
                                prefix -> field.getType().getName().startsWith(prefix)),
                        hudType.getSimpleName() + " must remain a read-only metrics consumer");
            }
            for (Method method : hudType.getDeclaredMethods()) {
                assertFalse(method.getName().toLowerCase(java.util.Locale.ROOT)
                                .contains("retry"),
                        hudType.getSimpleName() + " must not own retry policy");
                assertFalse(forbiddenPrefixes.stream().anyMatch(prefix ->
                                method.getReturnType().getName().startsWith(prefix)
                                        || Arrays.stream(method.getParameterTypes())
                                                .anyMatch(type -> type.getName()
                                                        .startsWith(prefix))),
                        hudType.getSimpleName()
                                + " methods must not invoke streaming authorities");
            }
        }

        Method retry = Arrays.stream(GameSession.class.getMethods())
                .filter(method -> method.getName().equals("retryChunkStreaming"))
                .findFirst()
                .orElseGet(() -> fail(
                        "explicit streaming retry must be an owner/session action"));
        assertEquals(boolean.class, retry.getReturnType());
        assertEquals(List.of(ChunkKey.class), List.of(retry.getParameterTypes()));
    }

    @Test
    void explicitOwnerRetryReportsMeshOnlyFailureCleared() {
        var access = GameSessionFactory.productionSessionTestAccess();
        GameSession session = access.factory().restore(productionSnapshot());
        ChunkKey key = new ChunkKey(0, 0);
        try {
            driveToReady(session);
            access.injectRetryableMeshFailure(session, key);

            assertTrue(session.retryChunkStreaming(key));
            assertFalse(session.retryChunkStreaming(key));
        } finally {
            session.close();
        }
    }

    @Test
    void productionUnloadPrepareCancelsHibernateWhenLaterCaptureFails() {
        MainThreadGuard guard = MainThreadGuard.captureCurrentThread();
        ChunkKey key = new ChunkKey(1, -1);
        SaveGameSnapshot baseCapture = productionSnapshot();
        LogicalWorldItemService logical = pagedLogicalService(
                guard, new SaveIdentity(UUID.fromString(
                        baseCapture.metadata().saveGameId().value())));
        var item = logical.spawn(new WorldItemSpawnRequest(
                new ItemStack(ResourceLocation.parse("gaia:dirt"), 1),
                key.worldOriginX() + 0.5,
                2.0,
                key.worldOriginZ() + 0.5,
                0.0,
                0.0,
                0.0,
                java.util.Optional.empty(),
                0L)).item().orElseThrow();
        World physicsWorld = new World();
        PhysicalWorldItemSystem physical = new PhysicalWorldItemSystem(
                logical,
                new PhysicsWorld(
                        new CollisionWorld(
                                physicsWorld,
                                BlockCollisionShapeResolver.fullCubesForNonAir()),
                        new Vector3f()),
                guard,
                new WorldItemPhysicsConfig(0.50f, 2));
        ChunkRepository repository = new ChunkRepository(
                8, new ChunkDirtyTracker());
        repository.generate(key, ignored -> {});
        var repositoryPreparation = repository.prepareStreamingUnload(key);
        RuntimeException injected = new RuntimeException("capture failed after prepare");
        ChunkStreamingPipeline.UnloadLifecycle lifecycle = productionUnloadLifecycle(
                productionSnapshot().metadata(),
                logical,
                physical,
                () -> { throw injected; });

        RuntimeException thrown = assertThrows(
                RuntimeException.class,
                () -> lifecycle.prepare(repositoryPreparation));

        assertSame(injected, thrown);
        WorldItemHibernateResult retry = logical.prepareHibernate(
                key, Map.of(item.id(), item.revision()));
        assertEquals(WorldItemHibernateResult.Status.PREPARED, retry.status(),
                "the exact linked preparation must remain retryable");
        logical.cancelHibernate(retry.ticket().orElseThrow());
        repository.cancelStreamingUnload(
                repositoryPreparation.ticket().orElseThrow());
        physical.close();
        logical.close();
    }

    @Test
    void productionUnloadCapturesSessionStateBeforePreparingWorldItems() {
        MainThreadGuard guard = MainThreadGuard.captureCurrentThread();
        ChunkKey key = new ChunkKey(3, -1);
        SaveGameSnapshot baseCapture = productionSnapshot();
        LogicalWorldItemService logical = pagedLogicalService(
                guard, new SaveIdentity(UUID.fromString(
                        baseCapture.metadata().saveGameId().value())));
        var item = logical.spawn(new WorldItemSpawnRequest(
                new ItemStack(ResourceLocation.parse("gaia:dirt"), 1),
                key.worldOriginX() + 0.5, 2.0, key.worldOriginZ() + 0.5,
                0.0, 0.0, 0.0, Optional.empty(), 0L)).item().orElseThrow();
        PhysicalWorldItemSystem physical = new PhysicalWorldItemSystem(
                logical,
                new PhysicsWorld(new CollisionWorld(
                        new World(), BlockCollisionShapeResolver.fullCubesForNonAir()),
                        new Vector3f()),
                guard, new WorldItemPhysicsConfig(0.50f, 2));
        ChunkRepository repository = new ChunkRepository(8, new ChunkDirtyTracker());
        repository.generate(key, ignored -> {});
        var repositoryPreparation = repository.prepareStreamingUnload(key);
        AtomicBoolean capturedBeforePendingTransaction = new AtomicBoolean();
        ChunkStreamingPipeline.UnloadLifecycle lifecycle = productionUnloadLifecycle(
                baseCapture.metadata(), logical, physical, () -> {
                    logical.canonicalSnapshot();
                    capturedBeforePendingTransaction.set(true);
                    return baseCapture;
                });

        ChunkStreamingPipeline.PreparedUnload prepared =
                lifecycle.prepare(repositoryPreparation);

        assertTrue(capturedBeforePendingTransaction.get());
        lifecycle.cancel(prepared);
        repository.cancelStreamingUnload(
                repositoryPreparation.ticket().orElseThrow());
        assertEquals(item.id(), logical.snapshot(item.id()).orElseThrow().id());
        physical.close();
        logical.close();
    }

    @Test
    void productionUnloadCancelRetainsLinkedMappingUntilHibernateCancelSucceeds()
            throws Exception {
        MainThreadGuard guard = MainThreadGuard.captureCurrentThread();
        ChunkKey key = new ChunkKey(2, -1);
        SaveGameSnapshot baseCapture = productionSnapshot();
        LogicalWorldItemService logical = pagedLogicalService(
                guard, new SaveIdentity(UUID.fromString(
                        baseCapture.metadata().saveGameId().value())));
        var item = logical.spawn(new WorldItemSpawnRequest(
                new ItemStack(ResourceLocation.parse("gaia:dirt"), 1),
                key.worldOriginX() + 0.5, 2.0, key.worldOriginZ() + 0.5,
                0.0, 0.0, 0.0, Optional.empty(), 0L)).item().orElseThrow();
        PhysicalWorldItemSystem physical = new PhysicalWorldItemSystem(
                logical,
                new PhysicsWorld(new CollisionWorld(
                        new World(), BlockCollisionShapeResolver.fullCubesForNonAir()),
                        new Vector3f()),
                guard, new WorldItemPhysicsConfig(0.50f, 2));
        ChunkRepository repository = new ChunkRepository(8, new ChunkDirtyTracker());
        repository.generate(key, ignored -> {});
        var repositoryPreparation = repository.prepareStreamingUnload(key);
        SaveGameSnapshot exactCapture = new SaveGameSnapshot(
                baseCapture.metadata(), 0L, baseCapture.chunks(),
                baseCapture.player(), baseCapture.inventory(), baseCapture.worldItems());
        ChunkStreamingPipeline.UnloadLifecycle lifecycle = productionUnloadLifecycle(
                baseCapture.metadata(), logical, physical, () -> exactCapture);
        ChunkStreamingPipeline.PreparedUnload prepared =
                lifecycle.prepare(repositoryPreparation);
        java.util.concurrent.atomic.AtomicReference<Throwable> foreignFailure =
                new java.util.concurrent.atomic.AtomicReference<>();
        Thread foreign = new Thread(() -> {
            try {
                lifecycle.cancel(prepared);
            } catch (Throwable failure) {
                foreignFailure.set(failure);
            }
        }, "task11-foreign-unload-cancel");
        foreign.start();
        foreign.join(5_000L);
        assertTrue(foreignFailure.get() instanceof IllegalStateException);

        lifecycle.cancel(prepared);

        WorldItemHibernateResult retry = logical.prepareHibernate(
                key, Map.of(item.id(), item.revision()));
        assertEquals(WorldItemHibernateResult.Status.PREPARED, retry.status(),
                "failed cancel must retain the H/P mapping for exact owner retry");
        logical.cancelHibernate(retry.ticket().orElseThrow());
        repository.cancelStreamingUnload(
                repositoryPreparation.ticket().orElseThrow());
        physical.close();
        logical.close();
    }

    @Test
    void recorderCopiesNonzeroBoundedWorkAndDiagnostics() throws Exception {
        MainThreadGuard guard = MainThreadGuard.captureCurrentThread();
        ChunkKey activeKey = new ChunkKey(30, 0);
        ChunkKey diagnosticKey = new ChunkKey(31, 0);
        CountDownLatch activeStarted = new CountDownLatch(1);
        CountDownLatch releaseActive = new CountDownLatch(1);
        ChunkRepository streamingRepository = new ChunkRepository(
                1, new ChunkDirtyTracker());
        ChunkStreamingPipeline pipeline = new ChunkStreamingPipeline(
                streamingRepository,
                ChunkStreamingPolicy.productionDefaults(),
                guard,
                work -> {
                    if (work.key().equals(diagnosticKey)) {
                        throw new IllegalStateException("injected diagnostic");
                    }
                    activeStarted.countDown();
                    assertTrue(releaseActive.await(5, TimeUnit.SECONDS));
                    return ChunkWorkResult.loadSuccess(
                            work.workId(), work.key(), work.desiredEpoch(),
                            work.expectedRevision(),
                            ChunkStreamingTicket.SourcePreference.GENERATE,
                            new ChunkGenerationData(
                                    work.key(), 1, new byte[16 * 16]));
                },
                work -> { throw new AssertionError("save work was not expected"); },
                new NoUnloadLifecycle());
        ChunkDesiredSets desired = new ChunkDesiredSets(
                Set.of(activeKey, diagnosticKey),
                Set.of(activeKey, diagnosticKey),
                Set.of(activeKey, diagnosticKey));
        ChunkStreamingDecision decision = new ChunkStreamingDecision(
                desired,
                1L,
                List.of(activeKey, diagnosticKey),
                List.of(),
                List.of(),
                List.of());
        pipeline.apply(decision);
        assertTrue(activeStarted.await(5, TimeUnit.SECONDS));
        for (int spin = 0;
                spin < 100_000 && pipeline.loadWorkMetrics().completed() == 0;
                spin++) {
            Thread.yield();
        }
        pipeline.drainOwnerResults();

        ChunkRepository meshRepository = new ChunkRepository(
                1, new ChunkDirtyTracker());
        ChunkKey meshKey = new ChunkKey(0, 0);
        meshRepository.generate(meshKey, ignored -> {});
        List<Runnable> meshQueue = new ArrayList<>();
        Executor manualMeshExecutor = meshQueue::add;
        ChunkMeshManager meshes = new ChunkMeshManager(
                meshRepository,
                input -> new ChunkMeshData(
                        input.center().key(),
                        input.center().revision(),
                        new float[0]),
                manualMeshExecutor,
                new ChunkRenderBackend() {
                    @Override
                    public ChunkRenderObject upload(ChunkMeshData data) {
                        throw new AssertionError("upload was not expected");
                    }

                    @Override
                    public void release(ChunkRenderObject object) {
                        throw new AssertionError("release was not expected");
                    }
                },
                guard,
                1);
        assertEquals(1, meshes.scheduleEligible());
        LogicalWorldItemService worldItems = new LogicalWorldItemService(
                guard, 4, 0L);

        ChunkStreamingMetrics metrics = new ChunkStreamingMetricsRecorder().capture(
                new GlobalPosition(new ChunkKey(0, 0), 0.0, 2.0, 0.0),
                new SimulationOrigin(new ChunkKey(0, 0)),
                decision,
                0,
                pipeline,
                meshes,
                worldItems);

        assertTrue(metrics.loadGenerationWork().accepted() > 0);
        assertTrue(metrics.meshWork().accepted() > 0);
        assertEquals(List.of("chunk-streaming.worker-failure"),
                metrics.diagnosticCodes());
        assertTrue(pipeline.retry(diagnosticKey));
        assertEquals(List.of("chunk-streaming.worker-failure"),
                metrics.diagnosticCodes(),
                "captured diagnostics must not alias the live bounded map");
        assertThrows(UnsupportedOperationException.class,
                () -> metrics.diagnosticCodes().add("mutable"));

        releaseActive.countDown();
        pipeline.awaitWorkers(java.time.Duration.ofSeconds(5));
        pipeline.close();
        meshes.close();
        worldItems.close();
    }

    @Test
    void residentSimulationChunkWithoutRenderableMeshIsReportedAsCurrentGap() {
        MainThreadGuard guard = MainThreadGuard.captureCurrentThread();
        ChunkKey key = new ChunkKey(0, 0);
        ChunkRepository repository = new ChunkRepository(
                1, new ChunkDirtyTracker());
        repository.generate(key, ignored -> {});
        ChunkStreamingPipeline pipeline = new ChunkStreamingPipeline(
                repository,
                ChunkStreamingPolicy.productionDefaults(),
                guard,
                work -> { throw new AssertionError("load was not expected"); },
                work -> { throw new AssertionError("save was not expected"); },
                new NoUnloadLifecycle());
        ChunkMeshManager meshes = idleMeshes(repository, guard);
        LogicalWorldItemService worldItems = new LogicalWorldItemService(
                guard, 4, 0L);
        ChunkStreamingDecision decision = new ChunkStreamingDecision(
                new ChunkDesiredSets(Set.of(key), Set.of(key), Set.of(key)),
                1L,
                List.of(key),
                List.of(),
                List.of(),
                List.of(),
                List.of());
        try {
            ChunkStreamingMetrics metrics = new ChunkStreamingMetricsRecorder().capture(
                    new GlobalPosition(key, 0.0, 2.0, 0.0),
                    new SimulationOrigin(key),
                    decision,
                    1,
                    pipeline,
                    meshes,
                    worldItems);

            assertEquals(1, metrics.gaps().size());
            assertEquals(
                    com.gaia.world.streaming.ChunkGapObservation.DesiredClass.SIMULATION,
                    metrics.gaps().get(0).desiredClass());
            assertTrue(metrics.gaps().get(0).resident());
            assertFalse(metrics.gaps().get(0).renderObjectInstalled());
        } finally {
            pipeline.close();
            meshes.close();
            worldItems.close();
        }
    }

    @Test
    void recorderCountsActualAdmittedUnloadInsteadOfDecisionCandidates()
            throws Exception {
        MainThreadGuard guard = MainThreadGuard.captureCurrentThread();
        ChunkKey admitted = new ChunkKey(40, 0);
        ChunkKey candidateWithoutResidentAuthority = new ChunkKey(41, 0);
        ChunkRepository repository = new ChunkRepository(
                1, new ChunkDirtyTracker());
        repository.generate(admitted, ignored -> {});
        CountDownLatch saveStarted = new CountDownLatch(1);
        CountDownLatch releaseSave = new CountDownLatch(1);
        ChunkStreamingPipeline pipeline = new ChunkStreamingPipeline(
                repository,
                ChunkStreamingPolicy.productionDefaults(),
                guard,
                work -> { throw new AssertionError("load was not expected"); },
                work -> {
                    saveStarted.countDown();
                    assertTrue(releaseSave.await(5, TimeUnit.SECONDS));
                    return ChunkWorkResult.saveSuccess(
                            work.workId(), work.key(), work.desiredEpoch(),
                            work.expectedRevision(),
                            com.gaia.save.streaming.StreamedChunkUnloadResult
                                    .success(java.util.Optional.empty()));
                },
                new TestUnloadLifecycle());
        ChunkStreamingDecision decision = new ChunkStreamingDecision(
                new ChunkDesiredSets(Set.of(), Set.of(), Set.of()),
                1L, List.of(), List.of(), List.of(),
                List.of(admitted, candidateWithoutResidentAuthority));
        pipeline.apply(decision);
        assertTrue(saveStarted.await(5, TimeUnit.SECONDS));
        ChunkMeshManager meshes = idleMeshes(repository, guard);
        LogicalWorldItemService worldItems = new LogicalWorldItemService(
                guard, 4, 0L);
        try {
            ChunkStreamingMetrics metrics = new ChunkStreamingMetricsRecorder().capture(
                    new GlobalPosition(new ChunkKey(0, 0), 0.0, 2.0, 0.0),
                    new SimulationOrigin(new ChunkKey(0, 0)),
                    decision,
                    repository.keys().size(),
                    pipeline,
                    meshes,
                    worldItems);

            assertEquals(1, metrics.unloadPendingChunks(),
                    "candidate count is not actual admitted/pending unload work");
            assertEquals(1, metrics.saveWork().accepted());
        } finally {
            releaseSave.countDown();
            pipeline.awaitWorkers(java.time.Duration.ofSeconds(5));
            pipeline.close();
            meshes.close();
            worldItems.close();
        }
    }

    @Test
    void productionMetricsPublishNonzeroFrameDeltasResidentModificationAndScalarLatencies()
            throws Exception {
        var access = GameSessionFactory.productionSessionTestAccess();
        GameSession session = access.factory().restore(productionSnapshot());
        try {
            driveToReady(session);
            Object runtime = declaredField(session, "runtime");
            com.overlord.voxel.World world = (com.overlord.voxel.World)
                    declaredField(runtime, "world");
            assertTrue(world.setBlock(0, 0, 0, (byte) 1));
            com.overlord.physics.PlayerController player =
                    (com.overlord.physics.PlayerController)
                            declaredField(runtime, "playerController");
            player.body().teleport(new Vector3f(2 * 16 + 2.25f, 2.0f, 2.5f));
            ChunkStreamingMetrics initialMetrics = session.advancePlaying(
                    0.0, new MouseDelta(0.0, 0.0), true).streamingMetrics();
            ((ChunkStreamingPipeline) declaredField(runtime, "streamingPipeline"))
                    .awaitWorkers(java.time.Duration.ofSeconds(5));

            long publications = metricLong(initialMetrics, "publicationsThisFrame");
            long uploads = metricLong(initialMetrics, "uploadsThisFrame");
            long modifiedResident = metricLong(initialMetrics, "modifiedResidentChunks");
            long observedLatency = 0L;
            for (String latency : List.of(
                    "loadLatencyNanos", "generationLatencyNanos",
                    "meshLatencyNanos", "saveLatencyNanos",
                    "restoreLatencyNanos")) {
                observedLatency = Math.max(
                        observedLatency, metricLong(initialMetrics, latency));
            }
            for (int frame = 0; frame < 4_096; frame++) {
                ChunkStreamingMetrics metrics = session.advancePlaying(
                        0.0, new MouseDelta(0.0, 0.0), true).streamingMetrics();
                publications = Math.max(publications,
                        metricLong(metrics, "publicationsThisFrame"));
                uploads = Math.max(uploads,
                        metricLong(metrics, "uploadsThisFrame"));
                modifiedResident = Math.max(modifiedResident,
                        metricLong(metrics, "modifiedResidentChunks"));
                for (String latency : List.of(
                        "loadLatencyNanos", "generationLatencyNanos",
                        "meshLatencyNanos", "saveLatencyNanos",
                        "restoreLatencyNanos")) {
                    observedLatency = Math.max(
                            observedLatency, metricLong(metrics, latency));
                }
                if (publications > 0L && uploads > 0L
                        && modifiedResident > 0L
                        && observedLatency > 0L) {
                    break;
                }
                Thread.yield();
            }

            assertTrue(publications > 0L);
            assertTrue(uploads > 0L);
            assertTrue(modifiedResident > 0L);
            assertTrue(observedLatency > 0L,
                    "latency observations must be bounded scalars from real work");
        } finally {
            session.close();
        }
    }

    @Test
    void productionSaveBarrierPinsExactDirtyResidentChunkCapture()
            throws Exception {
        var access = GameSessionFactory.productionSessionTestAccess();
        GameSession session = access.factory().restore(productionSnapshot());
        boolean prepared = false;
        try {
            driveToReady(session);
            Object runtime = declaredField(session, "runtime");
            com.overlord.voxel.World world = (com.overlord.voxel.World)
                    declaredField(runtime, "world");
            assertTrue(world.setBlock(0, 0, 0, (byte) 91));

            session.prepareSaveCapture();
            prepared = true;

            List<com.gaia.save.session.SaveCoordinator.PreparedDirtyChunkCapture>
                    dirty = session.preparedDirtyChunks();
            assertEquals(1, dirty.size());
            assertEquals(new ChunkKey(0, 0), dirty.get(0).snapshot().key());
            assertEquals((byte) 91,
                    dirty.get(0).snapshot().getBlock(0, 0, 0));
            assertTrue(dirty.get(0).stillCurrent().getAsBoolean());
            assertThrows(UnsupportedOperationException.class,
                    () -> dirty.add(dirty.get(0)));
        } finally {
            if (prepared) {
                session.finishSaveCapture();
            }
            session.close();
        }
    }

    @Test
    void preparedDirtyChunkFreshnessInvalidatesOnMutation()
            throws Exception {
        var access = GameSessionFactory.productionSessionTestAccess();
        GameSession session = access.factory().restore(productionSnapshot());
        boolean prepared = false;
        try {
            driveToReady(session);
            Object runtime = declaredField(session, "runtime");
            World world = (World) declaredField(runtime, "world");
            assertTrue(world.setBlock(0, 0, 0, (byte) 91));

            session.prepareSaveCapture();
            prepared = true;
            List<com.gaia.save.session.SaveCoordinator.PreparedDirtyChunkCapture>
                    staleDirty = session.preparedDirtyChunks();
            assertEquals(1, staleDirty.size());
            assertTrue(staleDirty.get(0).stillCurrent().getAsBoolean());
            assertTrue(world.setBlock(0, 0, 0, (byte) 92),
                    "mutation after prepare must invalidate the exact ticket");
            assertFalse(staleDirty.get(0).stillCurrent().getAsBoolean());
        } finally {
            if (prepared) {
                session.finishSaveCapture();
            }
            session.close();
        }
    }

    @Test
    void metricsCaptureDoesNotAcquireTheStreamedStoreTransactionMonitor(
            @TempDir Path root) throws Exception {
        MainThreadGuard guard = MainThreadGuard.captureCurrentThread();
        ChunkRepository repository = new ChunkRepository(1, new ChunkDirtyTracker());
        ChunkStreamingPipeline pipeline = new ChunkStreamingPipeline(
                repository, ChunkStreamingPolicy.productionDefaults(), guard,
                work -> { throw new AssertionError("no load expected"); },
                work -> { throw new AssertionError("no save expected"); },
                new NoUnloadLifecycle());
        ChunkMeshManager meshes = idleMeshes(repository, guard);
        LogicalWorldItemService worldItems = new LogicalWorldItemService(guard, 4, 0L);
        StreamedChunkStore store = new StreamedChunkStore(
                root,
                SaveGameId.parse("123e4567-e89b-12d3-a456-426614174111"),
                new StreamedChunkCodec(),
                new StreamedChunkIndexCodec(),
                new JdkSaveFileOperations());
        CountDownLatch monitorHeld = new CountDownLatch(1);
        Thread holder = new Thread(() -> {
            synchronized (store) {
                monitorHeld.countDown();
                try {
                    Thread.sleep(1_000L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }, "task11-store-monitor-holder");
        holder.start();
        assertTrue(monitorHeld.await(5, TimeUnit.SECONDS));
        ChunkStreamingDecision idle = new ChunkStreamingDecision(
                new ChunkDesiredSets(Set.of(), Set.of(), Set.of()),
                1L, List.of(), List.of(), List.of(), List.of());
        ChunkStreamingMetricsRecorder recorder = new ChunkStreamingMetricsRecorder();
        long started = System.nanoTime();
        long elapsedNanos;
        try {
            recorder.capture(
                    new GlobalPosition(new ChunkKey(0, 0), 0.0, 2.0, 0.0),
                    new SimulationOrigin(new ChunkKey(0, 0)),
                    idle, 0, pipeline, meshes, worldItems, store);
            Field cachedCount = StreamedChunkStore.class.getDeclaredField(
                    "lastValidatedModifiedChunkCount");
            cachedCount.setAccessible(true);
            cachedCount.setInt(store, 3);
            ChunkStreamingMetrics refreshed = recorder.capture(
                    new GlobalPosition(new ChunkKey(0, 0), 0.0, 2.0, 0.0),
                    new SimulationOrigin(new ChunkKey(0, 0)),
                    idle, 0, pipeline, meshes, worldItems, store);
            assertEquals(3L, refreshed.modifiedPersistedChunks(),
                    "session-save publication must refresh the lock-free store scalar "
                            + "even when pipeline unload totals did not change");
            elapsedNanos = System.nanoTime() - started;
        } finally {
            holder.join(5_000L);
            pipeline.close();
            meshes.close();
            worldItems.close();
        }
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(elapsedNanos);
        assertTrue(elapsedMillis < 250L,
                "metrics must read a lock-free last-validated store scalar, not transaction IO");
    }

    @Test
    void productionUnknownCollisionPublishesCanonicalBoundedBlockedObservation()
            throws Exception {
        var access = GameSessionFactory.productionSessionTestAccess();
        GameSession session = access.factory().restore(productionSnapshot());
        try {
            driveToReady(session);
            Object runtime = declaredField(session, "runtime");
            World world = (World) declaredField(runtime, "world");
            ChunkKey boundary = world.chunks().keys().stream()
                    .min(java.util.Comparator.comparingInt(ChunkKey::z))
                    .orElseThrow();
            ChunkKey unavailable = boundary.north();
            assertEquals(ChunkAvailability.UNKNOWN,
                    world.chunks().availability(unavailable));
            com.overlord.physics.PlayerController player =
                    (com.overlord.physics.PlayerController)
                            declaredField(runtime, "playerController");
            player.body().teleport(new Vector3f(
                    boundary.worldOriginX() + 8.0f,
                    2.0f,
                    boundary.worldOriginZ() + 0.05f));
            player.body().setLinearVelocity(new Vector3f(0.0f, 0.0f, -20.0f));
            Method fixed = runtime.getClass().getDeclaredMethod(
                    "runFixedStep", InputSnapshot.class);
            fixed.setAccessible(true);
            for (int step = 0; step < 8; step++) {
                try {
                    fixed.invoke(runtime, new InputSnapshot(
                            Set.of(GameConfig.Input.KEY_FORWARD), Set.of()));
                } catch (InvocationTargetException blockedRaycast) {
                    assertTrue(blockedRaycast.getCause() instanceof IllegalStateException);
                    break;
                }
            }

            Method streamingFrame = runtime.getClass().getDeclaredMethod(
                    "advanceStreamingFrame");
            streamingFrame.setAccessible(true);
            streamingFrame.invoke(runtime);
            ChunkStreamingMetrics metrics = (ChunkStreamingMetrics)
                    declaredField(runtime, "streamingMetrics");
            assertEquals(1, metrics.blockedUnknownDirections().size(),
                    "the frame must publish one bounded canonical UNKNOWN block");
            Object observation = metrics.blockedUnknownDirections().get(0);
            assertEquals(ChunkAvailability.UNKNOWN,
                    observation.getClass().getMethod("availability").invoke(observation));
            assertEquals(unavailable,
                    observation.getClass().getMethod("key").invoke(observation));
            assertEquals("NORTH", observation.getClass().getMethod("direction")
                    .invoke(observation).toString());
            assertThrows(UnsupportedOperationException.class,
                    () -> metrics.blockedUnknownDirections().clear());
        } finally {
            session.close();
        }
    }

    private static long metricLong(ChunkStreamingMetrics metrics, String component)
            throws Exception {
        Method method;
        try {
            method = metrics.getClass().getDeclaredMethod(component);
        } catch (NoSuchMethodException missing) {
            return fail("Missing Task 11 metrics observation: " + component);
        }
        Object value = method.invoke(metrics);
        assertTrue(value instanceof Number,
                component + " must be a bounded scalar number");
        long observed = ((Number) value).longValue();
        assertTrue(observed >= 0L, component + " must be non-negative");
        return observed;
    }

    private static Object declaredField(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static ChunkMeshManager idleMeshes(
            ChunkRepository repository, MainThreadGuard guard) {
        return new ChunkMeshManager(
                repository,
                input -> new ChunkMeshData(
                        input.center().key(), input.center().revision(), new float[0]),
                Runnable::run,
                new ChunkRenderBackend() {
                    @Override
                    public ChunkRenderObject upload(ChunkMeshData data) {
                        throw new AssertionError("idle metrics mesh must not upload");
                    }

                    @Override
                    public void release(ChunkRenderObject object) {}
                },
                guard,
                1);
    }

    private static final class TestUnloadLifecycle
            implements ChunkStreamingPipeline.UnloadLifecycle {
        @Override
        public ChunkStreamingPipeline.PreparedUnload prepare(
                com.overlord.voxel.ChunkUnloadPreparation preparation) {
            var capture = preparation.capture().orElseThrow();
            var payload = new com.gaia.save.streaming.StreamedChunkPayload(
                    GameSessionSaveLifecycleTest.ID,
                    capture.key(), "task11-metrics", "77".repeat(32),
                    capture.revision(), 0L, true, true,
                    capture.worldHeight(), capture.copyBlocks(), List.of());
            return new ChunkStreamingPipeline.PreparedUnload(
                    new com.gaia.save.streaming.StreamedChunkUnloadPlan(
                            new com.gaia.save.streaming.StreamedChunkStore.ExactChunkCapture(
                                    payload, preparation.stillCurrent()),
                            java.util.Optional.empty(), List.of()),
                    capture.revision());
        }

        @Override
        public boolean commit(
                ChunkStreamingPipeline.PreparedUnload prepared,
                com.gaia.save.streaming.StreamedChunkUnloadResult durability) {
            return true;
        }

        @Override
        public void cancel(ChunkStreamingPipeline.PreparedUnload prepared) {}
    }

    private static long recordComponentCount(Class<?> type, String componentType) {
        return Arrays.stream(type.getRecordComponents())
                .filter(component -> component.getType().getName().equals(componentType))
                .count();
    }

    static LogicalWorldItemService pagedLogicalService(
            MainThreadGuard guard) {
        return pagedLogicalService(
                guard,
                new SaveIdentity(UUID.fromString(
                        "123e4567-e89b-12d3-a456-426614174211")));
    }

    static LogicalWorldItemService pagedLogicalService(
            MainThreadGuard guard, SaveIdentity identity) {
        return new LogicalWorldItemService(
                guard,
                8,
                0L,
                identity,
                new WorldItemPageCachePolicy(
                        1_024, 32, 16L * 1_024L * 1_024L,
                        64, 1_024, 16L * 1_024L * 1_024L,
                        64, 64L * 1_024L),
                (ticket, plan, proof) -> {},
                ChunkStreamingSessionIntegrationTest::descriptor);
    }

    private static WorldItemPageDescriptor descriptor(
            WorldItemPageSnapshot page) {
        return new WorldItemPageDescriptor(
                page.chunkKey(),
                page.pageRevision(),
                "22".repeat(32),
                page.entries().size(),
                page.entries().size());
    }

    private static ChunkStreamingPipeline.UnloadLifecycle productionUnloadLifecycle(
            SaveGameSnapshot.StaticMetadata metadata,
            LogicalWorldItemService logical,
            PhysicalWorldItemSystem physical,
            Supplier<SaveGameSnapshot> capture) {
        try {
            Class<?> type = requireClass(
                    "com.gaia.session.GameSessionFactory$ProductionUnloadLifecycle");
            Constructor<?> constructor = type.getDeclaredConstructor(
                    SaveGameSnapshot.StaticMetadata.class,
                    LogicalWorldItemService.class,
                    PhysicalWorldItemSystem.class,
                    GameSessionFactory.UnloadSessionCapture.class);
            constructor.setAccessible(true);
            GameSessionFactory.UnloadSessionCapture sessionCapture = () -> {
                SaveGameSnapshot snapshot = capture.get();
                return new GameSessionFactory.UnloadSessionState(
                        snapshot.player(), snapshot.inventory());
            };
            return (ChunkStreamingPipeline.UnloadLifecycle) constructor.newInstance(
                    metadata, logical, physical, sessionCapture);
        } catch (InvocationTargetException invoked) {
            Throwable cause = invoked.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new AssertionError(cause);
        } catch (ReflectiveOperationException reflectionFailure) {
            throw new AssertionError(reflectionFailure);
        }
    }

    private static final class NoUnloadLifecycle
            implements ChunkStreamingPipeline.UnloadLifecycle {
        @Override
        public ChunkStreamingPipeline.PreparedUnload prepare(
                com.overlord.voxel.ChunkUnloadPreparation preparation) {
            throw new AssertionError("unload preparation was not expected");
        }

        @Override
        public boolean commit(
                ChunkStreamingPipeline.PreparedUnload prepared,
                com.gaia.save.streaming.StreamedChunkUnloadResult durability) {
            throw new AssertionError("unload commit was not expected");
        }

        @Override
        public void cancel(ChunkStreamingPipeline.PreparedUnload prepared) {
            throw new AssertionError("unload cancellation was not expected");
        }
    }

    private static SaveGameSnapshot sparseProductionSnapshot() {
        SaveGameSnapshot full = productionSnapshot();
        int worldHeight = GameConfig.Chunk.MAX_HEIGHT;
        return new SaveGameSnapshot(
                new SaveGameSnapshot.StaticMetadata(
                        full.metadata().formatVersion(),
                        full.metadata().gameVersion(),
                        full.metadata().saveGameId(),
                        full.metadata().displayName(),
                        full.metadata().createdAt(),
                        full.metadata().worldSeed(),
                        full.metadata().generatorVersion(),
                        full.metadata().generatorConfigFingerprint(),
                        full.metadata().chunkRadius(),
                        worldHeight,
                        full.metadata().summary()),
                full.fixedTick(),
                new ChunkRepositorySnapshot(
                        worldHeight,
                        full.chunks().revisionHighWater(),
                        List.of()),
                new PlayerSaveSnapshot(
                        full.player().owner(),
                        full.player().feetPositionX(),
                        200.0,
                        full.player().feetPositionZ(),
                        full.player().velocityX(),
                        full.player().velocityY(),
                        full.player().velocityZ(),
                        full.player().yaw(),
                        full.player().pitch(),
                        full.player().gameMode(),
                        full.player().noclip()),
                full.inventory(),
                full.worldItems());
    }

    static SaveGameSnapshot productionSnapshot() {
        SaveGameSnapshot base = GameSessionSaveLifecycleTest.snapshot();
        int height = 8;
        List<ChunkSnapshot> chunks = new ArrayList<>();
        for (int x = -2; x <= 2; x++) {
            for (int z = -2; z <= 2; z++) {
                chunks.add(ChunkSnapshot.of(
                        new ChunkKey(x, z),
                        1L,
                        height,
                        new byte[16 * height * 16]));
            }
        }
        return new SaveGameSnapshot(
                new SaveGameSnapshot.StaticMetadata(
                        base.metadata().formatVersion(),
                        base.metadata().gameVersion(),
                        base.metadata().saveGameId(),
                        base.metadata().displayName(),
                        base.metadata().createdAt(),
                        base.metadata().worldSeed(),
                        base.metadata().generatorVersion(),
                        base.metadata().generatorConfigFingerprint(),
                        2,
                        height,
                        base.metadata().summary()),
                base.fixedTick(),
                new ChunkRepositorySnapshot(
                        height,
                        1L,
                        chunks),
                new PlayerSaveSnapshot(
                        base.player().owner(),
                        0.0,
                        2.0,
                        0.0,
                        0.0,
                        0.0,
                        0.0,
                        base.player().yaw(),
                        base.player().pitch(),
                        base.player().gameMode(),
                        base.player().noclip()),
                base.inventory(),
                base.worldItems());
    }

    private static void driveToReady(GameSession session) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15L);
        while (session.state() == GameSessionState.LOADING
                && System.nanoTime() < deadline) {
            session.pollLoad();
            if (session.state() == GameSessionState.LOADING) {
                java.util.concurrent.locks.LockSupport.parkNanos(
                        TimeUnit.MILLISECONDS.toNanos(1L));
            }
        }
        assertEquals(GameSessionState.READY, session.state());
    }

    @SuppressWarnings("unchecked")
    private static List<String> invokeList(Method method, Object target) throws Exception {
        return List.copyOf((List<String>) method.invoke(target));
    }

    private static Method requireMethod(Class<?> type, String name) {
        try {
            Method method = type.getDeclaredMethod(name);
            method.setAccessible(true);
            return method;
        } catch (ReflectiveOperationException missing) {
            return fail("Missing Task 11 production observation seam: "
                    + type.getName() + "." + name + "()");
        }
    }

    private static Class<?> requireClass(String name) {
        try {
            return Class.forName(name);
        } catch (ClassNotFoundException missing) {
            return fail("Missing Task 11 production type: " + name);
        }
    }
}

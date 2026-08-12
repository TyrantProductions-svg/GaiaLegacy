package com.gaia.session;

import com.gaia.assets.GaiaAssetCatalog;
import com.gaia.assets.GaiaResourceLoader;
import com.gaia.blocks.BlockRegistry;
import com.gaia.inventory.BodyInventoryInputController;
import com.gaia.inventory.BodyInventoryService;
import com.gaia.inventory.DebugInventoryProfile;
import com.gaia.inventory.InventoryDebugCommands;
import com.gaia.inventory.InventoryDebugSeeder;
import com.gaia.inventory.InventoryDropController;
import com.gaia.inventory.InventoryDropLocation;
import com.gaia.inventory.InventorySnapshotFormatter;
import com.gaia.interaction.BlockBreakTransaction;
import com.gaia.interaction.BlockInteractionController;
import com.gaia.interaction.BlockPlacementTransaction;
import com.gaia.interaction.CreativeSelection;
import com.gaia.interaction.GaiaBlockRaycastService;
import com.gaia.interaction.GaiaBlockWorldAccess;
import com.gaia.interaction.GameModeManager;
import com.gaia.interaction.PlayerBlockTargeting;
import com.gaia.interaction.feedback.CommittedBreakVisualAdapter;
import com.gaia.interaction.feedback.FirstPersonMovementState;
import com.gaia.interaction.feedback.GaiaVisualRegionResolver;
import com.gaia.interaction.feedback.GaiaWorldItemFaceResolver;
import com.gaia.interaction.feedback.InteractionBlockState;
import com.gaia.interaction.feedback.InteractionFeedbackCoordinator;
import com.gaia.interaction.feedback.VisualFeedbackDiagnostics;
import com.gaia.interaction.feedback.VisualRegionDiagnostics;
import com.gaia.interaction.feedback.WorldItemVisualTracker;
import com.gaia.save.format.SaveFormatVersion;
import com.gaia.save.format.SaveGameId;
import com.gaia.save.session.SessionRestoreCoordinator;
import com.gaia.save.snapshot.InventorySaveSnapshot;
import com.gaia.save.snapshot.PlayerSaveSnapshot;
import com.gaia.save.snapshot.SaveGameSnapshot;
import com.gaia.save.snapshot.WorldItemsSaveSnapshot;
import com.gaia.ui.GaiaHudScreen;
import com.gaia.ui.GaiaUiAssets;
import com.gaia.ui.GaiaUiAssetLoader;
import com.gaia.ui.HudDebugSnapshot;
import com.gaia.ui.HudFrameCoordinator;
import com.gaia.ui.HudPresenter;
import com.gaia.ui.HudVisibility;
import com.gaia.ui.UiIconResolver;
import com.gaia.world.GaiaWorldGenerator;
import com.gaia.world.SafeSpawnSelector;
import com.gaia.world.WorldLoadResult;
import com.gaia.world.WorldLoadState;
import com.gaia.world.WorldLoader;
import com.gaia.world.generation.WorldGenerationConfig;
import com.gaia.world.generation.WorldGenerator;
import com.gaia.worlditem.PhysicalWorldItemSystem;
import com.gaia.worlditem.RoutedWorldInteractionInput;
import com.gaia.worlditem.WorldInteractionInputRouter;
import com.gaia.worlditem.WorldItemDropKinematics;
import com.gaia.worlditem.WorldItemPhysicsConfig;
import com.gaia.worlditem.WorldItemPickupController;
import com.gaia.worlditem.WorldItemPickupTransaction;
import com.gaia.worlditem.WorldItemPresentationSnapshot;
import com.gaia.worlditem.WorldItemTargetingService;
import com.overlord.assets.ResourceLocation;
import com.overlord.assets.AssetManager;
import com.overlord.config.GameConfig;
import com.overlord.core.Engine;
import com.overlord.core.ModuleManager;
import com.overlord.core.PlayerManager;
import com.overlord.core.input.InputManager;
import com.overlord.core.input.InputSnapshot;
import com.overlord.core.input.MouseDelta;
import com.overlord.core.lifecycle.ShutdownCoordinator;
import com.overlord.core.thread.MainThreadGuard;
import com.overlord.core.time.FixedStepClock;
import com.overlord.event.EventBus;
import com.overlord.interaction.DefaultWorldMutationService;
import com.overlord.interaction.SynchronousBlockChangeEventPublisher;
import com.overlord.interaction.api.BlockChangeDecision;
import com.overlord.interaction.api.EntityRef;
import com.overlord.inventory.api.ItemStack;
import com.overlord.physics.Aabb;
import com.overlord.physics.BlockCollisionShapeResolver;
import com.overlord.physics.BlockRaycast;
import com.overlord.physics.CollisionWorld;
import com.overlord.physics.MassProperties;
import com.overlord.physics.PhysicsBody;
import com.overlord.physics.PhysicsWorld;
import com.overlord.physics.PlayerController;
import com.overlord.renderer.Camera;
import com.overlord.renderer.ChunkGpuMesh;
import com.overlord.renderer.ChunkRenderBackend;
import com.overlord.renderer.ChunkRenderObject;
import com.overlord.renderer.RenderFrameInput;
import com.overlord.renderer.RenderSurfaceMetrics;
import com.overlord.renderer.feedback.FeedbackVisibility;
import com.overlord.renderer.feedback.InteractionFeedbackFrame;
import com.overlord.renderer.metrics.RenderMetricsSnapshot;
import com.overlord.renderer.particle.ParticleSystem;
import com.overlord.renderer.ui.TextRenderer;
import com.overlord.voxel.ChunkKey;
import com.overlord.voxel.ChunkMeshBuilder;
import com.overlord.voxel.ChunkMeshData;
import com.overlord.voxel.ChunkMeshManager;
import com.overlord.voxel.ChunkRepository;
import com.overlord.voxel.ChunkRepositorySnapshot;
import com.overlord.voxel.World;
import com.overlord.worlditem.LogicalWorldItemService;
import com.overlord.worlditem.api.WorldItemSnapshot;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.joml.Vector3f;

public final class GameSessionFactory {
    private static final double FIXED_STEP_SECONDS = 1.0 / 60.0;
    private static final int MAX_FIXED_STEPS_PER_FRAME = 8;

    private final SessionAssembler assembler;
    private final NamedSessionAssembler namedAssembler;
    private final RestoreSessionAssembler restoreAssembler;
    private final boolean validateCanonicalFiniteWorld;
    private final Thread ownerThread;

    public GameSessionFactory(
            Engine engine,
            InputManager inputManager,
            MainThreadGuard mainThreadGuard,
            GaiaAssetCatalog catalog,
            GaiaUiAssets uiAssets,
            boolean inventoryDebugShortcuts) {
        Objects.requireNonNull(engine, "engine");
        Objects.requireNonNull(inputManager, "inputManager");
        Objects.requireNonNull(mainThreadGuard, "mainThreadGuard");
        Objects.requireNonNull(catalog, "catalog");
        Objects.requireNonNull(uiAssets, "uiAssets");
        ownerThread = Thread.currentThread();
        validateCanonicalFiniteWorld = true;
        ProductionEnvironment environment =
                new EngineProductionEnvironment(engine);
        assembler =
                (config, world, shutdown) ->
                        assembleProduction(
                                config,
                                world,
                                shutdown,
                                environment,
                                inputManager,
                                mainThreadGuard,
                                catalog,
                                uiAssets,
                                inventoryDebugShortcuts,
                                ProductionHooks.NONE);
        namedAssembler =
                (request, config, world, shutdown) ->
                        assembleNewProduction(
                                request,
                                config,
                                world,
                                shutdown,
                                environment,
                                inputManager,
                                mainThreadGuard,
                                catalog,
                                uiAssets,
                                inventoryDebugShortcuts,
                                ProductionHooks.NONE);
        restoreAssembler =
                (snapshot, world, shutdown) ->
                        assembleRestoredProduction(
                                snapshot,
                                world,
                                shutdown,
                                environment,
                                inputManager,
                                mainThreadGuard,
                                catalog,
                                uiAssets,
                                inventoryDebugShortcuts,
                                ProductionHooks.NONE);
    }

    private GameSessionFactory(
            ProductionEnvironment environment,
            InputManager inputManager,
            MainThreadGuard mainThreadGuard,
            GaiaAssetCatalog catalog,
            GaiaUiAssets uiAssets,
            ProductionHooks hooks) {
        Objects.requireNonNull(environment, "environment");
        Objects.requireNonNull(inputManager, "inputManager");
        Objects.requireNonNull(mainThreadGuard, "mainThreadGuard");
        Objects.requireNonNull(catalog, "catalog");
        Objects.requireNonNull(uiAssets, "uiAssets");
        Objects.requireNonNull(hooks, "hooks");
        ownerThread = Thread.currentThread();
        validateCanonicalFiniteWorld = true;
        assembler =
                (config, world, shutdown) ->
                        assembleProduction(
                                config,
                                world,
                                shutdown,
                                environment,
                                inputManager,
                                mainThreadGuard,
                                catalog,
                                uiAssets,
                                false,
                                hooks);
        namedAssembler =
                (request, config, world, shutdown) ->
                        assembleNewProduction(
                                request,
                                config,
                                world,
                                shutdown,
                                environment,
                                inputManager,
                                mainThreadGuard,
                                catalog,
                                uiAssets,
                                false,
                                hooks);
        restoreAssembler =
                (snapshot, world, shutdown) ->
                        assembleRestoredProduction(
                                snapshot,
                                world,
                                shutdown,
                                environment,
                                inputManager,
                                mainThreadGuard,
                                catalog,
                                uiAssets,
                                false,
                                hooks);
    }

    GameSessionFactory(SessionAssembler assembler) {
        ownerThread = Thread.currentThread();
        validateCanonicalFiniteWorld = false;
        this.assembler = Objects.requireNonNull(assembler, "assembler");
        namedAssembler = (request, config, world, shutdown) ->
                this.assembler.assemble(config, world, shutdown);
        restoreAssembler =
                (snapshot, world, shutdown) -> {
                    throw new IllegalStateException(
                            "This session factory does not provide restore assembly");
                };
    }

    GameSessionFactory(
            SessionAssembler assembler,
            RestoreSessionAssembler restoreAssembler) {
        ownerThread = Thread.currentThread();
        validateCanonicalFiniteWorld = false;
        this.assembler = Objects.requireNonNull(assembler, "assembler");
        namedAssembler = (request, config, world, shutdown) ->
                this.assembler.assemble(config, world, shutdown);
        this.restoreAssembler =
                Objects.requireNonNull(restoreAssembler, "restoreAssembler");
    }

    public GameSession create(GameSessionConfig config) {
        Objects.requireNonNull(config, "config");
        World world = new World();
        ShutdownCoordinator shutdown = new ShutdownCoordinator();
        try {
            SessionRuntime runtime =
                    Objects.requireNonNull(
                            assembler.assemble(config, world, shutdown),
                            "session runtime");
            return new OwnedGameSession(runtime, shutdown);
        } catch (RuntimeException | Error failure) {
            closeWithSuppression(shutdown, failure);
            throw failure;
        }
    }

    public GameSession create(
            NewWorldRequest request, GameSessionConfig config) {
        NewWorldRequest validatedRequest = Objects.requireNonNull(request, "request");
        GameSessionConfig validatedConfig = Objects.requireNonNull(config, "config");
        if (validatedRequest.seed() != validatedConfig.seed()) {
            throw new IllegalArgumentException(
                    "new-world request seed must match the session config");
        }
        World world = new World();
        ShutdownCoordinator shutdown = new ShutdownCoordinator();
        try {
            SessionRuntime runtime = Objects.requireNonNull(
                    namedAssembler.assemble(
                            validatedRequest,
                            validatedConfig,
                            world,
                            shutdown),
                    "session runtime");
            return new OwnedGameSession(runtime, shutdown);
        } catch (RuntimeException | Error failure) {
            closeWithSuppression(shutdown, failure);
            throw failure;
        }
    }

    public GameSession restore(SaveGameSnapshot snapshot) {
        requireFactoryOwnerThread("restore session");
        SaveGameSnapshot validated = Objects.requireNonNull(snapshot, "snapshot");
        if (validateCanonicalFiniteWorld) {
            validateRestoreSnapshot(validated);
        }
        World world =
                new World(
                        new ChunkRepository(
                                validated.chunks().worldHeight()));
        ShutdownCoordinator shutdown = new ShutdownCoordinator();
        try {
            SessionRuntime runtime =
                    Objects.requireNonNull(
                            restoreAssembler.assemble(
                                    validated, world, shutdown),
                            "restored session runtime");
            return new OwnedGameSession(runtime, shutdown);
        } catch (RuntimeException | Error failure) {
            closeWithSuppression(shutdown, failure);
            throw failure;
        }
    }

    private static void validateRestoreSnapshot(SaveGameSnapshot snapshot) {
        int radius = snapshot.metadata().chunkRadius();
        Set<ChunkKey> restoredKeys = new HashSet<>();
        for (var chunk : snapshot.chunks().chunks()) {
            ChunkKey key = chunk.key();
            if (key.x() < -radius
                    || key.x() > radius
                    || key.z() < -radius
                    || key.z() > radius) {
                throw new IllegalArgumentException(
                        "chunk key is outside the saved world radius: " + key);
            }
            restoredKeys.add(key);
        }
        long diameter = Math.addExact(Math.multiplyExact((long) radius, 2L), 1L);
        long expectedChunkCount = Math.multiplyExact(diameter, diameter);
        if (restoredKeys.size() != expectedChunkCount) {
            throw new IllegalArgumentException(
                    "saved Chunks must contain every key in radius "
                            + radius
                            + ": expected "
                            + expectedChunkCount
                            + " but found "
                            + restoredKeys.size());
        }
        for (int chunkX = -radius; chunkX <= radius; chunkX++) {
            for (int chunkZ = -radius; chunkZ <= radius; chunkZ++) {
                ChunkKey expected = new ChunkKey(chunkX, chunkZ);
                if (!restoredKeys.contains(expected)) {
                    throw new IllegalArgumentException(
                            "saved Chunks are missing expected key "
                                    + expected);
                }
            }
        }

        PlayerSaveSnapshot player = snapshot.player();
        requireExactFloat(player.feetPositionX(), "feetPositionX");
        requireExactFloat(player.feetPositionY(), "feetPositionY");
        requireExactFloat(player.feetPositionZ(), "feetPositionZ");
        requireExactFloat(player.velocityX(), "velocityX");
        requireExactFloat(player.velocityY(), "velocityY");
        requireExactFloat(player.velocityZ(), "velocityZ");
        requireExactFloat(player.yaw(), "yaw");
        requireExactFloat(player.pitch(), "pitch");
    }

    private static void requireExactFloat(double value, String field) {
        float converted = (float) value;
        if (!Float.isFinite(converted)
                || Double.doubleToRawLongBits(value)
                        != Double.doubleToRawLongBits((double) converted)) {
            throw new IllegalArgumentException(
                    field + " must round-trip exactly through float");
        }
    }

    private void requireFactoryOwnerThread(String operation) {
        if (Thread.currentThread() != ownerThread) {
            throw new IllegalStateException(
                    "Cannot " + operation + " outside the factory owner thread");
        }
    }

    private static SessionRuntime assembleProduction(
            GameSessionConfig config,
            World world,
            ShutdownCoordinator shutdown,
            ProductionEnvironment environment,
            InputManager inputManager,
            MainThreadGuard mainThreadGuard,
            GaiaAssetCatalog catalog,
            GaiaUiAssets uiAssets,
            boolean inventoryDebugShortcuts,
            ProductionHooks hooks) {
        return assembleProduction(
                config,
                Optional.empty(),
                Optional.empty(),
                world,
                shutdown,
                environment,
                inputManager,
                mainThreadGuard,
                catalog,
                uiAssets,
                inventoryDebugShortcuts,
                hooks);
    }

    private static SessionRuntime assembleNewProduction(
            NewWorldRequest request,
            GameSessionConfig config,
            World world,
            ShutdownCoordinator shutdown,
            ProductionEnvironment environment,
            InputManager inputManager,
            MainThreadGuard mainThreadGuard,
            GaiaAssetCatalog catalog,
            GaiaUiAssets uiAssets,
            boolean inventoryDebugShortcuts,
            ProductionHooks hooks) {
        return assembleProduction(
                config,
                Optional.of(Objects.requireNonNull(request, "request")),
                Optional.empty(),
                world,
                shutdown,
                environment,
                inputManager,
                mainThreadGuard,
                catalog,
                uiAssets,
                inventoryDebugShortcuts,
                hooks);
    }

    private static SessionRuntime assembleRestoredProduction(
            SaveGameSnapshot snapshot,
            World world,
            ShutdownCoordinator shutdown,
            ProductionEnvironment environment,
            InputManager inputManager,
            MainThreadGuard mainThreadGuard,
            GaiaAssetCatalog catalog,
            GaiaUiAssets uiAssets,
            boolean inventoryDebugShortcuts,
            ProductionHooks hooks) {
        GameSessionConfig config =
                new GameSessionConfig(
                        snapshot.metadata().worldSeed(),
                        snapshot.metadata().chunkRadius(),
                        snapshot.player().gameMode(),
                        false);
        return assembleProduction(
                config,
                Optional.empty(),
                Optional.of(snapshot),
                world,
                shutdown,
                environment,
                inputManager,
                mainThreadGuard,
                catalog,
                uiAssets,
                inventoryDebugShortcuts,
                hooks);
    }

    private static SessionRuntime assembleProduction(
            GameSessionConfig config,
            Optional<NewWorldRequest> newWorldRequest,
            Optional<SaveGameSnapshot> restoreSnapshot,
            World world,
            ShutdownCoordinator shutdown,
            ProductionEnvironment environment,
            InputManager inputManager,
            MainThreadGuard mainThreadGuard,
            GaiaAssetCatalog catalog,
            GaiaUiAssets uiAssets,
            boolean inventoryDebugShortcuts,
            ProductionHooks hooks) {
        Objects.requireNonNull(environment, "environment");
        Objects.requireNonNull(hooks, "hooks");
        hooks.registerShutdown(shutdown);
        BlockCollisionShapeResolver shapes =
                BlockCollisionShapeResolver.fullCubesForNonAir();
        CollisionWorld collisionWorld = new CollisionWorld(world, shapes);
        BlockRaycast blockRaycast = new BlockRaycast(world, shapes);
        PhysicsBody playerBody =
                new PhysicsBody(
                        new Aabb(
                                -GameConfig.Player.WIDTH / 2.0f,
                                0.0f,
                                -GameConfig.Player.WIDTH / 2.0f,
                                GameConfig.Player.WIDTH / 2.0f,
                                GameConfig.Player.HEIGHT,
                                GameConfig.Player.WIDTH / 2.0f),
                        MassProperties.dynamic(1.0f));
        PlayerController playerController =
                new PlayerController(
                        playerBody,
                        collisionWorld,
                        GameConfig.Player.MOVEMENT_SPEED,
                        GameConfig.Player.NOCLIP_SPEED,
                        GameConfig.Player.JUMP_VELOCITY,
                        GameConfig.Physics.GRAVITY,
                        GameConfig.Physics.TERMINAL_VELOCITY);
        PhysicsWorld physicsWorld =
                new PhysicsWorld(
                        collisionWorld,
                        new Vector3f(0, GameConfig.Physics.GRAVITY, 0));
        PlayerManager playerManager =
                new PlayerManager(environment.camera(), playerController);
        FixedStepClock fixedStepClock =
                new FixedStepClock(
                        FIXED_STEP_SECONDS,
                        MAX_FIXED_STEPS_PER_FRAME);

        BlockRegistry blocks = catalog.blockRegistry();
        TextRenderer hudText =
                new TextRenderer(
                        uiAssets.renderAssets().glyphs(),
                        codePoint ->
                                System.err.println(
                                        "[UI] Missing glyph for U+"
                                                + Integer.toHexString(codePoint)
                                                        .toUpperCase(
                                                                java.util.Locale.ROOT)));
        HudFrameCoordinator hudFrames =
                new HudFrameCoordinator(
                        new HudPresenter(blocks::itemForm),
                        new GaiaHudScreen(
                                new UiIconResolver(
                                        uiAssets.icons(),
                                        item ->
                                                System.err.println(
                                                        "[UI] Missing icon for "
                                                                + item)),
                                hudText));
        EntityRef inventoryOwner = new EntityRef(0);
        BodyInventoryService inventoryService =
                new BodyInventoryService(
                        inventoryOwner,
                        blocks,
                        mainThreadGuard,
                        EventBus.getInstance()::publish);
        InventoryDebugCommands inventoryDebugCommands =
                createInventoryDebugCommands(
                        inventoryService, inventoryOwner);
        LogicalWorldItemService worldItems =
                new LogicalWorldItemService(
                        mainThreadGuard,
                        GameConfig.Interaction.MAX_LOGICAL_WORLD_ITEMS,
                        GameConfig.Interaction.WORLD_ITEM_PICKUP_DELAY_TICKS);
        PhysicalWorldItemSystem physicalWorldItems =
                new PhysicalWorldItemSystem(
                        worldItems,
                        physicsWorld,
                        world.chunks(),
                        mainThreadGuard,
                        WorldItemPhysicsConfig.production());
        hooks.observeOwners(
                physicsWorld,
                inventoryService,
                inventoryOwner,
                worldItems);
        VisualRegionDiagnostics visualRegionDiagnostics =
                VisualRegionDiagnostics.safe(
                        (item, failure) ->
                                System.err.println(
                                        "[InteractionFeedback] item="
                                                + item
                                                + " failure="
                                                + failure));
        GaiaVisualRegionResolver visualRegions =
                new GaiaVisualRegionResolver(
                        blocks,
                        catalog.blockAtlas(),
                        visualRegionDiagnostics);
        GaiaWorldItemFaceResolver worldItemFaces =
                new GaiaWorldItemFaceResolver(
                        blocks,
                        catalog.blockAtlas().requireRegion(
                                ResourceLocation.parse("gaia:missing")),
                        visualRegionDiagnostics);
        ParticleSystem particles = new ParticleSystem();
        WorldItemVisualTracker worldItemVisuals =
                new WorldItemVisualTracker(worldItemFaces::resolve);
        VisualFeedbackDiagnostics visualDiagnostics =
                (event, failure) ->
                        System.err.println(
                                "[InteractionFeedback] event="
                                        + event
                                        + " failure="
                                        + failure);
        CommittedBreakVisualAdapter committedBreaks =
                new CommittedBreakVisualAdapter(
                        ResourceLocation.parse("gaia:air"),
                        visualRegions::resolve,
                        particles,
                        visualDiagnostics);
        InteractionFeedbackCoordinator feedback =
                new InteractionFeedbackCoordinator(
                        committedBreaks,
                        particles,
                        worldItemVisuals,
                        visualRegions::resolve,
                        worldItemFaces::resolve,
                        new com.gaia.interaction.feedback.FirstPersonActionAnimator(),
                        new com.gaia.interaction.feedback.CameraImpulseController(),
                        new com.gaia.interaction.feedback.TransientBlockVisualSystem());
        Consumer<Throwable> fatalSpawnBarrier =
                failure -> {
                    throw new IllegalStateException(
                            "fatal canonical world-item spawn barrier",
                            failure);
                };
        InventoryDropController inventoryDrop =
                new InventoryDropController(
                        inventoryService,
                        worldItems,
                        fatalSpawnBarrier);
        BodyInventoryInputController inventoryInput =
                new BodyInventoryInputController(
                        inventoryService,
                        inventoryOwner,
                        Optional.of(inventoryDrop));
        GaiaBlockWorldAccess blockWorld =
                new GaiaBlockWorldAccess(world, blocks);
        DefaultWorldMutationService worldMutations =
                new DefaultWorldMutationService(
                        mainThreadGuard,
                        blockWorld,
                        new SynchronousBlockChangeEventPublisher(
                                ignored -> BlockChangeDecision.ALLOW,
                                ignored -> {},
                                ignored -> {}));
        GaiaBlockRaycastService blockRaycasts =
                new GaiaBlockRaycastService(blockRaycast, blocks);
        PlayerBlockTargeting blockTargeting =
                new PlayerBlockTargeting(
                        blockRaycasts,
                        playerBody,
                        environment.camera(),
                        world.chunks(),
                        GameConfig.Player.EYE_HEIGHT,
                        GameConfig.Interaction.REACH);
        GameModeManager gameModes =
                new GameModeManager(
                        config.defaultGameMode(),
                        EventBus.getInstance()::publish);
        WorldInteractionInputRouter worldInteractionInput =
                new WorldInteractionInputRouter();
        WorldItemPickupTransaction worldItemPickupTransaction =
                new WorldItemPickupTransaction(
                        inventoryService,
                        worldItems,
                        inventoryOwner,
                        failure -> {
                            throw new IllegalStateException(
                                    "fatal world-item pickup invariant",
                                    failure);
                        });
        WorldItemPickupController worldItemPickup =
                new WorldItemPickupController(
                        worldItems,
                        playerBody,
                        environment.camera(),
                        () ->
                                inventoryService
                                        .viewModel(inventoryOwner)
                                        .orElseThrow()
                                        .activeSlot(),
                        new WorldItemTargetingService(blockRaycasts),
                        worldItemPickupTransaction,
                        feedback,
                        GameConfig.Player.EYE_HEIGHT,
                        GameConfig.Interaction.WORLD_ITEM_PICKUP_REACH);
        CreativeSelection creativeSelection =
                new CreativeSelection(
                        blocks,
                        Optional.of(ResourceLocation.parse("gaia:dirt")));
        BlockBreakTransaction blockBreak =
                new BlockBreakTransaction(
                        worldMutations,
                        inventoryService,
                        inventoryOwner,
                        worldItems,
                        playerBody,
                        ResourceLocation.parse("gaia:air"),
                        fatalSpawnBarrier);
        BlockInteractionController blockInteraction =
                new BlockInteractionController(
                        gameModes,
                        blockTargeting,
                        world.chunks(),
                        blocks,
                        inventoryService,
                        inventoryOwner,
                        creativeSelection,
                        blockBreak,
                        new BlockPlacementTransaction(
                                worldMutations,
                                inventoryService,
                                inventoryOwner,
                                blocks,
                                blockWorld,
                                playerBody,
                                ResourceLocation.parse("gaia:air")),
                        GameConfig.Interaction.BASE_BREAK_SPEED,
                        feedback);
        if (restoreSnapshot.isEmpty()) {
            runConfiguredInventoryDebugCommand(inventoryDebugCommands);
        }

        SessionShutdownBarrier shutdownBarrier =
                new SessionShutdownBarrier(5, TimeUnit.SECONDS);
        ExecutorService meshExecutor =
                Executors.newFixedThreadPool(
                        2,
                        instrumentedThreadFactory(
                                namedThreadFactory("Gaia-Chunk-Mesher"),
                                hooks));
        ChunkMeshManager chunkMeshes =
                shutdownBarrier.registerChunkMeshes(
                        shutdown,
                        meshExecutor,
                        () ->
                                environment.newChunkMeshManager(
                                        world.chunks(),
                                        blocks,
                                        meshExecutor,
                                        mainThreadGuard),
                        ChunkMeshManager::close);

        shutdown.register(
                "interaction-feedback", feedback::close);
        shutdown.register(
                "physical-world-items", physicalWorldItems::close);
        shutdown.register(
                "world-item-pickup", worldItemPickup::close);
        shutdown.register(
                "inventory-drop", inventoryDrop::close);
        shutdown.register(
                "block-break", blockBreak::close);

        Optional<WorldLoader> sessionWorldLoader = Optional.empty();
        Optional<CompletableFuture<WorldLoadResult>> worldLoad =
                Optional.empty();
        Set<ChunkKey> meshReadiness = Set.of();
        long initialFixedTick = 0L;
        SaveGameSnapshot.StaticMetadata persistenceMetadata;
        PendingCameraOrientation cameraOrientation =
                new PendingCameraOrientation(environment.camera());
        if (restoreSnapshot.isPresent()) {
            SaveGameSnapshot restored = restoreSnapshot.orElseThrow();
            AtomicLong restoredFixedTick = new AtomicLong(-1L);
            AtomicReference<Set<ChunkKey>> restoredReadiness =
                    new AtomicReference<>(Set.of());
            new SessionRestoreCoordinator(
                            world.chunks(),
                            inventoryService,
                            inventoryOwner,
                            worldItems,
                            playerController,
                            environment.camera(),
                            gameModes,
                            physicalWorldItems,
                            restoredFixedTick::set,
                            keys -> restoredReadiness.set(Set.copyOf(keys)),
                            cameraOrientation::stage,
                            stage -> beforeRestoreStage(hooks, stage))
                    .restore(restored);
            if (restoredFixedTick.get() != restored.fixedTick()) {
                throw new IllegalStateException(
                        "restore did not preserve the fixed tick");
            }
            initialFixedTick = restoredFixedTick.get();
            meshReadiness = restoredReadiness.get();
            persistenceMetadata = restored.metadata();
        } else {
            hooks.generationInvoked();
            WorldGenerator generator =
                    GaiaWorldGenerator.createVisualRevisionCandidate();
            WorldGenerationConfig worldGenerationConfig =
                    configuredGeneration(config);
            ExecutorService worldExecutor =
                    Executors.newSingleThreadExecutor(
                            runnable -> {
                                Thread thread =
                                        new Thread(
                                                runnable,
                                                "Gaia-World-Loader");
                                thread.setDaemon(true);
                                return thread;
                            });
            shutdownBarrier.registerWorldExecutor(
                    shutdown, worldExecutor);
            WorldLoader worldLoader =
                    new WorldLoader(
                            generator,
                            blocks,
                            worldGenerationConfig,
                            new SafeSpawnSelector(),
                            worldExecutor);
            CompletableFuture<WorldLoadResult> createdWorldLoad =
                    worldLoader.loadAsync(world);
            shutdown.register(
                    "world-load", () -> createdWorldLoad.cancel(true));
            sessionWorldLoader = Optional.of(worldLoader);
            worldLoad = Optional.of(createdWorldLoad);
            persistenceMetadata =
                    newSessionMetadata(
                            config, worldGenerationConfig, newWorldRequest);
        }

        return new ProductionSessionRuntime(
                environment,
                inputManager,
                world,
                playerManager,
                physicsWorld,
                playerController,
                fixedStepClock,
                chunkMeshes,
                cameraOrientation,
                sessionWorldLoader,
                worldLoad,
                meshReadiness,
                initialFixedTick,
                persistenceMetadata,
                inventoryOwner,
                inventoryService,
                inventoryInput,
                inventoryDebugCommands,
                inventoryDebugShortcuts,
                gameModes,
                worldInteractionInput,
                worldItemPickup,
                blockInteraction,
                worldItems,
                physicalWorldItems,
                feedback,
                InteractionBlockState.unblocked(),
                hudFrames,
                config.debugHudDefault(),
                hooks);
    }

    static SessionSaveCaptureResult captureSave(
            SaveGameSnapshot.StaticMetadata metadata,
            LongSupplier persistenceRevision,
            LongSupplier fixedTick,
            World world,
            EntityRef inventoryOwner,
            BodyInventoryService inventoryService,
            LogicalWorldItemService worldItems,
            PlayerController playerController,
            Camera camera,
            GameModeManager gameModes) {
        return captureSave(
                metadata,
                persistenceRevision,
                fixedTick,
                world,
                inventoryOwner,
                inventoryService,
                worldItems,
                playerController,
                camera,
                gameModes,
                SessionPersistenceClock.restored(0L, 0L));
    }

    private static SessionSaveCaptureResult captureSave(
            SaveGameSnapshot.StaticMetadata metadata,
            LongSupplier persistenceRevision,
            LongSupplier fixedTick,
            World world,
            EntityRef inventoryOwner,
            BodyInventoryService inventoryService,
            LogicalWorldItemService worldItems,
            PlayerController playerController,
            Camera camera,
            GameModeManager gameModes,
            SessionPersistenceClock captureAuthority) {
        Objects.requireNonNull(metadata, "metadata");
        Objects.requireNonNull(persistenceRevision, "persistenceRevision");
        Objects.requireNonNull(fixedTick, "fixedTick");
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(inventoryOwner, "inventoryOwner");
        Objects.requireNonNull(inventoryService, "inventoryService");
        Objects.requireNonNull(worldItems, "worldItems");
        Objects.requireNonNull(playerController, "playerController");
        Objects.requireNonNull(camera, "camera");
        Objects.requireNonNull(gameModes, "gameModes");
        Objects.requireNonNull(captureAuthority, "captureAuthority");

        long revisionBefore = requireNonnegativeRevision(persistenceRevision.getAsLong());
        long fixedTickBefore = requireNonnegativeFixedTick(fixedTick.getAsLong());
        ChunkRepositorySnapshot chunks;
        try {
            chunks = world.chunks().canonicalSnapshot();
        } catch (IllegalStateException inconsistentRevision) {
            return SessionSaveCaptureResult.inconsistentRevision();
        }
        InventorySaveSnapshot inventory;
        WorldItemsSaveSnapshot logicalItems;
        try {
            inventory =
                    new InventorySaveSnapshot(
                            inventoryService.canonicalSnapshot(inventoryOwner));
            logicalItems =
                    new WorldItemsSaveSnapshot(
                            fixedTickBefore, worldItems.canonicalSnapshot());
        } catch (IllegalStateException pendingTransaction) {
            return SessionSaveCaptureResult.pendingTransaction();
        }

        Vector3f feet =
                playerController.body().position(new Vector3f());
        Vector3f velocity =
                playerController.body().linearVelocity(new Vector3f());
        PlayerSaveSnapshot player =
                new PlayerSaveSnapshot(
                        inventoryOwner,
                        feet.x,
                        feet.y,
                        feet.z,
                        velocity.x,
                        velocity.y,
                        velocity.z,
                        camera.getYaw(),
                        camera.getPitch(),
                        gameModes.mode(),
                        playerController.isNoclip());

        long fixedTickAfter = requireNonnegativeFixedTick(fixedTick.getAsLong());
        long revisionAfter = requireNonnegativeRevision(persistenceRevision.getAsLong());
        if (fixedTickAfter != fixedTickBefore || revisionAfter != revisionBefore) {
            return SessionSaveCaptureResult.inconsistentRevision();
        }
        SaveGameSnapshot snapshot =
                new SaveGameSnapshot(
                        metadata,
                        fixedTickBefore,
                        chunks,
                        player,
                        inventory,
                        logicalItems);
        return captureAuthority.captured(snapshot, revisionBefore);
    }

    private static long requireNonnegativeRevision(long revision) {
        if (revision < 0) {
            throw new IllegalStateException(
                    "session persistence revision must be non-negative");
        }
        return revision;
    }

    private static long requireNonnegativeFixedTick(long fixedTick) {
        if (fixedTick < 0) {
            throw new IllegalStateException("fixed tick must be non-negative");
        }
        return fixedTick;
    }

    private static WorldGenerationConfig configuredGeneration(
            GameSessionConfig config) {
        WorldGenerationConfig defaults =
                WorldGenerationConfig.visualRevisionCandidate();
        return new WorldGenerationConfig(
                config.seed(),
                defaults.algorithmVersion(),
                config.chunkRadius(),
                defaults.biome(),
                defaults.height(),
                defaults.cave(),
                defaults.surface(),
                defaults.decoration(),
                defaults.spawn());
    }

    private static SaveGameSnapshot.StaticMetadata newSessionMetadata(
            GameSessionConfig config,
            WorldGenerationConfig generationConfig,
            Optional<NewWorldRequest> newWorldRequest) {
        NewWorldRequest request = newWorldRequest.orElse(null);
        if (request != null && request.seed() != config.seed()) {
            throw new IllegalArgumentException(
                    "new-world request seed must match generation config");
        }
        String implementationVersion =
                GameSessionFactory.class
                        .getPackage()
                        .getImplementationVersion();
        return new SaveGameSnapshot.StaticMetadata(
                SaveFormatVersion.CURRENT,
                implementationVersion == null
                        ? "development"
                        : implementationVersion,
                request == null
                        ? SaveGameId.parse(UUID.randomUUID().toString())
                        : request.saveGameId(),
                request == null ? "New World" : request.displayName(),
                Instant.now(),
                config.seed(),
                "gaia-v" + generationConfig.algorithmVersion(),
                generationFingerprint(generationConfig),
                config.chunkRadius(),
                GameConfig.Chunk.MAX_HEIGHT,
                Optional.empty());
    }

    private static String generationFingerprint(
            WorldGenerationConfig generationConfig) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of()
                    .formatHex(
                            digest.digest(
                                    generationConfig
                                            .canonicalFingerprintInput()
                                            .getBytes(
                                                    StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(
                    "SHA-256 is unavailable", impossible);
        }
    }

    private static InventoryDebugCommands createInventoryDebugCommands(
            BodyInventoryService inventoryService,
            EntityRef owner) {
        DebugInventoryProfile profile =
                new DebugInventoryProfile(
                        new ItemStack(
                                ResourceLocation.parse("gaia:dirt"),
                                12),
                        new ItemStack(
                                ResourceLocation.parse("gaia:dirt"),
                                64),
                        new ItemStack(
                                ResourceLocation.parse("gaia:stone"),
                                64),
                        new ItemStack(
                                ResourceLocation.parse("gaia:oak_leaves"),
                                1));
        return new InventoryDebugCommands(
                new InventoryDebugSeeder(
                        inventoryService, owner, profile),
                inventoryService,
                owner,
                new InventorySnapshotFormatter());
    }

    private static void runConfiguredInventoryDebugCommand(
            InventoryDebugCommands commands) {
        String command =
                System.getProperty("gaia.inventory.debugCommand");
        if (command == null || command.isBlank()) {
            return;
        }
        System.out.println(
                "[InventoryDebug] "
                        + commands.execute(command).message());
    }

    private static java.util.concurrent.ThreadFactory namedThreadFactory(
            String name) {
        return runnable -> {
            Thread thread = new Thread(runnable, name);
            thread.setDaemon(true);
            return thread;
        };
    }

    private static ThreadFactory instrumentedThreadFactory(
            ThreadFactory delegate,
            ProductionHooks hooks) {
        Objects.requireNonNull(delegate, "delegate");
        Objects.requireNonNull(hooks, "hooks");
        return runnable -> {
            Thread worker = delegate.newThread(runnable);
            hooks.workerCreated(worker);
            return worker;
        };
    }

    private static void beforeRestoreStage(
            ProductionHooks hooks,
            SessionRestoreCoordinator.RestoreStage stage) {
        switch (stage) {
            case CHUNKS -> hooks.before(ProductionFailurePoint.CHUNKS);
            case INVENTORY -> hooks.before(ProductionFailurePoint.INVENTORY);
            case WORLD_ITEMS -> hooks.before(ProductionFailurePoint.WORLD_ITEMS);
            case PLAYER -> hooks.before(ProductionFailurePoint.PLAYER);
            case PROJECTIONS -> hooks.before(ProductionFailurePoint.PROJECTION);
            case MESH_READINESS -> {
                // Mesh failure injection belongs to the real runtime pump.
            }
        }
    }

    private static void closeWithSuppression(
            ShutdownCoordinator shutdown,
            Throwable primaryFailure) {
        try {
            shutdown.close();
        } catch (RuntimeException | Error cleanupFailure) {
            if (cleanupFailure != primaryFailure) {
                primaryFailure.addSuppressed(cleanupFailure);
            }
        }
    }

    /**
     * Production camera publication boundary shared by restored and generated
     * sessions. Staging never mutates the shared camera; publication occurs
     * only after its supplied READY-frame operation succeeds.
     */
    private static final class PendingCameraOrientation {
        private final Camera camera;
        private Float pendingYaw;
        private Float pendingPitch;
        private boolean committed;

        private PendingCameraOrientation(Camera camera) {
            this.camera = Objects.requireNonNull(camera, "camera");
        }

        private void stage(float yaw, float pitch) {
            if (!Float.isFinite(yaw) || !Float.isFinite(pitch)) {
                throw new IllegalArgumentException(
                        "camera orientation must be finite");
            }
            if (committed) {
                throw new IllegalStateException(
                        "camera orientation is already published");
            }
            pendingYaw = yaw;
            pendingPitch = pitch;
        }

        private <T> T beforeReady(Supplier<T> operation) {
            if (committed) {
                throw new IllegalStateException(
                        "camera orientation is already published");
            }
            return Objects.requireNonNull(operation, "operation").get();
        }

        private <T> T commitAfterReady(Supplier<T> readyPublication) {
            T published = beforeReady(readyPublication);
            if (pendingYaw == null || pendingPitch == null) {
                throw new IllegalStateException(
                        "camera orientation was not staged");
            }
            camera.setYaw(pendingYaw);
            camera.setPitch(pendingPitch);
            committed = true;
            return published;
        }

        private boolean isCommitted() {
            return committed;
        }
    }

    enum ProductionFailurePoint {
        CHUNKS,
        INVENTORY,
        WORLD_ITEMS,
        PLAYER,
        PROJECTION,
        INITIAL_FRAME,
        MESH_PUMP,
        READY_FRAME
    }

    private interface ProductionHooks {
        ProductionHooks NONE = ignored -> {};

        void before(ProductionFailurePoint failurePoint);

        default void registerShutdown(
                ShutdownCoordinator shutdown) {}

        default void observeOwners(
                PhysicsWorld physicsWorld,
                BodyInventoryService inventory,
                EntityRef inventoryOwner,
                LogicalWorldItemService worldItems) {}

        default void generationInvoked() {}

        default void frameCaptured(
                HudVisibility.Lifecycle lifecycle,
                GameSessionFrame frame) {}

        default void readyPublished() {}

        default void workerCreated(Thread worker) {}
    }

    private interface ProductionEnvironment {
        Camera camera();

        ChunkMeshManager newChunkMeshManager(
                ChunkRepository chunks,
                BlockRegistry blocks,
                ExecutorService meshExecutor,
                MainThreadGuard mainThreadGuard);

        RenderMetricsSnapshot renderMetricsSnapshot();

        RenderSurfaceMetrics surfaceMetrics();
    }

    private static final class EngineProductionEnvironment
            implements ProductionEnvironment {
        private final Engine engine;

        private EngineProductionEnvironment(Engine engine) {
            this.engine = Objects.requireNonNull(engine, "engine");
        }

        @Override
        public Camera camera() {
            return engine.getCamera();
        }

        @Override
        public ChunkMeshManager newChunkMeshManager(
                ChunkRepository chunks,
                BlockRegistry blocks,
                ExecutorService meshExecutor,
                MainThreadGuard mainThreadGuard) {
            World world = new World(chunks);
            return new ChunkMeshManager(
                    world.chunks(),
                    new ChunkMeshBuilder(blocks),
                    meshExecutor,
                    engine.getRenderer(),
                    mainThreadGuard,
                    2);
        }

        @Override
        public RenderMetricsSnapshot renderMetricsSnapshot() {
            return engine.getRenderer().metrics().snapshot();
        }

        @Override
        public RenderSurfaceMetrics surfaceMetrics() {
            return engine.getWindow().currentSurfaceMetrics();
        }
    }

    private static final class HeadlessProductionEnvironment
            implements ProductionEnvironment {
        private final Camera camera = new Camera();
        private final ChunkRenderBackend chunkRenderBackend =
                new ChunkRenderBackend() {
                    @Override
                    public ChunkRenderObject upload(
                            ChunkMeshData data) {
                        return new ChunkRenderObject(
                                data.key(),
                                data.revision(),
                                new HeadlessChunkGpuMesh(
                                        data.vertexCount()),
                                data.localBounds().orElseThrow());
                    }

                    @Override
                    public void release(
                            ChunkRenderObject object) {
                        Objects.requireNonNull(object, "object")
                                .mesh()
                                .cleanup();
                    }
                };
        private final RenderMetricsSnapshot metrics =
                new RenderMetricsSnapshot(0.0, 0.0, 0, 0, 0L, 0);
        private final RenderSurfaceMetrics surface =
                new RenderSurfaceMetrics(1280, 720, 1280, 720, 1.0f, 1.0f);

        @Override
        public Camera camera() {
            return camera;
        }

        @Override
        public ChunkMeshManager newChunkMeshManager(
                ChunkRepository chunks,
                BlockRegistry blocks,
                ExecutorService meshExecutor,
                MainThreadGuard mainThreadGuard) {
            return new ChunkMeshManager(
                    chunks,
                    new ChunkMeshBuilder(blocks),
                    meshExecutor,
                    chunkRenderBackend,
                    mainThreadGuard,
                    2);
        }

        @Override
        public RenderMetricsSnapshot renderMetricsSnapshot() {
            return metrics;
        }

        @Override
        public RenderSurfaceMetrics surfaceMetrics() {
            return surface;
        }

        private record HeadlessChunkGpuMesh(int vertexCount)
                implements ChunkGpuMesh {
            private HeadlessChunkGpuMesh {
                if (vertexCount <= 0) {
                    throw new IllegalArgumentException(
                            "headless chunk mesh must contain vertices");
                }
            }

            @Override
            public void draw() {}

            @Override
            public void cleanup() {}
        }
    }

    private static final class ProductionInstrumentation
            implements ProductionHooks {
        private final ProductionFailurePoint failurePoint;
        private final RuntimeException primaryFailure =
                new RuntimeException("injected actual production failure");
        private final RuntimeException cleanupFailure =
                new RuntimeException("injected actual production cleanup failure");
        private final ConcurrentLinkedQueue<Thread> workers =
                new ConcurrentLinkedQueue<>();
        private boolean failureEnabled = true;
        private int failureHookCalls;
        private int generationInvocations;
        private int capturedFrames;
        private int transientPresentations;
        private int readyPublications;
        private int closeCalls;
        private ShutdownCoordinator failedShutdown;
        private PhysicsWorld physicsWorld;
        private BodyInventoryService inventory;
        private EntityRef inventoryOwner;
        private LogicalWorldItemService worldItems;

        private ProductionInstrumentation(
                ProductionFailurePoint failurePoint) {
            this.failurePoint = Objects.requireNonNull(
                    failurePoint, "failurePoint");
            failureEnabled = true;
        }

        private ProductionInstrumentation() {
            failurePoint = null;
            failureEnabled = false;
        }

        @Override
        public void before(
                ProductionFailurePoint currentPoint) {
            if (failureEnabled && currentPoint == failurePoint) {
                failureHookCalls++;
                throw primaryFailure;
            }
        }

        @Override
        public void registerShutdown(
                ShutdownCoordinator shutdown) {
            if (failureEnabled) {
                failedShutdown = shutdown;
            }
            shutdown.register(
                    "production-test-observer",
                    () -> {
                        closeCalls++;
                        if (failureEnabled) {
                            throw cleanupFailure;
                        }
                    });
        }

        @Override
        public void observeOwners(
                PhysicsWorld physicsWorld,
                BodyInventoryService inventory,
                EntityRef inventoryOwner,
                LogicalWorldItemService worldItems) {
            this.physicsWorld = Objects.requireNonNull(
                    physicsWorld, "physicsWorld");
            this.inventory = Objects.requireNonNull(
                    inventory, "inventory");
            this.inventoryOwner = Objects.requireNonNull(
                    inventoryOwner, "inventoryOwner");
            this.worldItems = Objects.requireNonNull(
                    worldItems, "worldItems");
        }

        @Override
        public void generationInvoked() {
            generationInvocations++;
        }

        @Override
        public void frameCaptured(
                HudVisibility.Lifecycle lifecycle,
                GameSessionFrame frame) {
            capturedFrames++;
            transientPresentations = Objects.requireNonNull(frame, "frame")
                    .renderInput()
                    .feedback()
                    .transientBlocks()
                    .size();
        }

        @Override
        public void readyPublished() {
            readyPublications++;
        }

        @Override
        public void workerCreated(Thread worker) {
            workers.add(Objects.requireNonNull(worker, "worker"));
        }

        private void beginSuccessfulRetry() {
            failureEnabled = false;
        }

        private int physicsBodyCount() {
            return Objects.requireNonNull(
                    physicsWorld, "production physics owner")
                    .bodies()
                    .size();
        }

        private int inventoryPendingReservations() {
            try {
                Objects.requireNonNull(
                                inventory, "production inventory owner")
                        .canonicalSnapshot(inventoryOwner);
                return 0;
            } catch (IllegalStateException pending) {
                return 1;
            }
        }

        private int worldItemPendingReservations() {
            try {
                Objects.requireNonNull(
                                worldItems, "production world-item owner")
                        .canonicalSnapshot();
                return 0;
            } catch (IllegalStateException pending) {
                return 1;
            }
        }

        private int liveWorkerCount() {
            long deadline =
                    System.nanoTime() + TimeUnit.SECONDS.toNanos(5L);
            boolean interrupted = Thread.interrupted();
            try {
                for (Thread worker : workers) {
                    while (worker.isAlive()
                            && System.nanoTime() < deadline) {
                        try {
                            worker.join(10L);
                        } catch (InterruptedException failure) {
                            interrupted = true;
                        }
                    }
                }
            } finally {
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
            return (int) workers.stream().filter(Thread::isAlive).count();
        }
    }

    static final class ProductionLifecycleTestAccess {
        private static final int MAX_LOAD_POLLS = 100_000;

        private final SaveGameSnapshot failedSnapshot;
        private final SaveGameSnapshot successfulRetrySnapshot;
        private final HeadlessProductionEnvironment environment;
        private final ProductionInstrumentation instrumentation;
        private final GameSessionFactory factory;
        private GameSession failedSession;
        private GameSession successfulSession;
        private int failedFrameCount;

        private ProductionLifecycleTestAccess(
                ProductionFailurePoint failurePoint,
                SaveGameSnapshot failedSnapshot,
                SaveGameSnapshot successfulRetrySnapshot) {
            this.failedSnapshot = Objects.requireNonNull(
                    failedSnapshot, "failedSnapshot");
            this.successfulRetrySnapshot = Objects.requireNonNull(
                    successfulRetrySnapshot, "successfulRetrySnapshot");
            environment = new HeadlessProductionEnvironment();
            instrumentation =
                    new ProductionInstrumentation(failurePoint);
            MainThreadGuard guard =
                    MainThreadGuard.captureCurrentThread();
            ProductionTestAssets assets =
                    ProductionTestAssetsHolder.ASSETS;
            factory = new GameSessionFactory(
                    environment,
                    new InputManager(guard),
                    guard,
                    assets.catalog(),
                    assets.uiAssets(),
                    instrumentation);
        }

        void triggerFailure() {
            try {
                failedSession = factory.restore(failedSnapshot);
                driveToReady(failedSession);
                throw new AssertionError(
                        "injected production failure did not run");
            } catch (RuntimeException | Error failure) {
                failedFrameCount = instrumentation.capturedFrames;
                throw failure;
            }
        }

        RuntimeException primaryFailure() {
            return instrumentation.primaryFailure;
        }

        RuntimeException cleanupFailure() {
            return instrumentation.cleanupFailure;
        }

        Optional<GameSessionState> failedSessionState() {
            return failedSession == null
                    ? Optional.empty()
                    : Optional.of(failedSession.state());
        }

        Camera camera() {
            return environment.camera();
        }

        boolean readyPublished() {
            return instrumentation.readyPublications != 0;
        }

        int capturedFrameCount() {
            return failedFrameCount;
        }

        int failureHookCalls() {
            return instrumentation.failureHookCalls;
        }

        int closeCalls() {
            return instrumentation.closeCalls;
        }

        int physicsBodyCount() {
            return instrumentation.physicsBodyCount();
        }

        int inventoryPendingReservations() {
            return instrumentation.inventoryPendingReservations();
        }

        int worldItemPendingReservations() {
            return instrumentation.worldItemPendingReservations();
        }

        int liveWorkerCount() {
            return instrumentation.liveWorkerCount();
        }

        void closeFailedSessionAgain() {
            if (failedSession != null) {
                failedSession.close();
            } else {
                Objects.requireNonNull(
                                instrumentation.failedShutdown,
                                "failed production shutdown")
                        .close();
            }
        }

        void startSuccessfulRetry() {
            instrumentation.beginSuccessfulRetry();
            successfulSession =
                    factory.restore(successfulRetrySnapshot);
            driveToReady(successfulSession);
        }

        GameSessionState successfulRetryState() {
            return Objects.requireNonNull(
                            successfulSession,
                            "successful retry session")
                    .state();
        }

        int successfulRetryFrameCount() {
            return instrumentation.capturedFrames - failedFrameCount;
        }

        void closeSuccessfulRetry() {
            Objects.requireNonNull(
                            successfulSession,
                            "successful retry session")
                    .close();
        }

        private static void driveToReady(GameSession session) {
            for (int poll = 0;
                    poll < MAX_LOAD_POLLS
                            && session.state() == GameSessionState.LOADING;
                    poll++) {
                session.pollLoad();
                if (session.state() == GameSessionState.LOADING) {
                    Thread.yield();
                }
            }
            if (session.state() == GameSessionState.LOADING) {
                throw new IllegalStateException(
                        "production test session did not finish loading");
            }
        }
    }

    static ProductionLifecycleTestAccess
            productionLifecycleTestAccess(
                    ProductionFailurePoint failurePoint,
                    SaveGameSnapshot failedSnapshot,
                    SaveGameSnapshot successfulRetrySnapshot) {
        return new ProductionLifecycleTestAccess(
                failurePoint,
                failedSnapshot,
                successfulRetrySnapshot);
    }

    static final class ProductionSessionTestAccess {
        private final ProductionInstrumentation instrumentation;
        private final GameSessionFactory factory;

        private ProductionSessionTestAccess() {
            HeadlessProductionEnvironment environment =
                    new HeadlessProductionEnvironment();
            instrumentation = new ProductionInstrumentation();
            MainThreadGuard guard =
                    MainThreadGuard.captureCurrentThread();
            ProductionTestAssets assets =
                    ProductionTestAssetsHolder.ASSETS;
            factory = new GameSessionFactory(
                    environment,
                    new InputManager(guard),
                    guard,
                    assets.catalog(),
                    assets.uiAssets(),
                    instrumentation);
        }

        GameSessionFactory factory() {
            return factory;
        }

        int generationInvocationCount() {
            return instrumentation.generationInvocations;
        }

        int readyPublicationCount() {
            return instrumentation.readyPublications;
        }

        int capturedFrameCount() {
            return instrumentation.capturedFrames;
        }

        int transientPresentationCount() {
            return instrumentation.transientPresentations;
        }

        int physicsBodyCount() {
            return instrumentation.physicsBodyCount();
        }

        int inventoryPendingReservations() {
            return instrumentation.inventoryPendingReservations();
        }

        int worldItemPendingReservations() {
            return instrumentation.worldItemPendingReservations();
        }

        int liveWorkerCount() {
            return (int) instrumentation.workers.stream()
                    .filter(Thread::isAlive)
                    .count();
        }

        int authorizationEntryCount(GameSession session) {
            if (!(Objects.requireNonNull(session, "session")
                    instanceof OwnedGameSession owned)) {
                throw new IllegalArgumentException(
                        "session is not owned by the production factory");
            }
            return owned.authorizationEntryCount();
        }
    }

    static ProductionSessionTestAccess productionSessionTestAccess() {
        return new ProductionSessionTestAccess();
    }

    private record ProductionTestAssets(
            GaiaAssetCatalog catalog,
            GaiaUiAssets uiAssets) {}

    private static final class ProductionTestAssetsHolder {
        private static final ProductionTestAssets ASSETS = load();

        private static ProductionTestAssets load() {
            AssetManager assets =
                    new AssetManager(
                            GameSessionFactory.class.getClassLoader());
            return new ProductionTestAssets(
                    new GaiaResourceLoader(assets).load(),
                    new GaiaUiAssetLoader(assets).load());
        }
    }

    static final class PersistenceAuthorizationTestAccess {
        private final OwnedGameSession session;
        private final AuthorizationRuntime runtime;

        private PersistenceAuthorizationTestAccess(
                OwnedGameSession session,
                AuthorizationRuntime runtime) {
            this.session = session;
            this.runtime = runtime;
        }

        void makeReady() {
            runtime.ready = true;
            session.pollLoad();
        }

        GameSession session() {
            return session;
        }

        void enqueueCapture(
                SaveGameSnapshot snapshot, long revision) {
            runtime.captures.addLast(
                    new CapturePayload(
                            Objects.requireNonNull(snapshot, "snapshot"),
                            revision));
        }

        int authorizationEntryCount() {
            return session.authorizationEntryCount();
        }

        List<Long> markedRevisions() {
            return List.copyOf(runtime.markedRevisions);
        }
    }

    static PersistenceAuthorizationTestAccess
            persistenceAuthorizationTestAccess(
                    GameSessionConfig config) {
        Objects.requireNonNull(config, "config");
        AuthorizationRuntime runtime = new AuthorizationRuntime();
        OwnedGameSession session =
                new OwnedGameSession(
                        runtime, new ShutdownCoordinator());
        return new PersistenceAuthorizationTestAccess(
                session, runtime);
    }

    private static final class AuthorizationRuntime
            implements SessionRuntime {
        private final java.util.ArrayDeque<CapturePayload> captures =
                new java.util.ArrayDeque<>();
        private final List<Long> markedRevisions =
                new java.util.ArrayList<>();
        private final SessionPersistenceClock clock =
                SessionPersistenceClock.restored(0L, 0L);
        private boolean ready;

        @Override
        public boolean pollLoad() {
            return ready;
        }

        @Override
        public GameSessionFrame advancePlaying(
                double frameDeltaSeconds,
                MouseDelta look,
                boolean focused) {
            throw new AssertionError("advancePlaying was not expected");
        }

        @Override
        public GameSessionFrame capturePaused() {
            throw new AssertionError("capturePaused was not expected");
        }

        @Override
        public SessionSaveCaptureResult captureSave() {
            CapturePayload capture = captures.removeFirst();
            return clock.captured(
                    capture.snapshot(), capture.revision());
        }

        @Override
        public void markSaved(
                SessionPersistenceRevision revision) {
            markedRevisions.add(revision.value());
        }

        @Override
        public void discardGameplayEligibility() {}

        @Override
        public void discardFixedTime() {}
    }

    private record CapturePayload(
            SaveGameSnapshot snapshot, long revision) {}

    @FunctionalInterface
    interface SessionAssembler {
        SessionRuntime assemble(
                GameSessionConfig config,
                World world,
                ShutdownCoordinator shutdown);
    }

    @FunctionalInterface
    private interface NamedSessionAssembler {
        SessionRuntime assemble(
                NewWorldRequest request,
                GameSessionConfig config,
                World world,
                ShutdownCoordinator shutdown);
    }

    @FunctionalInterface
    interface RestoreSessionAssembler {
        SessionRuntime assemble(
                SaveGameSnapshot snapshot,
                World world,
                ShutdownCoordinator shutdown);
    }

    interface SessionRuntime {
        boolean pollLoad();

        GameSessionFrame advancePlaying(
                double frameDeltaSeconds,
                MouseDelta look,
                boolean focused);

        GameSessionFrame capturePaused();

        default SessionSaveCaptureResult captureSave() {
            throw new UnsupportedOperationException(
                    "This runtime does not provide persistence capture");
        }

        default void markSaved(SessionPersistenceRevision revision) {
            throw new UnsupportedOperationException(
                    "This runtime does not provide persistence checkpoints");
        }

        default long persistenceRevision() {
            return -1L;
        }

        void discardGameplayEligibility();

        void discardFixedTime();
    }

    private static final class OwnedGameSession
            implements GameSession {
        private final SessionRuntime runtime;
        private final ShutdownCoordinator shutdown;
        private final Thread ownerThread;
        private SessionPersistenceRevision latestCapturedRevision;
        private SessionPersistenceRevision lastSavedToken;
        private GameSessionState state = GameSessionState.LOADING;
        private long lastCapturedRevision = -1L;
        private long lastSavedRevision = -1L;

        private OwnedGameSession(
                SessionRuntime runtime,
                ShutdownCoordinator shutdown) {
            this.runtime = Objects.requireNonNull(runtime, "runtime");
            this.shutdown = Objects.requireNonNull(shutdown, "shutdown");
            ownerThread = Thread.currentThread();
        }

        @Override
        public GameSessionState state() {
            return state;
        }

        @Override
        public void pollLoad() {
            if (state != GameSessionState.LOADING) {
                return;
            }
            try {
                if (runtime.pollLoad()) {
                    state = GameSessionState.READY;
                }
            } catch (RuntimeException | Error failure) {
                state = GameSessionState.FAILED;
                closeWithSuppression(shutdown, failure);
                throw failure;
            }
        }

        @Override
        public GameSessionFrame advancePlaying(
                double frameDeltaSeconds,
                MouseDelta look,
                boolean focused) {
            requireReady("advance playing");
            try {
                return Objects.requireNonNull(
                        runtime.advancePlaying(
                                frameDeltaSeconds, look, focused),
                        "session frame");
            } catch (RuntimeException | Error failure) {
                state = GameSessionState.FAILED;
                closeWithSuppression(shutdown, failure);
                throw failure;
            }
        }

        @Override
        public GameSessionFrame capturePaused() {
            if (state != GameSessionState.LOADING
                    && state != GameSessionState.READY) {
                throw new IllegalStateException(
                        "Cannot capture a " + state + " session");
            }
            return Objects.requireNonNull(
                    runtime.capturePaused(), "paused session frame");
        }

        @Override
        public SessionSaveCaptureResult captureSave() {
            requireOwnerThread("capture save");
            requireReady("capture save");
            SessionSaveCaptureResult result =
                    Objects.requireNonNull(
                            runtime.captureSave(), "session save capture result");
            if (result.status()
                    != SessionSaveCaptureResult.Status.CAPTURED) {
                return result;
            }
            result.snapshot().orElseThrow();
            SessionPersistenceRevision captured =
                    result.persistenceRevision().orElseThrow(
                            () -> new IllegalStateException(
                                    "captured result requires a persistence revision"));
            long revision = result.capturedRevision().orElseThrow();
            if (captured.value() != revision) {
                throw new IllegalStateException(
                        "captured persistence revision does not match the numeric revision");
            }
            if (revision < lastCapturedRevision) {
                return SessionSaveCaptureResult.inconsistentRevision();
            }
            lastCapturedRevision = revision;
            latestCapturedRevision = captured;
            return result;
        }

        @Override
        public void markSaved(SessionPersistenceRevision revision) {
            requireOwnerThread("mark saved");
            requireReady("mark saved");
            SessionPersistenceRevision captured =
                    Objects.requireNonNull(revision, "revision");
            long value = captured.value();
            if (captured.equals(lastSavedToken)) {
                return;
            }
            if (!captured.equals(latestCapturedRevision)) {
                throw new IllegalArgumentException(
                        "revision was not captured by this session: " + value);
            }
            if (value < lastSavedRevision) {
                throw new IllegalArgumentException(
                        "saved revisions must be monotonic");
            }
            if (value == lastSavedRevision) {
                lastSavedToken = captured;
                return;
            }
            runtime.markSaved(captured);
            lastSavedRevision = value;
            lastSavedToken = captured;
        }

        @Override
        public boolean hasUnsavedChanges() {
            requireOwnerThread("query unsaved changes");
            requireReady("query unsaved changes");
            long currentRevision = runtime.persistenceRevision();
            if (currentRevision < 0L) {
                currentRevision = lastCapturedRevision;
            }
            return currentRevision != lastSavedRevision;
        }

        private int authorizationEntryCount() {
            if (latestCapturedRevision == null) {
                return lastSavedToken == null ? 0 : 1;
            }
            return lastSavedToken == null
                            || latestCapturedRevision.equals(lastSavedToken)
                    ? 1
                    : 2;
        }

        @Override
        public void discardFixedTime() {
            if (state == GameSessionState.LOADING
                    || state == GameSessionState.READY) {
                runtime.discardGameplayEligibility();
                runtime.discardFixedTime();
            }
        }

        @Override
        public void close() {
            requireOwnerThread("close");
            if (state == GameSessionState.CLOSED) {
                return;
            }
            if (state != GameSessionState.FAILED) {
                state = GameSessionState.CLOSED;
            }
            shutdown.close();
        }

        private void requireReady(String operation) {
            if (state != GameSessionState.READY) {
                throw new IllegalStateException(
                        "Cannot " + operation + " while session is " + state);
            }
        }

        private void requireOwnerThread(String operation) {
            if (Thread.currentThread() != ownerThread) {
                throw new IllegalStateException(
                        "Cannot "
                                + operation
                                + " outside the session owner thread");
            }
        }
    }

    private static final class ProductionSessionRuntime
            implements SessionRuntime {
        private final ProductionEnvironment environment;
        private final InputManager inputManager;
        private final World world;
        private final PlayerManager playerManager;
        private final PhysicsWorld physicsWorld;
        private final PlayerController playerController;
        private final FixedStepClock fixedStepClock;
        private final ChunkMeshManager chunkMeshes;
        private final PendingCameraOrientation cameraOrientation;
        private final Optional<WorldLoader> worldLoader;
        private final Optional<CompletableFuture<WorldLoadResult>> worldLoad;
        private final SaveGameSnapshot.StaticMetadata persistenceMetadata;
        private final EntityRef inventoryOwner;
        private final BodyInventoryService inventoryService;
        private final BodyInventoryInputController inventoryInput;
        private final InventoryDebugCommands inventoryDebugCommands;
        private final boolean inventoryDebugShortcuts;
        private final GameModeManager gameModes;
        private final WorldInteractionInputRouter worldInteractionInput;
        private final WorldItemPickupController worldItemPickup;
        private final BlockInteractionController blockInteraction;
        private final LogicalWorldItemService worldItems;
        private final PhysicalWorldItemSystem physicalWorldItems;
        private final InteractionFeedbackCoordinator feedback;
        private final InteractionBlockState interactionBlockState;
        private final HudFrameCoordinator hudFrames;
        private final ProductionHooks hooks;
        private final Vector3f interpolationScratch = new Vector3f();
        private final Vector3f movementPositionScratch = new Vector3f();
        private final Vector3f movementVelocityScratch = new Vector3f();
        private final Vector3f dropPositionScratch = new Vector3f();
        private final Vector3f dropVelocityScratch = new Vector3f();
        private final Vector3f feetScratch = new Vector3f();
        private WorldLoadResult loadResult;
        private Set<ChunkKey> meshReadiness;
        private final SessionPersistenceClock persistenceClock;
        private long savedPersistenceRevision = -1L;
        private boolean hasAdvancedFrame;
        private boolean debugHudDefaultPending;
        private GameSessionFrame lastFrame;

        private ProductionSessionRuntime(
                ProductionEnvironment environment,
                InputManager inputManager,
                World world,
                PlayerManager playerManager,
                PhysicsWorld physicsWorld,
                PlayerController playerController,
                FixedStepClock fixedStepClock,
                ChunkMeshManager chunkMeshes,
                PendingCameraOrientation cameraOrientation,
                Optional<WorldLoader> worldLoader,
                Optional<CompletableFuture<WorldLoadResult>> worldLoad,
                Set<ChunkKey> meshReadiness,
                long initialFixedTick,
                SaveGameSnapshot.StaticMetadata persistenceMetadata,
                EntityRef inventoryOwner,
                BodyInventoryService inventoryService,
                BodyInventoryInputController inventoryInput,
                InventoryDebugCommands inventoryDebugCommands,
                boolean inventoryDebugShortcuts,
                GameModeManager gameModes,
                WorldInteractionInputRouter worldInteractionInput,
                WorldItemPickupController worldItemPickup,
                BlockInteractionController blockInteraction,
                LogicalWorldItemService worldItems,
                PhysicalWorldItemSystem physicalWorldItems,
                InteractionFeedbackCoordinator feedback,
                InteractionBlockState interactionBlockState,
                HudFrameCoordinator hudFrames,
                boolean debugHudDefault,
                ProductionHooks hooks) {
            this.environment = Objects.requireNonNull(
                    environment, "environment");
            this.inputManager = inputManager;
            this.world = world;
            this.playerManager = playerManager;
            this.physicsWorld = physicsWorld;
            this.playerController = playerController;
            this.fixedStepClock = fixedStepClock;
            this.chunkMeshes = chunkMeshes;
            this.cameraOrientation = Objects.requireNonNull(
                    cameraOrientation, "cameraOrientation");
            this.worldLoader = Objects.requireNonNull(worldLoader, "worldLoader");
            this.worldLoad = Objects.requireNonNull(worldLoad, "worldLoad");
            this.meshReadiness =
                    Set.copyOf(
                            Objects.requireNonNull(
                                    meshReadiness, "meshReadiness"));
            persistenceClock =
                    SessionPersistenceClock.restored(
                            requireNonnegativeFixedTick(initialFixedTick),
                            0L);
            this.persistenceMetadata =
                    Objects.requireNonNull(
                            persistenceMetadata, "persistenceMetadata");
            this.inventoryOwner = inventoryOwner;
            this.inventoryService = inventoryService;
            this.inventoryInput = inventoryInput;
            this.inventoryDebugCommands = inventoryDebugCommands;
            this.inventoryDebugShortcuts = inventoryDebugShortcuts;
            this.gameModes = gameModes;
            this.worldInteractionInput = worldInteractionInput;
            this.worldItemPickup = worldItemPickup;
            this.blockInteraction = blockInteraction;
            this.worldItems = worldItems;
            this.physicalWorldItems = physicalWorldItems;
            this.feedback = feedback;
            this.interactionBlockState = interactionBlockState;
            this.hudFrames = hudFrames;
            this.hooks = Objects.requireNonNull(hooks, "hooks");
            debugHudDefaultPending = debugHudDefault;
            if (worldLoad.isEmpty()) {
                updateRenderCamera();
            }
            hooks.before(ProductionFailurePoint.INITIAL_FRAME);
            lastFrame =
                    cameraOrientation.beforeReady(
                            () -> captureFrame(
                                    FixedBatch.zeroSteps(),
                                    0.0,
                                    false,
                                    HudVisibility.Lifecycle.LOADING));
        }

        @Override
        public boolean pollLoad() {
            if (worldLoad.isPresent() && loadResult == null) {
                CompletableFuture<WorldLoadResult> pendingLoad =
                        worldLoad.orElseThrow();
                if (!pendingLoad.isDone()) {
                    return false;
                }
                loadResult = joinWorldLoad();
                WorldLoader completedLoader = worldLoader.orElseThrow();
                if (completedLoader.state()
                        != WorldLoadState.SUCCEEDED) {
                    throw new IllegalStateException(
                            "World load future completed while loader state was "
                                    + completedLoader.state());
                }
                if (!persistenceMetadata
                        .generatorConfigFingerprint()
                        .equals(loadResult.configFingerprint())) {
                    throw new IllegalStateException(
                            "World load configuration fingerprint did not match session metadata");
                }
                completePlayerLoading(loadResult);
                updateRenderCamera();
                cameraOrientation.stage(-90.0f, -30.0f);
                meshReadiness = Set.copyOf(loadResult.initialChunks());
            }

            hooks.before(ProductionFailurePoint.MESH_PUMP);
            cameraOrientation.beforeReady(
                    () -> {
                        pumpChunkMeshes();
                        return Boolean.TRUE;
                    });
            if (!chunkMeshes.allRenderable(meshReadiness)) {
                return false;
            }
            if (worldLoader.isPresent()
                    && worldLoader.orElseThrow().state()
                            != WorldLoadState.SUCCEEDED) {
                throw new IllegalStateException(
                        "World loader is not successful");
            }
            if (!cameraOrientation.isCommitted()) {
                hooks.before(ProductionFailurePoint.READY_FRAME);
                GameSessionFrame readyFrame =
                        cameraOrientation.commitAfterReady(
                                () -> captureFrame(
                                        FixedBatch.zeroSteps(),
                                        0.0,
                                        false,
                                        HudVisibility.Lifecycle.RUNNING));
                lastFrame = readyFrame;
                hooks.readyPublished();
            }
            return true;
        }

        private WorldLoadResult joinWorldLoad() {
            try {
                return worldLoad.orElseThrow().join();
            } catch (CancellationException cancellation) {
                throw cancellation;
            } catch (CompletionException failure) {
                Throwable cause = failure.getCause();
                if (cause instanceof RuntimeException runtimeException) {
                    throw runtimeException;
                }
                if (cause instanceof Error error) {
                    throw error;
                }
                throw new RuntimeException(
                        "World loading failed", cause);
            }
        }

        @Override
        public GameSessionFrame advancePlaying(
                double frameDeltaSeconds,
                MouseDelta look,
                boolean focused) {
            if (!Double.isFinite(frameDeltaSeconds)
                    || frameDeltaSeconds < 0.0) {
                throw new IllegalArgumentException(
                        "frameDeltaSeconds must be finite and non-negative");
            }
            Objects.requireNonNull(look, "look");
            if (focused) {
                if (look.x() != 0.0 || look.y() != 0.0) {
                    var reservation =
                            persistenceClock.reserveRevisionMutation();
                    playerManager.applyLook(look);
                    reservation.commit();
                } else {
                    playerManager.applyLook(look);
                }
            }
            int fixedSteps = fixedStepClock.advance(frameDeltaSeconds);
            FixedBatch fixedBatch = runFixedBatch(fixedSteps);
            pumpChunkMeshes();
            updateRenderCamera();
            GameSessionFrame captured =
                    captureFrame(
                            fixedBatch,
                            frameDeltaSeconds,
                            focused,
                            HudVisibility.Lifecycle.RUNNING);
            feedback.renderUpdate(frameDeltaSeconds);
            hasAdvancedFrame = true;
            lastFrame = captured;
            return captured;
        }

        @Override
        public GameSessionFrame capturePaused() {
            return lastFrame.copy();
        }

        @Override
        public SessionSaveCaptureResult captureSave() {
            return GameSessionFactory.captureSave(
                    persistenceMetadata,
                    persistenceClock::revision,
                    persistenceClock::fixedTick,
                    world,
                    inventoryOwner,
                    inventoryService,
                    worldItems,
                    playerController,
                    environment.camera(),
                    gameModes,
                    persistenceClock);
        }

        @Override
        public void markSaved(SessionPersistenceRevision revision) {
            SessionPersistenceRevision validated =
                    Objects.requireNonNull(revision, "revision");
            if (validated.value() > persistenceClock.revision()) {
                throw new IllegalArgumentException(
                        "cannot save a future session revision");
            }
            if (validated.value() < savedPersistenceRevision) {
                throw new IllegalArgumentException(
                        "saved revisions must be monotonic");
            }
            savedPersistenceRevision = validated.value();
        }

        @Override
        public long persistenceRevision() {
            return persistenceClock.revision();
        }

        @Override
        public void discardGameplayEligibility() {
            blockInteraction.cancel();
            feedback.clearTransient();
        }

        @Override
        public void discardFixedTime() {
            fixedStepClock.discardRemainder();
        }

        private void completePlayerLoading(
                WorldLoadResult completedLoad) {
            playerController.teleport(
                    completedLoad.playerFeetPosition());
            if (!playerController.recoverFromPenetration()) {
                throw new IllegalStateException(
                        "Player safe spawn recovery failed after world loading");
            }
        }

        private FixedBatch runFixedBatch(int fixedSteps) {
            if (fixedSteps == 0) {
                return FixedBatch.zeroSteps();
            }
            InputSnapshot frameInput =
                    Objects.requireNonNull(
                            inputManager.consumeFixedInput(),
                            "consumed fixed input");
            for (int step = 0; step < fixedSteps; step++) {
                runFixedStep(
                        step == 0
                                ? frameInput
                                : frameInput.heldOnly());
            }
            return new FixedBatch(
                    Optional.of(frameInput), fixedSteps);
        }

        private FirstPersonMovementState movementState() {
            playerController
                    .body()
                    .position(movementPositionScratch);
            playerController
                    .body()
                    .linearVelocity(movementVelocityScratch);
            float horizontalSpeed =
                    (float)
                            Math.sqrt(
                                    movementVelocityScratch.x
                                                    * movementVelocityScratch.x
                                            + movementVelocityScratch.z
                                                    * movementVelocityScratch.z);
            return new FirstPersonMovementState(
                    movementPositionScratch.y,
                    horizontalSpeed,
                    movementVelocityScratch.y,
                    playerController.isGrounded(),
                    playerController.isNoclip());
        }

        private boolean interactionEnabled() {
            return interactionEnabled(
                    true,
                    true,
                    inputManager.isWindowFocused(),
                    interactionBlockState.blocked());
        }

        private static boolean interactionEnabled(
                boolean running,
                boolean cursorCaptured,
                boolean focused,
                boolean blocked) {
            return running
                    && cursorCaptured
                    && focused
                    && !blocked;
        }

        private void runFixedStep(InputSnapshot stepInput) {
            var persistenceReservation =
                    persistenceClock.reserveFixedStep();
            long inventoryTick = persistenceClock.fixedTick();
            float fixedDelta = fixedStepClock.fixedStepSeconds();
            inventoryInput.handleSelection(stepInput);
            runInventoryDebugShortcut(stepInput);
            playerManager.fixedUpdate(fixedDelta, stepInput);
            feedback.fixedMovementUpdate(
                    fixedDelta, movementState());
            physicalWorldItems.prepareStep(inventoryTick);
            try {
                physicsWorld.step(fixedDelta);
            } catch (RuntimeException | Error failure) {
                physicalWorldItems.abortStep();
                throw failure;
            }
            physicalWorldItems.finishStep();

            boolean fixedInteractionEnabled = interactionEnabled();
            var dropInput =
                    inventoryInput.handleDrop(
                            stepInput,
                            inventoryTick,
                            Optional.of(dropLocation(inventoryTick)),
                            fixedInteractionEnabled);
            dropInput.drop().ifPresent(
                    result -> {
                        if (result.status()
                                        == com.gaia.inventory.InventoryDropResult.Status.DROPPED
                                || result.status()
                                        == com.gaia.inventory.InventoryDropResult.Status.DROPPED_WITH_NOTIFICATION_FAILURE) {
                            var spawned = result.worldItem().orElseThrow();
                            feedback.onDropCommitted(
                                    spawned.stack().itemId(),
                                    spawned.id().value());
                        }
                    });
            RoutedWorldInteractionInput routed =
                    worldInteractionInput.route(
                            stepInput,
                            gameModes.mode(),
                            playerController.isNoclip(),
                            fixedInteractionEnabled);
            worldItemPickup.fixedUpdate(
                    routed.pickupPressed(), inventoryTick);
            blockInteraction.fixedUpdate(
                    routed.blockInput(),
                    fixedDelta,
                    inventoryTick,
                    Math.max(0L, System.nanoTime()),
                    fixedInteractionEnabled);
            feedback.fixedUpdate(
                    blockInteraction.viewModel(),
                    fixedInteractionEnabled,
                    inventoryTick);
            ModuleManager.getInstance().updateAll(fixedDelta);
            EventBus.getInstance().processAll();
            persistenceReservation.commit();
        }

        private void pumpChunkMeshes() {
            chunkMeshes.scheduleEligible();
            chunkMeshes.processMainThreadWork();
            chunkMeshes.pollFailure().ifPresent(
                    GameSessionFactory::rethrowMeshFailure);
        }

        private GameSessionFrame captureFrame(
                FixedBatch fixedBatch,
                double frameDeltaSeconds,
                boolean focused,
                HudVisibility.Lifecycle lifecycle) {
            boolean running =
                    lifecycle == HudVisibility.Lifecycle.RUNNING;
            boolean blocked = interactionBlockState.blocked();
            if (!interactionEnabled(
                    running, true, focused, blocked)) {
                feedback.clearTransient();
            }
            List<WorldItemSnapshot> worldItemSnapshots =
                    List.copyOf(worldItems.snapshots());
            List<WorldItemPresentationSnapshot> physicalSnapshots =
                    List.copyOf(
                            physicalWorldItems.presentationSnapshots());
            InteractionFeedbackFrame feedbackFrame =
                    feedback.snapshotPhysical(
                            blockInteraction.viewModel(),
                            running ? physicalSnapshots : List.of(),
                            (float) fixedStepClock.interpolationAlpha(),
                            new FeedbackVisibility(
                                    running,
                                    true,
                                    focused,
                                    blocked));
            playerController.body().position(feetScratch);
            Optional<RenderMetricsSnapshot> previousMetrics =
                    hasAdvancedFrame
                            ? Optional.of(
                                    environment.renderMetricsSnapshot())
                            : Optional.empty();
            HudDebugSnapshot.Counts counts =
                    new HudDebugSnapshot.Counts(
                            world.chunks().keys().size(),
                            physicsWorld.bodies().size(),
                            worldItemSnapshots.size(),
                            feedbackFrame.blockDamage().isPresent()
                                    ? 1
                                    : 0,
                            feedbackFrame.worldItems().size(),
                            feedbackFrame.particles()
                                    .particles()
                                    .size());
            var inventory =
                    inventoryService
                            .viewModel(inventoryOwner)
                            .orElseThrow();
            var interaction = blockInteraction.viewModel();
            var feet =
                    new HudDebugSnapshot.FeetPosition(
                            feetScratch.x,
                            feetScratch.y,
                            feetScratch.z);
            var surface = environment.surfaceMetrics();
            if (debugHudDefaultPending
                    && lifecycle
                            == HudVisibility.Lifecycle.RUNNING) {
                debugHudDefaultPending = false;
                hudFrames.capture(
                        new HudFrameCoordinator.FrameCapture(
                                inventory,
                                interaction,
                                previousMetrics,
                                feet,
                                counts,
                                Optional.of(debugHudDefaultInput()),
                                0.0,
                                lifecycle,
                                focused,
                                true,
                                blocked,
                                surface));
            }
            HudFrameCoordinator.CapturedFrame hud =
                    hudFrames.capture(
                            new HudFrameCoordinator.FrameCapture(
                                    inventory,
                                    interaction,
                                    previousMetrics,
                                    feet,
                                    counts,
                                    fixedBatch.presentationInput(),
                                    frameDeltaSeconds,
                                    lifecycle,
                                    focused,
                                    true,
                                    blocked,
                                    surface));
            GameSessionFrame frame = new GameSessionFrame(
                    new RenderFrameInput(
                            running
                                    ? List.copyOf(
                                            chunkMeshes.renderObjects())
                                    : List.of(),
                            frameDeltaSeconds,
                            chunkMeshes.meshQueueDepth(),
                            feedbackFrame,
                            hud.frame()));
            hooks.frameCaptured(lifecycle, frame);
            return frame;
        }

        private void updateRenderCamera() {
            Vector3f cameraFeet =
                    playerController
                            .body()
                            .interpolatedPosition(
                                    (float)
                                            fixedStepClock
                                                    .interpolationAlpha(),
                                    interpolationScratch);
            cameraFeet.y += GameConfig.Player.EYE_HEIGHT;
            environment.camera().setPosition(cameraFeet);
        }

        private InventoryDropLocation dropLocation(
                long eventIdentity) {
            playerController.body().position(dropPositionScratch);
            dropPositionScratch.y += GameConfig.Player.EYE_HEIGHT;
            environment.camera().getForward(dropVelocityScratch);
            Vector3f right =
                    environment.camera().getRight(new Vector3f());
            return WorldItemDropKinematics.qDrop(
                    dropPositionScratch,
                    dropVelocityScratch,
                    right,
                    eventIdentity);
        }

        private void runInventoryDebugShortcut(
                InputSnapshot input) {
            if (!inventoryDebugShortcuts) {
                return;
            }
            String command = null;
            if (input.isKeyPressed(
                    GameConfig.Input.KEY_DEBUG_INVENTORY_SEED)) {
                command = "seed";
            } else if (input.isKeyPressed(
                    GameConfig.Input.KEY_DEBUG_INVENTORY_CLEAR)) {
                command = "clear";
            } else if (input.isKeyPressed(
                    GameConfig.Input.KEY_DEBUG_INVENTORY_FILL)) {
                command = "fill";
            } else if (input.isKeyPressed(
                    GameConfig.Input.KEY_DEBUG_INVENTORY_PRINT)) {
                command = "print";
            }
            if (command != null) {
                System.out.println(
                        "[InventoryDebug] "
                                + inventoryDebugCommands
                                        .execute(command)
                                        .message());
            }
        }

        private static InputSnapshot debugHudDefaultInput() {
            return new InputSnapshot(
                    Set.of(GameConfig.Input.KEY_TOGGLE_DEBUG_HUD),
                    Set.of(GameConfig.Input.KEY_TOGGLE_DEBUG_HUD));
        }

        private record FixedBatch(
                Optional<InputSnapshot> presentationInput,
                int fixedSteps) {
            private FixedBatch {
                presentationInput =
                        Objects.requireNonNull(
                                presentationInput,
                                "presentationInput");
                if (fixedSteps < 0) {
                    throw new IllegalArgumentException(
                            "fixedSteps must be non-negative");
                }
                if (fixedSteps == 0
                        && presentationInput.isPresent()) {
                    throw new IllegalArgumentException(
                            "zero-step batches cannot contain consumed input");
                }
                if (fixedSteps > 0
                        && presentationInput.isEmpty()) {
                    throw new IllegalArgumentException(
                            "fixed-step batches must contain consumed input");
                }
            }

            private static FixedBatch zeroSteps() {
                return new FixedBatch(Optional.empty(), 0);
            }
        }
    }

    private static void rethrowMeshFailure(Throwable failure) {
        if (failure instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
        throw (Error) failure;
    }

    static final class SessionShutdownBarrier {
        private final long timeout;
        private final TimeUnit timeoutUnit;
        private boolean managerCleanupRequired;
        private boolean worldExecutorRequired;
        private boolean worldExecutorStopped;
        private boolean meshExecutorStopped;
        private boolean managerCleanupAttempted;

        SessionShutdownBarrier(long timeout, TimeUnit unit) {
            if (timeout < 0) {
                throw new IllegalArgumentException(
                        "timeout must not be negative");
            }
            this.timeout = timeout;
            timeoutUnit = Objects.requireNonNull(unit, "unit");
        }

        <T> T registerChunkMeshes(
                ShutdownCoordinator shutdown,
                ExecutorService meshExecutor,
                Supplier<T> managerFactory,
                Consumer<T> managerCleanup) {
            Objects.requireNonNull(shutdown, "shutdown");
            Objects.requireNonNull(meshExecutor, "meshExecutor");
            Objects.requireNonNull(managerFactory, "managerFactory");
            Objects.requireNonNull(managerCleanup, "managerCleanup");

            boolean meshCleanupRegistered = false;
            try {
                T manager =
                        Objects.requireNonNull(
                                managerFactory.get(),
                                "chunk mesh manager");
                shutdown.register(
                        "chunk-meshes",
                        () ->
                                closeManager(
                                        () ->
                                                managerCleanup.accept(
                                                        manager)));
                managerCleanupRequired = true;
                shutdown.register(
                        "mesh-executor",
                        () -> stopMeshExecutor(meshExecutor));
                meshCleanupRegistered = true;
                return manager;
            } catch (RuntimeException | Error failure) {
                if (!meshCleanupRegistered) {
                    try {
                        stopMeshExecutor(meshExecutor);
                    } catch (RuntimeException | Error cleanupFailure) {
                        if (cleanupFailure != failure) {
                            failure.addSuppressed(cleanupFailure);
                        }
                    }
                }
                throw failure;
            }
        }

        void registerWorldExecutor(
                ShutdownCoordinator shutdown,
                ExecutorService worldExecutor) {
            Objects.requireNonNull(shutdown, "shutdown");
            Objects.requireNonNull(worldExecutor, "worldExecutor");
            try {
                shutdown.register(
                        "world-executor",
                        () -> stopWorldExecutor(worldExecutor));
            } catch (RuntimeException | Error failure) {
                try {
                    stopWorldExecutor(worldExecutor);
                } catch (RuntimeException | Error cleanupFailure) {
                    if (cleanupFailure != failure) {
                        failure.addSuppressed(cleanupFailure);
                    }
                }
                throw failure;
            }
            worldExecutorRequired = true;
        }

        void stopWorldExecutor(ExecutorService worldExecutor) {
            shutdownExecutor(
                    worldExecutor,
                    "World loader executor",
                    timeout,
                    timeoutUnit);
            if (!worldExecutor.isTerminated()) {
                throw new IllegalStateException(
                        "World loader executor termination was not confirmed");
            }
            worldExecutorStopped = true;
        }

        void stopMeshExecutor(ExecutorService meshExecutor) {
            shutdownExecutor(
                    meshExecutor,
                    "Chunk mesh executor",
                    timeout,
                    timeoutUnit);
            if (!meshExecutor.isTerminated()) {
                throw new IllegalStateException(
                        "Chunk mesh executor termination was not confirmed");
            }
            meshExecutorStopped = true;
        }

        void closeManager(Runnable managerCleanup) {
            Objects.requireNonNull(managerCleanup, "managerCleanup");
            if (!meshExecutorStopped
                    || (worldExecutorRequired
                            && !worldExecutorStopped)) {
                throw new IllegalStateException(
                        "Chunk mesh manager cleanup was skipped because "
                                + "executor termination was not confirmed");
            }
            managerCleanupAttempted = true;
            managerCleanup.run();
        }

        boolean managerCleanupAttempted() {
            return managerCleanupAttempted;
        }
    }

    private static void shutdownExecutor(
            ExecutorService executor,
            String component,
            long timeout,
            TimeUnit unit) {
        Objects.requireNonNull(executor, "executor");
        Objects.requireNonNull(component, "component");
        Objects.requireNonNull(unit, "unit");
        if (timeout < 0) {
            throw new IllegalArgumentException(
                    "timeout must not be negative");
        }

        long timeoutNanos = unit.toNanos(timeout);
        long deadline = System.nanoTime() + timeoutNanos;
        boolean interrupted = Thread.interrupted();
        InterruptedException firstInterruption = null;
        try {
            executor.shutdownNow();
            while (!executor.isTerminated()) {
                executor.shutdownNow();
                long remainingNanos = deadline - System.nanoTime();
                if (remainingNanos <= 0) {
                    throw terminationFailure(
                            component, firstInterruption);
                }
                try {
                    executor.awaitTermination(
                            remainingNanos,
                            TimeUnit.NANOSECONDS);
                } catch (InterruptedException failure) {
                    interrupted = true;
                    if (firstInterruption == null) {
                        firstInterruption = failure;
                    }
                    Thread.interrupted();
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static IllegalStateException terminationFailure(
            String component,
            InterruptedException firstInterruption) {
        String message =
                component
                        + " did not terminate within the shutdown deadline";
        if (firstInterruption == null) {
            return new IllegalStateException(message);
        }
        return new IllegalStateException(message, firstInterruption);
    }
}

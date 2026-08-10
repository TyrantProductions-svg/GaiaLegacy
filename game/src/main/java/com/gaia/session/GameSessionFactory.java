package com.gaia.session;

import com.gaia.assets.GaiaAssetCatalog;
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
import com.gaia.ui.GaiaHudScreen;
import com.gaia.ui.GaiaUiAssets;
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
import com.overlord.renderer.RenderFrameInput;
import com.overlord.renderer.feedback.FeedbackVisibility;
import com.overlord.renderer.feedback.InteractionFeedbackFrame;
import com.overlord.renderer.metrics.RenderMetricsSnapshot;
import com.overlord.renderer.particle.ParticleSystem;
import com.overlord.renderer.ui.TextRenderer;
import com.overlord.voxel.ChunkMeshBuilder;
import com.overlord.voxel.ChunkMeshManager;
import com.overlord.voxel.World;
import com.overlord.worlditem.LogicalWorldItemService;
import com.overlord.worlditem.api.WorldItemSnapshot;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.joml.Vector3f;

public final class GameSessionFactory {
    private static final double FIXED_STEP_SECONDS = 1.0 / 60.0;
    private static final int MAX_FIXED_STEPS_PER_FRAME = 8;

    private final SessionAssembler assembler;

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
        assembler =
                (config, world, shutdown) ->
                        assembleProduction(
                                config,
                                world,
                                shutdown,
                                engine,
                                inputManager,
                                mainThreadGuard,
                                catalog,
                                uiAssets,
                                inventoryDebugShortcuts);
    }

    GameSessionFactory(SessionAssembler assembler) {
        this.assembler = Objects.requireNonNull(assembler, "assembler");
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

    private static SessionRuntime assembleProduction(
            GameSessionConfig config,
            World world,
            ShutdownCoordinator shutdown,
            Engine engine,
            InputManager inputManager,
            MainThreadGuard mainThreadGuard,
            GaiaAssetCatalog catalog,
            GaiaUiAssets uiAssets,
            boolean inventoryDebugShortcuts) {
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
                new PlayerManager(engine.getCamera(), playerController);
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
                        engine.getCamera(),
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
                        engine.getCamera(),
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
        runConfiguredInventoryDebugCommand(inventoryDebugCommands);

        WorldGenerator generator =
                GaiaWorldGenerator.createVisualRevisionCandidate();
        WorldGenerationConfig worldGenerationConfig =
                configuredGeneration(config);
        SessionShutdownBarrier shutdownBarrier =
                new SessionShutdownBarrier(5, TimeUnit.SECONDS);
        ExecutorService meshExecutor =
                Executors.newFixedThreadPool(
                        2,
                        namedThreadFactory("Gaia-Chunk-Mesher"));
        ChunkMeshManager chunkMeshes =
                shutdownBarrier.registerChunkMeshes(
                        shutdown,
                        meshExecutor,
                        () ->
                                new ChunkMeshManager(
                                        world.chunks(),
                                        new ChunkMeshBuilder(blocks),
                                        meshExecutor,
                                        engine.getRenderer(),
                                        mainThreadGuard,
                                        2),
                        ChunkMeshManager::close);

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
        CompletableFuture<WorldLoadResult> worldLoad =
                worldLoader.loadAsync(world);
        shutdown.register(
                "world-load", () -> worldLoad.cancel(true));
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

        return new ProductionSessionRuntime(
                engine,
                inputManager,
                world,
                playerManager,
                physicsWorld,
                playerController,
                fixedStepClock,
                chunkMeshes,
                worldLoader,
                worldLoad,
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
                config.debugHudDefault());
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

    @FunctionalInterface
    interface SessionAssembler {
        SessionRuntime assemble(
                GameSessionConfig config,
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

        void discardGameplayEligibility();

        void discardFixedTime();
    }

    private static final class OwnedGameSession
            implements GameSession {
        private final SessionRuntime runtime;
        private final ShutdownCoordinator shutdown;
        private GameSessionState state = GameSessionState.LOADING;

        private OwnedGameSession(
                SessionRuntime runtime,
                ShutdownCoordinator shutdown) {
            this.runtime = Objects.requireNonNull(runtime, "runtime");
            this.shutdown = Objects.requireNonNull(shutdown, "shutdown");
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
            return Objects.requireNonNull(
                    runtime.advancePlaying(
                            frameDeltaSeconds, look, focused),
                    "session frame");
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
        public void discardFixedTime() {
            if (state == GameSessionState.LOADING
                    || state == GameSessionState.READY) {
                runtime.discardGameplayEligibility();
                runtime.discardFixedTime();
            }
        }

        @Override
        public void close() {
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
    }

    private static final class ProductionSessionRuntime
            implements SessionRuntime {
        private final Engine engine;
        private final InputManager inputManager;
        private final World world;
        private final PlayerManager playerManager;
        private final PhysicsWorld physicsWorld;
        private final PlayerController playerController;
        private final FixedStepClock fixedStepClock;
        private final ChunkMeshManager chunkMeshes;
        private final WorldLoader worldLoader;
        private final CompletableFuture<WorldLoadResult> worldLoad;
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
        private final Vector3f interpolationScratch = new Vector3f();
        private final Vector3f movementPositionScratch = new Vector3f();
        private final Vector3f movementVelocityScratch = new Vector3f();
        private final Vector3f dropPositionScratch = new Vector3f();
        private final Vector3f dropVelocityScratch = new Vector3f();
        private final Vector3f feetScratch = new Vector3f();
        private WorldLoadResult loadResult;
        private long inventoryTick;
        private boolean hasAdvancedFrame;
        private boolean debugHudDefaultPending;
        private GameSessionFrame lastFrame;

        private ProductionSessionRuntime(
                Engine engine,
                InputManager inputManager,
                World world,
                PlayerManager playerManager,
                PhysicsWorld physicsWorld,
                PlayerController playerController,
                FixedStepClock fixedStepClock,
                ChunkMeshManager chunkMeshes,
                WorldLoader worldLoader,
                CompletableFuture<WorldLoadResult> worldLoad,
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
                boolean debugHudDefault) {
            this.engine = engine;
            this.inputManager = inputManager;
            this.world = world;
            this.playerManager = playerManager;
            this.physicsWorld = physicsWorld;
            this.playerController = playerController;
            this.fixedStepClock = fixedStepClock;
            this.chunkMeshes = chunkMeshes;
            this.worldLoader = worldLoader;
            this.worldLoad = worldLoad;
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
            debugHudDefaultPending = debugHudDefault;
            lastFrame =
                    captureFrame(
                            FixedBatch.zeroSteps(),
                            0.0,
                            false,
                            HudVisibility.Lifecycle.LOADING);
        }

        @Override
        public boolean pollLoad() {
            if (loadResult == null) {
                if (!worldLoad.isDone()) {
                    return false;
                }
                loadResult = joinWorldLoad();
                if (worldLoader.state() != WorldLoadState.SUCCEEDED) {
                    throw new IllegalStateException(
                            "World load future completed while loader state was "
                                    + worldLoader.state());
                }
                completePlayerLoading(loadResult);
                updateRenderCamera();
                engine.getCamera().setPitch(-30.0f);
            }

            pumpChunkMeshes();
            if (!chunkMeshes.allRenderable(loadResult.initialChunks())) {
                return false;
            }
            if (worldLoader.state() != WorldLoadState.SUCCEEDED) {
                throw new IllegalStateException(
                        "World loader is not successful");
            }
            return true;
        }

        private WorldLoadResult joinWorldLoad() {
            try {
                return worldLoad.join();
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
                playerManager.applyLook(look);
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
            inventoryTick++;
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
                                    engine.getRenderer()
                                            .metrics()
                                            .snapshot())
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
            var surface =
                    engine.getWindow().currentSurfaceMetrics();
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
            return new GameSessionFrame(
                    new RenderFrameInput(
                            running
                                    ? List.copyOf(
                                            chunkMeshes.renderObjects())
                                    : List.of(),
                            frameDeltaSeconds,
                            chunkMeshes.meshQueueDepth(),
                            feedbackFrame,
                            hud.frame()));
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
            engine.getCamera().setPosition(cameraFeet);
        }

        private InventoryDropLocation dropLocation(
                long eventIdentity) {
            playerController.body().position(dropPositionScratch);
            dropPositionScratch.y += GameConfig.Player.EYE_HEIGHT;
            engine.getCamera().getForward(dropVelocityScratch);
            Vector3f right =
                    engine.getCamera().getRight(new Vector3f());
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

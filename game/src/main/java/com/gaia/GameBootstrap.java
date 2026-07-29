package com.gaia;

import com.gaia.assets.GaiaAssetCatalog;
import com.gaia.assets.GaiaResourceLoader;
import com.gaia.blocks.BlockRegistry;
import com.gaia.inventory.BodyInventoryInputController;
import com.gaia.inventory.BodyInventoryService;
import com.gaia.inventory.DebugInventoryProfile;
import com.gaia.inventory.InventoryDebugCommands;
import com.gaia.inventory.InventoryDebugSeeder;
import com.gaia.inventory.InventoryDropController;
import com.gaia.inventory.InventorySnapshotFormatter;
import com.gaia.interaction.BlockBreakTransaction;
import com.gaia.interaction.BlockInteractionController;
import com.gaia.interaction.BlockPlacementTransaction;
import com.gaia.interaction.CreativeSelection;
import com.gaia.interaction.GaiaBlockRaycastService;
import com.gaia.interaction.GaiaBlockWorldAccess;
import com.gaia.interaction.GameMode;
import com.gaia.interaction.GameModeManager;
import com.gaia.interaction.PlayerBlockTargeting;
import com.gaia.interaction.feedback.CommittedBreakVisualAdapter;
import com.gaia.interaction.feedback.GaiaVisualRegionResolver;
import com.gaia.interaction.feedback.InteractionBlockState;
import com.gaia.interaction.feedback.InteractionFeedbackCoordinator;
import com.gaia.interaction.feedback.VisualFeedbackDiagnostics;
import com.gaia.interaction.feedback.VisualRegionDiagnostics;
import com.gaia.interaction.feedback.WorldItemVisualTracker;
import com.gaia.ui.GaiaHudScreen;
import com.gaia.ui.GaiaUiAssetLoader;
import com.gaia.ui.GaiaUiAssets;
import com.gaia.ui.HudFrameCoordinator;
import com.gaia.ui.HudPresenter;
import com.gaia.ui.UiIconResolver;
import com.gaia.world.GaiaWorldGenerator;
import com.gaia.world.SafeSpawnSelector;
import com.gaia.world.WorldLoadResult;
import com.gaia.world.WorldLoader;
import com.gaia.world.generation.WorldGenerationConfig;
import com.gaia.world.generation.WorldGenerator;
import com.overlord.assets.AssetDiagnostic;
import com.overlord.assets.AssetLoadReport;
import com.overlord.assets.AssetManager;
import com.overlord.assets.ResourceLocation;
import com.overlord.config.GameConfig;
import com.overlord.core.Engine;
import com.overlord.core.ModuleManager;
import com.overlord.core.PlayerManager;
import com.overlord.core.input.InputManager;
import com.overlord.core.lifecycle.ShutdownCoordinator;
import com.overlord.core.thread.MainThreadGuard;
import com.overlord.event.EventBus;
import com.overlord.interaction.DefaultWorldMutationService;
import com.overlord.interaction.SynchronousBlockChangeEventPublisher;
import com.overlord.interaction.api.EntityRef;
import com.overlord.interaction.api.BlockChangeDecision;
import com.overlord.inventory.api.ItemStack;
import com.overlord.core.time.FixedStepClock;
import com.overlord.core.time.FrameClock;
import com.overlord.physics.Aabb;
import com.overlord.physics.BlockCollisionShapeResolver;
import com.overlord.physics.BlockRaycast;
import com.overlord.physics.CollisionWorld;
import com.overlord.physics.MassProperties;
import com.overlord.physics.PhysicsBody;
import com.overlord.physics.PhysicsWorld;
import com.overlord.physics.PlayerController;
import com.overlord.renderer.visual.RenderVisualSettings;
import com.overlord.renderer.particle.ParticleSystem;
import com.overlord.renderer.ui.TextRenderer;
import com.overlord.voxel.ChunkMeshBuilder;
import com.overlord.voxel.ChunkMeshManager;
import com.overlord.worlditem.LogicalWorldItemService;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.joml.Vector3f;

public final class GameBootstrap {
    private static final double FIXED_STEP_SECONDS = 1.0 / 60.0;
    private static final int MAX_FIXED_STEPS_PER_FRAME = 8;
    private static final double MAX_FRAME_DELTA_SECONDS = 0.25;

    public void run() {
        MainThreadGuard mainThreadGuard = MainThreadGuard.captureCurrentThread();
        ShutdownCoordinator shutdownCoordinator = new ShutdownCoordinator();
        ShutdownBarrier shutdownBarrier =
                new ShutdownBarrier(5, TimeUnit.SECONDS);
        Throwable primaryFailure = null;
        try {
            AssetManager assetManager =
                    new AssetManager(
                            GameBootstrap.class.getClassLoader());
            GaiaAssetCatalog catalog =
                    new GaiaResourceLoader(assetManager).load();
            GaiaUiAssets uiAssets = new GaiaUiAssetLoader(assetManager).load();
            logAssetReport(catalog.report());

            RenderVisualSettings visualSettings = RenderVisualSettings.milestoneOneDefaults();
            Engine engine =
                    new Engine(
                            mainThreadGuard,
                            catalog.renderAssets(),
                            assetManager,
                            visualSettings);
            engine.init();
            shutdownCoordinator.register(
                    "engine",
                    () -> shutdownBarrier.closeEngine(engine::shutdown));
            engine.getRenderer().installUiAssets(uiAssets.renderAssets());

            InputManager inputManager = new InputManager(mainThreadGuard);
            inputManager.install(engine.getWindow().getWindow());

            BlockCollisionShapeResolver shapes =
                    BlockCollisionShapeResolver.fullCubesForNonAir();
            CollisionWorld collisionWorld =
                    new CollisionWorld(engine.getWorld(), shapes);
            BlockRaycast blockRaycast =
                    new BlockRaycast(engine.getWorld(), shapes);
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
                            new Vector3f(
                                    0,
                                    GameConfig.Physics.GRAVITY,
                                    0));
            PlayerManager playerManager =
                    new PlayerManager(
                            engine.getCamera(), playerController);
            ModuleManager.getInstance().initAll();

            FrameClock frameClock =
                    new FrameClock(System::nanoTime, MAX_FRAME_DELTA_SECONDS);
            FixedStepClock fixedStepClock =
                    new FixedStepClock(
                            FIXED_STEP_SECONDS, MAX_FIXED_STEPS_PER_FRAME);

            BlockRegistry blocks = catalog.blockRegistry();
            TextRenderer hudText = new TextRenderer(
                    uiAssets.renderAssets().glyphs(),
                    codePoint -> System.err.println(
                            "[UI] Missing glyph for U+"
                                    + Integer.toHexString(codePoint)
                                            .toUpperCase(java.util.Locale.ROOT)));
            HudFrameCoordinator hudFrames = new HudFrameCoordinator(
                    new HudPresenter(blocks::itemForm),
                    new GaiaHudScreen(
                            new UiIconResolver(
                                    uiAssets.icons(),
                                    item -> System.err.println(
                                            "[UI] Missing icon for " + item)),
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
            ParticleSystem particles = new ParticleSystem();
            WorldItemVisualTracker worldItemVisuals =
                    new WorldItemVisualTracker(visualRegions::resolve);
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
                            visualRegions::resolve);
            shutdownCoordinator.register("interaction-feedback", feedback::clearAll);
            BodyInventoryInputController inventoryInput =
                    new BodyInventoryInputController(
                            inventoryService,
                            inventoryOwner,
                            Optional.of(new InventoryDropController(
                                    inventoryService, worldItems)));
            GaiaBlockWorldAccess blockWorld =
                    new GaiaBlockWorldAccess(engine.getWorld(), blocks);
            DefaultWorldMutationService worldMutations =
                    new DefaultWorldMutationService(
                            mainThreadGuard,
                            blockWorld,
                            new SynchronousBlockChangeEventPublisher(
                                    ignored -> BlockChangeDecision.ALLOW,
                                    feedback::onBlockChanged,
                                    ignored -> {}));
            PlayerBlockTargeting blockTargeting =
                    new PlayerBlockTargeting(
                            new GaiaBlockRaycastService(blockRaycast, blocks),
                            playerBody,
                            engine.getCamera(),
                            engine.getWorld().chunks(),
                            GameConfig.Player.EYE_HEIGHT,
                            GameConfig.Interaction.REACH);
            GameModeManager gameModes =
                    new GameModeManager(
                            GameMode.SURVIVAL,
                            EventBus.getInstance()::publish);
            CreativeSelection creativeSelection =
                    new CreativeSelection(
                            blocks,
                            Optional.of(ResourceLocation.parse("gaia:dirt")));
            BlockInteractionController blockInteraction =
                    new BlockInteractionController(
                            gameModes,
                            blockTargeting,
                            engine.getWorld().chunks(),
                            blocks,
                            inventoryService,
                            inventoryOwner,
                            creativeSelection,
                            new BlockBreakTransaction(
                                    worldMutations,
                                    inventoryService,
                                    inventoryOwner,
                                    worldItems,
                                    ResourceLocation.parse("gaia:air")),
                            new BlockPlacementTransaction(
                                    worldMutations,
                                    inventoryService,
                                    inventoryOwner,
                                    blocks,
                                    blockWorld,
                                    playerBody,
                                    ResourceLocation.parse("gaia:air")),
                            GameConfig.Interaction.BASE_BREAK_SPEED);
            runConfiguredInventoryDebugCommand(inventoryDebugCommands);
            WorldGenerator generator =
                    GaiaWorldGenerator.createVisualRevisionCandidate();
            WorldGenerationConfig worldGenerationConfig =
                    WorldGenerationConfig.visualRevisionCandidate();
            ExecutorService meshExecutor =
                    Executors.newFixedThreadPool(
                            2,
                            namedThreadFactory("Gaia-Chunk-Mesher"));
            ChunkMeshManager chunkMeshes =
                    shutdownBarrier.registerChunkMeshes(
                            shutdownCoordinator,
                            meshExecutor,
                            () ->
                                    new ChunkMeshManager(
                                            engine.getWorld().chunks(),
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
                                        new Thread(runnable, "Gaia-World-Loader");
                                thread.setDaemon(true);
                                return thread;
                            });
            shutdownBarrier.registerWorldExecutor(
                    shutdownCoordinator, worldExecutor);

            WorldLoader worldLoader =
                    new WorldLoader(
                            generator,
                            blocks,
                            worldGenerationConfig,
                            new SafeSpawnSelector(),
                            worldExecutor);

            CompletableFuture<WorldLoadResult> worldLoad =
                    worldLoader.loadAsync(engine.getWorld());
            shutdownCoordinator.register(
                    "world-load", () -> worldLoad.cancel(true));

            GameContext context =
                    new GameContext(
                            engine,
                            inputManager,
                            playerManager,
                            physicsWorld,
                            collisionWorld,
                            playerController,
                            blockRaycast,
                            frameClock,
                            fixedStepClock,
                            chunkMeshes,
                            worldLoader,
                            worldLoad,
                            inventoryOwner,
                            inventoryService,
                            inventoryInput,
                            inventoryDebugCommands,
                            Boolean.getBoolean("gaia.inventory.debugShortcuts"),
                            blockInteraction,
                            worldItems,
                            feedback,
                            InteractionBlockState.unblocked(),
                            hudFrames,
                            shutdownCoordinator,
                            new RenderMetricsConsoleReporter(Boolean.getBoolean("gaia.renderMetrics"), System::nanoTime, System.out));
            new GameLoop(context).run();
        } catch (RuntimeException | Error failure) {
            primaryFailure = failure;
            throw failure;
        } finally {
            closeAfterRun(shutdownCoordinator, primaryFailure);
        }
    }

    static void logAssetReport(AssetLoadReport report) {
        for (AssetDiagnostic diagnostic : report.diagnostics()) {
            StringBuilder line =
                    new StringBuilder()
                            .append(diagnostic.severity())
                            .append(' ')
                            .append(diagnostic.code())
                            .append(" source=")
                            .append(diagnostic.source());
            if (diagnostic.resource() != null) {
                line.append(" resource=")
                        .append(diagnostic.resource());
            }
            if (diagnostic.field() != null) {
                line.append(" field=")
                        .append(diagnostic.field());
            }
            line.append(" message=")
                    .append(diagnostic.message());
            if (diagnostic.fallback() != null) {
                line.append(" fallback=")
                        .append(diagnostic.fallback());
            }
            System.out.println(line);
        }
    }

    private static InventoryDebugCommands createInventoryDebugCommands(
            BodyInventoryService inventoryService, EntityRef owner) {
        DebugInventoryProfile profile = new DebugInventoryProfile(
                new ItemStack(ResourceLocation.parse("gaia:dirt"), 12),
                new ItemStack(ResourceLocation.parse("gaia:dirt"), 64),
                new ItemStack(ResourceLocation.parse("gaia:stone"), 64),
                new ItemStack(ResourceLocation.parse("gaia:oak_leaves"), 1));
        return new InventoryDebugCommands(
                new InventoryDebugSeeder(inventoryService, owner, profile),
                inventoryService,
                owner,
                new InventorySnapshotFormatter());
    }

    private static void runConfiguredInventoryDebugCommand(
            InventoryDebugCommands commands) {
        String command = System.getProperty("gaia.inventory.debugCommand");
        if (command == null || command.isBlank()) {
            return;
        }
        System.out.println("[InventoryDebug] " + commands.execute(command).message());
    }

    static void closeAfterRun(
            ShutdownCoordinator shutdownCoordinator, Throwable primaryFailure) {
        try {
            shutdownCoordinator.close();
        } catch (RuntimeException | Error cleanupFailure) {
            if (primaryFailure == null) {
                throw cleanupFailure;
            }
            primaryFailure.addSuppressed(cleanupFailure);
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
                long remainingNanos =
                        deadline - System.nanoTime();
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
        return new IllegalStateException(
                message, firstInterruption);
    }

    private static java.util.concurrent.ThreadFactory namedThreadFactory(
            String name) {
        return runnable -> {
            Thread thread = new Thread(runnable, name);
            thread.setDaemon(true);
            return thread;
        };
    }

    static final class ShutdownBarrier {
        private final long timeout;
        private final TimeUnit timeoutUnit;
        private boolean managerCleanupRequired;
        private boolean worldExecutorRequired;
        private boolean worldExecutorStopped;
        private boolean meshExecutorStopped;
        private boolean managerCleanupAttempted;

        ShutdownBarrier(long timeout, TimeUnit unit) {
            if (timeout < 0) {
                throw new IllegalArgumentException(
                        "timeout must not be negative");
            }
            this.timeout = timeout;
            timeoutUnit =
                    Objects.requireNonNull(unit, "unit");
        }

        <T> T registerChunkMeshes(
                ShutdownCoordinator shutdownCoordinator,
                ExecutorService meshExecutor,
                Supplier<T> managerFactory,
                Consumer<T> managerCleanup) {
            Objects.requireNonNull(
                    shutdownCoordinator, "shutdownCoordinator");
            Objects.requireNonNull(
                    meshExecutor, "meshExecutor");
            Objects.requireNonNull(
                    managerFactory, "managerFactory");
            Objects.requireNonNull(
                    managerCleanup, "managerCleanup");

            boolean meshCleanupRegistered = false;
            try {
                T manager =
                        Objects.requireNonNull(
                                managerFactory.get(),
                                "chunk mesh manager");
                shutdownCoordinator.register(
                        "chunk-meshes",
                        () ->
                                closeManager(
                                        () ->
                                                managerCleanup.accept(
                                                        manager)));
                managerCleanupRequired = true;
                shutdownCoordinator.register(
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
                ShutdownCoordinator shutdownCoordinator,
                ExecutorService worldExecutor) {
            Objects.requireNonNull(
                    shutdownCoordinator, "shutdownCoordinator");
            Objects.requireNonNull(
                    worldExecutor, "worldExecutor");
            try {
                shutdownCoordinator.register(
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
            Objects.requireNonNull(
                    managerCleanup, "managerCleanup");
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

        void closeEngine(Runnable engineCleanup) {
            Objects.requireNonNull(
                    engineCleanup, "engineCleanup");
            if (managerCleanupRequired
                    && !managerCleanupAttempted) {
                throw new IllegalStateException(
                        "Engine cleanup was skipped because chunk mesh "
                                + "manager cleanup could not safely run");
            }
            engineCleanup.run();
        }
    }
}

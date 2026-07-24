package com.gaia;

import com.gaia.assets.GaiaAssetCatalog;
import com.gaia.assets.GaiaResourceLoader;
import com.gaia.blocks.BlockRegistry;
import com.gaia.world.GaiaWorldGenerator;
import com.gaia.world.WorldLoadResult;
import com.gaia.world.WorldLoader;
import com.overlord.assets.AssetDiagnostic;
import com.overlord.assets.AssetLoadReport;
import com.overlord.assets.AssetManager;
import com.overlord.assets.ResourceLocation;
import com.overlord.core.Engine;
import com.overlord.core.ModuleManager;
import com.overlord.core.PlayerManager;
import com.overlord.core.input.InputManager;
import com.overlord.core.lifecycle.ShutdownCoordinator;
import com.overlord.core.thread.MainThreadGuard;
import com.overlord.core.time.FixedStepClock;
import com.overlord.core.time.FrameClock;
import com.overlord.physics.PhysicsManager;
import com.overlord.voxel.ChunkMeshBuilder;
import com.overlord.voxel.ChunkMeshManager;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;

public final class GameBootstrap {
    private static final double FIXED_STEP_SECONDS = 1.0 / 60.0;
    private static final int MAX_FIXED_STEPS_PER_FRAME = 5;
    private static final double MAX_FRAME_DELTA_SECONDS = 0.25;

    public void run() {
        MainThreadGuard mainThreadGuard = MainThreadGuard.captureCurrentThread();
        ShutdownCoordinator shutdownCoordinator = new ShutdownCoordinator();
        ShutdownBarrier shutdownBarrier =
                new ShutdownBarrier(5, TimeUnit.SECONDS);
        Throwable primaryFailure = null;
        try {
            ClassLoader classLoader =
                    GameBootstrap.class.getClassLoader();
            GaiaAssetCatalog catalog =
                    new GaiaResourceLoader(
                                    new AssetManager(classLoader))
                            .load();
            logAssetReport(catalog.report());

            Engine engine =
                    new Engine(
                            mainThreadGuard,
                            catalog.renderAssets());
            engine.init();
            shutdownCoordinator.register(
                    "engine",
                    () -> shutdownBarrier.closeEngine(engine::shutdown));

            InputManager inputManager = new InputManager(mainThreadGuard);
            inputManager.install(engine.getWindow().getWindow());

            PhysicsManager physicsManager =
                    new PhysicsManager(engine.getCamera(), engine.getWorld());
            PlayerManager playerManager =
                    new PlayerManager(engine.getCamera(), physicsManager);
            ModuleManager.getInstance().initAll();

            FrameClock frameClock =
                    new FrameClock(System::nanoTime, MAX_FRAME_DELTA_SECONDS);
            FixedStepClock fixedStepClock =
                    new FixedStepClock(
                            FIXED_STEP_SECONDS, MAX_FIXED_STEPS_PER_FRAME);

            BlockRegistry blocks = catalog.blockRegistry();
            GaiaWorldGenerator generator =
                    new GaiaWorldGenerator(blocks);
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

            byte fallbackGroundId =
                    blocks.requireStoredId(
                            ResourceLocation.parse("gaia:grass"));
            WorldLoader worldLoader =
                    new WorldLoader(
                            generator,
                            fallbackGroundId);

            ExecutorService worldExecutor =
                    Executors.newSingleThreadExecutor(
                            runnable -> {
                                Thread thread =
                                        new Thread(runnable, "Gaia-World-Loader");
                                thread.setDaemon(true);
                                return thread;
                            });
            shutdownCoordinator.register(
                    "world-executor",
                    () ->
                            shutdownExecutor(
                                    worldExecutor,
                                    "World loader executor",
                                    5,
                                    TimeUnit.SECONDS));

            CompletableFuture<WorldLoadResult> worldLoad =
                    CompletableFuture.supplyAsync(
                            () -> worldLoader.load(engine.getWorld()),
                            worldExecutor);
            shutdownCoordinator.register(
                    "world-load", () -> worldLoad.cancel(true));

            GameContext context =
                    new GameContext(
                            engine,
                            inputManager,
                            playerManager,
                            physicsManager,
                            frameClock,
                            fixedStepClock,
                            chunkMeshes,
                            worldLoad,
                            shutdownCoordinator);
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
            if (!meshExecutorStopped) {
                throw new IllegalStateException(
                        "Chunk mesh manager cleanup was skipped because "
                                + "mesh executor termination was not confirmed");
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

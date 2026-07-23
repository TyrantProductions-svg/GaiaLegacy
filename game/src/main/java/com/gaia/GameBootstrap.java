package com.gaia;

import com.gaia.blocks.BlockRegistry;
import com.gaia.world.WorldLoadResult;
import com.gaia.world.WorldLoader;
import com.overlord.core.Engine;
import com.overlord.core.ModuleManager;
import com.overlord.core.PlayerManager;
import com.overlord.core.input.InputManager;
import com.overlord.core.lifecycle.ShutdownCoordinator;
import com.overlord.core.thread.MainThreadGuard;
import com.overlord.core.time.FixedStepClock;
import com.overlord.core.time.FrameClock;
import com.overlord.physics.PhysicsManager;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class GameBootstrap {
    private static final double FIXED_STEP_SECONDS = 1.0 / 60.0;
    private static final int MAX_FIXED_STEPS_PER_FRAME = 5;
    private static final double MAX_FRAME_DELTA_SECONDS = 0.25;

    public void run() {
        MainThreadGuard mainThreadGuard = MainThreadGuard.captureCurrentThread();
        ShutdownCoordinator shutdownCoordinator = new ShutdownCoordinator();
        Throwable primaryFailure = null;
        try {
            BlockRegistry.init();
            BlockRegistry.loadAllFromResources();

            Engine engine = new Engine(mainThreadGuard);
            engine.init();
            shutdownCoordinator.register("engine", engine::shutdown);

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

            ExecutorService worldExecutor =
                    Executors.newSingleThreadExecutor(
                            runnable -> {
                                Thread thread =
                                        new Thread(runnable, "Gaia-World-Loader");
                                thread.setDaemon(true);
                                return thread;
                            });
            shutdownCoordinator.register(
                    "world-executor", () -> shutdownExecutor(worldExecutor));

            CompletableFuture<WorldLoadResult> worldLoad =
                    CompletableFuture.supplyAsync(
                            () -> new WorldLoader().load(engine.getWorld()),
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

    private static void shutdownExecutor(ExecutorService executor) {
        executor.shutdownNow();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException(
                        "World loader did not terminate within five seconds");
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "Interrupted while stopping world loader", failure);
        }
    }
}

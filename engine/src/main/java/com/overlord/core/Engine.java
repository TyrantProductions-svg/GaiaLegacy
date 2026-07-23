package com.overlord.core;

import com.overlord.config.GameConfig;
import com.overlord.core.thread.MainThreadGuard;
import com.overlord.event.EventBus;
import com.overlord.renderer.Camera;
import com.overlord.renderer.RenderAssets;
import com.overlord.renderer.Renderer;
import com.overlord.voxel.World;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

public class Engine {
    public static final int CORE_RENDER = GameConfig.Core.RENDER;
    public static final int CORE_PLAYER = GameConfig.Core.PLAYER;
    public static final int CORE_WORLD = GameConfig.Core.WORLD;
    public static final int CORE_PHYSICS = GameConfig.Core.PHYSICS;

    private final int availableCores;
    private final TaskScheduler taskScheduler;
    private final MainThreadGuard mainThreadGuard;
    private final RenderAssets renderAssets;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private Window window;
    private Camera camera;
    private Renderer renderer;
    private World world;

    public Engine() {
        this(
                MainThreadGuard.captureCurrentThread(),
                RenderAssets.missing());
    }

    public Engine(MainThreadGuard mainThreadGuard) {
        this(mainThreadGuard, RenderAssets.missing());
    }

    public Engine(
            MainThreadGuard mainThreadGuard,
            RenderAssets renderAssets) {
        this.mainThreadGuard =
                Objects.requireNonNull(mainThreadGuard, "mainThreadGuard");
        this.renderAssets =
                Objects.requireNonNull(renderAssets, "renderAssets");
        int maxCores = Runtime.getRuntime().availableProcessors();
        availableCores = Math.min(4, Math.max(1, maxCores));
        taskScheduler = new TaskScheduler(availableCores);
    }

    public void init() {
        mainThreadGuard.assertMainThread("engine initialization");
        if (closed.get()) {
            throw new IllegalStateException("Engine has already been closed");
        }
        if (running.get()) {
            throw new IllegalStateException("Engine is already initialized");
        }

        Window initializedWindow = null;
        Renderer initializedRenderer = null;
        boolean schedulerStarted = false;
        try {
            initializedWindow = new Window(mainThreadGuard);
            Camera initializedCamera = new Camera();
            initializedRenderer =
                    new Renderer(mainThreadGuard, renderAssets);
            World initializedWorld = new World();

            initializedRenderer.init(
                    initializedCamera,
                    initializedWindow.getFramebufferWidth(),
                    initializedWindow.getFramebufferHeight());
            initializedWindow.setCursorCaptured(true);
            schedulerStarted = true;
            taskScheduler.start();

            window = initializedWindow;
            camera = initializedCamera;
            renderer = initializedRenderer;
            world = initializedWorld;
            registerServices();
            running.set(true);

            System.out.println("[Engine] Initialized with " + availableCores + " cores");
        } catch (RuntimeException | Error failure) {
            running.set(false);
            closed.set(true);
            ServiceLocator.getInstance().clear();
            EventBus.getInstance().clear();
            if (schedulerStarted) {
                suppressCleanup(taskScheduler::shutdown, failure);
            }
            if (initializedRenderer != null) {
                Renderer rendererToClean = initializedRenderer;
                suppressCleanup(rendererToClean::cleanup, failure);
            }
            if (initializedWindow != null) {
                Window windowToDestroy = initializedWindow;
                suppressCleanup(windowToDestroy::destroy, failure);
            }
            window = null;
            camera = null;
            renderer = null;
            world = null;
            throw failure;
        }
    }

    private void registerServices() {
        ServiceLocator services = ServiceLocator.getInstance();
        services.register(Engine.class, this);
        services.register(EventBus.class, EventBus.getInstance());
        services.register(ModuleManager.class, ModuleManager.getInstance());
        services.register(TaskScheduler.class, taskScheduler);
        services.register(Window.class, window);
        services.register(Camera.class, camera);
        services.register(Renderer.class, renderer);
        services.register(World.class, world);
    }

    public void submitToCore(int core, Runnable task) {
        taskScheduler.submit(task, core);
    }

    public void submitToCore(int core, Runnable task, TaskScheduler.TaskPriority priority) {
        taskScheduler.submit(task, core, priority);
    }

    public int getAvailableCores() {
        return availableCores;
    }

    public MainThreadGuard getMainThreadGuard() {
        return mainThreadGuard;
    }

    public Window getWindow() {
        return window;
    }

    public Camera getCamera() {
        return camera;
    }

    public Renderer getRenderer() {
        return renderer;
    }

    public World getWorld() {
        return world;
    }

    public boolean isRunning() {
        return running.get();
    }

    public void shutdown() {
        mainThreadGuard.assertMainThread("engine shutdown");
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        running.set(false);

        Throwable firstFailure = null;
        firstFailure = runCleanup(ModuleManager.getInstance()::shutdownAll, firstFailure);
        firstFailure = runCleanup(taskScheduler::shutdown, firstFailure);
        if (renderer != null) {
            firstFailure = runCleanup(renderer::cleanup, firstFailure);
        }
        if (window != null) {
            firstFailure = runCleanup(window::destroy, firstFailure);
        }
        firstFailure = runCleanup(ServiceLocator.getInstance()::clear, firstFailure);
        firstFailure = runCleanup(EventBus.getInstance()::clear, firstFailure);

        if (firstFailure != null) {
            rethrow(firstFailure);
        }
    }

    private static Throwable runCleanup(Runnable cleanup, Throwable firstFailure) {
        try {
            cleanup.run();
        } catch (RuntimeException | Error failure) {
            if (firstFailure == null) {
                return failure;
            }
            firstFailure.addSuppressed(failure);
        }
        return firstFailure;
    }

    private static void suppressCleanup(Runnable cleanup, Throwable primaryFailure) {
        try {
            cleanup.run();
        } catch (RuntimeException | Error cleanupFailure) {
            primaryFailure.addSuppressed(cleanupFailure);
        }
    }

    private static void rethrow(Throwable failure) {
        if (failure instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
        throw (Error) failure;
    }
}

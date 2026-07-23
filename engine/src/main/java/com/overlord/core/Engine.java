package com.overlord.core;

import com.overlord.config.GameConfig;
import com.overlord.event.EventBus;
import com.overlord.renderer.Camera;
import com.overlord.renderer.Renderer;
import com.overlord.voxel.World;

import java.util.concurrent.atomic.AtomicBoolean;

public class Engine {
    
    /**
     * Core assignments:
     * Core 0 (CORE_RENDER):  Rendering pipeline (OpenGL, shaders, meshes)
     * Core 1 (CORE_PLAYER):  Player input + Audio system (music, sound effects)
     * Core 2 (CORE_WORLD):   World generation + Block updates (place/break)
     * Core 3 (CORE_PHYSICS): Physics + Entity AI (collision, mob behavior)
     */
    public static final int CORE_RENDER = GameConfig.Core.RENDER;
    public static final int CORE_PLAYER = GameConfig.Core.PLAYER;
    public static final int CORE_WORLD = GameConfig.Core.WORLD;
    public static final int CORE_PHYSICS = GameConfig.Core.PHYSICS;
    
    private final int availableCores;
    private final TaskScheduler taskScheduler;
    private final AtomicBoolean running = new AtomicBoolean(false);
    
    private Window window;
    private Camera camera;
    private Renderer renderer;
    private World world;
    
    public Engine() {
        int maxCores = Runtime.getRuntime().availableProcessors();
        this.availableCores = Math.min(4, Math.max(1, maxCores));
        
        this.taskScheduler = new TaskScheduler(availableCores);
        
        ServiceLocator.getInstance().register(Engine.class, this);
        ServiceLocator.getInstance().register(EventBus.class, EventBus.getInstance());
        ServiceLocator.getInstance().register(ModuleManager.class, ModuleManager.getInstance());
        ServiceLocator.getInstance().register(TaskScheduler.class, taskScheduler);
        
        System.out.println("[Engine] Initialized with " + availableCores + " cores");
    }
    
    public void init() {
        window = new Window();
        camera = new Camera();
        renderer = new Renderer();
        world = new World();
        
        renderer.init(camera, window.getWidth(), window.getHeight());
        window.setCursorCaptured(true);
        
        ServiceLocator.getInstance().register(Window.class, window);
        ServiceLocator.getInstance().register(Camera.class, camera);
        ServiceLocator.getInstance().register(Renderer.class, renderer);
        ServiceLocator.getInstance().register(World.class, world);
        
        taskScheduler.start();
        
        running.set(true);
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
        running.set(false);
        
        ModuleManager.getInstance().shutdownAll();
        taskScheduler.shutdown();
        
        renderer.cleanup();
        window.destroy();
        
        ServiceLocator.getInstance().clear();
        EventBus.getInstance().clear();
    }
}
package com.overlord.core;

import com.overlord.config.GameConfig;
import com.overlord.renderer.Camera;
import com.overlord.renderer.Renderer;
import com.overlord.voxel.World;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
    private final ExecutorService[] coreThreads;
    private final AtomicBoolean running = new AtomicBoolean(false);
    
    private Window window;
    private Camera camera;
    private Renderer renderer;
    private World world;
    
    public Engine() {
        int maxCores = Runtime.getRuntime().availableProcessors();
        this.availableCores = Math.min(4, Math.max(1, maxCores));
        
        coreThreads = new ExecutorService[availableCores];
        for (int i = 0; i < availableCores; i++) {
            final int coreIndex = i;
            coreThreads[i] = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "Core-" + coreIndex);
                t.setDaemon(true);
                return t;
            });
        }
        
        System.out.println("[Engine] Initialized with " + availableCores + " cores");
    }
    
    public void init() {
        window = new Window();
        camera = new Camera();
        renderer = new Renderer();
        world = new World();
        
        renderer.init(camera, window.getWidth(), window.getHeight());
        window.setCursorCaptured(true);
        
        running.set(true);
    }
    
    public void submitToCore(int core, Runnable task) {
        if (core >= availableCores) {
            core = core % availableCores;
        }
        coreThreads[core].submit(task);
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
        for (ExecutorService core : coreThreads) {
            core.shutdownNow();
        }
        renderer.cleanup();
        window.destroy();
    }
}
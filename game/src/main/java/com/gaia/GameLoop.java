package com.gaia;

import com.gaia.GameBootstrap.GameContext;
import com.overlord.core.ModuleManager;
import com.overlord.core.TaskScheduler;
import com.overlord.event.EventBus;
import com.overlord.renderer.Mesh;

import static org.lwjgl.glfw.GLFW.*;

public class GameLoop {
    
    private final GameContext context;
    
    private int fps = 0;
    private int frameCount = 0;
    private long lastFpsTime = System.nanoTime();
    
    public GameLoop(GameContext context) {
        this.context = context;
        setupMouseCallback();
        
        ModuleManager.getInstance().initAll();
    }
    
    public void run() {
        while (context.engine.isRunning()) {
            context.engine.submitToCore(
                com.overlord.core.Engine.CORE_PLAYER,
                context.playerManager::update,
                TaskScheduler.TaskPriority.HIGH
            );
            
            ModuleManager.getInstance().updateAll(1.0f / 60.0f);
            
            EventBus.getInstance().processAll();
            
            if (context.playerManager.shouldClose()) {
                break;
            }
            
            calculateFps();
            updateMesh();
            render();
        }
        
        cleanup();
        context.engine.shutdown();
        System.out.println("[GaiaLegacy] Shutdown complete.");
    }
    
    private void setupMouseCallback() {
        long windowHandle = context.engine.getWindow().getWindow();
        
        glfwSetCursorPosCallback(windowHandle, (win, xpos, ypos) -> {
            context.playerManager.onMouseMove(xpos, ypos);
        });
    }
    
    private void calculateFps() {
        frameCount++;
        long currentTime = System.nanoTime();
        
        if (currentTime - lastFpsTime >= 1_000_000_000L) {
            fps = frameCount;
            frameCount = 0;
            lastFpsTime = currentTime;
        }
    }
    
    private void updateMesh() {
        float[] pendingData = context.chunkMeshData.getAndSet(null);
        
        if (pendingData != null && pendingData.length > 0) {
            if (context.chunkMesh.get() != null) {
                context.chunkMesh.get().cleanup();
            }
            context.chunkMesh.set(new Mesh(pendingData));
            context.engine.getRenderer().setMesh(context.chunkMesh.get());
        }
    }
    
    private void render() {
        context.engine.getRenderer().render();
        glfwSwapBuffers(context.engine.getWindow().getWindow());
        glfwPollEvents();
    }
    
    private void cleanup() {
        if (context.chunkMesh.get() != null) {
            context.chunkMesh.get().cleanup();
        }
    }
    
    public int getFps() {
        return fps;
    }
}
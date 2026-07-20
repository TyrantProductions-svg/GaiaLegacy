package com.overlord.core;

import com.overlord.renderer.Camera;

import static org.lwjgl.glfw.GLFW.*;

public class PlayerManager {
    
    private final Camera camera;
    private final long windowHandle;
    
    private long lastTime;
    
    public PlayerManager(Camera camera, long windowHandle) {
        this.camera = camera;
        this.windowHandle = windowHandle;
        this.lastTime = System.nanoTime();
    }
    
    public void update() {
        long currentTime = System.nanoTime();
        float deltaTime = (currentTime - lastTime) / 1_000_000_000.0f;
        lastTime = currentTime;
        
        if (glfwGetKey(windowHandle, GLFW_KEY_W) == GLFW_PRESS) {
            camera.processKeyboard(0, deltaTime);
        }
        if (glfwGetKey(windowHandle, GLFW_KEY_S) == GLFW_PRESS) {
            camera.processKeyboard(1, deltaTime);
        }
        if (glfwGetKey(windowHandle, GLFW_KEY_A) == GLFW_PRESS) {
            camera.processKeyboard(2, deltaTime);
        }
        if (glfwGetKey(windowHandle, GLFW_KEY_D) == GLFW_PRESS) {
            camera.processKeyboard(3, deltaTime);
        }
        if (glfwGetKey(windowHandle, GLFW_KEY_SPACE) == GLFW_PRESS) {
            camera.processKeyboard(4, deltaTime);
        }
        if (glfwGetKey(windowHandle, GLFW_KEY_LEFT_SHIFT) == GLFW_PRESS) {
            camera.processKeyboard(5, deltaTime);
        }
    }
    
    public boolean shouldClose() {
        return glfwGetKey(windowHandle, GLFW_KEY_ESCAPE) == GLFW_PRESS;
    }
}
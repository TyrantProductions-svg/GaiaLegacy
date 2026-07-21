package com.overlord.core;

import com.overlord.physics.PhysicsManager;
import com.overlord.renderer.Camera;

import static org.lwjgl.glfw.GLFW.*;

public class PlayerManager {
    
    private static final float MOVEMENT_SPEED = 5.0f;
    
    private final Camera camera;
    private final PhysicsManager physicsManager;
    private final long windowHandle;
    
    private long lastTime;
    private boolean jumpPressed = false;
    private boolean spaceTogglePressed = false;
    private long lastSpacePressTime = 0;
    private int spacePressCount = 0;
    
    public PlayerManager(Camera camera, PhysicsManager physicsManager, long windowHandle) {
        this.camera = camera;
        this.physicsManager = physicsManager;
        this.windowHandle = windowHandle;
        this.lastTime = System.nanoTime();
    }
    
    public void update() {
        long currentTime = System.nanoTime();
        float deltaTime = (currentTime - lastTime) / 1_000_000_000.0f;
        lastTime = currentTime;
        
        org.joml.Vector3f forward = camera.getForward();
        org.joml.Vector3f right = camera.getRight();
        
        forward.y = 0;
        forward.normalize();
        right.y = 0;
        right.normalize();
        
        org.joml.Vector3f movement = new org.joml.Vector3f();
        
        if (glfwGetKey(windowHandle, GLFW_KEY_W) == GLFW_PRESS) {
            movement.add(forward);
        }
        if (glfwGetKey(windowHandle, GLFW_KEY_S) == GLFW_PRESS) {
            movement.sub(forward);
        }
        if (glfwGetKey(windowHandle, GLFW_KEY_A) == GLFW_PRESS) {
            movement.sub(right);
        }
        if (glfwGetKey(windowHandle, GLFW_KEY_D) == GLFW_PRESS) {
            movement.add(right);
        }
        
        if (movement.length() > 0) {
            movement.normalize();
            movement.mul(MOVEMENT_SPEED * deltaTime);
            
            org.joml.Vector3f pos = camera.getPosition();
            pos.x += movement.x;
            pos.z += movement.z;
        }
        
        boolean spacePressed = glfwGetKey(windowHandle, GLFW_KEY_SPACE) == GLFW_PRESS;
        if (spacePressed && !spaceTogglePressed) {
            long spacePressTime = System.nanoTime();
            long timeDiff = (spacePressTime - lastSpacePressTime) / 1_000_000;
            
            if (timeDiff < 300) {
                spacePressCount++;
                if (spacePressCount >= 2) {
                    physicsManager.toggleNoclip();
                    spacePressCount = 0;
                }
            } else {
                spacePressCount = 1;
            }
            lastSpacePressTime = spacePressTime;
            
            if (!physicsManager.isNoclip()) {
                physicsManager.jump();
            }
        }
        spaceTogglePressed = spacePressed;
        
        if (physicsManager.isNoclip()) {
            if (spacePressed) {
                physicsManager.applyNoclipMovement(deltaTime, 1.0f);
            }
            if (glfwGetKey(windowHandle, GLFW_KEY_LEFT_SHIFT) == GLFW_PRESS) {
                physicsManager.applyNoclipMovement(deltaTime, -1.0f);
            }
        }
    }
    
    public boolean shouldClose() {
        return glfwGetKey(windowHandle, GLFW_KEY_ESCAPE) == GLFW_PRESS;
    }
}
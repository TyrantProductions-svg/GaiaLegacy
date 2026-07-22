package com.overlord.core;

import com.overlord.physics.PhysicsManager;
import com.overlord.renderer.Camera;

import static org.lwjgl.glfw.GLFW.*;

public class PlayerManager {
    
    private static final float MOVEMENT_SPEED = 5.0f;
    private static final float JUMP_VELOCITY = 13.0f;
    
    private final Camera camera;
    private final PhysicsManager physicsManager;
    private final long windowHandle;
    
    private long lastTime;
    private boolean jumpPressed = false;
    private boolean noclipTogglePressed = false;
    
    private double lastMouseX = 0;
    private double lastMouseY = 0;
    private boolean firstMouse = true;

    public PlayerManager(Camera camera, PhysicsManager physicsManager, long windowHandle) {
        this.camera = camera;
        this.physicsManager = physicsManager;
        this.windowHandle = windowHandle;
        this.lastTime = System.nanoTime();
    }
    
    private void processMouseInput() {
        double[] mouseX = new double[1];
        double[] mouseY = new double[1];
        glfwGetCursorPos(windowHandle, mouseX, mouseY);
        
        if (firstMouse) {
            lastMouseX = mouseX[0];
            lastMouseY = mouseY[0];
            firstMouse = false;
        }
        
        double xOffset = mouseX[0] - lastMouseX;
        double yOffset = lastMouseY - mouseY[0]; // Reversed because y-coords go from bottom to top
        
        lastMouseX = mouseX[0];
        lastMouseY = mouseY[0];
        
        camera.processMouseMovement((float)xOffset, (float)yOffset);
    }

    public void update() {
        long currentTime = System.nanoTime();
        float deltaTime = (currentTime - lastTime) / 1_000_000_000.0f;
        lastTime = currentTime;
        
        processMouseInput();
        
        org.joml.Vector3f forward = camera.getForward();
        org.joml.Vector3f right = camera.getRight();
        
        org.joml.Vector3f horizontalForward = new org.joml.Vector3f(forward.x, 0.0f, forward.z).normalize();
        org.joml.Vector3f horizontalRight = new org.joml.Vector3f(right.x, 0.0f, right.z).normalize();
        
        org.joml.Vector3f movement = new org.joml.Vector3f();
        
        if (glfwGetKey(windowHandle, GLFW_KEY_W) == GLFW_PRESS) {
            movement.add(horizontalForward);
        }
        if (glfwGetKey(windowHandle, GLFW_KEY_S) == GLFW_PRESS) {
            movement.sub(horizontalForward);
        }
        if (glfwGetKey(windowHandle, GLFW_KEY_A) == GLFW_PRESS) {
            movement.sub(horizontalRight);
        }
        if (glfwGetKey(windowHandle, GLFW_KEY_D) == GLFW_PRESS) {
            movement.add(horizontalRight);
        }
        
        if (movement.length() > 0) {
            movement.normalize();
            movement.mul(MOVEMENT_SPEED * deltaTime);
        }
        
        // Pass movement to physics for collision-aware movement
        physicsManager.update(deltaTime, movement.x, movement.z);
        
        boolean spacePressed = glfwGetKey(windowHandle, GLFW_KEY_SPACE) == GLFW_PRESS;
        if (spacePressed && !jumpPressed) {
            if (physicsManager.isNoclip()) {
                physicsManager.applyNoclipMovement(deltaTime, 1.0f);
            } else {
                physicsManager.jump(JUMP_VELOCITY);
            }
        }
        jumpPressed = spacePressed;
        
        boolean shiftPressed = glfwGetKey(windowHandle, GLFW_KEY_LEFT_SHIFT) == GLFW_PRESS;
        if (shiftPressed && physicsManager.isNoclip()) {
            physicsManager.applyNoclipMovement(deltaTime, -1.0f);
        }
        
        boolean fPressed = glfwGetKey(windowHandle, GLFW_KEY_F) == GLFW_PRESS;
        if (fPressed && !noclipTogglePressed) {
            physicsManager.toggleNoclip();
        }
        noclipTogglePressed = fPressed;
    }
    
    public boolean shouldClose() {
        return glfwGetKey(windowHandle, GLFW_KEY_ESCAPE) == GLFW_PRESS;
    }
}
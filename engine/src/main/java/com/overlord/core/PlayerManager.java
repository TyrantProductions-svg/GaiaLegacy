package com.overlord.core;

import com.overlord.config.GameConfig;
import com.overlord.physics.PhysicsManager;
import com.overlord.renderer.Camera;

import static org.lwjgl.glfw.GLFW.*;

public class PlayerManager {
    
    private final Camera camera;
    private final PhysicsManager physicsManager;
    private final long windowHandle;
    
    private long lastTime;
    private boolean jumpPressed = false;
    
    private final org.joml.Vector3f forward = new org.joml.Vector3f();
    private final org.joml.Vector3f right = new org.joml.Vector3f();
    private final org.joml.Vector3f horizontalForward = new org.joml.Vector3f();
    private final org.joml.Vector3f horizontalRight = new org.joml.Vector3f();
    private final org.joml.Vector3f movement = new org.joml.Vector3f();

    private double lastMouseX = 0;
    private double lastMouseY = 0;
    private boolean firstMouse = true;

    public PlayerManager(Camera camera, PhysicsManager physicsManager, long windowHandle) {
        this.camera = camera;
        this.physicsManager = physicsManager;
        this.windowHandle = windowHandle;
        this.lastTime = System.nanoTime();
    }
    
    public void onMouseMove(double xpos, double ypos) {
        double xOffset = xpos - lastMouseX;
        double yOffset = lastMouseY - ypos;
        
        lastMouseX = xpos;
        lastMouseY = ypos;
        
        camera.processMouseMovement((float)xOffset, (float)yOffset);
    }
    
    public void onWindowFocus() {
        firstMouse = true;
    }

    public void update() {
        long currentTime = System.nanoTime();
        float deltaTime = (currentTime - lastTime) / 1_000_000_000.0f;
        lastTime = currentTime;
        
        camera.getForward(forward);
        camera.getRight(right);
        
        horizontalForward.set(forward.x, 0.0f, forward.z).normalize();
        horizontalRight.set(right.x, 0.0f, right.z).normalize();
        
        movement.zero();
        
        if (glfwGetKey(windowHandle, GameConfig.Input.KEY_FORWARD) == GLFW_PRESS) {
            movement.add(horizontalForward);
        }
        if (glfwGetKey(windowHandle, GameConfig.Input.KEY_BACKWARD) == GLFW_PRESS) {
            movement.sub(horizontalForward);
        }
        if (glfwGetKey(windowHandle, GameConfig.Input.KEY_LEFT) == GLFW_PRESS) {
            movement.sub(horizontalRight);
        }
        if (glfwGetKey(windowHandle, GameConfig.Input.KEY_RIGHT) == GLFW_PRESS) {
            movement.add(horizontalRight);
        }
        
        if (movement.lengthSquared() > 0) {
            movement.normalize();
            movement.mul(GameConfig.Player.MOVEMENT_SPEED * deltaTime);
        }
        
        physicsManager.update(deltaTime, movement.x, movement.z);
        
        boolean spacePressed = glfwGetKey(windowHandle, GameConfig.Input.KEY_JUMP) == GLFW_PRESS;
        if (spacePressed && !jumpPressed) {
            physicsManager.jump(GameConfig.Player.JUMP_VELOCITY);
        }
        jumpPressed = spacePressed;
    }
    
    public boolean shouldClose() {
        return glfwGetKey(windowHandle, GameConfig.Input.KEY_CLOSE) == GLFW_PRESS;
    }
}
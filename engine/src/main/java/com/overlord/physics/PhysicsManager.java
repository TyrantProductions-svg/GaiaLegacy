package com.overlord.physics;

import com.overlord.renderer.Camera;
import com.overlord.voxel.World;

public class PhysicsManager {
    
    private static final float GRAVITY = -25.0f;
    private static final float TERMINAL_VELOCITY = -60.0f;
    private static final float JUMP_VELOCITY = 9.0f;
    
    private static final float PLAYER_WIDTH = 0.6f;
    private static final float PLAYER_HEIGHT = 1.8f;
    
    private final Camera camera;
    private final World world;
    
    private float velocityY = 0.0f;
    private boolean onGround = false;
    private boolean noclipMode = false;
    
    public PhysicsManager(Camera camera, World world) {
        this.camera = camera;
        this.world = world;
        this.onGround = true;
        this.velocityY = 0.0f;
    }
    
    public void toggleNoclip() {
        noclipMode = !noclipMode;
        if (noclipMode) {
            velocityY = 0.0f;
            System.out.println("[Physics] Noclip mode ENABLED - flying");
        } else {
            System.out.println("[Physics] Noclip mode DISABLED - gravity enabled");
        }
    }
    
    public boolean isNoclip() {
        return noclipMode;
    }
    
    public void initializeSpawnPosition() {
        org.joml.Vector3f pos = camera.getPosition();
        int playerX = (int) Math.floor(pos.x);
        int playerFeetY = (int) Math.floor(pos.y - PLAYER_HEIGHT);
        int playerZ = (int) Math.floor(pos.z);
        
        System.out.println("[Physics] Initializing spawn at (" + playerX + ", " + (int)pos.y + ", " + playerZ + ")");
        System.out.println("[Physics] Feet would be at Y=" + playerFeetY);
        
        for (int y = playerFeetY; y >= 0; y--) {
            byte block = world.getBlock(playerX, y, playerZ);
            System.out.println("[Physics] Checking block at (" + playerX + ", " + y + ", " + playerZ + ") = " + block);
            if (block != 0) {
                pos.y = y + 1 + PLAYER_HEIGHT - 0.01f;
                onGround = true;
                velocityY = 0.0f;
                System.out.println("[Physics] Spawn initialized at Y=" + pos.y + " (ground at Y=" + y + ")");
                return;
            }
        }
        
        System.out.println("[Physics] Warning: No ground found at spawn! Falling back to Y=50");
        pos.y = 50.0f;
        onGround = false;
    }
    
    public void update(float deltaTime) {
        if (deltaTime <= 0) return;
        
        if (noclipMode) {
            return;
        }
        
        applyGravity(deltaTime);
        resolveCollisions(deltaTime);
    }
    
    public void applyNoclipMovement(float deltaTime, float upDownInput) {
        if (!noclipMode) return;
        
        org.joml.Vector3f pos = camera.getPosition();
        pos.y += upDownInput * 10.0f * deltaTime;
    }
    
    private void applyGravity(float deltaTime) {
        if (!onGround) {
            velocityY += GRAVITY * deltaTime;
            velocityY = Math.max(velocityY, TERMINAL_VELOCITY);
        }
    }
    
    private void resolveCollisions(float deltaTime) {
        org.joml.Vector3f pos = camera.getPosition();
        
        float playerMinX = pos.x - PLAYER_WIDTH / 2;
        float playerMaxX = pos.x + PLAYER_WIDTH / 2;
        float playerMinY = pos.y - PLAYER_HEIGHT;
        float playerMaxY = pos.y;
        float playerMinZ = pos.z - PLAYER_WIDTH / 2;
        float playerMaxZ = pos.z + PLAYER_WIDTH / 2;
        
        int minX = (int) Math.floor(playerMinX);
        int maxX = (int) Math.floor(playerMaxX);
        int minY = (int) Math.floor(playerMinY);
        int maxY = (int) Math.floor(playerMaxY);
        int minZ = (int) Math.floor(playerMinZ);
        int maxZ = (int) Math.floor(playerMaxZ);
        
        boolean collidedX = false;
        boolean collidedY = false;
        boolean collidedZ = false;
        boolean onGroundBlock = false;
        
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    byte block = world.getBlock(x, y, z);
                    if (block == 0) continue;
                    
                    float blockMinX = x;
                    float blockMaxX = x + 1;
                    float blockMinY = y;
                    float blockMaxY = y + 1;
                    float blockMinZ = z;
                    float blockMaxZ = z + 1;
                    
                    if (playerMinX < blockMaxX && playerMaxX > blockMinX &&
                        playerMinY < blockMaxY && playerMaxY > blockMinY &&
                        playerMinZ < blockMaxZ && playerMaxZ > blockMinZ) {
                        
                        float overlapX = Math.min(playerMaxX - blockMinX, blockMaxX - playerMinX);
                        float overlapY = Math.min(playerMaxY - blockMinY, blockMaxY - playerMinY);
                        float overlapZ = Math.min(playerMaxZ - blockMinZ, blockMaxZ - playerMinZ);
                        
                        if (overlapY < overlapX && overlapY < overlapZ) {
                            if (!collidedY) {
                                if (velocityY < 0) {
                                    pos.y = blockMaxY;
                                    onGround = true;
                                } else {
                                    pos.y = blockMinY - PLAYER_HEIGHT;
                                }
                                velocityY = 0;
                                collidedY = true;
                            }
                        } else if (overlapX < overlapZ) {
                            if (!collidedX) {
                                if (playerMaxX - blockMinX < blockMaxX - playerMinX) {
                                    pos.x = blockMinX - PLAYER_WIDTH / 2;
                                } else {
                                    pos.x = blockMaxX + PLAYER_WIDTH / 2;
                                }
                                collidedX = true;
                            }
                        } else {
                            if (!collidedZ) {
                                if (playerMaxZ - blockMinZ < blockMaxZ - playerMinZ) {
                                    pos.z = blockMinZ - PLAYER_WIDTH / 2;
                                } else {
                                    pos.z = blockMaxZ + PLAYER_WIDTH / 2;
                                }
                                collidedZ = true;
                            }
                        }
                    }
                }
            }
        }
        
        int feetBlockX = (int) Math.floor(pos.x);
        int feetBlockY = (int) Math.floor(pos.y - PLAYER_HEIGHT - 0.01f);
        int feetBlockZ = (int) Math.floor(pos.z);
        
        byte blockBelow = world.getBlock(feetBlockX, feetBlockY, feetBlockZ);
        if (blockBelow != 0) {
            float blockTop = feetBlockY + 1;
            float feetY = pos.y - PLAYER_HEIGHT;
            
            if (feetY >= blockTop - 0.1f && feetY <= blockTop + 0.1f && velocityY <= 0) {
                pos.y = blockTop + PLAYER_HEIGHT;
                velocityY = 0;
                onGround = true;
                onGroundBlock = true;
            }
        }
        
        if (!collidedY && !onGroundBlock) {
            pos.y += velocityY * deltaTime;
            onGround = false;
        }
    }
    
    public void jump() {
        if (onGround) {
            velocityY = JUMP_VELOCITY;
            onGround = false;
        }
    }
    
    public boolean isOnGround() {
        return onGround;
    }
}
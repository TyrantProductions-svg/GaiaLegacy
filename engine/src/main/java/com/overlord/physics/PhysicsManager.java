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
    
    public void update(float deltaTime, float moveX, float moveZ) {
        if (deltaTime <= 0) return;
        
        if (noclipMode) {
            return;
        }
        
        resolveCollisions(deltaTime, moveX, moveZ);
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
    
    private void resolveCollisions(float deltaTime, float moveX, float moveZ) {
        org.joml.Vector3f pos = camera.getPosition();
        
        // Apply gravity
        velocityY += GRAVITY * deltaTime;
        velocityY = Math.max(velocityY, TERMINAL_VELOCITY);
        
        // Try new vertical position
        float newY = pos.y + velocityY * deltaTime;
        
        // Check vertical collision - check ALL blocks under player's feet area
        boolean verticalCollision = false;
        float feetY = newY - PLAYER_HEIGHT;
        int feetBlockY = (int) Math.floor(feetY);
        
        // Check all blocks that player's feet overlap with (wider area)
        int minX = (int) Math.floor(pos.x - PLAYER_WIDTH / 2);
        int maxX = (int) Math.floor(pos.x + PLAYER_WIDTH / 2);
        int minZ = (int) Math.floor(pos.z - PLAYER_WIDTH / 2);
        int maxZ = (int) Math.floor(pos.z + PLAYER_WIDTH / 2);
        
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                byte blockBelow = world.getBlock(x, feetBlockY, z);
                if (blockBelow != 0) {
                    float blockTop = feetBlockY + 1.0f;
                    
                    // If feet would be inside or below the block, snap to top with tolerance
                    if (feetY < blockTop) {
                        pos.y = blockTop + PLAYER_HEIGHT + 0.001f;  // Small tolerance to stay ON TOP
                        velocityY = 0.0f;
                        onGround = true;
                        verticalCollision = true;
                        break;
                    }
                }
            }
            if (verticalCollision) break;
        }
        
        // If no vertical collision, apply the movement
        if (!verticalCollision) {
            pos.y = newY;
            onGround = false;
        }
        
        // Predict new horizontal position
        float newX = pos.x + moveX;
        float newZ = pos.z + moveZ;
        
        // Check horizontal collisions - check feet level and one block above
        float playerMinX = newX - PLAYER_WIDTH / 2;
        float playerMaxX = newX + PLAYER_WIDTH / 2;
        float playerMinZ = newZ - PLAYER_WIDTH / 2;
        float playerMaxZ = newZ + PLAYER_WIDTH / 2;
        
        int hMinX = (int) Math.floor(playerMinX);
        int hMaxX = (int) Math.floor(playerMaxX);
        int hMinZ = (int) Math.floor(playerMinZ);
        int hMaxZ = (int) Math.floor(playerMaxZ);
        
        // Use UPDATED position after vertical resolution
        int hFeetBlockY = (int) Math.floor(pos.y - PLAYER_HEIGHT);
        
        boolean blockedX = false;
        boolean blockedZ = false;
        boolean steppedUp = false;
        
        for (int x = hMinX; x <= hMaxX; x++) {
            for (int z = hMinZ; z <= hMaxZ; z++) {
                // Check feet level
                byte block = world.getBlock(x, hFeetBlockY, z);
                if (block != 0) {
                    float blockMinX = x;
                    float blockMaxX = x + 1;
                    float blockMinZ = z;
                    float blockMaxZ = z + 1;
                    
                    if (playerMinX < blockMaxX && playerMaxX > blockMinX &&
                        playerMinZ < blockMaxZ && playerMaxZ > blockMinZ) {
                        
                        // Try auto step-up: check if space above is clear
                        byte blockAbove = world.getBlock(x, hFeetBlockY + 1, z);
                        byte blockAbove2 = world.getBlock(x, hFeetBlockY + 2, z);
                        
                        if (blockAbove == 0 && blockAbove2 == 0) {
                            // Space is clear, step up!
                            pos.y = (hFeetBlockY + 1) + PLAYER_HEIGHT + 0.001f;
                            onGround = true;
                            steppedUp = true;
                            break;
                        } else {
                            // Can't step up, block movement
                            float overlapX = Math.min(playerMaxX - blockMinX, blockMaxX - playerMinX);
                            float overlapZ = Math.min(playerMaxZ - blockMinZ, blockMaxZ - playerMinZ);
                            
                            if (overlapX < overlapZ) {
                                blockedX = true;
                            } else {
                                blockedZ = true;
                            }
                        }
                    }
                }
                
                // Also check one block above (for body collision when jumping)
                byte blockAbove = world.getBlock(x, hFeetBlockY + 1, z);
                if (blockAbove != 0) {
                    float blockMinX = x;
                    float blockMaxX = x + 1;
                    float blockMinZ = z;
                    float blockMaxZ = z + 1;
                    
                    if (playerMinX < blockMaxX && playerMaxX > blockMinX &&
                        playerMinZ < blockMaxZ && playerMaxZ > blockMinZ) {
                        
                        float overlapX = Math.min(playerMaxX - blockMinX, blockMaxX - playerMinX);
                        float overlapZ = Math.min(playerMaxZ - blockMinZ, blockMaxZ - playerMinZ);
                        
                        if (overlapX < overlapZ) {
                            blockedX = true;
                        } else {
                            blockedZ = true;
                        }
                    }
                }
            }
            if (steppedUp) break;
        }
        
        // Apply movement only on non-blocked axes
        if (!blockedX) {
            pos.x = newX;
        }
        if (!blockedZ) {
            pos.z = newZ;
        }
    }
    
    public void jump(float jumpVelocity) {
        if (onGround) {
            velocityY = jumpVelocity;
            onGround = false;
        }
    }
    
    public boolean isOnGround() {
        return onGround;
    }
}
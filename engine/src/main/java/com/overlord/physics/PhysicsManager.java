package com.overlord.physics;

import com.overlord.config.GameConfig;
import com.overlord.renderer.Camera;
import com.overlord.voxel.World;

public class PhysicsManager {
    
    private final Camera camera;
    private final World world;
    
    private float velocityY = 0.0f;
    private boolean onGround = false;
    
    public PhysicsManager(Camera camera, World world) {
        this.camera = camera;
        this.world = world;
        this.onGround = true;
        this.velocityY = 0.0f;
    }
    
    public void initializeSpawnPosition() {
        org.joml.Vector3f pos = camera.getPosition();
        int playerX = (int) Math.floor(pos.x);
        int playerFeetY = (int) Math.floor(pos.y - GameConfig.Player.HEIGHT);
        int playerZ = (int) Math.floor(pos.z);
        
        for (int y = playerFeetY; y >= 0; y--) {
            byte block = world.getBlock(playerX, y, playerZ);
            if (block != 0) {
                pos.y = y + 1 + GameConfig.Player.HEIGHT - 0.01f;
                onGround = true;
                velocityY = 0.0f;
                return;
            }
        }
        
        pos.y = 50.0f;
        onGround = false;
    }
    
    public void update(float deltaTime, float moveX, float moveZ) {
        if (deltaTime <= 0) return;
        
        resolveCollisions(deltaTime, moveX, moveZ);
    }
    
    private void resolveCollisions(float deltaTime, float moveX, float moveZ) {
        applyGravity(deltaTime);
        resolveVerticalCollision(deltaTime);
        resolveHorizontalCollision(moveX, moveZ);
    }
    
    private void applyGravity(float deltaTime) {
        velocityY += GameConfig.Physics.GRAVITY * deltaTime;
        velocityY = Math.max(velocityY, GameConfig.Physics.TERMINAL_VELOCITY);
    }
    
    private void resolveVerticalCollision(float deltaTime) {
        org.joml.Vector3f pos = camera.getPosition();
        float newY = pos.y + velocityY * deltaTime;
        float feetY = newY - GameConfig.Player.HEIGHT;
        int feetBlockY = (int) Math.floor(feetY);
        
        int minX = (int) Math.floor(pos.x - GameConfig.Player.WIDTH / 2);
        int maxX = (int) Math.floor(pos.x + GameConfig.Player.WIDTH / 2);
        int minZ = (int) Math.floor(pos.z - GameConfig.Player.WIDTH / 2);
        int maxZ = (int) Math.floor(pos.z + GameConfig.Player.WIDTH / 2);
        
        boolean collision = checkVerticalCollision(minX, maxX, minZ, maxZ, feetY, feetBlockY);
        
        if (collision) {
            velocityY = 0.0f;
            onGround = true;
        } else {
            pos.y = newY;
            onGround = false;
        }
    }
    
    private boolean checkVerticalCollision(int minX, int maxX, int minZ, int maxZ, float feetY, int feetBlockY) {
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                byte blockBelow = world.getBlock(x, feetBlockY, z);
                if (blockBelow != 0) {
                    float blockHeight = getBlockHeight(x, feetBlockY, z);
                    float blockTop = feetBlockY + blockHeight;
                    if (feetY < blockTop) {
                        org.joml.Vector3f pos = camera.getPosition();
                        pos.y = blockTop + GameConfig.Player.HEIGHT + GameConfig.Physics.COLLISION_TOLERANCE;
                        return true;
                    }
                }
            }
        }
        return false;
    }
    
    private void resolveHorizontalCollision(float moveX, float moveZ) {
        org.joml.Vector3f pos = camera.getPosition();
        float newX = pos.x + moveX;
        float newZ = pos.z + moveZ;
        
        float playerMinX = newX - GameConfig.Player.WIDTH / 2;
        float playerMaxX = newX + GameConfig.Player.WIDTH / 2;
        float playerMinZ = newZ - GameConfig.Player.WIDTH / 2;
        float playerMaxZ = newZ + GameConfig.Player.WIDTH / 2;
        
        int hMinX = (int) Math.floor(playerMinX);
        int hMaxX = (int) Math.floor(playerMaxX);
        int hMinZ = (int) Math.floor(playerMinZ);
        int hMaxZ = (int) Math.floor(playerMaxZ);
        int hFeetBlockY = (int) Math.floor(pos.y - GameConfig.Player.HEIGHT);
        
        CollisionResult result = new CollisionResult();
        
        for (int x = hMinX; x <= hMaxX; x++) {
            for (int z = hMinZ; z <= hMaxZ; z++) {
                checkBlockCollision(x, hFeetBlockY, z, playerMinX, playerMaxX, playerMinZ, playerMaxZ, result);
                if (result.steppedUp) break;
            }
            if (result.steppedUp) break;
        }
        
        if (!result.blockedX) {
            pos.x = newX;
        }
        if (!result.blockedZ) {
            pos.z = newZ;
        }
    }
    
    private void checkBlockCollision(int x, int feetBlockY, int z,
                                     float playerMinX, float playerMaxX,
                                     float playerMinZ, float playerMaxZ,
                                     CollisionResult result) {
        byte blockAtFeet = world.getBlock(x, feetBlockY, z);
        if (blockAtFeet != 0) {
            float blockHeight = getBlockHeight(x, feetBlockY, z);
            float blockTop = feetBlockY + blockHeight;
            float playerFeetY = (float) Math.floor(camera.getPosition().y - GameConfig.Player.HEIGHT);
            
            if (playerFeetY < blockTop && checkAABBOverlap(playerMinX, playerMaxX, playerMinZ, playerMaxZ, x, z, blockHeight)) {
                handleFootLevelCollision(x, feetBlockY, z, playerMinX, playerMaxX, playerMinZ, playerMaxZ, result);
                return;
            }
        }
        
        byte blockAbove = world.getBlock(x, feetBlockY + 1, z);
        if (blockAbove != 0) {
            float blockHeight = getBlockHeight(x, feetBlockY + 1, z);
            if (checkAABBOverlap(playerMinX, playerMaxX, playerMinZ, playerMaxZ, x, z, blockHeight)) {
                handleBodyCollision(playerMinX, playerMaxX, playerMinZ, playerMaxZ, x, z, result);
            }
        }
    }
    
    private void handleFootLevelCollision(int x, int feetBlockY, int z,
                                          float playerMinX, float playerMaxX,
                                          float playerMinZ, float playerMaxZ,
                                          CollisionResult result) {
        float blockHeight = getBlockHeight(x, feetBlockY, z);
        float blockTop = feetBlockY + blockHeight;
        
        if (blockHeight <= GameConfig.Player.MAX_STEP_HEIGHT) {
            float spaceAbove = GameConfig.Player.HEIGHT + GameConfig.Physics.COLLISION_TOLERANCE;
            int checkY = feetBlockY + 1;
            float currentHeight = blockHeight;
            boolean hasSpace = true;
            
            while (currentHeight < spaceAbove && checkY < GameConfig.Chunk.MAX_HEIGHT) {
                byte blockAtCheck = world.getBlock(x, checkY, z);
                if (blockAtCheck != 0) {
                    hasSpace = false;
                    break;
                }
                currentHeight += 1.0f;
                checkY++;
            }
            
            if (hasSpace) {
                org.joml.Vector3f pos = camera.getPosition();
                pos.y = blockTop + GameConfig.Player.HEIGHT + GameConfig.Physics.COLLISION_TOLERANCE;
                onGround = true;
                result.steppedUp = true;
                return;
            }
        }
        
        resolveAxisBlockage(playerMinX, playerMaxX, playerMinZ, playerMaxZ, x, z, result);
    }
    
    private void handleBodyCollision(float playerMinX, float playerMaxX,
                                     float playerMinZ, float playerMaxZ,
                                     int x, int z, CollisionResult result) {
        resolveAxisBlockage(playerMinX, playerMaxX, playerMinZ, playerMaxZ, x, z, result);
    }
    
    private void resolveAxisBlockage(float playerMinX, float playerMaxX,
                                     float playerMinZ, float playerMaxZ,
                                     int blockX, int blockZ, CollisionResult result) {
        float overlapX = Math.min(playerMaxX - blockX, blockX + 1 - playerMinX);
        float overlapZ = Math.min(playerMaxZ - blockZ, blockZ + 1 - playerMinZ);
        
        if (overlapX < overlapZ) {
            result.blockedX = true;
        } else {
            result.blockedZ = true;
        }
    }
    
    private float getBlockHeight(int x, int y, int z) {
        com.overlord.voxel.BlockSize blockSize = world.getBlockSize(x, y, z);
        return blockSize.units();
    }
    
    private boolean checkAABBOverlap(float playerMinX, float playerMaxX,
                                     float playerMinZ, float playerMaxZ,
                                     int blockX, int blockZ, float blockHeight) {
        return playerMinX < blockX + blockHeight && playerMaxX > blockX &&
               playerMinZ < blockZ + blockHeight && playerMaxZ > blockZ;
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
    
    private static class CollisionResult {
        boolean blockedX = false;
        boolean blockedZ = false;
        boolean steppedUp = false;
    }
}
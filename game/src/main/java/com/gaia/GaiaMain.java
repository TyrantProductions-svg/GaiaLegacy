package com.gaia;

import com.gaia.GameBootstrap.GameContext;

public class GaiaMain {
    
    public static void main(String[] args) {
<<<<<<< HEAD
        GameContext context = GameBootstrap.bootstrap();
        GameLoop gameLoop = new GameLoop(context);
        gameLoop.run();
=======
        System.out.println("[GaiaLegacy] Starting Gaia Legacy...");
        
        BlockRegistry.init();
        BlockRegistry.loadAllFromResources();
        System.out.println("[GaiaLegacy] Total blocks registered: " + BlockRegistry.getBlockCount());
        
        Engine engine = new Engine();
        engine.init();
        
        World world = engine.getWorld();
        
        AtomicReference<float[]> chunkMeshData = new AtomicReference<>(null);
        AtomicReference<Mesh> chunkMesh = new AtomicReference<>(null);
        AtomicReference<Vector3f> spawnPosition = new AtomicReference<>(null);
        
        engine.submitToCore(Engine.CORE_WORLD, () -> {
            System.out.println("[GaiaLegacy] Generating terrain...");
            int chunkRadius = 2;
            for (int cx = -chunkRadius; cx < chunkRadius; cx++) {
                for (int cz = -chunkRadius; cz < chunkRadius; cz++) {
                    GaiaWorldGenerator.generateChunk(world, cx, cz);
                    System.out.println("[GaiaLegacy] Generated chunk (" + cx + ", " + cz + ")");
                }
            }
            
            List<float[]> allMeshData = new ArrayList<>();
            for (int cx = -chunkRadius; cx < chunkRadius; cx++) {
                for (int cz = -chunkRadius; cz < chunkRadius; cz++) {
                    Chunk chunk = world.getChunk(cx, cz);
                    float[] meshData = ChunkMeshBuilder.buildChunkMeshData(chunk, cx, cz, world);
                    if (meshData.length > 0) {
                        allMeshData.add(meshData);
                    }
                }
            }
            
            float[] combinedMeshData = combineMeshData(allMeshData);
            chunkMeshData.set(combinedMeshData);
            
            int spawnX = 0;
            int spawnZ = 0;
            int highestBlockY = findHighestBlock(world, spawnX, spawnZ);
            
            System.out.println("[GaiaLegacy] Found highest block at (" + spawnX + ", " + highestBlockY + ", " + spawnZ + ")");
            
            if (highestBlockY <= 0) {
                System.out.println("[GaiaLegacy] WARNING: No blocks found at spawn! Using default height 30.");
                highestBlockY = 30;
                for (int y = 0; y < 30; y++) {
                    world.setBlock(spawnX, y, spawnZ, (byte) 1);
                }
                highestBlockY = 29;
            }
            
            int spawnY = highestBlockY + 1;
            spawnPosition.set(new Vector3f(spawnX + 0.5f, spawnY + 1.8f, spawnZ + 0.5f));
            
            System.out.println("[GaiaLegacy] Spawn position: (" + spawnX + ", " + spawnY + ", " + spawnZ + ")");
            System.out.println("[GaiaLegacy] Terrain generation complete!");
        });
        
        while (spawnPosition.get() == null) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
        
        PhysicsManager physicsManager = new PhysicsManager(
            engine.getCamera(),
            world
        );
        
        PlayerManager playerManager = new PlayerManager(
            engine.getCamera(),
            physicsManager,
            engine.getWindow().getWindow()
        );
        
        int fps = 0;
        int frameCount = 0;
        long lastFpsTime = System.nanoTime();
        
        glfwSetCursorPosCallback(engine.getWindow().getWindow(), (win, xpos, ypos) -> {
            if (firstMouse) {
                lastX = xpos;
                lastY = ypos;
                firstMouse = false;
            }
            
            double xoffset = xpos - lastX;
            double yoffset = lastY - ypos;
            
            lastX = xpos;
            lastY = ypos;
            
            engine.getCamera().processMouseMovement((float) xoffset, (float) yoffset);
        });
        
        engine.getCamera().setPosition(spawnPosition.get());
        engine.getCamera().setPitch(-30.0f);
        
        physicsManager.initializeSpawnPosition();
        
        while (engine.isRunning()) {
            engine.submitToCore(Engine.CORE_PLAYER, playerManager::update);
            
            if (playerManager.shouldClose()) {
                break;
            }
            
            // Calculate FPS
            frameCount++;
            long currentTime = System.nanoTime();
            if (currentTime - lastFpsTime >= 1_000_000_000L) {
                fps = frameCount;
                frameCount = 0;
                lastFpsTime = currentTime;
            }
            
            float[] pendingData = chunkMeshData.getAndSet(null);
            if (pendingData != null && pendingData.length > 0) {
                if (chunkMesh.get() != null) {
                    chunkMesh.get().cleanup();
                }
                chunkMesh.set(new Mesh(pendingData));
                engine.getRenderer().setMesh(chunkMesh.get());
            }
            
            engine.getRenderer().render();
            glfwSwapBuffers(engine.getWindow().getWindow());
            glfwPollEvents();
        }
        
        if (chunkMesh.get() != null) {
            chunkMesh.get().cleanup();
        }
        
        engine.shutdown();
        System.out.println("[GaiaLegacy] Shutdown complete.");
    }
    
    private static int findHighestBlock(World world, int x, int z) {
        for (int y = 255; y >= 0; y--) {
            if (world.getBlock(x, y, z) != 0) {
                return y;
            }
        }
        return 0;
    }
    
    private static float[] combineMeshData(List<float[]> meshDataList) {
        int totalLength = 0;
        for (float[] data : meshDataList) {
            totalLength += data.length;
        }
        
        float[] combined = new float[totalLength];
        int offset = 0;
        for (float[] data : meshDataList) {
            System.arraycopy(data, 0, combined, offset, data.length);
            offset += data.length;
        }
        
        return combined;
>>>>>>> 4a233705f8ce7b58cfd97d93e8f5aa28dfdee6bd
    }
}
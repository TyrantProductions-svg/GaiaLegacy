package com.gaia;

import com.gaia.blocks.BlockRegistry;
import com.gaia.world.GaiaWorldGenerator;
import com.overlord.config.GameConfig;
import com.overlord.core.Engine;
import com.overlord.core.PlayerManager;
import com.overlord.physics.PhysicsManager;
import com.overlord.renderer.Mesh;
import com.overlord.voxel.Chunk;
import com.overlord.voxel.ChunkMeshBuilder;
import com.overlord.voxel.World;

import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

public class GameBootstrap {
    
    public static GameContext bootstrap() {
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
            generateTerrain(world, chunkMeshData, spawnPosition);
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
        
        engine.getCamera().setPosition(spawnPosition.get());
        engine.getCamera().setPitch(-30.0f);
        
        physicsManager.initializeSpawnPosition();
        
        return new GameContext(
            engine,
            world,
            physicsManager,
            playerManager,
            chunkMeshData,
            chunkMesh
        );
    }
    
    private static void generateTerrain(World world, AtomicReference<float[]> chunkMeshData, AtomicReference<Vector3f> spawnPosition) {
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
        
        Vector3f spawnPos = calculateSpawnPosition(world);
        spawnPosition.set(spawnPos);
        
        System.out.println("[GaiaLegacy] Spawn position: (" + spawnPos.x + ", " + spawnPos.y + ", " + spawnPos.z + ")");
        System.out.println("[GaiaLegacy] Terrain generation complete!");
    }
    
    private static Vector3f calculateSpawnPosition(World world) {
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
        return new Vector3f(spawnX + 0.5f, spawnY + GameConfig.Player.HEIGHT, spawnZ + 0.5f);
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
    }
    
    public static class GameContext {
        public final Engine engine;
        public final World world;
        public final PhysicsManager physicsManager;
        public final PlayerManager playerManager;
        public final AtomicReference<float[]> chunkMeshData;
        public final AtomicReference<Mesh> chunkMesh;
        
        public GameContext(
            Engine engine,
            World world,
            PhysicsManager physicsManager,
            PlayerManager playerManager,
            AtomicReference<float[]> chunkMeshData,
            AtomicReference<Mesh> chunkMesh
        ) {
            this.engine = engine;
            this.world = world;
            this.physicsManager = physicsManager;
            this.playerManager = playerManager;
            this.chunkMeshData = chunkMeshData;
            this.chunkMesh = chunkMesh;
        }
    }
}
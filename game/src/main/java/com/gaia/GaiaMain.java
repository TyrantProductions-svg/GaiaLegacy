package com.gaia;

import com.gaia.blocks.BlockRegistry;
import com.gaia.world.GaiaWorldGenerator;
import com.overlord.core.Engine;
import com.overlord.core.PlayerManager;
import com.overlord.renderer.Mesh;
import com.overlord.voxel.Chunk;
import com.overlord.voxel.ChunkMeshBuilder;
import com.overlord.voxel.World;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.lwjgl.glfw.GLFW.*;

public class GaiaMain {
    
    private static double lastX;
    private static double lastY;
    private static boolean firstMouse = true;
    
    public static void main(String[] args) {
        System.out.println("[GaiaLegacy] Starting Gaia Legacy...");
        
        BlockRegistry.init();
        BlockRegistry.loadAllFromResources();
        System.out.println("[GaiaLegacy] Total blocks registered: " + BlockRegistry.getBlockCount());
        
        Engine engine = new Engine();
        engine.init();
        
        PlayerManager playerManager = new PlayerManager(
            engine.getCamera(),
            engine.getWindow().getWindow()
        );
        
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
        
        engine.getCamera().setPosition(new org.joml.Vector3f(32, 60, -32));
        engine.getCamera().setPitch(-30.0f);
        
        World world = engine.getWorld();
        AtomicReference<float[]> chunkMeshData = new AtomicReference<>(null);
        AtomicReference<Mesh> chunkMesh = new AtomicReference<>(null);
        
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
            System.out.println("[GaiaLegacy] Terrain generation complete!");
        });
        
        while (engine.isRunning()) {
            engine.submitToCore(Engine.CORE_PLAYER, playerManager::update);
            
            if (playerManager.shouldClose()) {
                break;
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
}
package com.overlord.voxel;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class ChunkMeshBuilder {
    
    private static final int TEXTURE_SIZE = 16;
    private static final int ATLAS_WIDTH = 128;
    private static final int ATLAS_HEIGHT = 64;
    
    public static float[] buildChunkMeshData(Chunk chunk, int chunkX, int chunkZ, World world) {
        List<Float> vertices = new ArrayList<>();
        
        Map<Integer, SubChunk> subChunks = chunk.getSubChunks();
        if (subChunks.isEmpty()) {
            return new float[0];
        }
        
        for (Map.Entry<Integer, SubChunk> entry : subChunks.entrySet()) {
            int sectionIndex = entry.getKey();
            SubChunk subChunk = entry.getValue();
            
            int baseY = sectionIndex * SubChunk.SIZE;
            
            for (int x = 0; x < SubChunk.SIZE; x++) {
                for (int y = 0; y < SubChunk.SIZE; y++) {
                    for (int z = 0; z < SubChunk.SIZE; z++) {
                        byte block = subChunk.getBlock(x, y, z);
                        if (block == 0) continue;
                        
                        int worldY = baseY + y;
                        int worldX = chunkX * Chunk.SIZE + x;
                        int worldZ = chunkZ * Chunk.SIZE + z;
                        
                        float px = worldX;
                        float py = worldY;
                        float pz = worldZ;
                        
                        int topTexture = getTopTexture(block);
                        int sideTexture = getSideTexture(block);
                        int bottomTexture = getBottomTexture(block);
                        
                        if (!isBlockSolid(world, worldX, worldY, worldZ - 1)) {
                            addFace(vertices, px, py, pz, 0, sideTexture);
                        }
                        if (!isBlockSolid(world, worldX, worldY, worldZ + 1)) {
                            addFace(vertices, px, py, pz, 1, sideTexture);
                        }
                        if (!isBlockSolid(world, worldX, worldY + 1, worldZ)) {
                            addFace(vertices, px, py, pz, 2, topTexture);
                        }
                        if (!isBlockSolid(world, worldX, worldY - 1, worldZ)) {
                            addFace(vertices, px, py, pz, 3, bottomTexture);
                        }
                        if (!isBlockSolid(world, worldX - 1, worldY, worldZ)) {
                            addFace(vertices, px, py, pz, 4, sideTexture);
                        }
                        if (!isBlockSolid(world, worldX + 1, worldY, worldZ)) {
                            addFace(vertices, px, py, pz, 5, sideTexture);
                        }
                    }
                }
            }
        }
        
        if (vertices.isEmpty()) {
            return new float[0];
        }
        
        float[] vertexArray = new float[vertices.size()];
        for (int i = 0; i < vertices.size(); i++) {
            vertexArray[i] = vertices.get(i);
        }
        
        return vertexArray;
    }
    
    private static int getTopTexture(byte blockType) {
        switch (blockType) {
            case 1: return 0;
            case 2: return 2;
            case 3: return 3;
            default: return 0;
        }
    }
    
    private static int getSideTexture(byte blockType) {
        switch (blockType) {
            case 1: return 1;
            case 2: return 2;
            case 3: return 3;
            default: return 0;
        }
    }
    
    private static int getBottomTexture(byte blockType) {
        switch (blockType) {
            case 1: return 2;
            case 2: return 2;
            case 3: return 3;
            default: return 0;
        }
    }
    
    private static boolean isBlockSolid(World world, int x, int y, int z) {
        if (y < 0) return false;
        return world.getBlock(x, y, z) != 0;
    }
    
    private static void addFace(List<Float> vertices, float x, float y, float z, int face, int textureIndex) {
        float[] faceVerts = getFaceVertices(x, y, z, face, textureIndex);
        for (float v : faceVerts) {
            vertices.add(v);
        }
    }
    
    private static float[] getFaceVertices(float x, float y, float z, int face, int textureIndex) {
        float u = (textureIndex * TEXTURE_SIZE) / (float) ATLAS_WIDTH;
        float uEnd = ((textureIndex + 1) * TEXTURE_SIZE) / (float) ATLAS_WIDTH;
        float v = 0.0f;
        float vEnd = TEXTURE_SIZE / (float) ATLAS_HEIGHT;
        
        boolean flipV = (face != 2);
        float v0 = flipV ? vEnd : v;
        float v1 = flipV ? v : vEnd;
        
        switch (face) {
            case 0: return new float[] {
                x, y, z, u, v0,
                x + 1, y, z, uEnd, v0,
                x + 1, y + 1, z, uEnd, v1,
                x + 1, y + 1, z, uEnd, v1,
                x, y + 1, z, u, v1,
                x, y, z, u, v0
            };
            case 1: return new float[] {
                x, y, z + 1, u, v0,
                x + 1, y, z + 1, uEnd, v0,
                x + 1, y + 1, z + 1, uEnd, v1,
                x + 1, y + 1, z + 1, uEnd, v1,
                x, y + 1, z + 1, u, v1,
                x, y, z + 1, u, v0
            };
            case 2: return new float[] {
                x, y + 1, z + 1, u, v0,
                x + 1, y + 1, z + 1, uEnd, v0,
                x + 1, y + 1, z, uEnd, v1,
                x + 1, y + 1, z, uEnd, v1,
                x, y + 1, z, u, v1,
                x, y + 1, z + 1, u, v0
            };
            case 3: return new float[] {
                x, y, z, u, v0,
                x + 1, y, z, uEnd, v0,
                x + 1, y, z + 1, uEnd, v1,
                x + 1, y, z + 1, uEnd, v1,
                x, y, z + 1, u, v1,
                x, y, z, u, v0
            };
            case 4: return new float[] {
                x, y, z, u, v0,
                x, y, z + 1, uEnd, v0,
                x, y + 1, z + 1, uEnd, v1,
                x, y + 1, z + 1, uEnd, v1,
                x, y + 1, z, u, v1,
                x, y, z, u, v0
            };
            case 5: return new float[] {
                x + 1, y, z + 1, u, v0,
                x + 1, y, z, uEnd, v0,
                x + 1, y + 1, z, uEnd, v1,
                x + 1, y + 1, z, uEnd, v1,
                x + 1, y + 1, z + 1, u, v1,
                x + 1, y, z + 1, u, v0
            };
            default: return new float[0];
        }
    }
}
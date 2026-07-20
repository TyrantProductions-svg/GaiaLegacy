package com.overlord.voxel;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class ChunkMeshBuilder {
    
    private static final float[] FACE_COLORS = {
        1.0f, 0.95f, 0.8f,
        0.8f, 0.9f, 1.0f,
        0.9f, 1.0f, 0.85f,
        0.7f, 0.7f, 0.7f,
        0.85f, 0.85f, 0.9f,
        0.95f, 0.85f, 0.85f
    };
    
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
                        
                        float px = x;
                        float py = worldY;
                        float pz = z;
                        
                        if (!isBlockSolid(world, worldX, worldY, worldZ - 1)) {
                            addFace(vertices, px, py, pz, 0);
                        }
                        if (!isBlockSolid(world, worldX, worldY, worldZ + 1)) {
                            addFace(vertices, px, py, pz, 1);
                        }
                        if (!isBlockSolid(world, worldX, worldY + 1, worldZ)) {
                            addFace(vertices, px, py, pz, 2);
                        }
                        if (!isBlockSolid(world, worldX, worldY - 1, worldZ)) {
                            addFace(vertices, px, py, pz, 3);
                        }
                        if (!isBlockSolid(world, worldX - 1, worldY, worldZ)) {
                            addFace(vertices, px, py, pz, 4);
                        }
                        if (!isBlockSolid(world, worldX + 1, worldY, worldZ)) {
                            addFace(vertices, px, py, pz, 5);
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
    
    private static boolean isBlockSolid(World world, int x, int y, int z) {
        if (y < 0) return false;
        return world.getBlock(x, y, z) != 0;
    }
    
    private static void addFace(List<Float> vertices, float x, float y, float z, int face) {
        float[] faceVerts = getFaceVertices(x, y, z, face);
        for (float v : faceVerts) {
            vertices.add(v);
        }
    }
    
    private static float[] getFaceVertices(float x, float y, float z, int face) {
        float[] color = new float[3];
        System.arraycopy(FACE_COLORS, face * 3, color, 0, 3);
        
        switch (face) {
            case 0: return new float[] {
                x, y, z, color[0], color[1], color[2],
                x + 1, y, z, color[0], color[1], color[2],
                x + 1, y + 1, z, color[0], color[1], color[2],
                x + 1, y + 1, z, color[0], color[1], color[2],
                x, y + 1, z, color[0], color[1], color[2],
                x, y, z, color[0], color[1], color[2]
            };
            case 1: return new float[] {
                x, y, z + 1, color[0], color[1], color[2],
                x + 1, y, z + 1, color[0], color[1], color[2],
                x + 1, y + 1, z + 1, color[0], color[1], color[2],
                x + 1, y + 1, z + 1, color[0], color[1], color[2],
                x, y + 1, z + 1, color[0], color[1], color[2],
                x, y, z + 1, color[0], color[1], color[2]
            };
            case 2: return new float[] {
                x, y + 1, z + 1, color[0], color[1], color[2],
                x + 1, y + 1, z + 1, color[0], color[1], color[2],
                x + 1, y + 1, z, color[0], color[1], color[2],
                x + 1, y + 1, z, color[0], color[1], color[2],
                x, y + 1, z, color[0], color[1], color[2],
                x, y + 1, z + 1, color[0], color[1], color[2]
            };
            case 3: return new float[] {
                x, y, z, color[0], color[1], color[2],
                x + 1, y, z, color[0], color[1], color[2],
                x + 1, y, z + 1, color[0], color[1], color[2],
                x + 1, y, z + 1, color[0], color[1], color[2],
                x, y, z + 1, color[0], color[1], color[2],
                x, y, z, color[0], color[1], color[2]
            };
            case 4: return new float[] {
                x, y, z, color[0], color[1], color[2],
                x, y, z + 1, color[0], color[1], color[2],
                x, y + 1, z + 1, color[0], color[1], color[2],
                x, y + 1, z + 1, color[0], color[1], color[2],
                x, y + 1, z, color[0], color[1], color[2],
                x, y, z, color[0], color[1], color[2]
            };
            case 5: return new float[] {
                x + 1, y, z + 1, color[0], color[1], color[2],
                x + 1, y, z, color[0], color[1], color[2],
                x + 1, y + 1, z, color[0], color[1], color[2],
                x + 1, y + 1, z, color[0], color[1], color[2],
                x + 1, y + 1, z + 1, color[0], color[1], color[2],
                x + 1, y, z + 1, color[0], color[1], color[2]
            };
            default: return new float[0];
        }
    }
}
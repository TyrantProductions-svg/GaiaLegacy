package com.overlord.voxel;

import com.overlord.config.GameConfig;
import com.overlord.renderer.texture.TextureRegion;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class ChunkMeshBuilder {
    private static final BlockFace[] FACES = {
        BlockFace.NORTH,
        BlockFace.SOUTH,
        BlockFace.UP,
        BlockFace.DOWN,
        BlockFace.WEST,
        BlockFace.EAST
    };

    private final BlockRenderResolver renderResolver;

    public ChunkMeshBuilder(BlockRenderResolver renderResolver) {
        this.renderResolver =
                Objects.requireNonNull(
                        renderResolver, "renderResolver");
    }

    public float[] buildChunkMeshData(
            Chunk chunk, int chunkX, int chunkZ, World world) {
        List<Float> vertices = new ArrayList<>();
        
        Map<Integer, SubChunk> subChunks = chunk.getSubChunks();
        if (subChunks.isEmpty()) {
            return new float[0];
        }
        
        for (Map.Entry<Integer, SubChunk> entry : subChunks.entrySet()) {
            int sectionIndex = entry.getKey();
            SubChunk subChunk = entry.getValue();
            
            int baseY = sectionIndex * GameConfig.Chunk.SUBCHUNK_HEIGHT;
            
            for (int x = 0; x < GameConfig.Chunk.SUBCHUNK_HEIGHT; x++) {
                for (int y = 0; y < GameConfig.Chunk.SUBCHUNK_HEIGHT; y++) {
                    for (int z = 0; z < GameConfig.Chunk.SUBCHUNK_HEIGHT; z++) {
                        byte block = subChunk.getBlock(x, y, z);
                        if (block == 0) continue;

                        BlockRenderInfo renderInfo =
                                renderResolver.resolve(
                                        Byte.toUnsignedInt(block));
                        if (!renderInfo.renderable()) {
                            continue;
                        }
                        
                        int worldY = baseY + y;
                        int worldX = chunkX * GameConfig.Chunk.SIZE + x;
                        int worldZ = chunkZ * GameConfig.Chunk.SIZE + z;
                        
                        float px = worldX;
                        float py = worldY;
                        float pz = worldZ;
                        
                        if (!isBlockSolid(world, worldX, worldY, worldZ - 1)) {
                            addFace(
                                    vertices, px, py, pz, 0,
                                    renderInfo.region(FACES[0]));
                        }
                        if (!isBlockSolid(world, worldX, worldY, worldZ + 1)) {
                            addFace(
                                    vertices, px, py, pz, 1,
                                    renderInfo.region(FACES[1]));
                        }
                        if (!isBlockSolid(world, worldX, worldY + 1, worldZ)) {
                            addFace(
                                    vertices, px, py, pz, 2,
                                    renderInfo.region(FACES[2]));
                        }
                        if (!isBlockSolid(world, worldX, worldY - 1, worldZ)) {
                            addFace(
                                    vertices, px, py, pz, 3,
                                    renderInfo.region(FACES[3]));
                        }
                        if (!isBlockSolid(world, worldX - 1, worldY, worldZ)) {
                            addFace(
                                    vertices, px, py, pz, 4,
                                    renderInfo.region(FACES[4]));
                        }
                        if (!isBlockSolid(world, worldX + 1, worldY, worldZ)) {
                            addFace(
                                    vertices, px, py, pz, 5,
                                    renderInfo.region(FACES[5]));
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
    
    private boolean isBlockSolid(World world, int x, int y, int z) {
        if (y < 0) return false;
        byte neighbor = world.getBlock(x, y, z);
        return neighbor != 0
                && renderResolver
                        .resolve(Byte.toUnsignedInt(neighbor))
                        .renderable();
    }
    
    private static void addFace(
            List<Float> vertices,
            float x,
            float y,
            float z,
            int face,
            TextureRegion region) {
        float[] faceVerts =
                getFaceVertices(x, y, z, face, region);
        for (float v : faceVerts) {
            vertices.add(v);
        }
    }
    
    private static float[] getFaceVertices(
            float x,
            float y,
            float z,
            int face,
            TextureRegion region) {
        float u = region.uMin();
        float uEnd = region.uMax();
        float v = region.vMin();
        float vEnd = region.vMax();
        
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

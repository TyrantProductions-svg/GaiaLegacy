package com.overlord.voxel;

import com.overlord.config.GameConfig;
import com.overlord.renderer.texture.TextureRegion;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class ChunkMeshBuilder implements ChunkMesher {
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

    @Override
    public ChunkMeshData build(ChunkMeshInput input) {
        Objects.requireNonNull(input, "input");
        List<Float> vertices = new ArrayList<>();

        for (int x = 0; x < GameConfig.Chunk.SIZE; x++) {
            for (int y = 0;
                    y < input.center().worldHeight();
                    y++) {
                for (int z = 0;
                        z < GameConfig.Chunk.SIZE;
                        z++) {
                    byte block = input.center().getBlock(x, y, z);
                    if (block == 0) {
                        continue;
                    }

                    BlockRenderInfo renderInfo =
                            renderResolver.resolve(
                                    Byte.toUnsignedInt(block));
                    if (!renderInfo.renderable()) {
                        continue;
                    }

                    if (!isBlockSolid(
                            input.getBlock(x, y, z - 1))) {
                        addFace(
                                vertices, x, y, z, FACES[0],
                                renderInfo.region(FACES[0]));
                    }
                    if (!isBlockSolid(
                            input.getBlock(x, y, z + 1))) {
                        addFace(
                                vertices, x, y, z, FACES[1],
                                renderInfo.region(FACES[1]));
                    }
                    if (!isBlockSolid(
                            input.getBlock(x, y + 1, z))) {
                        addFace(
                                vertices, x, y, z, FACES[2],
                                renderInfo.region(FACES[2]));
                    }
                    if (!isBlockSolid(
                            input.getBlock(x, y - 1, z))) {
                        addFace(
                                vertices, x, y, z, FACES[3],
                                renderInfo.region(FACES[3]));
                    }
                    if (!isBlockSolid(
                            input.getBlock(x - 1, y, z))) {
                        addFace(
                                vertices, x, y, z, FACES[4],
                                renderInfo.region(FACES[4]));
                    }
                    if (!isBlockSolid(
                            input.getBlock(x + 1, y, z))) {
                        addFace(
                                vertices, x, y, z, FACES[5],
                                renderInfo.region(FACES[5]));
                    }
                }
            }
        }

        return new ChunkMeshData(
                input.center().key(),
                input.center().revision(),
                toArray(vertices));
    }

    private boolean isBlockSolid(byte block) {
        return block != 0
                && renderResolver
                        .resolve(Byte.toUnsignedInt(block))
                        .renderable();
    }

    private static float[] toArray(List<Float> vertices) {
        float[] vertexArray = new float[vertices.size()];
        for (int i = 0; i < vertices.size(); i++) {
            vertexArray[i] = vertices.get(i);
        }
        return vertexArray;
    }
    
    private static void addFace(
            List<Float> vertices,
            float x,
            float y,
            float z,
            BlockFace face,
            TextureRegion region) {
        float u = region.uMin();
        float uEnd = region.uMax();
        float v = region.vMin();
        float vEnd = region.vMax();

        boolean flipV = face != BlockFace.UP;
        float v0 = flipV ? vEnd : v;
        float v1 = flipV ? v : vEnd;

        switch (face) {
            case NORTH -> {
                addVertex(vertices, x, y, z, u, v0, face, 0, 0, -1);
                addVertex(vertices, x + 1, y, z, uEnd, v0, face, 0, 0, -1);
                addVertex(vertices, x + 1, y + 1, z, uEnd, v1, face, 0, 0, -1);
                addVertex(vertices, x + 1, y + 1, z, uEnd, v1, face, 0, 0, -1);
                addVertex(vertices, x, y + 1, z, u, v1, face, 0, 0, -1);
                addVertex(vertices, x, y, z, u, v0, face, 0, 0, -1);
            }
            case SOUTH -> {
                addVertex(vertices, x, y, z + 1, u, v0, face, 0, 0, 1);
                addVertex(vertices, x + 1, y, z + 1, uEnd, v0, face, 0, 0, 1);
                addVertex(vertices, x + 1, y + 1, z + 1, uEnd, v1, face, 0, 0, 1);
                addVertex(vertices, x + 1, y + 1, z + 1, uEnd, v1, face, 0, 0, 1);
                addVertex(vertices, x, y + 1, z + 1, u, v1, face, 0, 0, 1);
                addVertex(vertices, x, y, z + 1, u, v0, face, 0, 0, 1);
            }
            case UP -> {
                addVertex(vertices, x, y + 1, z + 1, u, v0, face, 0, 1, 0);
                addVertex(vertices, x + 1, y + 1, z + 1, uEnd, v0, face, 0, 1, 0);
                addVertex(vertices, x + 1, y + 1, z, uEnd, v1, face, 0, 1, 0);
                addVertex(vertices, x + 1, y + 1, z, uEnd, v1, face, 0, 1, 0);
                addVertex(vertices, x, y + 1, z, u, v1, face, 0, 1, 0);
                addVertex(vertices, x, y + 1, z + 1, u, v0, face, 0, 1, 0);
            }
            case DOWN -> {
                addVertex(vertices, x, y, z, u, v0, face, 0, -1, 0);
                addVertex(vertices, x + 1, y, z, uEnd, v0, face, 0, -1, 0);
                addVertex(vertices, x + 1, y, z + 1, uEnd, v1, face, 0, -1, 0);
                addVertex(vertices, x + 1, y, z + 1, uEnd, v1, face, 0, -1, 0);
                addVertex(vertices, x, y, z + 1, u, v1, face, 0, -1, 0);
                addVertex(vertices, x, y, z, u, v0, face, 0, -1, 0);
            }
            case WEST -> {
                addVertex(vertices, x, y, z, u, v0, face, -1, 0, 0);
                addVertex(vertices, x, y, z + 1, uEnd, v0, face, -1, 0, 0);
                addVertex(vertices, x, y + 1, z + 1, uEnd, v1, face, -1, 0, 0);
                addVertex(vertices, x, y + 1, z + 1, uEnd, v1, face, -1, 0, 0);
                addVertex(vertices, x, y + 1, z, u, v1, face, -1, 0, 0);
                addVertex(vertices, x, y, z, u, v0, face, -1, 0, 0);
            }
            case EAST -> {
                addVertex(vertices, x + 1, y, z + 1, u, v0, face, 1, 0, 0);
                addVertex(vertices, x + 1, y, z, uEnd, v0, face, 1, 0, 0);
                addVertex(vertices, x + 1, y + 1, z, uEnd, v1, face, 1, 0, 0);
                addVertex(vertices, x + 1, y + 1, z, uEnd, v1, face, 1, 0, 0);
                addVertex(vertices, x + 1, y + 1, z + 1, u, v1, face, 1, 0, 0);
                addVertex(vertices, x + 1, y, z + 1, u, v0, face, 1, 0, 0);
            }
        }
    }

    private static void addVertex(
            List<Float> vertices,
            float x,
            float y,
            float z,
            float u,
            float v,
            BlockFace face,
            float normalX,
            float normalY,
            float normalZ) {
        vertices.add(x);
        vertices.add(y);
        vertices.add(z);
        vertices.add(u);
        vertices.add(v);
        vertices.add(normalX);
        vertices.add(normalY);
        vertices.add(normalZ);
        vertices.add(
                VoxelVertexFormat.encodeFaceLight(
                        face,
                        VoxelVertexFormat.DEFAULT_LIGHT_LEVEL));
        vertices.add(VoxelVertexFormat.DEFAULT_AMBIENT_OCCLUSION);
    }
}

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

    private static final int TEXTURE_VARIANTS = 4;

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

                    BlockSize blockSize = input.center().getBlockSize(x, y, z);
                    float size = blockSize.units();

                    BlockRenderInfo renderInfo =
                            renderResolver.resolve(
                                    Byte.toUnsignedInt(block));
                    if (!renderInfo.renderable()) {
                        continue;
                    }

                    int textureVariant = computeTextureVariant(x, y, z);

                    if (!isFaceOccluded(input, x, y, z, 0, blockSize)) {
                        addFace(
                                vertices, x, y, z, size, 0,
                                renderInfo.region(FACES[0]), textureVariant);
                    }
                    if (!isFaceOccluded(input, x, y, z, 1, blockSize)) {
                        addFace(
                                vertices, x, y, z, size, 1,
                                renderInfo.region(FACES[1]), textureVariant);
                    }
                    if (!isFaceOccluded(input, x, y, z, 2, blockSize)) {
                        addFace(
                                vertices, x, y, z, size, 2,
                                renderInfo.region(FACES[2]), textureVariant);
                    }
                    if (!isFaceOccluded(input, x, y, z, 3, blockSize)) {
                        addFace(
                                vertices, x, y, z, size, 3,
                                renderInfo.region(FACES[3]), textureVariant);
                    }
                    if (!isFaceOccluded(input, x, y, z, 4, blockSize)) {
                        addFace(
                                vertices, x, y, z, size, 4,
                                renderInfo.region(FACES[4]), textureVariant);
                    }
                    if (!isFaceOccluded(input, x, y, z, 5, blockSize)) {
                        addFace(
                                vertices, x, y, z, size, 5,
                                renderInfo.region(FACES[5]), textureVariant);
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

    private boolean isFaceOccluded(
            ChunkMeshInput input,
            int x, int y, int z,
            int face,
            BlockSize selfSize) {
        float selfSizeInUnits = selfSize.units();

        switch (face) {
            case 0: // NORTH
                return isSpaceOccupied(input, x, y, z - 1, selfSizeInUnits, FaceDirection.NORTH);
            case 1: // SOUTH
                return isSpaceOccupied(input, x, y, z + 1, selfSizeInUnits, FaceDirection.SOUTH);
            case 2: // UP
                return isSpaceOccupied(input, x, y + 1, z, selfSizeInUnits, FaceDirection.UP);
            case 3: // DOWN
                return isSpaceOccupied(input, x, y - 1, z, selfSizeInUnits, FaceDirection.DOWN);
            case 4: // WEST
                return isSpaceOccupied(input, x - 1, y, z, selfSizeInUnits, FaceDirection.WEST);
            case 5: // EAST
                return isSpaceOccupied(input, x + 1, y, z, selfSizeInUnits, FaceDirection.EAST);
            default:
                return false;
        }
    }

    private boolean isSpaceOccupied(
            ChunkMeshInput input,
            int x, int y, int z,
            float selfSizeInUnits,
            FaceDirection direction) {
        byte neighborBlock = input.getBlock(x, y, z);
        if (!isBlockSolid(neighborBlock)) {
            return false;
        }

        BlockSize neighborSize = input.getBlockSize(x, y, z);
        float neighborSizeInUnits = neighborSize.units();

        return neighborSizeInUnits >= selfSizeInUnits;
    }

    private static int computeTextureVariant(int x, int y, int z) {
        return 0;
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
            float size,
            int face,
            TextureRegion region,
            int textureVariant) {
        float[] faceVerts =
                getFaceVertices(x, y, z, size, face, region, textureVariant);
        for (float v : faceVerts) {
            vertices.add(v);
        }
    }
    
    private static float[] getFaceVertices(
            float x,
            float y,
            float z,
            float size,
            int face,
            TextureRegion region,
            int textureVariant) {
        float variantOffset = textureVariant * (1.0f / TEXTURE_VARIANTS);
        float variantWidth = 1.0f / TEXTURE_VARIANTS;
        
        float baseUMin = region.uMin();
        float baseUMax = region.uMax();
        float baseVMin = region.vMin();
        float baseVMax = region.vMax();
        
        float u = baseUMin + variantOffset * (baseUMax - baseUMin);
        float uEnd = baseUMin + (variantOffset + variantWidth) * (baseUMax - baseUMin);
        float v = baseVMin;
        float vEnd = baseVMax;
        
        boolean flipV = (face != 2);
        float v0 = flipV ? vEnd : v;
        float v1 = flipV ? v : vEnd;
        
        switch (face) {
            case 0: return new float[] {
                x, y, z, u, v0,
                x + size, y, z, uEnd, v0,
                x + size, y + size, z, uEnd, v1,
                x + size, y + size, z, uEnd, v1,
                x, y + size, z, u, v1,
                x, y, z, u, v0
            };
            case 1: return new float[] {
                x, y, z + size, u, v0,
                x + size, y, z + size, uEnd, v0,
                x + size, y + size, z + size, uEnd, v1,
                x + size, y + size, z + size, uEnd, v1,
                x, y + size, z + size, u, v1,
                x, y, z + size, u, v0
            };
            case 2: return new float[] {
                x, y + size, z + size, u, v0,
                x + size, y + size, z + size, uEnd, v0,
                x + size, y + size, z, uEnd, v1,
                x + size, y + size, z, uEnd, v1,
                x, y + size, z, u, v1,
                x, y + size, z + size, u, v0
            };
            case 3: return new float[] {
                x, y, z, u, v0,
                x + size, y, z, uEnd, v0,
                x + size, y, z + size, uEnd, v1,
                x + size, y, z + size, uEnd, v1,
                x, y, z + size, u, v1,
                x, y, z, u, v0
            };
            case 4: return new float[] {
                x, y, z, u, v0,
                x, y, z + size, uEnd, v0,
                x, y + size, z + size, uEnd, v1,
                x, y + size, z + size, uEnd, v1,
                x, y + size, z, u, v1,
                x, y, z, u, v0
            };
            case 5: return new float[] {
                x + size, y, z + size, u, v0,
                x + size, y, z, uEnd, v0,
                x + size, y + size, z, uEnd, v1,
                x + size, y + size, z, uEnd, v1,
                x + size, y + size, z + size, u, v1,
                x + size, y, z + size, u, v0
            };
            default: return new float[0];
        }
    }

    private enum FaceDirection {
        NORTH, SOUTH, UP, DOWN, WEST, EAST
    }
}
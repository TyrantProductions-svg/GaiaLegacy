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

                    BlockPlacement placement =
                            input.center().getBlockPlacement(x, y, z);
                    BlockSize blockSize = placement.size();
                    BlockRenderInfo renderInfo =
                            renderResolver.resolve(
                                    Byte.toUnsignedInt(block));
                    if (!renderInfo.renderable()) {
                        continue;
                    }

                    if (!isFaceOccludedByNeighbor(input, x, y, z, FACES[0], placement)) {
                        addFace(
                                vertices, input, x, y, z, FACES[0],
                                renderInfo.region(FACES[0]), placement);
                    }
                    if (!isFaceOccludedByNeighbor(input, x, y, z, FACES[1], placement)) {
                        addFace(
                                vertices, input, x, y, z, FACES[1],
                                renderInfo.region(FACES[1]), placement);
                    }
                    if (!isFaceOccludedByNeighbor(input, x, y, z, FACES[2], placement)) {
                        addFace(
                                vertices, input, x, y, z, FACES[2],
                                renderInfo.region(FACES[2]), placement);
                    }
                    if (!isFaceOccludedByNeighbor(input, x, y, z, FACES[3], placement)) {
                        addFace(
                                vertices, input, x, y, z, FACES[3],
                                renderInfo.region(FACES[3]), placement);
                    }
                    if (!isFaceOccludedByNeighbor(input, x, y, z, FACES[4], placement)) {
                        addFace(
                                vertices, input, x, y, z, FACES[4],
                                renderInfo.region(FACES[4]), placement);
                    }
                    if (!isFaceOccludedByNeighbor(input, x, y, z, FACES[5], placement)) {
                        addFace(
                                vertices, input, x, y, z, FACES[5],
                                renderInfo.region(FACES[5]), placement);
                    }
                }
            }
        }

        return new ChunkMeshData(
                input.center().key(),
                input.center().revision(),
                toArray(vertices));
    }

    private boolean isFaceOccludedByNeighbor(
            ChunkMeshInput input,
            int x,
            int y,
            int z,
            BlockFace face,
            BlockPlacement placement) {
        int neighborX = x;
        int neighborY = y;
        int neighborZ = z;

        switch (face) {
            case NORTH -> neighborZ--;
            case SOUTH -> neighborZ++;
            case UP -> neighborY++;
            case DOWN -> neighborY--;
            case WEST -> neighborX--;
            case EAST -> neighborX++;
        }

        if (neighborY < 0 || neighborY >= input.center().worldHeight()) {
            return false;
        }

        byte neighborBlock = input.getBlock(neighborX, neighborY, neighborZ);
        if (neighborBlock == 0) {
            return false;
        }

        BlockRenderInfo neighborRender =
                renderResolver.resolve(Byte.toUnsignedInt(neighborBlock));
        if (!neighborRender.renderable()) {
            return false;
        }

        BlockPlacement neighborPlacement =
                input.getBlockPlacement(neighborX, neighborY, neighborZ);
        return isFaceOccludedByNeighbor(
                face,
                x,
                y,
                z,
                placement,
                neighborX,
                neighborY,
                neighborZ,
                neighborPlacement);
    }

    private static boolean isFaceOccludedByNeighbor(
            BlockFace face,
            int x,
            int y,
            int z,
            BlockPlacement placement,
            int neighborX,
            int neighborY,
            int neighborZ,
            BlockPlacement neighborPlacement) {
        float selfMinX = x + placement.offsetX();
        float selfMaxX = selfMinX + placement.size().units();
        float selfMinY = y + placement.offsetY();
        float selfMaxY = selfMinY + placement.size().units();
        float selfMinZ = z + placement.offsetZ();
        float selfMaxZ = selfMinZ + placement.size().units();

        float neighborMinX = neighborX + neighborPlacement.offsetX();
        float neighborMaxX = neighborMinX + neighborPlacement.size().units();
        float neighborMinY = neighborY + neighborPlacement.offsetY();
        float neighborMaxY = neighborMinY + neighborPlacement.size().units();
        float neighborMinZ = neighborZ + neighborPlacement.offsetZ();
        float neighborMaxZ = neighborMinZ + neighborPlacement.size().units();

        final float epsilon = 1e-6f;
        return switch (face) {
            case NORTH -> floatEquals(selfMinZ, neighborMaxZ, epsilon)
                    && rangesOverlap(selfMinX, selfMaxX, neighborMinX, neighborMaxX)
                    && rangesOverlap(selfMinY, selfMaxY, neighborMinY, neighborMaxY);
            case SOUTH -> floatEquals(selfMaxZ, neighborMinZ, epsilon)
                    && rangesOverlap(selfMinX, selfMaxX, neighborMinX, neighborMaxX)
                    && rangesOverlap(selfMinY, selfMaxY, neighborMinY, neighborMaxY);
            case WEST -> floatEquals(selfMinX, neighborMaxX, epsilon)
                    && rangesOverlap(selfMinY, selfMaxY, neighborMinY, neighborMaxY)
                    && rangesOverlap(selfMinZ, selfMaxZ, neighborMinZ, neighborMaxZ);
            case EAST -> floatEquals(selfMaxX, neighborMinX, epsilon)
                    && rangesOverlap(selfMinY, selfMaxY, neighborMinY, neighborMaxY)
                    && rangesOverlap(selfMinZ, selfMaxZ, neighborMinZ, neighborMaxZ);
            case DOWN -> floatEquals(selfMinY, neighborMaxY, epsilon)
                    && rangesOverlap(selfMinX, selfMaxX, neighborMinX, neighborMaxX)
                    && rangesOverlap(selfMinZ, selfMaxZ, neighborMinZ, neighborMaxZ);
            case UP -> floatEquals(selfMaxY, neighborMinY, epsilon)
                    && rangesOverlap(selfMinX, selfMaxX, neighborMinX, neighborMaxX)
                    && rangesOverlap(selfMinZ, selfMaxZ, neighborMinZ, neighborMaxZ);
        };
    }

    private static boolean rangesOverlap(
            float minA,
            float maxA,
            float minB,
            float maxB) {
        return Math.min(maxA, maxB) > Math.max(minA, minB);
    }

    private static boolean floatEquals(
            float a,
            float b,
            float epsilon) {
        return Math.abs(a - b) < epsilon;
    }

    private static float[] toArray(List<Float> vertices) {
        float[] vertexArray = new float[vertices.size()];
        for (int i = 0; i < vertices.size(); i++) {
            vertexArray[i] = vertices.get(i);
        }
        return vertexArray;
    }
    
    private void addFace(
            List<Float> vertices,
            ChunkMeshInput input,
            int x,
            int y,
            int z,
            BlockFace face,
            TextureRegion region,
            BlockPlacement placement) {
        BlockSize blockSize = placement.size();
        int pixels = blockSize.pixels();
        float size = blockSize.units();
        float uRange = region.uMax() - region.uMin();
        float vRange = region.vMax() - region.vMin();

        float originX = x + placement.offsetX();
        float originY = y + placement.offsetY();
        float originZ = z + placement.offsetZ();
        
        float u, uEnd, v, vEnd;
        
        if (blockSize == BlockSize.SIZE_16) {
            u = region.uMin();
            uEnd = region.uMax();
            v = region.vMin();
            vEnd = region.vMax();
        } else {
            int localX = Math.round(placement.offsetX() * 16.0f);
            int localY = Math.round(placement.offsetY() * 16.0f);
            int localZ = Math.round(placement.offsetZ() * 16.0f);
            switch (face) {
                case NORTH, SOUTH -> {
                    u = region.uMin() + (localX / 16.0f) * uRange;
                    uEnd = region.uMin() + ((localX + pixels) / 16.0f) * uRange;
                    v = region.vMin() + (localY / 16.0f) * vRange;
                    vEnd = region.vMin() + ((localY + pixels) / 16.0f) * vRange;
                }
                case UP, DOWN -> {
                    u = region.uMin() + (localX / 16.0f) * uRange;
                    uEnd = region.uMin() + ((localX + pixels) / 16.0f) * uRange;
                    v = region.vMin() + (localZ / 16.0f) * vRange;
                    vEnd = region.vMin() + ((localZ + pixels) / 16.0f) * vRange;
                }
                case WEST, EAST -> {
                    u = region.uMin() + (localZ / 16.0f) * uRange;
                    uEnd = region.uMin() + ((localZ + pixels) / 16.0f) * uRange;
                    v = region.vMin() + (localY / 16.0f) * vRange;
                    vEnd = region.vMin() + ((localY + pixels) / 16.0f) * vRange;
                }
                default -> {
                    u = region.uMin();
                    uEnd = region.uMax();
                    v = region.vMin();
                    vEnd = region.vMax();
                }
            }
        }

        boolean flipV = face != BlockFace.UP;
        float v0 = flipV ? vEnd : v;
        float v1 = flipV ? v : vEnd;
        float aoNegativeNegative =
                VoxelAmbientOcclusion.sample(
                        input, renderResolver, x, y, z, face, -1, -1);
        float aoPositiveNegative =
                VoxelAmbientOcclusion.sample(
                        input, renderResolver, x, y, z, face, 1, -1);
        float aoPositivePositive =
                VoxelAmbientOcclusion.sample(
                        input, renderResolver, x, y, z, face, 1, 1);
        float aoNegativePositive =
                VoxelAmbientOcclusion.sample(
                        input, renderResolver, x, y, z, face, -1, 1);

        switch (face) {
            case NORTH -> {
                addVertex(vertices, originX, originY, originZ, u, v0, face, 0, 0, -1, aoNegativeNegative);
                addVertex(vertices, originX + size, originY, originZ, uEnd, v0, face, 0, 0, -1, aoPositiveNegative);
                addVertex(vertices, originX + size, originY + size, originZ, uEnd, v1, face, 0, 0, -1, aoPositivePositive);
                addVertex(vertices, originX + size, originY + size, originZ, uEnd, v1, face, 0, 0, -1, aoPositivePositive);
                addVertex(vertices, originX, originY + size, originZ, u, v1, face, 0, 0, -1, aoNegativePositive);
                addVertex(vertices, originX, originY, originZ, u, v0, face, 0, 0, -1, aoNegativeNegative);
            }
            case SOUTH -> {
                addVertex(vertices, originX, originY, originZ + size, u, v0, face, 0, 0, 1, aoNegativeNegative);
                addVertex(vertices, originX + size, originY, originZ + size, uEnd, v0, face, 0, 0, 1, aoPositiveNegative);
                addVertex(vertices, originX + size, originY + size, originZ + size, uEnd, v1, face, 0, 0, 1, aoPositivePositive);
                addVertex(vertices, originX + size, originY + size, originZ + size, uEnd, v1, face, 0, 0, 1, aoPositivePositive);
                addVertex(vertices, originX, originY + size, originZ + size, u, v1, face, 0, 0, 1, aoNegativePositive);
                addVertex(vertices, originX, originY, originZ + size, u, v0, face, 0, 0, 1, aoNegativeNegative);
            }
            case UP -> {
                addVertex(vertices, originX, originY + size, originZ + size, u, v0, face, 0, 1, 0, aoNegativePositive);
                addVertex(vertices, originX + size, originY + size, originZ + size, uEnd, v0, face, 0, 1, 0, aoPositivePositive);
                addVertex(vertices, originX + size, originY + size, originZ, uEnd, v1, face, 0, 1, 0, aoPositiveNegative);
                addVertex(vertices, originX + size, originY + size, originZ, uEnd, v1, face, 0, 1, 0, aoPositiveNegative);
                addVertex(vertices, originX, originY + size, originZ, u, v1, face, 0, 1, 0, aoNegativeNegative);
                addVertex(vertices, originX, originY + size, originZ + size, u, v0, face, 0, 1, 0, aoNegativePositive);
            }
            case DOWN -> {
                addVertex(vertices, originX, originY, originZ, u, v0, face, 0, -1, 0, aoNegativeNegative);
                addVertex(vertices, originX + size, originY, originZ, uEnd, v0, face, 0, -1, 0, aoPositiveNegative);
                addVertex(vertices, originX + size, originY, originZ + size, uEnd, v1, face, 0, -1, 0, aoPositivePositive);
                addVertex(vertices, originX + size, originY, originZ + size, uEnd, v1, face, 0, -1, 0, aoPositivePositive);
                addVertex(vertices, originX, originY, originZ + size, u, v1, face, 0, -1, 0, aoNegativePositive);
                addVertex(vertices, originX, originY, originZ, u, v0, face, 0, -1, 0, aoNegativeNegative);
            }
            case WEST -> {
                addVertex(vertices, originX, originY, originZ, u, v0, face, -1, 0, 0, aoNegativeNegative);
                addVertex(vertices, originX, originY, originZ + size, uEnd, v0, face, -1, 0, 0, aoPositiveNegative);
                addVertex(vertices, originX, originY + size, originZ + size, uEnd, v1, face, -1, 0, 0, aoPositivePositive);
                addVertex(vertices, originX, originY + size, originZ + size, uEnd, v1, face, -1, 0, 0, aoPositivePositive);
                addVertex(vertices, originX, originY + size, originZ, u, v1, face, -1, 0, 0, aoNegativePositive);
                addVertex(vertices, originX, originY, originZ, u, v0, face, -1, 0, 0, aoNegativeNegative);
            }
            case EAST -> {
                addVertex(vertices, originX + size, originY, originZ + size, u, v0, face, 1, 0, 0, aoPositiveNegative);
                addVertex(vertices, originX + size, originY, originZ, uEnd, v0, face, 1, 0, 0, aoNegativeNegative);
                addVertex(vertices, originX + size, originY + size, originZ, uEnd, v1, face, 1, 0, 0, aoNegativePositive);
                addVertex(vertices, originX + size, originY + size, originZ, uEnd, v1, face, 1, 0, 0, aoNegativePositive);
                addVertex(vertices, originX + size, originY + size, originZ + size, u, v1, face, 1, 0, 0, aoPositivePositive);
                addVertex(vertices, originX + size, originY, originZ + size, u, v0, face, 1, 0, 0, aoPositiveNegative);
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
            float normalZ,
            float ambientOcclusion) {
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
        vertices.add(ambientOcclusion);
    }
}
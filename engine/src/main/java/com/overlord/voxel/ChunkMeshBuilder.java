package com.overlord.voxel;

import com.overlord.config.GameConfig;
import com.overlord.renderer.texture.TextureRegion;
import java.util.Arrays;
import java.util.Objects;
import java.util.function.IntPredicate;

public final class ChunkMeshBuilder implements ChunkMesher {
    public static final long MAX_HYBRID_MESH_BYTES = 8L * 1024L * 1024L;
    private static final int VERTICES_PER_FACELET = 6;
    private static final int FLOATS_PER_FACELET = Math.multiplyExact(
            VERTICES_PER_FACELET, VoxelVertexFormat.FLOATS_PER_VERTEX);
    private static final int SUBDIVISIONS =
            VoxelScale.DETAIL_4.subdivisionsPerAxis();
    private static final float QUARTER = 0.25f;
    private static final BlockFace[] FACES = {
        BlockFace.NORTH,
        BlockFace.SOUTH,
        BlockFace.UP,
        BlockFace.DOWN,
        BlockFace.WEST,
        BlockFace.EAST
    };
    private static final int[][] AO_SIGNS = {
        {-1, -1}, {1, -1}, {1, 1}, {-1, 1}
    };

    private final BlockRenderResolver renderResolver;

    public ChunkMeshBuilder(BlockRenderResolver renderResolver) {
        this.renderResolver = Objects.requireNonNull(
                renderResolver, "renderResolver");
    }

    @Override
    public ChunkMeshData build(ChunkMeshInput input) {
        ChunkMeshInput required = Objects.requireNonNull(input, "input");
        return build(required, preflight(required));
    }

    @Override
    public ChunkMeshMemoryPlan preflight(ChunkMeshInput input) {
        ChunkMeshInput required = Objects.requireNonNull(input, "input");
        boolean hybrid = requiresQuarterGrid(required);
        ChunkMeshGeometryBounds.OutputBound bound = hybrid
                ? ChunkMeshGeometryBounds.forInput(required)
                : ChunkMeshGeometryBounds.fullOnly(
                        required.center().worldHeight());
        CountOutput vertices = new CountOutput(new OutputSizing(
                bound.floatArrayLimit(),
                hybrid ? required.center().key() : null,
                required.center().revision(),
                hybrid ? MAX_HYBRID_MESH_BYTES : Long.MAX_VALUE,
                0));
        if (hybrid) {
            buildHybrid(required, vertices);
        } else {
            buildFullOnly(required, vertices);
        }
        return vertices.plan();
    }

    @Override
    public ChunkMeshData build(
            ChunkMeshInput input, ChunkMeshMemoryPlan approvedPlan) {
        ChunkMeshInput required = Objects.requireNonNull(input, "input");
        ChunkMeshMemoryPlan plan = Objects.requireNonNull(
                approvedPlan, "approvedPlan");
        boolean hybrid = requiresQuarterGrid(required);
        ChunkMeshGeometryBounds.OutputBound bound = hybrid
                ? ChunkMeshGeometryBounds.forInput(required)
                : ChunkMeshGeometryBounds.fullOnly(
                        required.center().worldHeight());
        int expectedFloatCount = exactFloatCount(plan.outputBytes());
        long expectedReservation = Math.multiplyExact(
                plan.outputBytes(), 3L);
        if (plan.activeReservationBytes() != expectedReservation) {
            throw new IllegalArgumentException(
                    "approvedPlan does not match exact builder allocation");
        }
        FloatOutput vertices = new FloatOutput(new OutputSizing(
                bound.floatArrayLimit(),
                hybrid ? required.center().key() : null,
                required.center().revision(),
                hybrid ? MAX_HYBRID_MESH_BYTES : Long.MAX_VALUE,
                expectedFloatCount));
        if (hybrid) {
            buildHybrid(required, vertices);
        } else {
            buildFullOnly(required, vertices);
        }
        if (!vertices.plan().equals(plan)) {
            throw new IllegalStateException(
                    "Chunk mesh output differs from admitted preflight");
        }
        return new ChunkMeshData(
                required.center().key(),
                required.center().revision(),
                vertices.toArray());
    }

    private static int exactFloatCount(long outputBytes) {
        if (outputBytes < 0L || outputBytes % Float.BYTES != 0L) {
            throw new IllegalArgumentException(
                    "approvedPlan output bytes must describe whole floats");
        }
        return Math.toIntExact(outputBytes / Float.BYTES);
    }

    private void buildFullOnly(ChunkMeshInput input, MeshOutput vertices) {
        for (int x = 0; x < GameConfig.Chunk.SIZE; x++) {
            for (int y = 0; y < input.center().worldHeight(); y++) {
                for (int z = 0; z < GameConfig.Chunk.SIZE; z++) {
                    byte block = input.fullOnlyBlock(x, y, z);
                    if (block == 0) {
                        continue;
                    }
                    BlockRenderInfo renderInfo = renderResolver.resolve(
                            Byte.toUnsignedInt(block));
                    if (!renderInfo.renderable()) {
                        continue;
                    }
                    if (!isBlockSolid(input.fullOnlyBlock(x, y, z - 1))) {
                        addFullFace(vertices, input, x, y, z, FACES[0],
                                renderInfo.region(FACES[0]));
                    }
                    if (!isBlockSolid(input.fullOnlyBlock(x, y, z + 1))) {
                        addFullFace(vertices, input, x, y, z, FACES[1],
                                renderInfo.region(FACES[1]));
                    }
                    if (!isBlockSolid(input.fullOnlyBlock(x, y + 1, z))) {
                        addFullFace(vertices, input, x, y, z, FACES[2],
                                renderInfo.region(FACES[2]));
                    }
                    if (!isBlockSolid(input.fullOnlyBlock(x, y - 1, z))) {
                        addFullFace(vertices, input, x, y, z, FACES[3],
                                renderInfo.region(FACES[3]));
                    }
                    if (!isBlockSolid(input.fullOnlyBlock(x - 1, y, z))) {
                        addFullFace(vertices, input, x, y, z, FACES[4],
                                renderInfo.region(FACES[4]));
                    }
                    if (!isBlockSolid(input.fullOnlyBlock(x + 1, y, z))) {
                        addFullFace(vertices, input, x, y, z, FACES[5],
                                renderInfo.region(FACES[5]));
                    }
                }
            }
        }
    }

    private void buildHybrid(ChunkMeshInput input, MeshOutput vertices) {
        QuarterVoxelSampler sampler = new QuarterVoxelSampler(input);
        for (int x = 0; x < GameConfig.Chunk.SIZE; x++) {
            for (int y = 0; y < input.center().worldHeight(); y++) {
                for (int z = 0; z < GameConfig.Chunk.SIZE; z++) {
                    ParentCellState state = input.center().cellState(x, y, z);
                    if (state instanceof DetailCellState detail) {
                        addDetailParent(vertices, sampler, x, y, z, detail);
                    } else {
                        byte blockId = ((FullCellState) state).blockId();
                        if (blockId != 0) {
                            addHybridFullParent(
                                    vertices, input, sampler,
                                    x, y, z, blockId);
                        }
                    }
                }
            }
        }
    }

    private void addHybridFullParent(
            MeshOutput vertices,
            ChunkMeshInput input,
            QuarterVoxelSampler sampler,
            int x,
            int y,
            int z,
            byte blockId) {
        BlockRenderInfo renderInfo = renderResolver.resolve(
                Byte.toUnsignedInt(blockId));
        if (!renderInfo.renderable()) {
            return;
        }
        for (BlockFace face : FACES) {
            ParentCellState neighbor = input.cellState(
                    x + normalX(face),
                    y + normalY(face),
                    z + normalZ(face));
            if (neighbor instanceof DetailCellState
                    || fullFaceAoTouchesDetail(sampler, x, y, z, face)) {
                addClippedFullFace(
                        vertices, sampler, x, y, z, face,
                        renderInfo.region(face));
            } else if (!isSurfaceSolid(
                    ((FullCellState) neighbor).blockId())) {
                addFullFace(
                        vertices, input, x, y, z, face,
                        renderInfo.region(face));
            }
        }
    }

    private void addDetailParent(
            MeshOutput vertices,
            QuarterVoxelSampler sampler,
            int parentX,
            int parentY,
            int parentZ,
            DetailCellState detail) {
        long occupancy = detail.occupancyMask();
        for (int subIndex = 0;
                subIndex < DetailCellState.CELL_COUNT;
                subIndex++) {
            if ((occupancy & (1L << subIndex)) == 0L) {
                continue;
            }
            byte blockId = detail.blockIdAtIndex(subIndex);
            BlockRenderInfo renderInfo = renderResolver.resolve(
                    Byte.toUnsignedInt(blockId));
            if (!renderInfo.renderable()) {
                continue;
            }
            int subX = subIndex & 3;
            int subY = (subIndex >>> 2) & 3;
            int subZ = subIndex >>> 4;
            for (BlockFace face : FACES) {
                QuarterVoxelSample neighbor = sampler.sample(
                        parentX,
                        parentY,
                        parentZ,
                        subX + normalX(face),
                        subY + normalY(face),
                        subZ + normalZ(face));
                if (isSurfaceSolid(neighbor)) {
                    continue;
                }
                float aoNN = quarterAo(sampler, parentX, parentY, parentZ,
                        subX, subY, subZ, face, -1, -1);
                float aoPN = quarterAo(sampler, parentX, parentY, parentZ,
                        subX, subY, subZ, face, 1, -1);
                float aoPP = quarterAo(sampler, parentX, parentY, parentZ,
                        subX, subY, subZ, face, 1, 1);
                float aoNP = quarterAo(sampler, parentX, parentY, parentZ,
                        subX, subY, subZ, face, -1, 1);
                addDetailFacelet(
                        vertices,
                        parentX,
                        parentY,
                        parentZ,
                        face,
                        subX,
                        subY,
                        subZ,
                        renderInfo.region(face),
                        aoNN,
                        aoPN,
                        aoPP,
                        aoNP);
            }
        }
    }

    private static void addDetailFacelet(
            MeshOutput vertices,
            int parentX,
            int parentY,
            int parentZ,
            BlockFace face,
            int subX,
            int subY,
            int subZ,
            TextureRegion region,
            float aoNN,
            float aoPN,
            float aoPP,
            float aoNP) {
        float minX = parentX + subX * QUARTER;
        float minY = parentY + subY * QUARTER;
        float minZ = parentZ + subZ * QUARTER;
        float maxX = minX + QUARTER;
        float maxY = minY + QUARTER;
        float maxZ = minZ + QUARTER;
        addPatch(
                vertices,
                minX,
                minY,
                minZ,
                maxX,
                maxY,
                maxZ,
                face,
                region.uMin(),
                region.uMax(),
                region.vMin(),
                region.vMax(),
                aoNN,
                aoPN,
                aoPP,
                aoNP);
    }

    private void addClippedFullFace(
            MeshOutput vertices,
            QuarterVoxelSampler sampler,
            int parentX,
            int parentY,
            int parentZ,
            BlockFace face,
            TextureRegion region) {
        boolean[] visible = new boolean[16];
        boolean[] emitted = new boolean[16];
        float[] ao = new float[16 * 4];
        for (int v = 0; v < SUBDIVISIONS; v++) {
            for (int u = 0; u < SUBDIVISIONS; u++) {
                int facelet = u + SUBDIVISIONS * v;
                int subX = faceSubX(face, u, v);
                int subY = faceSubY(face, u, v);
                int subZ = faceSubZ(face, u, v);
                QuarterVoxelSample neighbor = sampler.sample(
                        parentX,
                        parentY,
                        parentZ,
                        subX + normalX(face),
                        subY + normalY(face),
                        subZ + normalZ(face));
                visible[facelet] = !isSurfaceSolid(neighbor);
                if (!visible[facelet]) {
                    continue;
                }
                for (int corner = 0; corner < AO_SIGNS.length; corner++) {
                    ao[facelet * 4 + corner] = quarterAo(
                            sampler,
                            parentX,
                            parentY,
                            parentZ,
                            subX,
                            subY,
                            subZ,
                            face,
                            AO_SIGNS[corner][0],
                            AO_SIGNS[corner][1]);
                }
            }
        }
        emitGreedyFullPatches(
                vertices,
                parentX,
                parentY,
                parentZ,
                face,
                region,
                visible,
                emitted,
                ao);
    }

    private static void emitGreedyFullPatches(
            MeshOutput vertices,
            int parentX,
            int parentY,
            int parentZ,
            BlockFace face,
            TextureRegion region,
            boolean[] visible,
            boolean[] emitted,
            float[] ao) {
        for (int v = 0; v < SUBDIVISIONS; v++) {
            for (int u = 0; u < SUBDIVISIONS; u++) {
                int seed = u + SUBDIVISIONS * v;
                if (!visible[seed] || emitted[seed]) {
                    continue;
                }
                int maxU = u + 1;
                int maxV = v + 1;
                float uniformAo = uniformAo(ao, seed);
                if (!Float.isNaN(uniformAo)) {
                    while (maxU < SUBDIVISIONS
                            && mergeCompatible(
                                    visible, emitted, ao,
                                    maxU, v, uniformAo)) {
                        maxU++;
                    }
                    while (maxV < SUBDIVISIONS
                            && mergeRowCompatible(
                                    visible, emitted, ao,
                                    u, maxU, maxV, uniformAo)) {
                        maxV++;
                    }
                }
                for (int mergedV = v; mergedV < maxV; mergedV++) {
                    for (int mergedU = u; mergedU < maxU; mergedU++) {
                        emitted[mergedU + SUBDIVISIONS * mergedV] = true;
                    }
                }
                float aoNN = Float.isNaN(uniformAo)
                        ? ao[seed * 4]
                        : uniformAo;
                float aoPN = Float.isNaN(uniformAo)
                        ? ao[seed * 4 + 1]
                        : uniformAo;
                float aoPP = Float.isNaN(uniformAo)
                        ? ao[seed * 4 + 2]
                        : uniformAo;
                float aoNP = Float.isNaN(uniformAo)
                        ? ao[seed * 4 + 3]
                        : uniformAo;
                addQuarterPatch(
                        vertices,
                        parentX,
                        parentY,
                        parentZ,
                        face,
                        u,
                        v,
                        maxU,
                        maxV,
                        region,
                        true,
                        aoNN,
                        aoPN,
                        aoPP,
                        aoNP);
            }
        }
    }

    private boolean fullFaceAoTouchesDetail(
            QuarterVoxelSampler sampler,
            int parentX,
            int parentY,
            int parentZ,
            BlockFace face) {
        for (int v = 0; v < SUBDIVISIONS; v++) {
            for (int u = 0; u < SUBDIVISIONS; u++) {
                int subX = faceSubX(face, u, v);
                int subY = faceSubY(face, u, v);
                int subZ = faceSubZ(face, u, v);
                for (int[] signs : AO_SIGNS) {
                    if (VoxelAmbientOcclusion.quarterSamplesDetail(
                            sampler,
                            parentX,
                            parentY,
                            parentZ,
                            subX,
                            subY,
                            subZ,
                            face,
                            signs[0],
                            signs[1])) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private float quarterAo(
            QuarterVoxelSampler sampler,
            int parentX,
            int parentY,
            int parentZ,
            int subX,
            int subY,
            int subZ,
            BlockFace face,
            int signA,
            int signB) {
        return VoxelAmbientOcclusion.sampleQuarter(
                sampler,
                renderResolver,
                parentX,
                parentY,
                parentZ,
                subX,
                subY,
                subZ,
                face,
                signA,
                signB);
    }

    private boolean isBlockSolid(byte block) {
        return block != 0
                && renderResolver.resolve(Byte.toUnsignedInt(block)).renderable();
    }

    private boolean isSurfaceSolid(byte blockId) {
        return blockId != 0
                && renderResolver.resolve(
                        Byte.toUnsignedInt(blockId)).renderable();
    }

    private boolean isSurfaceSolid(QuarterVoxelSample sample) {
        return sample.occupied()
                && renderResolver.resolve(
                        Byte.toUnsignedInt(sample.blockId())).renderable();
    }

    private void addFullFace(
            MeshOutput vertices,
            ChunkMeshInput input,
            int x,
            int y,
            int z,
            BlockFace face,
            TextureRegion region) {
        float aoNN = VoxelAmbientOcclusion.sample(
                input, renderResolver, x, y, z, face, -1, -1);
        float aoPN = VoxelAmbientOcclusion.sample(
                input, renderResolver, x, y, z, face, 1, -1);
        float aoPP = VoxelAmbientOcclusion.sample(
                input, renderResolver, x, y, z, face, 1, 1);
        float aoNP = VoxelAmbientOcclusion.sample(
                input, renderResolver, x, y, z, face, -1, 1);
        addPatch(
                vertices,
                x,
                y,
                z,
                x + 1,
                y + 1,
                z + 1,
                face,
                region.uMin(),
                region.uMax(),
                region.vMin(),
                region.vMax(),
                aoNN,
                aoPN,
                aoPP,
                aoNP);
    }

    private static void addQuarterPatch(
            MeshOutput vertices,
            int parentX,
            int parentY,
            int parentZ,
            BlockFace face,
            int minUQuarter,
            int minVQuarter,
            int maxUQuarter,
            int maxVQuarter,
            TextureRegion region,
            boolean cropFullTexture,
            float aoNN,
            float aoPN,
            float aoPP,
            float aoNP) {
        float minX;
        float minY;
        float minZ;
        float maxX;
        float maxY;
        float maxZ;
        switch (face) {
            case NORTH -> {
                minX = parentX + minUQuarter * QUARTER;
                maxX = parentX + maxUQuarter * QUARTER;
                minY = parentY + minVQuarter * QUARTER;
                maxY = parentY + maxVQuarter * QUARTER;
                minZ = parentZ;
                maxZ = parentZ;
            }
            case SOUTH -> {
                minX = parentX + minUQuarter * QUARTER;
                maxX = parentX + maxUQuarter * QUARTER;
                minY = parentY + minVQuarter * QUARTER;
                maxY = parentY + maxVQuarter * QUARTER;
                minZ = parentZ + 1;
                maxZ = parentZ + 1;
            }
            case UP -> {
                minX = parentX + minUQuarter * QUARTER;
                maxX = parentX + maxUQuarter * QUARTER;
                minY = parentY + 1;
                maxY = parentY + 1;
                minZ = parentZ + minVQuarter * QUARTER;
                maxZ = parentZ + maxVQuarter * QUARTER;
            }
            case DOWN -> {
                minX = parentX + minUQuarter * QUARTER;
                maxX = parentX + maxUQuarter * QUARTER;
                minY = parentY;
                maxY = parentY;
                minZ = parentZ + minVQuarter * QUARTER;
                maxZ = parentZ + maxVQuarter * QUARTER;
            }
            case WEST -> {
                minX = parentX;
                maxX = parentX;
                minY = parentY + minVQuarter * QUARTER;
                maxY = parentY + maxVQuarter * QUARTER;
                minZ = parentZ + minUQuarter * QUARTER;
                maxZ = parentZ + maxUQuarter * QUARTER;
            }
            case EAST -> {
                minX = parentX + 1;
                maxX = parentX + 1;
                minY = parentY + minVQuarter * QUARTER;
                maxY = parentY + maxVQuarter * QUARTER;
                minZ = parentZ + minUQuarter * QUARTER;
                maxZ = parentZ + maxUQuarter * QUARTER;
            }
            default -> throw new IllegalStateException("Unhandled face " + face);
        }

        float uMin = region.uMin();
        float uMax = region.uMax();
        float vMin = region.vMin();
        float vMax = region.vMax();
        if (cropFullTexture) {
            float uSpan = region.uMax() - region.uMin();
            float vSpan = region.vMax() - region.vMin();
            if (face == BlockFace.EAST) {
                uMin = region.uMin()
                        + uSpan * (SUBDIVISIONS - maxUQuarter) / SUBDIVISIONS;
                uMax = region.uMin()
                        + uSpan * (SUBDIVISIONS - minUQuarter) / SUBDIVISIONS;
            } else {
                uMin = region.uMin()
                        + uSpan * minUQuarter / SUBDIVISIONS;
                uMax = region.uMin()
                        + uSpan * maxUQuarter / SUBDIVISIONS;
            }
            vMin = region.vMax()
                    - vSpan * maxVQuarter / SUBDIVISIONS;
            vMax = region.vMax()
                    - vSpan * minVQuarter / SUBDIVISIONS;
        }
        addPatch(
                vertices,
                minX,
                minY,
                minZ,
                maxX,
                maxY,
                maxZ,
                face,
                uMin,
                uMax,
                vMin,
                vMax,
                aoNN,
                aoPN,
                aoPP,
                aoNP);
    }

    private static void addPatch(
            MeshOutput vertices,
            float minX,
            float minY,
            float minZ,
            float maxX,
            float maxY,
            float maxZ,
            BlockFace face,
            float u,
            float uEnd,
            float v,
            float vEnd,
            float aoNN,
            float aoPN,
            float aoPP,
            float aoNP) {
        vertices.beginFacelet();
        boolean flipV = face != BlockFace.UP;
        float v0 = flipV ? vEnd : v;
        float v1 = flipV ? v : vEnd;
        switch (face) {
            case NORTH -> {
                addVertex(vertices, minX, minY, minZ, u, v0, face, 0, 0, -1, aoNN);
                addVertex(vertices, maxX, minY, minZ, uEnd, v0, face, 0, 0, -1, aoPN);
                addVertex(vertices, maxX, maxY, minZ, uEnd, v1, face, 0, 0, -1, aoPP);
                addVertex(vertices, maxX, maxY, minZ, uEnd, v1, face, 0, 0, -1, aoPP);
                addVertex(vertices, minX, maxY, minZ, u, v1, face, 0, 0, -1, aoNP);
                addVertex(vertices, minX, minY, minZ, u, v0, face, 0, 0, -1, aoNN);
            }
            case SOUTH -> {
                addVertex(vertices, minX, minY, maxZ, u, v0, face, 0, 0, 1, aoNN);
                addVertex(vertices, maxX, minY, maxZ, uEnd, v0, face, 0, 0, 1, aoPN);
                addVertex(vertices, maxX, maxY, maxZ, uEnd, v1, face, 0, 0, 1, aoPP);
                addVertex(vertices, maxX, maxY, maxZ, uEnd, v1, face, 0, 0, 1, aoPP);
                addVertex(vertices, minX, maxY, maxZ, u, v1, face, 0, 0, 1, aoNP);
                addVertex(vertices, minX, minY, maxZ, u, v0, face, 0, 0, 1, aoNN);
            }
            case UP -> {
                addVertex(vertices, minX, maxY, maxZ, u, v0, face, 0, 1, 0, aoNP);
                addVertex(vertices, maxX, maxY, maxZ, uEnd, v0, face, 0, 1, 0, aoPP);
                addVertex(vertices, maxX, maxY, minZ, uEnd, v1, face, 0, 1, 0, aoPN);
                addVertex(vertices, maxX, maxY, minZ, uEnd, v1, face, 0, 1, 0, aoPN);
                addVertex(vertices, minX, maxY, minZ, u, v1, face, 0, 1, 0, aoNN);
                addVertex(vertices, minX, maxY, maxZ, u, v0, face, 0, 1, 0, aoNP);
            }
            case DOWN -> {
                addVertex(vertices, minX, minY, minZ, u, v0, face, 0, -1, 0, aoNN);
                addVertex(vertices, maxX, minY, minZ, uEnd, v0, face, 0, -1, 0, aoPN);
                addVertex(vertices, maxX, minY, maxZ, uEnd, v1, face, 0, -1, 0, aoPP);
                addVertex(vertices, maxX, minY, maxZ, uEnd, v1, face, 0, -1, 0, aoPP);
                addVertex(vertices, minX, minY, maxZ, u, v1, face, 0, -1, 0, aoNP);
                addVertex(vertices, minX, minY, minZ, u, v0, face, 0, -1, 0, aoNN);
            }
            case WEST -> {
                addVertex(vertices, minX, minY, minZ, u, v0, face, -1, 0, 0, aoNN);
                addVertex(vertices, minX, minY, maxZ, uEnd, v0, face, -1, 0, 0, aoPN);
                addVertex(vertices, minX, maxY, maxZ, uEnd, v1, face, -1, 0, 0, aoPP);
                addVertex(vertices, minX, maxY, maxZ, uEnd, v1, face, -1, 0, 0, aoPP);
                addVertex(vertices, minX, maxY, minZ, u, v1, face, -1, 0, 0, aoNP);
                addVertex(vertices, minX, minY, minZ, u, v0, face, -1, 0, 0, aoNN);
            }
            case EAST -> {
                addVertex(vertices, maxX, minY, maxZ, u, v0, face, 1, 0, 0, aoPN);
                addVertex(vertices, maxX, minY, minZ, uEnd, v0, face, 1, 0, 0, aoNN);
                addVertex(vertices, maxX, maxY, minZ, uEnd, v1, face, 1, 0, 0, aoNP);
                addVertex(vertices, maxX, maxY, minZ, uEnd, v1, face, 1, 0, 0, aoNP);
                addVertex(vertices, maxX, maxY, maxZ, u, v1, face, 1, 0, 0, aoPP);
                addVertex(vertices, maxX, minY, maxZ, u, v0, face, 1, 0, 0, aoPN);
            }
        }
    }

    private static void addVertex(
            MeshOutput vertices,
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
        vertices.add(VoxelVertexFormat.encodeFaceLight(
                face, VoxelVertexFormat.DEFAULT_LIGHT_LEVEL));
        vertices.add(ambientOcclusion);
    }

    private static boolean requiresQuarterGrid(ChunkMeshInput input) {
        if (!input.center().details().isEmpty()) {
            return true;
        }
        int height = input.center().worldHeight();
        return containsDetail(input.north(), index -> localZ(index, height) == 15)
                || containsDetail(input.northEast(), index ->
                        localX(index) == 0 && localZ(index, height) == 15)
                || containsDetail(input.east(), index -> localX(index) == 0)
                || containsDetail(input.southEast(), index ->
                        localX(index) == 0 && localZ(index, height) == 0)
                || containsDetail(input.south(), index -> localZ(index, height) == 0)
                || containsDetail(input.southWest(), index ->
                        localX(index) == 15 && localZ(index, height) == 0)
                || containsDetail(input.west(), index -> localX(index) == 15)
                || containsDetail(input.northWest(), index ->
                        localX(index) == 15 && localZ(index, height) == 15);
    }

    private static boolean containsDetail(
            ChunkSnapshot snapshot, IntPredicate predicate) {
        for (int parentIndex : snapshot.details().copyParentIndices()) {
            if (predicate.test(parentIndex)) {
                return true;
            }
        }
        return false;
    }

    private static int localX(int parentIndex) {
        return parentIndex % GameConfig.Chunk.SIZE;
    }

    private static int localZ(int parentIndex, int worldHeight) {
        return parentIndex / (GameConfig.Chunk.SIZE * worldHeight);
    }

    private static int normalX(BlockFace face) {
        return switch (face) {
            case WEST -> -1;
            case EAST -> 1;
            default -> 0;
        };
    }

    private static int normalY(BlockFace face) {
        return switch (face) {
            case DOWN -> -1;
            case UP -> 1;
            default -> 0;
        };
    }

    private static int normalZ(BlockFace face) {
        return switch (face) {
            case NORTH -> -1;
            case SOUTH -> 1;
            default -> 0;
        };
    }

    private static int faceSubX(BlockFace face, int u, int v) {
        return switch (face) {
            case WEST -> 0;
            case EAST -> 3;
            default -> u;
        };
    }

    private static int faceSubY(BlockFace face, int u, int v) {
        return switch (face) {
            case DOWN -> 0;
            case UP -> 3;
            default -> v;
        };
    }

    private static int faceSubZ(BlockFace face, int u, int v) {
        return switch (face) {
            case NORTH -> 0;
            case SOUTH -> 3;
            case UP, DOWN -> v;
            case WEST, EAST -> u;
        };
    }

    private static float uniformAo(float[] ao, int faceletIndex) {
        float value = ao[faceletIndex * 4];
        for (int corner = 1; corner < 4; corner++) {
            if (Float.floatToIntBits(ao[faceletIndex * 4 + corner])
                    != Float.floatToIntBits(value)) {
                return Float.NaN;
            }
        }
        return value;
    }

    private static boolean mergeCompatible(
            boolean[] visible,
            boolean[] emitted,
            float[] ao,
            int u,
            int v,
            float expectedAo) {
        int index = u + SUBDIVISIONS * v;
        return visible[index]
                && !emitted[index]
                && Float.floatToIntBits(uniformAo(ao, index))
                        == Float.floatToIntBits(expectedAo);
    }

    private static boolean mergeRowCompatible(
            boolean[] visible,
            boolean[] emitted,
            float[] ao,
            int minU,
            int maxU,
            int v,
            float expectedAo) {
        for (int u = minU; u < maxU; u++) {
            if (!mergeCompatible(
                    visible, emitted, ao, u, v, expectedAo)) {
                return false;
            }
        }
        return true;
    }

    private interface MeshOutput {
        void beginFacelet();

        void add(float value);
    }

    private static final class CountOutput implements MeshOutput {
        private final OutputSizing sizing;

        private CountOutput(OutputSizing sizing) {
            this.sizing = sizing;
        }

        @Override
        public void beginFacelet() {
            sizing.beginFacelet();
        }

        @Override
        public void add(float value) {
            sizing.addOne();
        }

        private ChunkMeshMemoryPlan plan() {
            return sizing.plan();
        }
    }

    private static final class FloatOutput implements MeshOutput {
        private final OutputSizing sizing;
        private final float[] values;

        private FloatOutput(OutputSizing sizing) {
            this.sizing = sizing;
            this.values = new float[sizing.allocatedFloatCount()];
        }

        @Override
        public void beginFacelet() {
            sizing.beginFacelet();
        }

        @Override
        public void add(float value) {
            sizing.addOne();
            if (sizing.size() > values.length) {
                throw new IllegalStateException(
                        "Chunk mesh emitted more floats than preflight");
            }
            values[sizing.size() - 1] = value;
        }

        private ChunkMeshMemoryPlan plan() {
            return sizing.plan();
        }

        private float[] toArray() {
            if (sizing.size() != values.length) {
                throw new IllegalStateException(
                        "Chunk mesh emitted fewer floats than preflight");
            }
            return Arrays.copyOf(values, values.length);
        }
    }

    private static final class OutputSizing {
        private final int maximumSize;
        private final ChunkKey chunkKey;
        private final long revision;
        private final long outputByteLimit;
        private final int allocatedFloatCount;
        private int size;

        private OutputSizing(
                int maximumSize,
                ChunkKey chunkKey,
                long revision,
                long outputByteLimit,
                int allocatedFloatCount) {
            if (maximumSize < 0) {
                throw new IllegalArgumentException(
                        "maximumSize must not be negative");
            }
            if (outputByteLimit <= 0L) {
                throw new IllegalArgumentException(
                        "outputByteLimit must be positive");
            }
            if (allocatedFloatCount < 0
                    || allocatedFloatCount > maximumSize) {
                throw new IllegalArgumentException(
                        "allocatedFloatCount exceeds checked geometry bound");
            }
            long allocatedBytes = bytesForFloats(allocatedFloatCount);
            if (allocatedBytes > outputByteLimit) {
                throw new IllegalArgumentException(
                        "allocatedFloatCount exceeds output byte limit");
            }
            this.maximumSize = maximumSize;
            this.chunkKey = chunkKey;
            this.revision = revision;
            this.outputByteLimit = outputByteLimit;
            this.allocatedFloatCount = allocatedFloatCount;
        }

        private void beginFacelet() {
            int required = Math.addExact(size, FLOATS_PER_FACELET);
            long requiredBytes = Math.multiplyExact(
                    (long) required, Float.BYTES);
            if (requiredBytes > outputByteLimit) {
                long acceptedBytes = Math.multiplyExact(
                        (long) size, Float.BYTES);
                long facelets = Math.addExact(
                        size / (long) FLOATS_PER_FACELET, 1L);
                long vertices = Math.multiplyExact(
                        facelets, VERTICES_PER_FACELET);
                long allocatedBytes = bytesForFloats(allocatedFloatCount);
                throw new ChunkMeshOutputLimitExceededException(
                        chunkKey,
                        revision,
                        outputByteLimit,
                        acceptedBytes,
                        requiredBytes,
                        facelets,
                        vertices,
                        allocatedBytes);
            }
            if (required > maximumSize) {
                throw new IllegalStateException(
                        "Chunk mesh exceeded its checked geometry bound");
            }
        }

        private void addOne() {
            int required = Math.addExact(size, 1);
            if (required > maximumSize) {
                throw new IllegalStateException(
                        "Chunk mesh exceeded its checked geometry bound");
            }
            size++;
        }

        private int size() {
            return size;
        }

        private int allocatedFloatCount() {
            return allocatedFloatCount;
        }

        private ChunkMeshMemoryPlan plan() {
            long outputBytes = bytesForFloats(size);
            return new ChunkMeshMemoryPlan(
                    outputBytes,
                    Math.multiplyExact(outputBytes, 3L));
        }

        private static long bytesForFloats(int floats) {
            return Math.multiplyExact((long) floats, Float.BYTES);
        }
    }
}

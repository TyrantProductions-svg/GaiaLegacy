package com.overlord.voxel;

import java.util.Objects;

/** Pure quarter-grid sampling over one detached nine-snapshot mesh input. */
public final class QuarterVoxelSampler {
    private static final int SUBDIVISIONS =
            VoxelScale.DETAIL_4.subdivisionsPerAxis();

    private final ChunkMeshInput input;

    public QuarterVoxelSampler(ChunkMeshInput input) {
        this.input = Objects.requireNonNull(input, "input");
    }

    public QuarterVoxelSample sample(
            int parentX,
            int parentY,
            int parentZ,
            int subX,
            int subY,
            int subZ) {
        int wrappedParentX = Math.addExact(
                parentX, Math.floorDiv(subX, SUBDIVISIONS));
        int wrappedParentY = Math.addExact(
                parentY, Math.floorDiv(subY, SUBDIVISIONS));
        int wrappedParentZ = Math.addExact(
                parentZ, Math.floorDiv(subZ, SUBDIVISIONS));
        int wrappedSubX = Math.floorMod(subX, SUBDIVISIONS);
        int wrappedSubY = Math.floorMod(subY, SUBDIVISIONS);
        int wrappedSubZ = Math.floorMod(subZ, SUBDIVISIONS);
        int subIndex = wrappedSubX
                + SUBDIVISIONS * wrappedSubY
                + SUBDIVISIONS * SUBDIVISIONS * wrappedSubZ;
        return input.quarterSample(
                wrappedParentX,
                wrappedParentY,
                wrappedParentZ,
                subIndex);
    }
}

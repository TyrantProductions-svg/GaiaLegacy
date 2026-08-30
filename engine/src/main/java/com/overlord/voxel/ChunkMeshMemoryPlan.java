package com.overlord.voxel;

/** Checked detached-output and active-build heap reservation for one mesh. */
public record ChunkMeshMemoryPlan(
        long outputBytes,
        long activeReservationBytes) {
    public ChunkMeshMemoryPlan {
        if (outputBytes < 0L
                || activeReservationBytes < outputBytes) {
            throw new IllegalArgumentException(
                    "invalid Chunk mesh memory plan");
        }
    }

    static ChunkMeshMemoryPlan conservativeFor(ChunkMeshInput input) {
        ChunkMeshGeometryBounds.OutputBound bound =
                ChunkMeshGeometryBounds.forInput(input);
        long output = input.center().details().isEmpty()
                ? bound.byteLimit()
                : Math.min(
                        bound.byteLimit(),
                        ChunkMeshBuilder.MAX_HYBRID_MESH_BYTES);
        return new ChunkMeshMemoryPlan(
                output,
                Math.multiplyExact(output, 3L));
    }
}

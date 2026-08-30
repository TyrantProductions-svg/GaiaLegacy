package com.overlord.voxel;

import java.util.Objects;

@FunctionalInterface
public interface ChunkMesher {
    /**
     * Computes a no-large-output reservation before an accepted job becomes
     * active. Production meshers should override with an output-sensitive
     * counted pass. The default derives a conservative canonical-geometry
     * bound for compatibility meshers.
     */
    default ChunkMeshMemoryPlan preflight(ChunkMeshInput input) {
        return ChunkMeshMemoryPlan.conservativeFor(
                Objects.requireNonNull(input, "input"));
    }

    ChunkMeshData build(ChunkMeshInput input);

    /** Builds with the exact plan admitted by the manager. */
    default ChunkMeshData build(
            ChunkMeshInput input, ChunkMeshMemoryPlan approvedPlan) {
        return build(input);
    }
}

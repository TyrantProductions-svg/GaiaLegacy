package com.overlord.voxel;

/** Immutable structural bounds for CPU meshing and owner-thread GPU drains. */
public record ChunkMeshBudget(
        int maxAccepted,
        int maxActive,
        int maxUploadsPerFrame,
        int maxDestructionsPerFrame) {
    public ChunkMeshBudget {
        if (maxAccepted <= 0
                || maxActive <= 0
                || maxActive > maxAccepted
                || maxUploadsPerFrame <= 0
                || maxDestructionsPerFrame <= 0) {
            throw new IllegalArgumentException("chunk mesh budgets are invalid");
        }
    }

    public static ChunkMeshBudget productionDefaults() {
        return new ChunkMeshBudget(32, 2, 2, 4);
    }
}

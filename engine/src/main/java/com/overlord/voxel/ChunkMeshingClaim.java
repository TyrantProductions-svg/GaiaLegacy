package com.overlord.voxel;

import java.util.Objects;

/** Repository-issued lifecycle capability paired with a detached mesh payload. */
record ChunkMeshingClaim(
        long claimId,
        ChunkKey key,
        long revision,
        ChunkMeshInput input) {
    ChunkMeshingClaim {
        if (claimId <= 0L) {
            throw new IllegalArgumentException("claimId must be positive");
        }
        key = ChunkCoordinatePolicy.requireSafe(key);
        if (revision <= 0L) {
            throw new IllegalArgumentException("revision must be positive");
        }
        input = Objects.requireNonNull(input, "input");
        if (!input.center().key().equals(key)
                || input.center().revision() != revision) {
            throw new IllegalArgumentException(
                    "meshing claim identity must match its detached input");
        }
    }
}

package com.overlord.renderer;

import com.overlord.voxel.ChunkCoordinatePolicy;
import com.overlord.voxel.ChunkKey;

/** Immutable render-local origin independent from the simulation-origin publication. */
public record RenderOrigin(ChunkKey chunkKey) {
    public RenderOrigin {
        chunkKey = ChunkCoordinatePolicy.requireSafe(chunkKey);
    }

    public long worldOriginX() {
        return ChunkCoordinatePolicy.worldOriginX(chunkKey);
    }

    public long worldOriginZ() {
        return ChunkCoordinatePolicy.worldOriginZ(chunkKey);
    }
}

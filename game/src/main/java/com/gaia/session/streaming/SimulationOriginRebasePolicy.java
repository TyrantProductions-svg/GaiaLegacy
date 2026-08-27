package com.gaia.session.streaming;

import com.overlord.physics.SimulationOrigin;
import com.overlord.voxel.ChunkKey;
import com.overlord.voxel.GlobalPosition;
import java.util.Objects;

/** Pure checked policy for requesting the existing atomic origin transaction. */
public record SimulationOriginRebasePolicy(int distanceChunks) {
    private static final int PRODUCTION_DISTANCE_CHUNKS = 64;

    public SimulationOriginRebasePolicy {
        if (distanceChunks <= 0) {
            throw new IllegalArgumentException("distanceChunks must be positive");
        }
    }

    public static SimulationOriginRebasePolicy productionDefaults() {
        return new SimulationOriginRebasePolicy(PRODUCTION_DISTANCE_CHUNKS);
    }

    public boolean requiresRebase(
            GlobalPosition playerPosition,
            SimulationOrigin currentOrigin) {
        ChunkKey player = Objects.requireNonNull(
                playerPosition, "playerPosition").chunkKey();
        ChunkKey origin = Objects.requireNonNull(
                currentOrigin, "currentOrigin").chunkKey();
        long deltaX = Math.abs((long) player.x() - origin.x());
        long deltaZ = Math.abs((long) player.z() - origin.z());
        return Math.max(deltaX, deltaZ) >= distanceChunks;
    }
}

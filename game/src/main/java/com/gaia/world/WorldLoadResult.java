package com.gaia.world;

import com.overlord.voxel.ChunkKey;
import java.util.Objects;
import java.util.Set;
import org.joml.Vector3f;

public record WorldLoadResult(
        Set<ChunkKey> initialChunks,
        Vector3f playerFeetPosition) {
    public WorldLoadResult {
        initialChunks =
                Set.copyOf(
                        Objects.requireNonNull(
                                initialChunks, "initialChunks"));
        playerFeetPosition =
                new Vector3f(
                        Objects.requireNonNull(
                                playerFeetPosition, "playerFeetPosition"));
    }

    @Override
    public Vector3f playerFeetPosition() {
        return new Vector3f(playerFeetPosition);
    }
}

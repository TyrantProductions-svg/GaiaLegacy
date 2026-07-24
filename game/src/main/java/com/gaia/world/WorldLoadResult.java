package com.gaia.world;

import com.overlord.voxel.ChunkKey;
import java.util.Objects;
import java.util.Set;
import org.joml.Vector3f;

public record WorldLoadResult(
        Set<ChunkKey> initialChunks,
        Vector3f spawnPosition) {
    public WorldLoadResult {
        initialChunks =
                Set.copyOf(
                        Objects.requireNonNull(
                                initialChunks, "initialChunks"));
        spawnPosition =
                new Vector3f(
                        Objects.requireNonNull(
                                spawnPosition, "spawnPosition"));
    }

    @Override
    public Vector3f spawnPosition() {
        return new Vector3f(spawnPosition);
    }
}

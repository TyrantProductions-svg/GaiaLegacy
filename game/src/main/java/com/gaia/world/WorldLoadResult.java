package com.gaia.world;

import java.util.Objects;
import org.joml.Vector3f;

public record WorldLoadResult(float[] meshData, Vector3f spawnPosition) {
    public WorldLoadResult {
        meshData = Objects.requireNonNull(meshData, "meshData");
        spawnPosition =
                new Vector3f(Objects.requireNonNull(spawnPosition, "spawnPosition"));
    }
}

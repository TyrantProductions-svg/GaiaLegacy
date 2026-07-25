package com.gaia.world;

import com.overlord.voxel.ChunkKey;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import org.joml.Vector3f;

public record WorldLoadResult(
        Set<ChunkKey> initialChunks,
        Vector3f playerFeetPosition,
        String configFingerprint,
        String generationHash) {
    public WorldLoadResult {
        initialChunks =
                Collections.unmodifiableSet(
                        new LinkedHashSet<>(
                                Objects.requireNonNull(
                                        initialChunks, "initialChunks")));
        playerFeetPosition =
                new Vector3f(
                        Objects.requireNonNull(
                                playerFeetPosition, "playerFeetPosition"));
        configFingerprint =
                Objects.requireNonNull(
                        configFingerprint, "configFingerprint");
        generationHash =
                Objects.requireNonNull(
                        generationHash, "generationHash");
    }

    @Override
    public Vector3f playerFeetPosition() {
        return new Vector3f(playerFeetPosition);
    }
}

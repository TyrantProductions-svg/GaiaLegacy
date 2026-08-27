package com.gaia.world.streaming;

import com.overlord.voxel.ChunkAvailability;
import com.overlord.voxel.ChunkKey;
import com.overlord.voxel.ChunkMeshManager;
import com.overlord.voxel.ChunkState;
import java.util.Objects;

/** Bounded current-only diagnosis of one desired Chunk that is not yet ready. */
public record ChunkGapObservation(
        DesiredClass desiredClass,
        ChunkKey key,
        ChunkAvailability availability,
        ChunkState repositoryState,
        boolean resident,
        LoadPhase loadPhase,
        ChunkMeshManager.MeshPhase meshPhase,
        boolean renderObjectInstalled) {
    public ChunkGapObservation {
        Objects.requireNonNull(desiredClass, "desiredClass");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(availability, "availability");
        Objects.requireNonNull(repositoryState, "repositoryState");
        Objects.requireNonNull(loadPhase, "loadPhase");
        Objects.requireNonNull(meshPhase, "meshPhase");
    }

    public enum DesiredClass { SIMULATION, RENDER, PRELOAD }

    public enum LoadPhase { NONE, QUEUED, ACTIVE, COMPLETED }
}

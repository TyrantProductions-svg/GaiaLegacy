package com.gaia.world.streaming;

import com.overlord.physics.SimulationOrigin;
import com.overlord.voxel.ChunkKey;
import com.overlord.voxel.ChunkSnapshot;
import com.overlord.voxel.GlobalPosition;
import com.overlord.worlditem.api.SaveIdentity;
import com.overlord.worlditem.api.WorldItemId;
import com.overlord.worlditem.api.WorldItemSnapshot;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Test-source-only contract for a bounded driver of the existing headless
 * production session graph. It is intentionally not a production authority.
 */
interface Gate15FStreamingProbe {
    Gate15FSoakObservation runStructuralSoak();

    Gate15FMeasurementObservation runMeasurement();
}

record Gate15FSoakObservation(
        List<ChunkKey> visitedChunkKeys,
        List<SimulationOrigin> originSequence,
        List<Gate15FEpochObservation> epochs,
        List<Gate15FPipelineCounterSample> pipelineCounters,
        List<Gate15FUntouchedChunkObservation> untouchedChunks,
        List<Gate15FModifiedChunkObservation> modifiedChunks,
        List<Gate15FWorldItemLifecycleObservation> worldItemLifecycles,
        Gate15FRestartObservation restart,
        List<Gate15FLocalTransformObservation> localTransforms,
        List<Gate15FCanonicalBlockQuery> canonicalBlockQueries,
        List<Gate15FRetainedStateObservation> retainedState) {
    Gate15FSoakObservation {
        visitedChunkKeys = List.copyOf(visitedChunkKeys);
        originSequence = List.copyOf(originSequence);
        epochs = List.copyOf(epochs);
        pipelineCounters = List.copyOf(pipelineCounters);
        untouchedChunks = List.copyOf(untouchedChunks);
        modifiedChunks = List.copyOf(modifiedChunks);
        worldItemLifecycles = List.copyOf(worldItemLifecycles);
        restart = Objects.requireNonNull(restart, "restart");
        localTransforms = List.copyOf(localTransforms);
        canonicalBlockQueries = List.copyOf(canonicalBlockQueries);
        retainedState = List.copyOf(retainedState);
    }
}

record Gate15FMeasurementObservation(
        List<Gate15FArchiveObservation> archives,
        List<Gate15FEpochObservation> epochs,
        List<Gate15FPipelineCounterSample> pipelineCounters,
        List<SimulationOrigin> originSequence,
        List<Gate15FLatencyObservation> latencies) {
    Gate15FMeasurementObservation {
        archives = List.copyOf(archives);
        epochs = List.copyOf(epochs);
        pipelineCounters = List.copyOf(pipelineCounters);
        originSequence = List.copyOf(originSequence);
        latencies = List.copyOf(latencies);
    }
}

record Gate15FArchiveObservation(String archiveName, long fileCount) {
    Gate15FArchiveObservation {
        archiveName = Objects.requireNonNull(archiveName, "archiveName");
        if (fileCount < 0L) throw new IllegalArgumentException("fileCount must be non-negative");
    }
}

record Gate15FEpochObservation(
        long transition,
        Gate15FLifecycleState lifecycleState,
        ChunkStreamingMetrics metrics,
        Set<WorldItemId> survivorIds,
        Set<WorldItemId> expiryIndexedIds,
        int physicalDependencyCount) {
    Gate15FEpochObservation {
        if (transition < 0L || physicalDependencyCount < 0) {
            throw new IllegalArgumentException("epoch counts must be non-negative");
        }
        lifecycleState = Objects.requireNonNull(lifecycleState, "lifecycleState");
        metrics = Objects.requireNonNull(metrics, "metrics");
        survivorIds = Set.copyOf(survivorIds);
        expiryIndexedIds = Set.copyOf(expiryIndexedIds);
    }
}

record Gate15FPipelineCounterSample(long transition, long canceled, long stale) {
    Gate15FPipelineCounterSample {
        if (transition < 0L || canceled < 0L || stale < 0L) {
            throw new IllegalArgumentException("pipeline counters must be non-negative");
        }
    }
}

record Gate15FUntouchedChunkObservation(
        ChunkKey key,
        String hashBeforeUnload,
        String hashAfterProductionReload,
        boolean absentFromDurableIndexBeforeReload) {
    Gate15FUntouchedChunkObservation {
        key = Objects.requireNonNull(key, "key");
        hashBeforeUnload = Objects.requireNonNull(hashBeforeUnload, "hashBeforeUnload");
        hashAfterProductionReload = Objects.requireNonNull(
                hashAfterProductionReload, "hashAfterProductionReload");
    }
}

record Gate15FModifiedChunkObservation(
        ChunkSnapshot beforeUnloadSnapshot,
        byte[] modifiedBytesBeforeUnload,
        ChunkSnapshot reloadedSnapshot,
        byte[] reloadedBytes) {
    Gate15FModifiedChunkObservation {
        beforeUnloadSnapshot = Objects.requireNonNull(
                beforeUnloadSnapshot, "beforeUnloadSnapshot");
        modifiedBytesBeforeUnload = Arrays.copyOf(
                Objects.requireNonNull(modifiedBytesBeforeUnload, "modifiedBytesBeforeUnload"),
                modifiedBytesBeforeUnload.length);
        reloadedSnapshot = Objects.requireNonNull(reloadedSnapshot, "reloadedSnapshot");
        reloadedBytes = Arrays.copyOf(Objects.requireNonNull(reloadedBytes, "reloadedBytes"),
                reloadedBytes.length);
    }

    @Override
    public byte[] modifiedBytesBeforeUnload() {
        return Arrays.copyOf(modifiedBytesBeforeUnload, modifiedBytesBeforeUnload.length);
    }

    @Override
    public byte[] reloadedBytes() {
        return Arrays.copyOf(reloadedBytes, reloadedBytes.length);
    }
}

record Gate15FWorldItemLifecycleObservation(
        WorldItemSnapshot beforeHibernate,
        WorldItemSnapshot afterActivate,
        List<WorldItemSnapshot> afterExpiry,
        List<WorldItemSnapshot> afterCleanupFailure,
        List<WorldItemSnapshot> afterExpiredPageRevisit,
        int pagesReadForExpiredRevisit) {
    Gate15FWorldItemLifecycleObservation {
        beforeHibernate = Objects.requireNonNull(beforeHibernate, "beforeHibernate");
        afterActivate = Objects.requireNonNull(afterActivate, "afterActivate");
        afterExpiry = List.copyOf(afterExpiry);
        afterCleanupFailure = List.copyOf(afterCleanupFailure);
        afterExpiredPageRevisit = List.copyOf(afterExpiredPageRevisit);
        if (pagesReadForExpiredRevisit < 0) {
            throw new IllegalArgumentException("pagesReadForExpiredRevisit must be non-negative");
        }
    }
}

record Gate15FRestartObservation(
        SaveIdentity saveIdentityBeforeQuit,
        long worldTickBeforeQuit,
        SaveIdentity saveIdentityAfterRestart,
        long worldTickAfterRestart,
        ChunkSnapshot modifiedChunkAfterRestart,
        ChunkSnapshot modifiedChunkAfterSecondReload,
        WorldItemSnapshot worldItemBeforeQuit,
        WorldItemSnapshot worldItemAfterRestart,
        long expiresAtWorldTickBeforeQuit,
        long expiresAtWorldTickAfterRestart) {
    Gate15FRestartObservation {
        saveIdentityBeforeQuit = Objects.requireNonNull(
                saveIdentityBeforeQuit, "saveIdentityBeforeQuit");
        saveIdentityAfterRestart = Objects.requireNonNull(
                saveIdentityAfterRestart, "saveIdentityAfterRestart");
        modifiedChunkAfterRestart = Objects.requireNonNull(
                modifiedChunkAfterRestart, "modifiedChunkAfterRestart");
        modifiedChunkAfterSecondReload = Objects.requireNonNull(
                modifiedChunkAfterSecondReload, "modifiedChunkAfterSecondReload");
        worldItemBeforeQuit = Objects.requireNonNull(
                worldItemBeforeQuit, "worldItemBeforeQuit");
        worldItemAfterRestart = Objects.requireNonNull(
                worldItemAfterRestart, "worldItemAfterRestart");
        if (worldTickBeforeQuit < 0L || worldTickAfterRestart < 0L) {
            throw new IllegalArgumentException("world ticks must be non-negative");
        }
        if (expiresAtWorldTickBeforeQuit < 0L || expiresAtWorldTickAfterRestart < 0L) {
            throw new IllegalArgumentException("expiry ticks must be non-negative");
        }
    }
}

record Gate15FLocalTransformObservation(
        float renderX, float renderY, float renderZ,
        float physicsX, float physicsY, float physicsZ) {}

record Gate15FCanonicalBlockQuery(
        GlobalPosition requested, GlobalPosition raycastHit, GlobalPosition collisionHit) {
    Gate15FCanonicalBlockQuery {
        requested = Objects.requireNonNull(requested, "requested");
        raycastHit = Objects.requireNonNull(raycastHit, "raycastHit");
        collisionHit = Objects.requireNonNull(collisionHit, "collisionHit");
    }
}

record Gate15FRetainedStateObservation(
        long traversedChunkDistance,
        Gate15FLifecycleState lifecycleState,
        int residentChunks,
        int retainedStreamingWork,
        int liveMetadataCount,
        int decodedPages,
        int physicalDescriptorCount) {
    Gate15FRetainedStateObservation {
        lifecycleState = Objects.requireNonNull(lifecycleState, "lifecycleState");
        if (traversedChunkDistance < 0L || residentChunks < 0
                || retainedStreamingWork < 0 || liveMetadataCount < 0
                || decodedPages < 0 || physicalDescriptorCount < 0) {
            throw new IllegalArgumentException("retained-state values must be non-negative");
        }
    }
}

record Gate15FLatencyObservation(String operation, long nanos) {
    Gate15FLatencyObservation {
        operation = Objects.requireNonNull(operation, "operation");
        if (nanos < 0L) throw new IllegalArgumentException("latency must be non-negative");
    }
}

enum TravelDirection {
    EAST,
    WEST,
    NORTH,
    SOUTH
}

enum Gate15FLifecycleState {
    TRANSITIONING,
    SETTLED
}

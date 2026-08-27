package com.gaia.world.streaming;

import com.overlord.voxel.ChunkKey;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Detached immutable observation; it owns no repository or scheduler state. */
public record ChunkStreamingObservation(
        Set<ChunkKey> resident,
        Map<ChunkKey, RequestedLoadPhase> requestedLoadPhases) {
    public ChunkStreamingObservation {
        resident = ChunkDesiredSets.canonicalSet(resident, "resident");
        Objects.requireNonNull(requestedLoadPhases, "requestedLoadPhases");
        if (requestedLoadPhases.size() > 32) {
            throw new IllegalArgumentException(
                    "requested load metadata exceeds the fixed bound");
        }
        LinkedHashMap<ChunkKey, RequestedLoadPhase> copy = new LinkedHashMap<>();
        requestedLoadPhases.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(
                        com.overlord.voxel.ChunkCoordinatePolicy
                                .canonicalComparator()))
                .forEach(entry -> copy.put(
                        com.overlord.voxel.ChunkCoordinatePolicy.requireSafe(
                                entry.getKey()),
                        Objects.requireNonNull(entry.getValue(), "requested load phase")));
        requestedLoadPhases = Collections.unmodifiableMap(copy);
    }

    public ChunkStreamingObservation(
            Set<ChunkKey> resident,
            Set<ChunkKey> requested) {
        this(resident, queued(requested));
    }

    public Set<ChunkKey> requested() {
        return requestedLoadPhases.keySet();
    }

    private static Map<ChunkKey, RequestedLoadPhase> queued(Set<ChunkKey> requested) {
        Set<ChunkKey> checked = ChunkDesiredSets.canonicalSet(requested, "requested");
        LinkedHashMap<ChunkKey, RequestedLoadPhase> phases = new LinkedHashMap<>();
        checked.forEach(key -> phases.put(key, RequestedLoadPhase.QUEUED));
        return phases;
    }
}

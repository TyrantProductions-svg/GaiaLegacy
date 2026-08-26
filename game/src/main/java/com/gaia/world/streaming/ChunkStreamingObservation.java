package com.gaia.world.streaming;

import com.overlord.voxel.ChunkKey;
import java.util.Set;

/** Detached immutable observation; it owns no repository or scheduler state. */
public record ChunkStreamingObservation(
        Set<ChunkKey> resident,
        Set<ChunkKey> requested) {
    public ChunkStreamingObservation {
        resident = ChunkDesiredSets.canonicalSet(resident, "resident");
        requested = ChunkDesiredSets.canonicalSet(requested, "requested");
    }
}

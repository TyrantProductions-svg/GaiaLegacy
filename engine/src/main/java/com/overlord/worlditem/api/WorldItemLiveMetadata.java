package com.overlord.worlditem.api;

import com.overlord.voxel.ChunkCoordinatePolicy;
import com.overlord.voxel.ChunkKey;
import java.util.Objects;
import java.util.Optional;

/** Minimal bounded identity/lifetime row retained while an item is live. */
public record WorldItemLiveMetadata(
        WorldItemId id,
        ChunkKey intendedChunkKey,
        long intendedPageRevision,
        long expiresAtWorldTick,
        WorldItemLiveState state,
        Optional<WorldItemDurablePageProof> durableProof) {
    public WorldItemLiveMetadata {
        id = Objects.requireNonNull(id, "id");
        intendedChunkKey = ChunkCoordinatePolicy.requireSafe(intendedChunkKey);
        if (intendedPageRevision < 0L || expiresAtWorldTick < 0L) {
            throw new IllegalArgumentException(
                    "page revision and expiry must be non-negative");
        }
        state = Objects.requireNonNull(state, "state");
        durableProof = Objects.requireNonNull(durableProof, "durableProof");
        if (durableProof.isPresent()) {
            WorldItemDurablePageProof proof = durableProof.orElseThrow();
            if (!proof.chunkKey().equals(intendedChunkKey)
                    && state == WorldItemLiveState.EVICTED_UNEXPIRED) {
                throw new IllegalArgumentException(
                        "evicted metadata must be owned by its durable page");
            }
        }
        if (state == WorldItemLiveState.EVICTED_UNEXPIRED
                && (durableProof.isEmpty()
                        || durableProof.orElseThrow().pageRevision()
                                != intendedPageRevision)) {
            throw new IllegalArgumentException(
                    "evicted metadata requires its exact durable page proof");
        }
    }

    public WorldItemLiveMetadata withState(
            WorldItemLiveState nextState,
            ChunkKey nextKey,
            long nextRevision,
            Optional<WorldItemDurablePageProof> nextProof) {
        return new WorldItemLiveMetadata(
                id, nextKey, nextRevision, expiresAtWorldTick, nextState, nextProof);
    }
}

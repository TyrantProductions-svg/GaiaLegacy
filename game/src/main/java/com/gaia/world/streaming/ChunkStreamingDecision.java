package com.gaia.world.streaming;

import com.overlord.voxel.ChunkCoordinatePolicy;
import com.overlord.voxel.ChunkKey;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** One immutable controller decision; execution belongs to the later pipeline. */
public record ChunkStreamingDecision(
        ChunkDesiredSets desiredSets,
        long desiredEpoch,
        List<ChunkKey> admissions,
        List<ChunkKey> cancellations,
        List<ChunkKey> rejections,
        List<ChunkKey> unloadCandidates) {
    public ChunkStreamingDecision {
        desiredSets = Objects.requireNonNull(desiredSets, "desiredSets");
        if (desiredEpoch <= 0L) {
            throw new IllegalArgumentException("desiredEpoch must be positive");
        }
        admissions = checkedList(admissions, "admissions");
        cancellations = checkedList(cancellations, "cancellations");
        rejections = checkedList(rejections, "rejections");
        unloadCandidates = checkedList(unloadCandidates, "unloadCandidates");
        if (!desiredSets.preload().containsAll(admissions)
                || !desiredSets.preload().containsAll(rejections)) {
            throw new IllegalArgumentException(
                    "admissions and rejections must belong to the preload set");
        }
        HashSet<ChunkKey> overlap = new HashSet<>(admissions);
        overlap.retainAll(rejections);
        if (!overlap.isEmpty()) {
            throw new IllegalArgumentException(
                    "a key cannot be both admitted and rejected");
        }
    }

    private static List<ChunkKey> checkedList(List<ChunkKey> values, String name) {
        Objects.requireNonNull(values, name);
        List<ChunkKey> copy = values.stream()
                .map(ChunkCoordinatePolicy::requireSafe)
                .toList();
        if (new HashSet<>(copy).size() != copy.size()) {
            throw new IllegalArgumentException(name + " repeats a Chunk key");
        }
        return copy;
    }
}

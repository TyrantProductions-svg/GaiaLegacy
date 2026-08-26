package com.gaia.world.streaming;

import com.overlord.voxel.ChunkCoordinatePolicy;
import com.overlord.voxel.ChunkKey;
import com.overlord.voxel.GlobalPosition;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Deterministic policy coordinator. It performs no work or authority mutation. */
public final class ChunkStreamingController {
    private final ChunkStreamingPolicy policy;
    private ChunkDesiredSets previousDesiredSets;
    private long desiredEpoch;

    public ChunkStreamingController(ChunkStreamingPolicy policy) {
        this.policy = Objects.requireNonNull(policy, "policy");
    }

    public ChunkStreamingDecision update(
            GlobalPosition playerPosition,
            ChunkStreamingObservation observation) {
        GlobalPosition position = Objects.requireNonNull(
                playerPosition, "playerPosition");
        ChunkStreamingObservation observed = Objects.requireNonNull(
                observation, "observation");
        ChunkKey center = position.chunkKey();
        ChunkDesiredSets desired = desiredSets(center);
        boolean identityChanged = !desired.equals(previousDesiredSets);
        long nextEpoch = identityChanged
                ? Math.addExact(desiredEpoch, 1L)
                : desiredEpoch;

        Set<ChunkKey> outstandingRequests = new LinkedHashSet<>(observed.requested());
        outstandingRequests.removeAll(observed.resident());
        List<ChunkKey> cancellations = outstandingRequests.stream()
                .filter(key -> !desired.preload().contains(key))
                .sorted(ChunkCoordinatePolicy.canonicalComparator())
                .toList();
        Set<ChunkKey> retainedRequests = new LinkedHashSet<>(outstandingRequests);
        retainedRequests.removeAll(cancellations);

        List<ChunkKey> candidates = desired.preload().stream()
                .filter(key -> !observed.resident().contains(key))
                .filter(key -> !retainedRequests.contains(key))
                .sorted(priorityComparator(center, desired))
                .toList();
        int availableSlots = Math.max(
                0,
                policy.loadGenerationQueueCapacity() - retainedRequests.size());
        int residentAuthorityCapacity = Math.multiplyExact(
                Math.addExact(Math.multiplyExact(policy.unloadRadius(), 2), 1),
                Math.addExact(Math.multiplyExact(policy.unloadRadius(), 2), 1));
        int authoritySlots = Math.max(
                0,
                residentAuthorityCapacity
                        - observed.resident().size()
                        - retainedRequests.size());
        availableSlots = Math.min(availableSlots, authoritySlots);
        int admittedCount = Math.min(availableSlots, candidates.size());
        List<ChunkKey> admissions = List.copyOf(candidates.subList(0, admittedCount));
        List<ChunkKey> rejections = List.copyOf(
                candidates.subList(admittedCount, candidates.size()));

        List<ChunkKey> unloadCandidates = observed.resident().stream()
                .filter(key -> outsideRadius(center, key, policy.unloadRadius()))
                .sorted(ChunkCoordinatePolicy.canonicalComparator())
                .toList();
        ChunkStreamingDecision decision = new ChunkStreamingDecision(
                desired,
                nextEpoch,
                admissions,
                cancellations,
                rejections,
                unloadCandidates);
        if (identityChanged) {
            previousDesiredSets = desired;
            desiredEpoch = nextEpoch;
        }
        return decision;
    }

    private ChunkDesiredSets desiredSets(ChunkKey center) {
        Set<ChunkKey> simulation = new LinkedHashSet<>();
        Set<ChunkKey> render = new LinkedHashSet<>();
        Set<ChunkKey> preload = new LinkedHashSet<>();
        int radius = policy.preloadRadius();
        for (int deltaX = -radius; deltaX <= radius; deltaX++) {
            for (int deltaZ = -radius; deltaZ <= radius; deltaZ++) {
                ChunkKey key = ChunkCoordinatePolicy.neighbor(center, deltaX, deltaZ);
                preload.add(key);
                int ring = Math.max(Math.abs(deltaX), Math.abs(deltaZ));
                if (ring <= policy.renderRadius()) {
                    render.add(key);
                }
                if (ring <= policy.simulationRadius()) {
                    simulation.add(key);
                }
            }
        }
        return new ChunkDesiredSets(simulation, render, preload);
    }

    private static Comparator<ChunkKey> priorityComparator(
            ChunkKey center, ChunkDesiredSets desiredSets) {
        return Comparator.comparing(key -> ChunkPriority.of(center, key, desiredSets));
    }

    private static boolean outsideRadius(
            ChunkKey center, ChunkKey key, int radius) {
        ChunkKey checkedCenter = ChunkCoordinatePolicy.requireSafe(center);
        ChunkKey checkedKey = ChunkCoordinatePolicy.requireSafe(key);
        long deltaX = Math.abs((long) checkedCenter.x() - checkedKey.x());
        long deltaZ = Math.abs((long) checkedCenter.z() - checkedKey.z());
        return Math.max(deltaX, deltaZ) > radius;
    }
}

package com.gaia.world.streaming;

import com.overlord.physics.SimulationOrigin;
import com.overlord.voxel.ChunkKey;
import com.overlord.voxel.ChunkAvailability;
import com.overlord.voxel.GlobalPosition;
import com.overlord.worlditem.api.WorldItemPagingMetrics;
import java.util.List;
import java.util.Objects;

/** Immutable owner-frame observation of bounded streaming state. */
public record ChunkStreamingMetrics(
        GlobalPosition playerGlobalPosition,
        SimulationOrigin simulationOrigin,
        int simulationChunks,
        int renderChunks,
        int preloadChunks,
        int residentChunks,
        int unloadPendingChunks,
        WorkMetrics loadGenerationWork,
        WorkMetrics meshWork,
        WorkMetrics saveWork,
        long publications,
        long canceled,
        long staleResults,
        WorldItemPagingMetrics worldItems,
        List<String> diagnosticCodes,
        long publicationsThisFrame,
        long uploadsThisFrame,
        long bytesUploadedThisFrame,
        long destructionsThisFrame,
        long modifiedPersistedChunks,
        long modifiedResidentChunks,
        long loadLatencyNanos,
        long generationLatencyNanos,
        long meshLatencyNanos,
        long saveLatencyNanos,
        long restoreLatencyNanos,
        List<BlockedUnknownObservation> blockedUnknownDirections,
        boolean streamingTerrain) {
    public ChunkStreamingMetrics {
        Objects.requireNonNull(playerGlobalPosition, "playerGlobalPosition");
        Objects.requireNonNull(simulationOrigin, "simulationOrigin");
        Objects.requireNonNull(loadGenerationWork, "loadGenerationWork");
        Objects.requireNonNull(meshWork, "meshWork");
        Objects.requireNonNull(saveWork, "saveWork");
        Objects.requireNonNull(worldItems, "worldItems");
        diagnosticCodes = List.copyOf(
                Objects.requireNonNull(diagnosticCodes, "diagnosticCodes"));
        blockedUnknownDirections = List.copyOf(Objects.requireNonNull(
                blockedUnknownDirections, "blockedUnknownDirections"));
        if (simulationChunks < 0 || renderChunks < 0 || preloadChunks < 0
                || residentChunks < 0 || unloadPendingChunks < 0
                || publications < 0L || canceled < 0L || staleResults < 0L
                || publicationsThisFrame < 0L || uploadsThisFrame < 0L
                || bytesUploadedThisFrame < 0L || destructionsThisFrame < 0L
                || modifiedPersistedChunks < 0L || modifiedResidentChunks < 0L
                || loadLatencyNanos < 0L || generationLatencyNanos < 0L
                || meshLatencyNanos < 0L || saveLatencyNanos < 0L
                || restoreLatencyNanos < 0L) {
            throw new IllegalArgumentException("streaming metrics must be non-negative");
        }
    }

    /** Source-compatible constructor for presentation fixtures. */
    public ChunkStreamingMetrics(
            GlobalPosition playerGlobalPosition,
            SimulationOrigin simulationOrigin,
            int simulationChunks,
            int renderChunks,
            int preloadChunks,
            int residentChunks,
            int unloadPendingChunks,
            WorkMetrics loadGenerationWork,
            WorkMetrics meshWork,
            WorkMetrics saveWork,
            long publications,
            long canceled,
            long staleResults,
            WorldItemPagingMetrics worldItems,
            List<String> diagnosticCodes,
            boolean streamingTerrain) {
        this(playerGlobalPosition, simulationOrigin,
                simulationChunks, renderChunks, preloadChunks, residentChunks,
                unloadPendingChunks, loadGenerationWork, meshWork, saveWork,
                publications, canceled, staleResults, worldItems, diagnosticCodes,
                0L, 0L, 0L, 0L, 0L, 0L,
                0L, 0L, 0L, 0L, 0L, List.of(), streamingTerrain);
    }

    public static ChunkStreamingMetrics empty() {
        ChunkKey zero = new ChunkKey(0, 0);
        WorkMetrics idle = new WorkMetrics(0, 0, 0, 0);
        return new ChunkStreamingMetrics(
                new GlobalPosition(zero, 0.0, 0.0, 0.0),
                new SimulationOrigin(zero),
                0, 0, 0, 0, 0,
                idle, idle, idle,
                0L, 0L, 0L,
                new WorldItemPagingMetrics(
                        0, 0, 0, 0, 0, 0, 0, 0L, 0, 0, 0L, 0, 0,
                        0, 0L, 0, 0L, 0L, 0L, 0, 0, 0, 0),
                List.of(),
                0L, 0L, 0L, 0L, 0L, 0L,
                0L, 0L, 0L, 0L, 0L, List.of(),
                false);
    }

    public record WorkMetrics(int accepted, int queued, int active, int completed) {
        public WorkMetrics {
            if (accepted < 0 || queued < 0 || active < 0 || completed < 0
                    || accepted != queued + active + completed) {
                throw new IllegalArgumentException("streaming work metrics are inconsistent");
            }
        }
    }

    public record BlockedUnknownObservation(
            ChunkAvailability availability,
            ChunkKey key,
            com.overlord.physics.PlayerController.Direction direction) {
        public BlockedUnknownObservation {
            Objects.requireNonNull(availability, "availability");
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(direction, "direction");
            if (availability != ChunkAvailability.UNKNOWN) {
                throw new IllegalArgumentException(
                        "blocked UNKNOWN observation must be UNKNOWN");
            }
        }
    }
}

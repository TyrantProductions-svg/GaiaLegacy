package com.gaia.world.streaming;

import com.gaia.save.streaming.StreamedChunkStore;
import com.overlord.physics.PlayerController;
import com.overlord.physics.SimulationOrigin;
import com.overlord.voxel.ChunkAvailability;
import com.overlord.voxel.ChunkMeshManager;
import com.overlord.voxel.GlobalPosition;
import com.overlord.voxel.ChunkKey;
import com.overlord.worlditem.LogicalWorldItemService;
import java.util.Objects;

/** Owner-only copier from live authorities into one immutable frame value. */
public final class ChunkStreamingMetricsRecorder {
    private long priorPublications;
    private long priorUploads;
    private long priorUploadedBytes;
    private long priorDestructions;
    private final long restoreLatencyNanos;

    public ChunkStreamingMetricsRecorder() {
        this(0L);
    }

    public ChunkStreamingMetricsRecorder(long restoreLatencyNanos) {
        if (restoreLatencyNanos < 0L) {
            throw new IllegalArgumentException("restore latency must be non-negative");
        }
        this.restoreLatencyNanos = restoreLatencyNanos;
    }

    public ChunkStreamingMetrics capture(
            GlobalPosition player,
            SimulationOrigin origin,
            ChunkStreamingDecision decision,
            int residentChunks,
            ChunkStreamingPipeline pipeline,
            ChunkMeshManager meshes,
            LogicalWorldItemService worldItems) {
        return capture(player, origin, decision, residentChunks,
                pipeline, meshes, worldItems, null);
    }

    public ChunkStreamingMetrics capture(
            GlobalPosition player,
            SimulationOrigin origin,
            ChunkStreamingDecision decision,
            int residentChunks,
            ChunkStreamingPipeline pipeline,
            ChunkMeshManager meshes,
            LogicalWorldItemService worldItems,
            StreamedChunkStore store) {
        return capture(player, origin, decision, residentChunks,
                pipeline, meshes, worldItems, store, null);
    }

    public ChunkStreamingMetrics capture(
            GlobalPosition player,
            SimulationOrigin origin,
            ChunkStreamingDecision decision,
            int residentChunks,
            ChunkStreamingPipeline pipeline,
            ChunkMeshManager meshes,
            LogicalWorldItemService worldItems,
            StreamedChunkStore store,
            PlayerController playerController) {
        Objects.requireNonNull(decision, "decision");
        ChunkWorkScheduler.Metrics loads = pipeline.loadWorkMetrics();
        ChunkWorkScheduler.Metrics saves = pipeline.saveWorkMetrics();
        ChunkMeshManager.Metrics mesh = meshes.metrics();
        ChunkStreamingPipeline.Metrics totals = pipeline.metrics();
        ChunkMeshManager.LifecycleMetrics lifecycle = meshes.lifecycleMetrics();
        ChunkStreamingMetrics.WorkMetrics loadWork = work(loads);
        ChunkStreamingMetrics.WorkMetrics saveWork = work(saves);
        ChunkStreamingMetrics.WorkMetrics meshWork = new ChunkStreamingMetrics.WorkMetrics(
                mesh.accepted(), mesh.queued(), mesh.active(),
                mesh.completed() + mesh.awaitingUpload() + mesh.failedUploads());
        boolean streaming = residentChunks < decision.desiredSets().preload().size()
                || loadWork.accepted() > 0 || meshWork.accepted() > 0
                || saveWork.accepted() > 0;
        long uploadsThisFrame = delta(lifecycle.uploadedTotal(), priorUploads);
        long publicationsThisFrame = Math.addExact(
                delta(totals.published(), priorPublications),
                uploadsThisFrame);
        long bytesUploadedThisFrame = delta(
                lifecycle.bytesUploadedTotal(), priorUploadedBytes);
        long destructionsThisFrame = delta(
                lifecycle.destroyedTotal(), priorDestructions);
        priorPublications = totals.published();
        priorUploads = lifecycle.uploadedTotal();
        priorUploadedBytes = lifecycle.bytesUploadedTotal();
        priorDestructions = lifecycle.destroyedTotal();
        long modifiedPersistedChunks = store == null ? 0L : store.modifiedChunkCount();
        java.util.Map<ChunkKey, RequestedLoadPhase> loadPhases =
                pipeline.requestedLoadPhases();
        java.util.List<ChunkGapObservation> gaps = decision.desiredPriorityOrder()
                .stream()
                .map(key -> gap(decision, key, pipeline, meshes, loadPhases))
                .filter(java.util.Objects::nonNull)
                .limit(16)
                .toList();
        return new ChunkStreamingMetrics(
                player,
                origin,
                decision.desiredSets().simulation().size(),
                decision.desiredSets().render().size(),
                decision.desiredSets().preload().size(),
                residentChunks,
                saveWork.accepted(),
                loadWork,
                meshWork,
                saveWork,
                totals.published(),
                totals.canceled(),
                pipeline.staleResultCount(),
                worldItems.pagingMetrics(),
                pipeline.diagnostics().stream()
                        .map(ChunkStreamingDiagnostic::code)
                        .distinct()
                        .toList(),
                publicationsThisFrame,
                uploadsThisFrame,
                bytesUploadedThisFrame,
                destructionsThisFrame,
                modifiedPersistedChunks,
                pipeline.modifiedResidentCount(),
                pipeline.lastLoadLatencyNanos(),
                pipeline.lastGenerationLatencyNanos(),
                lifecycle.lastMeshLatencyNanos(),
                pipeline.lastSaveLatencyNanos(),
                restoreLatencyNanos,
                playerController == null
                        ? java.util.List.of()
                        : playerController.lastBlockedSpace().stream()
                                .filter(blocked -> blocked.availability()
                                        == ChunkAvailability.UNKNOWN)
                                .map(blocked -> new ChunkStreamingMetrics
                                        .BlockedUnknownObservation(
                                                blocked.availability(),
                                                blocked.key(),
                                                blocked.direction()))
                                .toList(),
                gaps,
                streaming);
    }

    private static ChunkGapObservation gap(
            ChunkStreamingDecision decision,
            ChunkKey key,
            ChunkStreamingPipeline pipeline,
            ChunkMeshManager meshes,
            java.util.Map<ChunkKey, RequestedLoadPhase> loadPhases) {
        ChunkGapObservation.DesiredClass desiredClass =
                decision.desiredSets().simulation().contains(key)
                        ? ChunkGapObservation.DesiredClass.SIMULATION
                        : decision.desiredSets().render().contains(key)
                                ? ChunkGapObservation.DesiredClass.RENDER
                                : ChunkGapObservation.DesiredClass.PRELOAD;
        boolean resident = pipeline.resident(key);
        boolean installed = meshes.hasInstalledRenderObject(key);
        boolean gap = !resident
                || pipeline.availability(key) != ChunkAvailability.AVAILABLE
                || desiredClass != ChunkGapObservation.DesiredClass.PRELOAD
                        && !pipeline.renderable(key);
        if (!gap) {
            return null;
        }
        RequestedLoadPhase requested = loadPhases.get(key);
        ChunkGapObservation.LoadPhase loadPhase = requested == null
                ? ChunkGapObservation.LoadPhase.NONE
                : ChunkGapObservation.LoadPhase.valueOf(requested.name());
        return new ChunkGapObservation(
                desiredClass,
                key,
                pipeline.availability(key),
                pipeline.chunkState(key),
                resident,
                loadPhase,
                meshes.meshPhase(key),
                installed);
    }

    private static long delta(long current, long previous) {
        return Math.max(0L, current - previous);
    }

    private static ChunkStreamingMetrics.WorkMetrics work(
            ChunkWorkScheduler.Metrics metrics) {
        return new ChunkStreamingMetrics.WorkMetrics(
                metrics.accepted(), metrics.queued(), metrics.active(), metrics.completed());
    }
}

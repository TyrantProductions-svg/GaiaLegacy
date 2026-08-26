package com.gaia.world.streaming;

import com.gaia.save.streaming.StreamedChunkUnloadResult;
import com.overlord.voxel.ChunkCoordinatePolicy;
import com.overlord.voxel.ChunkGenerationData;
import com.overlord.voxel.ChunkKey;
import com.overlord.voxel.ChunkStreamingTicket;
import java.util.Objects;
import java.util.Optional;

/** Detached immutable worker result. Owner-thread publication happens elsewhere. */
public record ChunkWorkResult(
        long workId,
        ChunkKey key,
        long desiredEpoch,
        Kind kind,
        Status status,
        long expectedRevision,
        long persistedRevision,
        Optional<ChunkStreamingTicket.SourcePreference> sourcePreference,
        Optional<ChunkGenerationData> chunkData,
        Optional<StreamedChunkUnloadResult> unloadResult,
        Optional<ChunkStreamingDiagnostic> diagnostic) {
    public enum Kind { LOAD_GENERATE, SAVE }
    public enum Status { SUCCESS, FAILED, CANCELED }

    public ChunkWorkResult {
        if (workId <= 0L || desiredEpoch <= 0L
                || expectedRevision < 0L || persistedRevision < 0L) {
            throw new IllegalArgumentException("work identity is invalid");
        }
        key = ChunkCoordinatePolicy.requireSafe(key);
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(status, "status");
        sourcePreference = Objects.requireNonNull(sourcePreference, "sourcePreference");
        chunkData = Objects.requireNonNull(chunkData, "chunkData");
        unloadResult = Objects.requireNonNull(unloadResult, "unloadResult");
        diagnostic = Objects.requireNonNull(diagnostic, "diagnostic");
    }

    public static ChunkWorkResult success(
            long id, ChunkKey key, long epoch, Kind kind, long revision) {
        return new ChunkWorkResult(id, key, epoch, kind, Status.SUCCESS, revision, 0L,
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
    }

    public static ChunkWorkResult loadSuccess(
            long id,
            ChunkKey key,
            long epoch,
            long revision,
            ChunkStreamingTicket.SourcePreference source,
            ChunkGenerationData data) {
        return loadSuccess(id, key, epoch, revision, source, data, 0L);
    }

    public static ChunkWorkResult loadSuccess(
            long id,
            ChunkKey key,
            long epoch,
            long revision,
            ChunkStreamingTicket.SourcePreference source,
            ChunkGenerationData data,
            long persistedRevision) {
        return new ChunkWorkResult(id, key, epoch, Kind.LOAD_GENERATE,
                Status.SUCCESS, revision, persistedRevision,
                Optional.of(source), Optional.of(data),
                Optional.empty(), Optional.empty());
    }

    public static ChunkWorkResult loadFailure(
            long id, ChunkKey key, long epoch, long revision,
            ChunkStreamingDiagnostic diagnostic) {
        return failed(id, key, epoch, Kind.LOAD_GENERATE, revision, diagnostic);
    }

    public static ChunkWorkResult saveSuccess(
            long id, ChunkKey key, long epoch, long revision,
            StreamedChunkUnloadResult result) {
        return new ChunkWorkResult(id, key, epoch, Kind.SAVE, Status.SUCCESS,
                revision, 0L, Optional.empty(), Optional.empty(), Optional.of(result),
                Optional.empty());
    }

    public static ChunkWorkResult saveFailure(
            long id, ChunkKey key, long epoch, long revision,
            ChunkStreamingDiagnostic diagnostic) {
        return failed(id, key, epoch, Kind.SAVE, revision, diagnostic);
    }

    static ChunkWorkResult workerFailure(
            long id,
            ChunkKey key,
            long epoch,
            Kind kind,
            long revision,
            Throwable failure) {
        String message = failure.getMessage() == null
                ? failure.getClass().getSimpleName()
                : failure.getMessage();
        if (message.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 512) {
            message = failure.getClass().getSimpleName();
        }
        return failed(id, key, epoch, kind, revision,
                new ChunkStreamingDiagnostic(
                        1L, key, kind,
                        "chunk-streaming.worker-failure", message));
    }

    private static ChunkWorkResult failed(
            long id, ChunkKey key, long epoch, Kind kind, long revision,
            ChunkStreamingDiagnostic diagnostic) {
        return new ChunkWorkResult(id, key, epoch, kind, Status.FAILED,
                revision, 0L, Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.of(diagnostic));
    }

    ChunkWorkResult canceled() {
        return new ChunkWorkResult(workId, key, desiredEpoch, kind, Status.CANCELED,
                expectedRevision, persistedRevision, sourcePreference, chunkData,
                unloadResult, diagnostic);
    }
}

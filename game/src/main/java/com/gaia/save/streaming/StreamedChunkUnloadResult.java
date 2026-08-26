package com.gaia.save.streaming;

import com.overlord.worlditem.api.WorldItemDurableProof;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

public record StreamedChunkUnloadResult(
        Status status,
        Optional<WorldItemDurableProof> durableProof,
        OptionalLong persistedChunkRevision) {
    public enum Status {
        SUCCESS,
        STALE,
        FAILED
    }

    public StreamedChunkUnloadResult {
        Objects.requireNonNull(status, "status");
        durableProof = Objects.requireNonNull(durableProof, "durableProof");
        persistedChunkRevision = Objects.requireNonNull(
                persistedChunkRevision, "persistedChunkRevision");
        if (status != Status.SUCCESS && durableProof.isPresent()) {
            throw new IllegalArgumentException(
                    "Only successful unload persistence may carry proof");
        }
        if (status != Status.SUCCESS && persistedChunkRevision.isPresent()) {
            throw new IllegalArgumentException(
                    "Only successful unload persistence may carry a Chunk revision");
        }
        if (persistedChunkRevision.isPresent()
                && persistedChunkRevision.orElseThrow() <= 0L) {
            throw new IllegalArgumentException(
                    "persisted Chunk revision must be positive");
        }
    }

    public StreamedChunkUnloadResult(
            Status status, Optional<WorldItemDurableProof> durableProof) {
        this(status, durableProof, OptionalLong.empty());
    }

    public static StreamedChunkUnloadResult success(
            Optional<WorldItemDurableProof> proof) {
        return new StreamedChunkUnloadResult(Status.SUCCESS, proof);
    }

    public static StreamedChunkUnloadResult success(
            Optional<WorldItemDurableProof> proof, long persistedChunkRevision) {
        return new StreamedChunkUnloadResult(
                Status.SUCCESS, proof, OptionalLong.of(persistedChunkRevision));
    }

    static StreamedChunkUnloadResult stale() {
        return new StreamedChunkUnloadResult(Status.STALE, Optional.empty());
    }

    static StreamedChunkUnloadResult failed() {
        return new StreamedChunkUnloadResult(Status.FAILED, Optional.empty());
    }
}

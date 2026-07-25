package com.gaia.world.generation;

import com.overlord.assets.ResourceLocation;
import java.util.Objects;
import java.util.Optional;

public record GenerationStageResult(
        ResourceLocation stageId,
        Status status,
        int samples,
        int writes,
        Optional<Throwable> failure) {
    public GenerationStageResult {
        Objects.requireNonNull(stageId, "stageId");
        Objects.requireNonNull(status, "status");
        failure = Objects.requireNonNull(failure, "failure");
        if (samples < 0) {
            throw new IllegalArgumentException(
                    "samples must be non-negative");
        }
        if (writes < 0) {
            throw new IllegalArgumentException(
                    "writes must be non-negative");
        }
        if (status == Status.SUCCEEDED
                && failure.isPresent()) {
            throw new IllegalArgumentException(
                    "A succeeded stage cannot contain a failure");
        }
        if (status == Status.FAILED
                && failure.isEmpty()) {
            throw new IllegalArgumentException(
                    "A failed stage must contain a failure");
        }
    }

    public enum Status {
        SUCCEEDED,
        FAILED
    }
}

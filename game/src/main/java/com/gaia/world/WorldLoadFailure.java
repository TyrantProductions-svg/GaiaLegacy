package com.gaia.world;

import com.overlord.assets.ResourceLocation;
import com.overlord.voxel.ChunkKey;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public record WorldLoadFailure(
        Set<ChunkKey> completedChunks,
        Optional<ChunkKey> failedChunk,
        Optional<ResourceLocation> failedStage,
        ResourceLocation code,
        Throwable cause) {
    public WorldLoadFailure {
        completedChunks =
                Collections.unmodifiableSet(
                        new LinkedHashSet<>(
                                Objects.requireNonNull(
                                        completedChunks,
                                        "completedChunks")));
        failedChunk =
                Objects.requireNonNull(failedChunk, "failedChunk");
        failedStage =
                Objects.requireNonNull(failedStage, "failedStage");
        code = Objects.requireNonNull(code, "code");
        cause = Objects.requireNonNull(cause, "cause");
    }
}

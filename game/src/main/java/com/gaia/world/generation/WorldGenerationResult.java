package com.gaia.world.generation;

import com.overlord.voxel.ChunkGenerationData;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record WorldGenerationResult(
        Optional<ChunkGenerationData> chunkData,
        List<GenerationStageResult> stageResults) {
    public WorldGenerationResult {
        chunkData =
                Objects.requireNonNull(
                        chunkData, "chunkData");
        stageResults =
                List.copyOf(
                        Objects.requireNonNull(
                                stageResults, "stageResults"));
        boolean hasFailure =
                stageResults.stream()
                        .anyMatch(
                                result ->
                                        result.status()
                                                == GenerationStageResult
                                                        .Status.FAILED);
        if (chunkData.isPresent() == hasFailure) {
            throw new IllegalArgumentException(
                    "Chunk data must be present exactly when all "
                            + "stages succeeded");
        }
    }

    public boolean succeeded() {
        return chunkData.isPresent();
    }

    public Optional<GenerationStageResult> failedStage() {
        return stageResults.stream()
                .filter(
                        result ->
                                result.status()
                                        == GenerationStageResult
                                                .Status.FAILED)
                .findFirst();
    }
}

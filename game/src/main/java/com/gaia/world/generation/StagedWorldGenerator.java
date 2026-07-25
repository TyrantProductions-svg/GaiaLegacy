package com.gaia.world.generation;

import com.overlord.assets.ResourceLocation;
import com.overlord.config.GameConfig;
import com.overlord.voxel.ChunkKey;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;

public final class StagedWorldGenerator
        implements WorldGenerator {
    private final List<StageEntry> stages;

    public StagedWorldGenerator(
            List<WorldGenerationStage> stages) {
        Objects.requireNonNull(stages, "stages");
        if (stages.isEmpty()) {
            throw new IllegalArgumentException(
                    "stages must not be empty");
        }
        List<StageEntry> entries =
                new ArrayList<>(stages.size());
        Set<ResourceLocation> stageIds = new HashSet<>();
        for (WorldGenerationStage stage : stages) {
            Objects.requireNonNull(stage, "stage");
            ResourceLocation stageId =
                    Objects.requireNonNull(
                            stage.id(), "stage.id()");
            if (!stageIds.add(stageId)) {
                throw new IllegalArgumentException(
                        "Duplicate generation stage ID: "
                                + stageId);
            }
            entries.add(new StageEntry(stageId, stage));
        }
        this.stages = List.copyOf(entries);
    }

    @Override
    public WorldGenerationResult generate(
            GenerationContext context, ChunkKey key) {
        Objects.requireNonNull(context, "context");
        GenerationRegion region =
                new GenerationRegion(
                        Objects.requireNonNull(key, "key"),
                        GameConfig.Chunk.MAX_HEIGHT,
                        context.palette().air());
        List<GenerationStageResult> results =
                new ArrayList<>(stages.size());
        for (StageEntry stage : stages) {
            GenerationStageResult result =
                    execute(stage, context, region);
            results.add(result);
            if (result.status()
                    == GenerationStageResult.Status.FAILED) {
                return new WorldGenerationResult(
                        Optional.empty(), results);
            }
        }
        return new WorldGenerationResult(
                Optional.of(region.freeze()), results);
    }

    private static GenerationStageResult execute(
            StageEntry stage,
            GenerationContext context,
            GenerationRegion region) {
        try {
            GenerationStageResult result =
                    stage.provider().generate(
                            context, region);
            if (result == null) {
                throw new IllegalStateException(
                        "Generation stage "
                                + stage.id()
                                + " returned null");
            }
            if (!stage.id().equals(result.stageId())) {
                throw new IllegalStateException(
                        "Generation stage "
                                + stage.id()
                                + " returned result for "
                                + result.stageId());
            }
            return result;
        } catch (CancellationException cancellation) {
            throw cancellation;
        } catch (Throwable failure) {
            return new GenerationStageResult(
                    stage.id(),
                    GenerationStageResult.Status.FAILED,
                    0,
                    0,
                    Optional.of(failure));
        }
    }

    private record StageEntry(
            ResourceLocation id,
            WorldGenerationStage provider) {
    }
}

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
    private final GenerationRegion.WorldColumnSampler worldColumns;

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
        BiomeProvider biomeProvider = null;
        HeightProvider heightProvider = null;
        for (WorldGenerationStage stage : stages) {
            Objects.requireNonNull(stage, "stage");
            ResourceLocation stageId =
                    Objects.requireNonNull(
                            stage.id(), "stage.id()");
            GenerationStageContract contract =
                    Objects.requireNonNull(
                            stage.contract(), "stage.contract()");
            if (!stageId.equals(contract.id())) {
                throw new IllegalArgumentException(
                        "Generation stage ID does not match its contract: "
                                + stageId
                                + " != "
                                + contract.id());
            }
            if (!stageIds.add(stageId)) {
                throw new IllegalArgumentException(
                        "Duplicate generation stage ID: "
                                + stageId);
            }
            entries.add(new StageEntry(contract, stage));
            if (stage instanceof BiomeProvider provider) {
                biomeProvider = provider;
            }
            if (stage instanceof HeightProvider provider) {
                heightProvider = provider;
            }
        }
        this.stages = List.copyOf(entries);
        if ((biomeProvider == null) != (heightProvider == null)) {
            throw new IllegalArgumentException(
                    "Biome and height stages must be supplied together");
        }
        this.worldColumns =
                biomeProvider == null
                        ? null
                        : GenerationRegion.WorldColumnSampler.from(
                                biomeProvider, heightProvider);
    }

    @Override
    public WorldGenerationResult generate(
            GenerationContext context, ChunkKey key) {
        Objects.requireNonNull(context, "context");
        GenerationRegion region =
                new GenerationRegion(
                        Objects.requireNonNull(key, "key"),
                        GameConfig.Chunk.MAX_HEIGHT,
                        context.palette().air(),
                        worldColumns);
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
                                + stage.contract().id()
                                + " returned null");
            }
            if (!stage.contract().id().equals(result.stageId())) {
                throw new IllegalStateException(
                        "Generation stage "
                                + stage.contract().id()
                                + " returned result for "
                                + result.stageId());
            }
            return result;
        } catch (CancellationException cancellation) {
            throw cancellation;
        } catch (Throwable failure) {
            return new GenerationStageResult(
                    stage.contract().id(),
                    GenerationStageResult.Status.FAILED,
                    0,
                    0,
                    Optional.of(failure));
        }
    }

    private record StageEntry(
            GenerationStageContract contract,
            WorldGenerationStage provider) {
    }
}

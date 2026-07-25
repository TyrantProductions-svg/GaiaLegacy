package com.gaia.world.generation;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.overlord.assets.ResourceLocation;
import com.overlord.config.GameConfig;
import com.overlord.voxel.ChunkKey;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class StagedWorldGeneratorTest {
    private static final ChunkKey KEY =
            new ChunkKey(2, -3);

    @Test
    void executesStagesInDeclaredOrderAndPublishesFrozenData() {
        List<ResourceLocation> calls = new ArrayList<>();
        List<GenerationRegion> regions = new ArrayList<>();
        StagedWorldGenerator generator =
                new StagedWorldGenerator(
                        List.of(
                                successStage(
                                        "gaia:first",
                                        calls,
                                        regions),
                                successStage(
                                        "gaia:second",
                                        calls,
                                        regions)));

        WorldGenerationResult result =
                generator.generate(context(), KEY);

        assertTrue(result.succeeded());
        assertEquals(
                List.of(
                        parse("gaia:first"),
                        parse("gaia:second")),
                calls);
        assertEquals(
                calls,
                result.stageResults().stream()
                        .map(GenerationStageResult::stageId)
                        .toList());
        assertEquals(2, result.stageResults().size());
        assertEquals(KEY, result.chunkData().orElseThrow().key());
        assertEquals(
                GameConfig.Chunk.MAX_HEIGHT,
                result.chunkData().orElseThrow().worldHeight());
        assertEquals(2, regions.size());
        assertEquals(regions.get(0), regions.get(1));

        regions.get(0).writeBlock(0, 0, 0, (byte) 3);
        assertEquals(
                context().palette().air(),
                result.chunkData().orElseThrow()
                        .getBlock(0, 0, 0));
    }

    @Test
    void createsFreshRegionAndDeterministicResultForEachGeneration() {
        List<GenerationRegion> regions = new ArrayList<>();
        StagedWorldGenerator generator =
                new StagedWorldGenerator(
                        List.of(
                                writingStage(
                                        "gaia:write",
                                        regions)));
        GenerationContext context = context();

        WorldGenerationResult first =
                generator.generate(context, KEY);
        WorldGenerationResult second =
                generator.generate(context, KEY);

        assertNotSame(regions.get(0), regions.get(1));
        assertEquals(first.stageResults(), second.stageResults());
        assertArrayEquals(
                first.chunkData().orElseThrow().copyBlocks(),
                second.chunkData().orElseThrow().copyBlocks());
    }

    @Test
    void firstFailureStopsPipelineAndReturnsNoChunkData() {
        AtomicInteger forbiddenCalls = new AtomicInteger();
        WorldGenerationResult result =
                new StagedWorldGenerator(
                                List.of(
                                        successStage(
                                                "gaia:first"),
                                        failedStage(
                                                "gaia:failed"),
                                        forbiddenStage(
                                                forbiddenCalls)))
                        .generate(context(), KEY);

        assertFalse(result.succeeded());
        assertTrue(result.chunkData().isEmpty());
        assertEquals(0, forbiddenCalls.get());
        assertEquals(2, result.stageResults().size());
        assertEquals(
                parse("gaia:failed"),
                result.failedStage()
                        .orElseThrow()
                        .stageId());
    }

    @Test
    void convertsRuntimeExceptionAndErrorToCurrentStageFailure() {
        RuntimeException runtimeFailure =
                new IllegalStateException("runtime");
        Error errorFailure = new AssertionError("error");

        assertConvertedFailure(
                "gaia:runtime", runtimeFailure);
        assertConvertedFailure(
                "gaia:error", errorFailure);
    }

    @Test
    void rejectsNullEmptyAndDuplicateStageIds() {
        assertThrows(
                NullPointerException.class,
                () -> new StagedWorldGenerator(null));
        assertThrows(
                IllegalArgumentException.class,
                () -> new StagedWorldGenerator(List.of()));
        assertThrows(
                NullPointerException.class,
                () ->
                        new StagedWorldGenerator(
                                Arrays.asList(
                                        successStage(
                                                "gaia:first"),
                                        null)));
        assertThrows(
                NullPointerException.class,
                () ->
                        new StagedWorldGenerator(
                                List.of(stageWithNullId())));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new StagedWorldGenerator(
                                List.of(
                                        successStage(
                                                "gaia:same"),
                                        successStage(
                                                "gaia:same"))));
    }

    @Test
    void invalidReturnedStageResultFailsDeclaredStage() {
        assertInvalidStageResult(
                "gaia:null_result",
                stageReturning(
                        "gaia:null_result", null));
        assertInvalidStageResult(
                "gaia:declared",
                stageReturning(
                        "gaia:declared",
                        succeeded("gaia:other")));
    }

    private static void assertConvertedFailure(
            String id, Throwable failure) {
        AtomicInteger forbiddenCalls = new AtomicInteger();
        WorldGenerationResult result =
                new StagedWorldGenerator(
                                List.of(
                                        throwingStage(
                                                id, failure),
                                        forbiddenStage(
                                                forbiddenCalls)))
                        .generate(context(), KEY);

        assertFalse(result.succeeded());
        assertEquals(0, forbiddenCalls.get());
        GenerationStageResult failed =
                result.failedStage().orElseThrow();
        assertEquals(parse(id), failed.stageId());
        assertEquals(
                failure, failed.failure().orElseThrow());
    }

    private static void assertInvalidStageResult(
            String expectedId, WorldGenerationStage stage) {
        WorldGenerationResult result =
                new StagedWorldGenerator(List.of(stage))
                        .generate(context(), KEY);

        assertFalse(result.succeeded());
        GenerationStageResult failed =
                result.failedStage().orElseThrow();
        assertEquals(parse(expectedId), failed.stageId());
        assertInstanceOf(
                IllegalStateException.class,
                failed.failure().orElseThrow());
    }

    private static WorldGenerationStage successStage(
            String id) {
        return successStage(
                id, new ArrayList<>(), new ArrayList<>());
    }

    private static WorldGenerationStage successStage(
            String id,
            List<ResourceLocation> calls,
            List<GenerationRegion> regions) {
        ResourceLocation stageId = parse(id);
        return new WorldGenerationStage() {
            @Override
            public ResourceLocation id() {
                return stageId;
            }

            @Override
            public GenerationStageResult generate(
                    GenerationContext ignoredContext,
                    GenerationRegion region) {
                calls.add(stageId);
                regions.add(region);
                return succeeded(id);
            }
        };
    }

    private static WorldGenerationStage writingStage(
            String id, List<GenerationRegion> regions) {
        ResourceLocation stageId = parse(id);
        return new WorldGenerationStage() {
            @Override
            public ResourceLocation id() {
                return stageId;
            }

            @Override
            public GenerationStageResult generate(
                    GenerationContext ignoredContext,
                    GenerationRegion region) {
                regions.add(region);
                region.writeBlock(1, 2, 3, (byte) 2);
                return new GenerationStageResult(
                        stageId,
                        GenerationStageResult.Status.SUCCEEDED,
                        1,
                        1,
                        Optional.empty());
            }
        };
    }

    private static WorldGenerationStage failedStage(
            String id) {
        ResourceLocation stageId = parse(id);
        return new WorldGenerationStage() {
            @Override
            public ResourceLocation id() {
                return stageId;
            }

            @Override
            public GenerationStageResult generate(
                    GenerationContext ignoredContext,
                    GenerationRegion ignoredRegion) {
                return new GenerationStageResult(
                        stageId,
                        GenerationStageResult.Status.FAILED,
                        1,
                        0,
                        Optional.of(
                                new IllegalStateException(
                                        "failed")));
            }
        };
    }

    private static WorldGenerationStage throwingStage(
            String id, Throwable failure) {
        ResourceLocation stageId = parse(id);
        return new WorldGenerationStage() {
            @Override
            public ResourceLocation id() {
                return stageId;
            }

            @Override
            public GenerationStageResult generate(
                    GenerationContext ignoredContext,
                    GenerationRegion ignoredRegion) {
                return throwUnchecked(failure);
            }
        };
    }

    private static WorldGenerationStage forbiddenStage(
            AtomicInteger calls) {
        return new WorldGenerationStage() {
            @Override
            public ResourceLocation id() {
                return parse("gaia:forbidden");
            }

            @Override
            public GenerationStageResult generate(
                    GenerationContext ignoredContext,
                    GenerationRegion ignoredRegion) {
                calls.incrementAndGet();
                throw new AssertionError(
                        "Pipeline continued after failure");
            }
        };
    }

    private static WorldGenerationStage stageWithNullId() {
        return new WorldGenerationStage() {
            @Override
            public ResourceLocation id() {
                return null;
            }

            @Override
            public GenerationStageResult generate(
                    GenerationContext ignoredContext,
                    GenerationRegion ignoredRegion) {
                return succeeded("gaia:unreachable");
            }
        };
    }

    private static WorldGenerationStage stageReturning(
            String id, GenerationStageResult result) {
        ResourceLocation stageId = parse(id);
        return new WorldGenerationStage() {
            @Override
            public ResourceLocation id() {
                return stageId;
            }

            @Override
            public GenerationStageResult generate(
                    GenerationContext ignoredContext,
                    GenerationRegion ignoredRegion) {
                return result;
            }
        };
    }

    private static GenerationStageResult succeeded(
            String id) {
        return new GenerationStageResult(
                parse(id),
                GenerationStageResult.Status.SUCCEEDED,
                1,
                0,
                Optional.empty());
    }

    private static GenerationContext context() {
        WorldGenerationConfig config =
                WorldGenerationConfig.defaults();
        return new GenerationContext(
                config,
                new GenerationBlockPalette(
                        (byte) 0,
                        (byte) 1,
                        (byte) 2,
                        (byte) 3),
                new DeterministicCoordinateSampler(
                        config.seed(),
                        config.algorithmVersion()));
    }

    private static ResourceLocation parse(String id) {
        return ResourceLocation.parse(id);
    }

    @SuppressWarnings("unchecked")
    private static <T> T throwUnchecked(
            Throwable failure) {
        return StagedWorldGeneratorTest
                .<RuntimeException, T>throwAny(failure);
    }

    @SuppressWarnings("unchecked")
    private static <E extends Throwable, T> T throwAny(
            Throwable failure) throws E {
        throw (E) failure;
    }
}

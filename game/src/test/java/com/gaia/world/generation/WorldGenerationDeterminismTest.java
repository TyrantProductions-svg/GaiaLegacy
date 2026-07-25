package com.gaia.world.generation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gaia.world.GaiaWorldGenerator;
import com.overlord.assets.ResourceLocation;
import com.overlord.voxel.ChunkGenerationData;
import com.overlord.voxel.ChunkKey;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class WorldGenerationDeterminismTest {
    static final GenerationBlockPalette PALETTE =
            new GenerationBlockPalette((byte) 0, (byte) 1, (byte) 2, (byte) 3);

    @Test
    void sameSeedAndRegionProduceExactHashes() {
        WorldGenerationConfig config = WorldGenerationConfig.defaults();
        List<ChunkKey> keys = defaultKeys();

        List<ChunkGenerationData> first = generate(keys, config);
        List<ChunkGenerationData> second = generate(keys, config);

        assertEquals(
                WorldGenerationHasher.hashRegion(config, first),
                WorldGenerationHasher.hashRegion(config, second));
        for (int index = 0; index < keys.size(); index++) {
            assertEquals(
                    WorldGenerationHasher.hashChunk(config, first.get(index)),
                    WorldGenerationHasher.hashChunk(config, second.get(index)));
        }
    }

    @Test
    void changingSeedChangesChunkAndRegionHashes() {
        WorldGenerationConfig first = WorldGenerationConfig.defaults();
        WorldGenerationConfig second = withSeed(first, first.seed() + 1);
        ChunkKey key = new ChunkKey(0, 0);
        ChunkGenerationData firstData = generate(key, first);
        ChunkGenerationData secondData = generate(key, second);

        assertFalse(
                WorldGenerationHasher.hashChunk(first, firstData)
                        .equals(
                                WorldGenerationHasher.hashChunk(
                                        second, secondData)));
        assertFalse(
                WorldGenerationHasher.hashRegion(first, List.of(firstData))
                        .equals(
                                WorldGenerationHasher.hashRegion(
                                        second, List.of(secondData))));
    }

    @Test
    void configurationChangesHashEvenForIdenticalChunkBytes() {
        WorldGenerationConfig first = WorldGenerationConfig.defaults();
        WorldGenerationConfig second = withSeed(first, first.seed() + 1);
        ChunkGenerationData data = generate(new ChunkKey(0, 0), first);

        assertNotEquals(
                WorldGenerationHasher.hashChunk(first, data),
                WorldGenerationHasher.hashChunk(second, data));
    }

    @Test
    void regionHashRejectsDuplicateChunkKeys() {
        WorldGenerationConfig config = WorldGenerationConfig.defaults();
        ChunkGenerationData data = generate(new ChunkKey(0, 0), config);

        assertThrows(
                IllegalArgumentException.class,
                () ->
                        WorldGenerationHasher.hashRegion(
                                config, List.of(data, data)));
    }

    @Test
    void schedulingOrderDoesNotChangeAggregateHash() throws Exception {
        WorldGenerationConfig config = WorldGenerationConfig.defaults();
        List<ChunkKey> keys = defaultKeys();
        String forward =
                WorldGenerationHasher.hashRegion(
                        config, generate(keys, config));
        List<ChunkKey> reverse = new ArrayList<>(keys);
        Collections.reverse(reverse);
        String reversed =
                WorldGenerationHasher.hashRegion(
                        config, generate(reverse, config));
        List<ChunkKey> shuffled = new ArrayList<>(keys);
        Collections.shuffle(shuffled, new Random(99173L));
        String shuffledConcurrent =
                WorldGenerationHasher.hashRegion(
                        config, generateConcurrently(shuffled, config, 4));

        assertEquals(
                WorldGenerationSnapshotTest.VERSION_ONE_REGION_HASH,
                forward);
        assertEquals(forward, reversed);
        assertEquals(forward, shuffledConcurrent);
    }

    @Test
    void failedStageIsTerminalAndNeverPublishesChunkData() {
        AtomicInteger laterCalls = new AtomicInteger();
        WorldGenerationStage first =
                stage("first", GenerationStageResult.Status.SUCCEEDED);
        IllegalStateException cause = new IllegalStateException("failed");
        WorldGenerationStage failed =
                new WorldGenerationStage() {
                    @Override
                    public ResourceLocation id() {
                        return ResourceLocation.parse("test:failed");
                    }

                    @Override
                    public GenerationStageResult generate(
                            GenerationContext context, GenerationRegion region) {
                        return new GenerationStageResult(
                                id(),
                                GenerationStageResult.Status.FAILED,
                                1,
                                0,
                                Optional.of(cause));
                    }
                };
        WorldGenerationStage later =
                new WorldGenerationStage() {
                    @Override
                    public ResourceLocation id() {
                        return ResourceLocation.parse("test:later");
                    }

                    @Override
                    public GenerationStageResult generate(
                            GenerationContext context, GenerationRegion region) {
                        laterCalls.incrementAndGet();
                        return new GenerationStageResult(
                                id(),
                                GenerationStageResult.Status.SUCCEEDED,
                                1,
                                0,
                                Optional.empty());
                    }
                };

        WorldGenerationResult result =
                new StagedWorldGenerator(List.of(first, failed, later))
                        .generate(
                                context(WorldGenerationConfig.defaults()),
                                new ChunkKey(0, 0));

        assertFalse(result.succeeded());
        assertTrue(result.chunkData().isEmpty());
        assertEquals(List.of(first.id(), failed.id()),
                result.stageResults().stream()
                        .map(GenerationStageResult::stageId)
                        .toList());
        assertEquals(0, laterCalls.get());
        assertEquals(cause, result.failedStage().orElseThrow()
                .failure().orElseThrow());
    }

    static List<ChunkKey> defaultKeys() {
        int radius = WorldGenerationConfig.defaults().chunkRadius();
        List<ChunkKey> keys = new ArrayList<>();
        for (int x = -radius; x <= radius; x++) {
            for (int z = -radius; z <= radius; z++) {
                keys.add(new ChunkKey(x, z));
            }
        }
        return List.copyOf(keys);
    }

    static ChunkGenerationData generate(
            ChunkKey key, WorldGenerationConfig config) {
        return generate(
                GaiaWorldGenerator.createDefault(),
                context(config),
                key);
    }

    private static ChunkGenerationData generate(
            WorldGenerator generator,
            GenerationContext context,
            ChunkKey key) {
        WorldGenerationResult result =
                generator.generate(context, key);
        assertTrue(result.succeeded(), () -> "Generation failed for " + key);
        return result.chunkData().orElseThrow();
    }

    static List<ChunkGenerationData> generate(
            List<ChunkKey> keys, WorldGenerationConfig config) {
        WorldGenerator generator = GaiaWorldGenerator.createDefault();
        GenerationContext context = context(config);
        return keys.stream()
                .map(key -> generate(generator, context, key))
                .toList();
    }

    static GenerationContext context(WorldGenerationConfig config) {
        return new GenerationContext(
                config,
                PALETTE,
                new DeterministicCoordinateSampler(
                        config.seed(), config.algorithmVersion()));
    }

    private static List<ChunkGenerationData> generateConcurrently(
            List<ChunkKey> keys,
            WorldGenerationConfig config,
            int workerCount)
            throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(workerCount);
        try {
            WorldGenerator generator = GaiaWorldGenerator.createDefault();
            GenerationContext context = context(config);
            CompletionService<ChunkGenerationData> completions =
                    new ExecutorCompletionService<>(executor);
            for (ChunkKey key : keys) {
                completions.submit(
                        () -> generate(generator, context, key));
            }
            List<ChunkGenerationData> generated =
                    new ArrayList<>(keys.size());
            for (int index = 0; index < keys.size(); index++) {
                generated.add(completions.take().get());
            }
            return List.copyOf(generated);
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    private static WorldGenerationConfig withSeed(
            WorldGenerationConfig config, long seed) {
        return new WorldGenerationConfig(
                seed,
                config.algorithmVersion(),
                config.chunkRadius(),
                config.biome(),
                config.height(),
                config.cave(),
                config.surface(),
                config.decoration(),
                config.spawn());
    }

    private static WorldGenerationStage stage(
            String name, GenerationStageResult.Status status) {
        return new WorldGenerationStage() {
            @Override
            public ResourceLocation id() {
                return ResourceLocation.parse("test:" + name);
            }

            @Override
            public GenerationStageResult generate(
                    GenerationContext context, GenerationRegion region) {
                return new GenerationStageResult(
                        id(), status, 1, 0, Optional.empty());
            }
        };
    }
}

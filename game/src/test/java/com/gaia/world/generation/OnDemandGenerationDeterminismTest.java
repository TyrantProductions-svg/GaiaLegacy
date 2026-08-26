package com.gaia.world.generation;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gaia.world.GaiaWorldGenerator;
import com.overlord.assets.ResourceLocation;
import com.overlord.voxel.ChunkGenerationData;
import com.overlord.voxel.ChunkKey;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class OnDemandGenerationDeterminismTest {
    private static final List<ChunkKey> REQUESTED_KEYS =
            List.of(
                    new ChunkKey(0, 0),
                    new ChunkKey(1, -3),
                    new ChunkKey(1, 0),
                    new ChunkKey(-1, -1));
    private static final Map<ChunkKey, String> VERSION_ONE_HASHES =
            Map.of(
                    new ChunkKey(0, 0),
                    "3ffb824a152c4e6f1f3333d1d785bc2645a73a685cfcac6c3b6b964232c8bd73",
                    new ChunkKey(1, -3),
                    "56f65cf7d77948b8de20a192dde0a9d31e903b6ec04eb7811afb1ad62e81374a",
                    new ChunkKey(1, 0),
                    "8dfcb80a424ffe535b740be56adcae0e6d6286d5ec5b5c811e99953deb56e9cf",
                    new ChunkKey(-1, -1),
                    "743d49d229d22d7400898f43dae920a9195c3065915f529439861568ea5c9e3c");

    @Test
    void requestHistoryCannotChangeGeneratedBytesOrCanonicalHashes() {
        WorldGenerationConfig config = WorldGenerationConfig.defaults();
        GenerationContext context = context(config);
        Map<ChunkKey, GeneratedChunk> requestedFirst =
                generateSequential(
                        GaiaWorldGenerator.createDefault(),
                        context,
                        REQUESTED_KEYS);

        WorldGenerator afterHistoryGenerator =
                GaiaWorldGenerator.createDefault();
        for (int index = 0; index < 100; index++) {
            generate(
                    afterHistoryGenerator,
                    context,
                    new ChunkKey(1_000 + index, -2_000 - index));
        }
        Map<ChunkKey, GeneratedChunk> requestedAfterHistory =
                generateSequential(
                        afterHistoryGenerator, context, REQUESTED_KEYS);

        assertExactGeneration(requestedFirst, requestedAfterHistory);
        assertEquals(VERSION_ONE_HASHES, hashes(requestedFirst));
    }

    @Test
    void unloadEquivalentRepeatCannotChangeGeneratedBytesOrHashes() {
        WorldGenerationConfig config = WorldGenerationConfig.defaults();
        GenerationContext context = context(config);
        WorldGenerator generator = GaiaWorldGenerator.createDefault();
        Map<ChunkKey, GeneratedChunk> beforeUnload =
                generateSequential(generator, context, REQUESTED_KEYS);

        Map<ChunkKey, GeneratedChunk> releasedConsumerState =
                new HashMap<>(beforeUnload);
        releasedConsumerState.clear();

        Map<ChunkKey, GeneratedChunk> afterUnload =
                generateSequential(generator, context, REQUESTED_KEYS);

        assertTrue(releasedConsumerState.isEmpty());
        assertExactGeneration(beforeUnload, afterUnload);
    }

    @Test
    void reverseAndBoundedWorkerSchedulesKeepExactResults()
            throws Exception {
        WorldGenerationConfig config = WorldGenerationConfig.defaults();
        GenerationContext context = context(config);
        Map<ChunkKey, GeneratedChunk> forward =
                generateSequential(
                        GaiaWorldGenerator.createDefault(),
                        context,
                        REQUESTED_KEYS);
        List<ChunkKey> reverse = new ArrayList<>(REQUESTED_KEYS);
        java.util.Collections.reverse(reverse);
        Map<ChunkKey, GeneratedChunk> reversed =
                generateConcurrent(reverse, context, 1);
        List<ChunkKey> shuffled =
                List.of(
                        new ChunkKey(1, 0),
                        new ChunkKey(-1, -1),
                        new ChunkKey(0, 0),
                        new ChunkKey(1, -3));

        assertExactGeneration(forward, reversed);
        for (int workerCount : List.of(1, 2, 4)) {
            assertExactGeneration(
                    forward,
                    generateConcurrent(
                            shuffled, context, workerCount));
        }
    }

    @Test
    void stageVersionAndFullLongLatticeAreDeterministicInputs() {
        DeterministicCoordinateSampler sampler =
                new DeterministicCoordinateSampler(12345L, 1);
        GenerationStageContract firstVersion =
                new GenerationStageContract(
                        ResourceLocation.parse("gaia:long_lattice"),
                        1,
                        0);
        GenerationStageContract secondVersion =
                new GenerationStageContract(
                        ResourceLocation.parse("gaia:long_lattice"),
                        2,
                        0);
        long latticeX = 1L << 32;
        long latticeZ = -(1L << 33);

        double sample =
                sampler.unit(
                        firstVersion,
                        latticeX,
                        -2_147_483_649L,
                        latticeZ,
                        17L);

        assertEquals(
                sample,
                sampler.unit(
                        firstVersion,
                        latticeX,
                        -2_147_483_649L,
                        latticeZ,
                        17L));
        assertNotEquals(
                sample,
                sampler.unit(
                        firstVersion,
                        0L,
                        -2_147_483_649L,
                        latticeZ,
                        17L),
                "discarding the high 32 lattice bits aliases distant samples");
        assertNotEquals(
                sample,
                sampler.unit(
                        secondVersion,
                        latticeX,
                        -2_147_483_649L,
                        latticeZ,
                        17L),
                "stage version must participate in deterministic entropy");

        double noise =
                sampler.valueNoise2D(
                        firstVersion,
                        Integer.MAX_VALUE - 0.25,
                        Integer.MIN_VALUE + 0.75,
                        2.0,
                        91L);
        assertTrue(Double.isFinite(noise));
        assertEquals(
                noise,
                sampler.valueNoise2D(
                        firstVersion,
                        Integer.MAX_VALUE - 0.25,
                        Integer.MIN_VALUE + 0.75,
                        2.0,
                        91L));
    }

    @Test
    void bothProductionFactoriesExposeExactStageContracts() {
        List<GenerationStageContract> defaultContracts =
                List.of(
                        new ContinuousBiomeProvider().contract(),
                        new BlendedHeightProvider().contract(),
                        new DefaultStrataDensityProvider().contract(),
                        new NoiseCaveProvider().contract(),
                        new DefaultSurfaceProvider().contract(),
                        new StoneOutcropDecorationProvider().contract());
        HybridCaveProvider visualCaves =
                new HybridCaveProvider();
        List<GenerationStageContract> visualContracts =
                List.of(
                        new ContinuousBiomeProvider().contract(),
                        new BiomeShapedHeightProvider().contract(),
                        new DefaultStrataDensityProvider().contract(),
                        visualCaves.contract(),
                        new DefaultSurfaceProvider().contract(),
                        new CompositeDecorationProvider(
                                        visualCaves.entranceQuery())
                                .contract());

        assertEquals(
                List.of(
                        contract("gaia:continuous_biomes", 1, 0),
                        contract("gaia:blended_heights", 1, 0),
                        contract("gaia:strata_density", 1, 0),
                        contract("gaia:cave", 1, 0),
                        contract("gaia:surface", 1, 1),
                        contract("gaia:decoration", 1, 0)),
                defaultContracts);
        assertEquals(
                List.of(
                        contract("gaia:continuous_biomes", 1, 0),
                        contract("gaia:blended_heights", 1, 0),
                        contract("gaia:strata_density", 1, 0),
                        contract("gaia:cave", 1, 96),
                        contract("gaia:surface", 1, 1),
                        contract("gaia:decoration", 1, 4)),
                visualContracts);

        assertEquals(
                defaultContracts.stream()
                        .map(GenerationStageContract::id)
                        .toList(),
                stageIds(
                        GaiaWorldGenerator.createDefault(),
                        context(WorldGenerationConfig.defaults())));
        assertEquals(
                visualContracts.stream()
                        .map(GenerationStageContract::id)
                        .toList(),
                stageIds(
                        GaiaWorldGenerator.createVisualRevisionCandidate(),
                        context(
                                WorldGenerationConfig
                                        .visualRevisionCandidate())));
    }

    @Test
    void everyProductionStageDeclaresItsOwnContract() {
        for (Class<? extends WorldGenerationStage> stageClass :
                List.of(
                        ContinuousBiomeProvider.class,
                        BlendedHeightProvider.class,
                        BiomeShapedHeightProvider.class,
                        DefaultStrataDensityProvider.class,
                        NoiseCaveProvider.class,
                        HybridCaveProvider.class,
                        DefaultSurfaceProvider.class,
                        StoneOutcropDecorationProvider.class,
                        CompositeDecorationProvider.class)) {
            assertTrue(
                    declaresContract(stageClass),
                    () -> stageClass + " inherited an implicit contract");
        }
    }

    private static boolean declaresContract(
            Class<? extends WorldGenerationStage> stageClass) {
        try {
            return stageClass
                            .getDeclaredMethod("contract")
                            .getDeclaringClass()
                    == stageClass;
        } catch (NoSuchMethodException missing) {
            return false;
        }
    }

    @Test
    void exposedVersionsDriveChildGenerationAndDeclaredHaloBounds() {
        GenerationStageContract compositeV1 =
                contract("gaia:decoration", 1, 4);
        GenerationStageContract compositeV2 =
                compositeV1.withVersion(2);
        GenerationStageContract hybridV1 =
                contract("gaia:cave", 1, 96);
        GenerationStageContract hybridV2 = hybridV1.withVersion(2);

        assertEquals(
                new GenerationStageContract.RegionRange(3L, 6L),
                compositeV1.regionsForChunk(32L, 16, 8));
        assertEquals(
                new GenerationStageContract.RegionRange(-2L, 4L),
                hybridV1.regionsForChunk(32L, 16, 32));

        WorldGenerationConfig config =
                WorldGenerationConfig.visualRevisionCandidate();
        GenerationContext context = context(config);
        List<ChunkKey> keys =
                List.of(
                        new ChunkKey(-2, 1),
                        new ChunkKey(0, 0),
                        new ChunkKey(2, -1),
                        new ChunkKey(1, -3));
        assertNotEquals(
                aggregateHashes(
                        visualGenerator(
                                hybridV1,
                                compositeV1),
                        context,
                        keys),
                aggregateHashes(
                        visualGenerator(
                                hybridV2,
                                compositeV1),
                        context,
                        keys),
                "hybrid child sampling must derive from the exposed cave version");
        assertNotEquals(
                aggregateHashes(
                        visualGenerator(
                                hybridV1,
                                compositeV1),
                        context,
                        keys),
                aggregateHashes(
                        visualGenerator(
                                hybridV1,
                                compositeV2),
                        context,
                        keys),
                "tree/outcrop anchors must derive from the exposed decoration version");

        DeterministicCoordinateSampler sampler = context.sampler();
        for (GenerationStageContract exposed :
                List.of(
                        contract("gaia:continuous_biomes", 1, 0),
                        contract("gaia:blended_heights", 1, 0),
                        contract("gaia:strata_density", 1, 0),
                        contract("gaia:cave", 1, 0),
                        contract("gaia:surface", 1, 1),
                        contract("gaia:decoration", 1, 0),
                        hybridV1,
                        compositeV1)) {
            assertNotEquals(
                    sampler.unit(exposed, 37L, 19L, -53L, 7L),
                    sampler.unit(
                            exposed.withVersion(2),
                            37L,
                            19L,
                            -53L,
                            7L),
                    () -> "version did not perturb " + exposed.id());
        }
    }

    private static GenerationStageContract contract(
            String id, int version, int haloRadius) {
        return new GenerationStageContract(
                ResourceLocation.parse(id), version, haloRadius);
    }

    private static List<ResourceLocation> stageIds(
            WorldGenerator generator,
            GenerationContext context) {
        WorldGenerationResult result =
                generator.generate(context, new ChunkKey(0, 0));
        assertTrue(result.succeeded());
        return result.stageResults().stream()
                .map(GenerationStageResult::stageId)
                .toList();
    }

    private static WorldGenerator visualGenerator(
            GenerationStageContract caveContract,
            GenerationStageContract decorationContract) {
        HybridCaveProvider caves =
                new HybridCaveProvider(caveContract);
        CompositeDecorationProvider decoration =
                new CompositeDecorationProvider(
                        decorationContract,
                        caves.entranceQuery());
        return new StagedWorldGenerator(
                List.of(
                        new ContinuousBiomeProvider(),
                        new BiomeShapedHeightProvider(),
                        new DefaultStrataDensityProvider(),
                        caves,
                        new DefaultSurfaceProvider(),
                        decoration));
    }

    private static List<String> aggregateHashes(
            WorldGenerator generator,
            GenerationContext context,
            List<ChunkKey> keys) {
        return keys.stream()
                .map(key -> generated(generator, context, key).hash())
                .toList();
    }

    private static Map<ChunkKey, GeneratedChunk> generateConcurrent(
            List<ChunkKey> keys,
            GenerationContext context,
            int workerCount)
            throws Exception {
        ExecutorService executor =
                Executors.newFixedThreadPool(workerCount);
        try {
            WorldGenerator generator =
                    GaiaWorldGenerator.createDefault();
            List<Callable<GeneratedChunk>> tasks =
                    keys.stream()
                            .<Callable<GeneratedChunk>>map(
                                    key ->
                                            () ->
                                                    generated(
                                                            generator,
                                                            context,
                                                            key))
                            .toList();
            List<Future<GeneratedChunk>> futures =
                    executor.invokeAll(tasks, 20, TimeUnit.SECONDS);
            Map<ChunkKey, GeneratedChunk> results =
                    new HashMap<>();
            for (Future<GeneratedChunk> future : futures) {
                assertFalse(
                        future.isCancelled(),
                        "bounded generation schedule timed out");
                GeneratedChunk chunk = future.get(1, TimeUnit.SECONDS);
                assertNull(results.put(chunk.key(), chunk));
            }
            return Map.copyOf(results);
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    private static Map<ChunkKey, GeneratedChunk> generateSequential(
            WorldGenerator generator,
            GenerationContext context,
            List<ChunkKey> keys) {
        Map<ChunkKey, GeneratedChunk> results = new HashMap<>();
        for (ChunkKey key : keys) {
            GeneratedChunk chunk = generated(generator, context, key);
            assertNull(results.put(key, chunk));
        }
        return Map.copyOf(results);
    }

    private static GeneratedChunk generated(
            WorldGenerator generator,
            GenerationContext context,
            ChunkKey key) {
        ChunkGenerationData data = generate(generator, context, key);
        return new GeneratedChunk(
                key,
                data.copyBlocks(),
                WorldGenerationHasher.hashChunk(
                        context.config(), data));
    }

    private static ChunkGenerationData generate(
            WorldGenerator generator,
            GenerationContext context,
            ChunkKey key) {
        WorldGenerationResult result =
                generator.generate(context, key);
        assertTrue(
                result.succeeded(),
                () -> "Generation failed for " + key);
        return result.chunkData().orElseThrow();
    }

    private static GenerationContext context(
            WorldGenerationConfig config) {
        return new GenerationContext(
                config,
                new GenerationBlockPalette(
                        (byte) 0,
                        (byte) 1,
                        (byte) 2,
                        (byte) 3),
                new DeterministicCoordinateSampler(
                        config.seed(), config.algorithmVersion()));
    }

    private static void assertExactGeneration(
            Map<ChunkKey, GeneratedChunk> expected,
            Map<ChunkKey, GeneratedChunk> actual) {
        assertEquals(expected.keySet(), actual.keySet());
        for (ChunkKey key : expected.keySet()) {
            GeneratedChunk expectedChunk = expected.get(key);
            GeneratedChunk actualChunk = actual.get(key);
            assertArrayEquals(
                    expectedChunk.blocks(),
                    actualChunk.blocks(),
                    "Block bytes changed for " + key);
            assertEquals(
                    expectedChunk.hash(),
                    actualChunk.hash(),
                    "Canonical hash changed for " + key);
        }
    }

    private static Map<ChunkKey, String> hashes(
            Map<ChunkKey, GeneratedChunk> generated) {
        Map<ChunkKey, String> hashes = new HashMap<>();
        generated.forEach(
                (key, chunk) -> hashes.put(key, chunk.hash()));
        return Map.copyOf(hashes);
    }

    private record GeneratedChunk(
            ChunkKey key, byte[] blocks, String hash) {
    }
}

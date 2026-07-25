package com.gaia.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gaia.assets.GaiaAssetCatalog;
import com.gaia.assets.GaiaResourceLoader;
import com.gaia.world.generation.DeterministicCoordinateSampler;
import com.gaia.world.generation.GenerationBlockPalette;
import com.gaia.world.generation.GenerationContext;
import com.gaia.world.generation.GenerationStageResult;
import com.gaia.world.generation.WorldGenerationConfig;
import com.gaia.world.generation.WorldGenerationResult;
import com.gaia.world.generation.WorldGenerator;
import com.overlord.assets.AssetManager;
import com.overlord.assets.ResourceLocation;
import com.overlord.config.GameConfig;
import com.overlord.voxel.ChunkDirtyTracker;
import com.overlord.voxel.ChunkGenerationData;
import com.overlord.voxel.ChunkGenerationStatus;
import com.overlord.voxel.ChunkKey;
import com.overlord.voxel.ChunkRepository;
import com.overlord.voxel.ChunkState;
import com.overlord.voxel.World;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

class WorldLoaderTest {
    private static final GaiaAssetCatalog CATALOG = productionCatalog();
    private static final GenerationContext GENERATION_CONTEXT =
            productionContext();
    private static final WorldLoader LOADER =
            new WorldLoader(
                    GaiaWorldGenerator.createDefault(),
                    GENERATION_CONTEXT);

    @Test
    void generatesIndependentChunksWithoutCpuMeshCombination()
            throws Exception {
        Thread testThread = Thread.currentThread();
        AtomicReference<Thread> loaderThread = new AtomicReference<>();
        World world = new World();
        WorldLoadResult result =
                workerLoad(world, loaderThread);

        assertNotEquals(testThread, loaderThread.get());
        assertEquals(16, result.initialChunks().size());
        assertTrue(
                result.initialChunks().stream()
                        .allMatch(world.chunks()::contains));
        assertTrue(
                result.initialChunks().stream()
                        .map(world.chunks()::state)
                        .allMatch(
                                state ->
                                        state == ChunkState.GENERATED
                                                || state == ChunkState.DIRTY));
        assertTrue(
                result.initialChunks().stream()
                        .map(world.chunks()::generationStatus)
                        .allMatch(
                                status ->
                                        status
                                                == ChunkGenerationStatus
                                                        .COMMITTED));
        assertEquals(
                Set.class,
                WorldLoadResult.class.getRecordComponents()[0].getType());
        assertEquals(
                Vector3f.class,
                WorldLoadResult.class.getRecordComponents()[1].getType());
        assertEquals(0.5f, result.playerFeetPosition().x, 1.0e-6f);
        assertEquals(0.5f, result.playerFeetPosition().z, 1.0e-6f);

        int groundY =
                (int)
                                Math.floor(
                                        result.playerFeetPosition().y)
                        - 1;
        assertNotEquals(0, world.getBlock(0, groundY, 0));
        assertEquals(
                groundY + 1.0f,
                result.playerFeetPosition().y,
                1.0e-6f);
    }

    @Test
    void loadResultDefensivelyCopiesChunksAndSpawnPosition() {
        Set<ChunkKey> suppliedChunks =
                new LinkedHashSet<>(
                        Set.of(
                                new ChunkKey(0, 0),
                                new ChunkKey(1, 0)));
        Vector3f suppliedSpawn = new Vector3f(0.5f, 31.8f, 0.5f);

        WorldLoadResult result =
                new WorldLoadResult(suppliedChunks, suppliedSpawn);
        suppliedChunks.clear();
        suppliedSpawn.zero();

        assertEquals(2, result.initialChunks().size());
        assertThrows(
                UnsupportedOperationException.class,
                () ->
                        result.initialChunks()
                                .add(new ChunkKey(2, 0)));
        Vector3f returnedSpawn = result.playerFeetPosition();
        returnedSpawn.set(9.0f, 9.0f, 9.0f);
        assertEquals(
                new Vector3f(0.5f, 31.8f, 0.5f),
                result.playerFeetPosition());
    }

    @Test
    void workerLoaderDoesNotReferenceMeshingOrGpuTypes()
            throws IOException {
        String source =
                Files.readString(
                        Path.of(
                                "src/main/java/com/gaia/world/"
                                        + "WorldLoader.java"));

        assertFalse(source.contains("ChunkMeshBuilder"));
        assertFalse(source.contains("combineMeshData"));
        assertFalse(source.contains("float[]"));
        assertFalse(source.contains("com.overlord.renderer"));
        assertFalse(source.contains("new Mesh("));
        assertFalse(source.contains("Renderer"));
    }

    @Test
    void publishesOnlyThroughInitialGenerationTransactions()
            throws IOException {
        String source =
                Files.readString(
                        Path.of(
                                "src/main/java/com/gaia/world/"
                                        + "WorldLoader.java"));

        assertTrue(source.contains("beginGeneration("));
        assertTrue(source.contains("commitGeneration("));
        assertTrue(source.contains("failGeneration("));
        assertTrue(
                source.contains(
                        "ChunkGenerationMode.INITIAL"));
        assertFalse(source.contains("world.generate("));
        assertFalse(source.contains("world.setBlock("));
    }

    @Test
    void stageFailureFailsTicketWithoutPublishingChunk() {
        ResourceLocation failedStage =
                ResourceLocation.parse("gaia:failed");
        IllegalStateException stageCause =
                new IllegalStateException("stage failed");
        WorldGenerator failingGenerator =
                (context, key) ->
                        new WorldGenerationResult(
                                Optional.empty(),
                                List.of(
                                        new GenerationStageResult(
                                                failedStage,
                                                GenerationStageResult
                                                        .Status.FAILED,
                                                0,
                                                0,
                                                Optional.of(
                                                        stageCause))));
        WorldLoader loader =
                new WorldLoader(
                        failingGenerator,
                        GENERATION_CONTEXT);
        World world = new World();
        ChunkKey firstKey = new ChunkKey(-2, -2);

        IllegalStateException failure =
                assertThrows(
                        IllegalStateException.class,
                        () -> loader.load(world));

        assertTrue(
                failure.getMessage().contains(
                        failedStage.toString()));
        assertEquals(stageCause, failure.getCause());
        assertFalse(world.chunks().contains(firstKey));
        assertEquals(
                ChunkGenerationStatus.FAILED,
                world.chunks().generationStatus(firstKey));
        assertEquals(
                failure,
                world.chunks()
                        .generationFailure(firstKey)
                        .orElseThrow());
    }

    @Test
    void mismatchedGeneratedKeyFailsLiveTicket() {
        ChunkKey requestedKey = new ChunkKey(-2, -2);
        ChunkKey returnedKey = new ChunkKey(20, 30);
        World world = new World();
        IllegalStateException failure =
                assertRejectedGeneratedData(
                        world,
                        (context, key) ->
                                succeededGeneration(
                                        emptyData(
                                                returnedKey,
                                                GameConfig.Chunk
                                                        .MAX_HEIGHT)));

        assertFailedWithoutPublication(
                world, requestedKey, failure);
        assertTrue(failure.getMessage().contains("key"));
        assertTrue(
                failure.getMessage().contains(
                        requestedKey.toString()));
        assertTrue(
                failure.getMessage().contains(
                        returnedKey.toString()));
        assertFalse(world.chunks().contains(returnedKey));
    }

    @Test
    void mismatchedGeneratedWorldHeightFailsLiveTicket() {
        int repositoryHeight = 8;
        int returnedHeight = 9;
        World world =
                new World(
                        new ChunkRepository(
                                repositoryHeight,
                                new ChunkDirtyTracker()));
        ChunkKey requestedKey = new ChunkKey(-2, -2);
        IllegalStateException failure =
                assertRejectedGeneratedData(
                        world,
                        (context, key) ->
                                succeededGeneration(
                                        emptyData(
                                                key,
                                                returnedHeight)));

        assertFailedWithoutPublication(
                world, requestedKey, failure);
        assertTrue(
                failure.getMessage().contains(
                        "world height"));
        assertTrue(
                failure.getMessage().contains(
                        Integer.toString(repositoryHeight)));
        assertTrue(
                failure.getMessage().contains(
                        Integer.toString(returnedHeight)));
    }

    @Test
    void commitConflictIsReportedWithoutRunningAttemptLeak() {
        World world = new World();
        WorldGenerator conflictingGenerator =
                (context, key) -> {
                    assertTrue(
                            world.chunks().beginUnload(key));
                    assertTrue(
                            world.chunks().completeUnload(key));
                    return succeededGeneration(
                            emptyData(
                                    key,
                                    GameConfig.Chunk
                                            .MAX_HEIGHT));
                };
        WorldLoader loader =
                new WorldLoader(
                        conflictingGenerator,
                        GENERATION_CONTEXT);
        ChunkKey firstKey = new ChunkKey(-2, -2);

        IllegalStateException failure =
                assertThrows(
                        IllegalStateException.class,
                        () -> loader.load(world));

        assertTrue(failure.getMessage().contains("CONFLICT"));
        assertFalse(world.chunks().contains(firstKey));
        assertEquals(
                ChunkGenerationStatus.IDLE,
                world.chunks().generationStatus(firstKey));
        assertTrue(
                world.chunks()
                        .generationFailure(firstKey)
                        .isEmpty());
    }

    private static IllegalStateException
            assertRejectedGeneratedData(
                    World world,
                    WorldGenerator generator) {
        WorldLoader loader =
                new WorldLoader(
                        generator,
                        GENERATION_CONTEXT);
        return assertThrows(
                IllegalStateException.class,
                () -> loader.load(world));
    }

    private static void assertFailedWithoutPublication(
            World world,
            ChunkKey requestedKey,
            IllegalStateException failure) {
        assertFalse(world.chunks().contains(requestedKey));
        assertEquals(
                ChunkGenerationStatus.FAILED,
                world.chunks().generationStatus(
                        requestedKey));
        assertEquals(
                failure,
                world.chunks()
                        .generationFailure(requestedKey)
                        .orElseThrow());
    }

    private static WorldGenerationResult
            succeededGeneration(
                    ChunkGenerationData data) {
        return new WorldGenerationResult(
                Optional.of(data), List.of());
    }

    private static ChunkGenerationData emptyData(
            ChunkKey key, int worldHeight) {
        return new ChunkGenerationData(
                key,
                worldHeight,
                new byte[
                        GameConfig.Chunk.SIZE
                                * worldHeight
                                * GameConfig.Chunk.SIZE]);
    }

    private static WorldLoadResult workerLoad(
            World world, AtomicReference<Thread> loaderThread)
            throws Exception {
        ExecutorService worker =
                Executors.newSingleThreadExecutor(
                        runnable ->
                                new Thread(
                                        () -> {
                                            loaderThread.set(Thread.currentThread());
                                            runnable.run();
                                        },
                                        "world-loader-test"));
        try {
            Future<WorldLoadResult> future =
                    worker.submit(() -> LOADER.load(world));
            return future.get();
        } finally {
            worker.shutdownNow();
        }
    }

    @Test
    void cancelsWhenWorkerIsAlreadyInterrupted() throws InterruptedException {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            Future<WorldLoadResult> future =
                    worker.submit(
                            () -> {
                                Thread.currentThread().interrupt();
                                return LOADER.load(new World());
                            });

            ExecutionException failure =
                    assertThrows(
                            ExecutionException.class, future::get);
            assertNotNull(failure.getCause());
            assertInstanceOf(CancellationException.class, failure.getCause());
        } finally {
            worker.shutdownNow();
        }
    }

    private static GaiaAssetCatalog productionCatalog() {
        return new GaiaResourceLoader(
                        new AssetManager(
                                WorldLoaderTest.class.getClassLoader()))
                .load();
    }

    private static GenerationContext productionContext() {
        WorldGenerationConfig config =
                WorldGenerationConfig.defaults();
        return new GenerationContext(
                config,
                GenerationBlockPalette.from(
                        CATALOG.blockRegistry()),
                new DeterministicCoordinateSampler(
                        config.seed(),
                        config.algorithmVersion()));
    }
}

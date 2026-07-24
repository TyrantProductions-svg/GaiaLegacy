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
import com.overlord.assets.AssetManager;
import com.overlord.assets.ResourceLocation;
import com.overlord.config.GameConfig;
import com.overlord.voxel.ChunkKey;
import com.overlord.voxel.ChunkState;
import com.overlord.voxel.World;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
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
    private static final WorldLoader LOADER =
            new WorldLoader(
                    new GaiaWorldGenerator(CATALOG.blockRegistry()),
                    CATALOG.blockRegistry()
                            .requireStoredId(
                                    ResourceLocation.parse("gaia:grass")));

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
        assertEquals(
                Set.class,
                WorldLoadResult.class.getRecordComponents()[0].getType());
        assertEquals(
                Vector3f.class,
                WorldLoadResult.class.getRecordComponents()[1].getType());
        assertEquals(0.5f, result.spawnPosition().x, 1.0e-6f);
        assertEquals(0.5f, result.spawnPosition().z, 1.0e-6f);

        int groundY =
                (int)
                                Math.floor(
                                        result.spawnPosition().y
                                                - GameConfig.Player.HEIGHT)
                        - 1;
        assertNotEquals(0, world.getBlock(0, groundY, 0));
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
        Vector3f returnedSpawn = result.spawnPosition();
        returnedSpawn.set(9.0f, 9.0f, 9.0f);
        assertEquals(
                new Vector3f(0.5f, 31.8f, 0.5f),
                result.spawnPosition());
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
}

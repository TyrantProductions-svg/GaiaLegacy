package com.gaia.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gaia.assets.GaiaAssetCatalog;
import com.gaia.assets.GaiaResourceLoader;
import com.overlord.assets.AssetManager;
import com.overlord.assets.ResourceLocation;
import com.overlord.config.GameConfig;
import com.overlord.voxel.ChunkMeshBuilder;
import com.overlord.voxel.World;
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
                    new ChunkMeshBuilder(CATALOG.blockRegistry()),
                    CATALOG.blockRegistry()
                            .requireStoredId(
                                    ResourceLocation.parse("gaia:grass")));

    @Test
    void generatesTerrainAndCpuMeshOnWorkerThread() throws Exception {
        Thread testThread = Thread.currentThread();
        AtomicReference<Thread> loaderThread = new AtomicReference<>();
        World world = new World();
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
            WorldLoadResult result = future.get();

            assertNotEquals(testThread, loaderThread.get());
            assertTrue(result.meshData().length > 0);
            assertEquals(0, result.meshData().length % 5);
            assertEquals(0.5f, result.spawnPosition().x, 1.0e-6f);
            assertEquals(0.5f, result.spawnPosition().z, 1.0e-6f);

            int groundY =
                    (int)
                                    Math.floor(
                                            result.spawnPosition().y
                                                    - GameConfig.Player.HEIGHT)
                            - 1;
            assertNotEquals(0, world.getBlock(0, groundY, 0));
            assertEquals(2, WorldLoadResult.class.getRecordComponents().length);
            assertEquals(float[].class, WorldLoadResult.class.getRecordComponents()[0].getType());
            assertEquals(
                    Vector3f.class, WorldLoadResult.class.getRecordComponents()[1].getType());
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
                    org.junit.jupiter.api.Assertions.assertThrows(
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

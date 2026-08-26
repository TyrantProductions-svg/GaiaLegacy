package com.gaia.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gaia.assets.GaiaAssetCatalog;
import com.gaia.assets.GaiaResourceLoader;
import com.gaia.blocks.BlockRegistry;
import com.gaia.world.generation.GenerationContext;
import com.gaia.world.generation.GenerationRegion;
import com.gaia.world.generation.GenerationStageResult;
import com.gaia.world.generation.StagedWorldGenerator;
import com.gaia.world.generation.WorldGenerationConfig;
import com.gaia.world.generation.WorldGenerationResult;
import com.gaia.world.generation.WorldGenerationStage;
import com.gaia.world.generation.WorldGenerator;
import com.overlord.assets.AssetManager;
import com.overlord.assets.ResourceLocation;
import com.overlord.config.GameConfig;
import com.overlord.voxel.ChunkDirtyTracker;
import com.overlord.voxel.ChunkGenerationData;
import com.overlord.voxel.ChunkGenerationMode;
import com.overlord.voxel.ChunkGenerationResult;
import com.overlord.voxel.ChunkGenerationStatus;
import com.overlord.voxel.ChunkGenerationTicket;
import com.overlord.voxel.ChunkKey;
import com.overlord.voxel.ChunkRepository;
import com.overlord.voxel.ChunkState;
import com.overlord.voxel.World;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigestSpi;
import java.security.Provider;
import java.security.Security;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

class WorldLoaderTest {
    private static final GaiaAssetCatalog CATALOG = productionCatalog();
    private static final BlockRegistry BLOCKS = CATALOG.blockRegistry();
    private static final ExecutorService DIRECT_EXECUTOR =
            new AbstractExecutorService() {
                @Override
                public void shutdown() {}

                @Override
                public List<Runnable> shutdownNow() {
                    return List.of();
                }

                @Override
                public boolean isShutdown() {
                    return false;
                }

                @Override
                public boolean isTerminated() {
                    return false;
                }

                @Override
                public boolean awaitTermination(
                        long timeout, TimeUnit unit) {
                    return false;
                }

                @Override
                public void execute(Runnable command) {
                    command.run();
                }
            };
    private static final byte STONE =
            BLOCKS.requireStoredId(ResourceLocation.parse("gaia:stone"));

    @Test
    void defaultPipelineLoadPublishesCanonicalWorldAndSafeSpawn() {
        WorldGenerationConfig config = WorldGenerationConfig.defaults();
        WorldLoader loader =
                loader(GaiaWorldGenerator.createDefault(), config);
        World world = new World();

        WorldLoadResult result = loader.load(world);

        assertEquals(81, result.initialChunks().size());
        assertEquals(
                "161f6c10773c8dfd84e6961183e8706d5a0ec00750e727e83c4a08afcfbd5ce8",
                result.generationHash());
        assertTrue(
                result.initialChunks().stream()
                        .allMatch(
                                key ->
                                        world.chunks().contains(key)
                                                && world.chunks()
                                                        .snapshot(key)
                                                        .isPresent()));

        Vector3f spawn = result.playerFeetPosition();
        int x = (int) StrictMath.floor(spawn.x);
        int y = (int) StrictMath.floor(spawn.y);
        int z = (int) StrictMath.floor(spawn.z);
        assertNotEquals(0, world.getBlock(x, y - 1, z));
        assertEquals(0, world.getBlock(x, y, z));
        assertEquals(0, world.getBlock(x, y + 1, z));
        assertEquals(WorldLoadState.SUCCEEDED, loader.state());
    }

    @Test
    void defaultLoadCommitsInclusiveRadiusFourInDeterministicOrder() {
        List<ChunkKey> generatedOrder = new ArrayList<>();
        WorldLoader loader =
                loader(
                        (context, key) -> {
                            generatedOrder.add(key);
                            return succeededGeneration(flatData(key, STONE));
                        },
                        WorldGenerationConfig.defaults());
        World world = new World();

        WorldLoadResult result = loader.load(world);

        assertEquals(81, result.initialChunks().size());
        assertEquals(
                List.of(
                        new ChunkKey(-4, -4),
                        new ChunkKey(-4, -3),
                        new ChunkKey(-4, -2),
                        new ChunkKey(-4, -1),
                        new ChunkKey(-4, 0)),
                generatedOrder.subList(0, 5));
        assertEquals(new ChunkKey(4, 4), generatedOrder.get(80));
        assertEquals(generatedOrder, List.copyOf(result.initialChunks()));
        assertTrue(
                result.initialChunks().stream()
                        .allMatch(world.chunks()::contains));
        assertEquals(WorldLoadState.SUCCEEDED, loader.state());
        assertEquals(Optional.empty(), loader.failure());
        assertFalse(result.configFingerprint().isBlank());
        assertFalse(result.generationHash().isBlank());
        assertEquals(new Vector3f(0.5f, 1.0f, 0.5f),
                result.playerFeetPosition());
    }

    @Test
    void configuredRadiusOneLoadsNineChunks() {
        WorldGenerationConfig config =
                withRadius(WorldGenerationConfig.defaults(), 1);
        WorldLoadResult result =
                loader(flatGenerator(STONE), config).load(new World());

        assertEquals(9, result.initialChunks().size());
        assertTrue(result.initialChunks().contains(new ChunkKey(-1, -1)));
        assertTrue(result.initialChunks().contains(new ChunkKey(1, 1)));
    }

    @Test
    void rejectsRadiusWhoseInclusiveAreaOverflows() {
        WorldGenerationConfig config =
                withRadius(
                        WorldGenerationConfig.defaults(),
                        Integer.MAX_VALUE);

        assertThrows(
                IllegalArgumentException.class,
                () -> loader(flatGenerator(STONE), config));
    }

    @Test
    void asyncLoadAndRebuildUseTheSameInjectedWorkerOffCaller()
            throws Exception {
        Thread testThread = Thread.currentThread();
        List<Thread> generatorThreads = new ArrayList<>();
        ChunkKey key = new ChunkKey(0, 0);
        ExecutorService worker =
                Executors.newSingleThreadExecutor(
                        runnable ->
                                new Thread(
                                        runnable,
                                        "test-world-generation"));
        WorldLoader loader =
                loader(
                        (context, generatedKey) -> {
                            generatorThreads.add(Thread.currentThread());
                            return succeededGeneration(
                                    flatData(generatedKey, STONE));
                        },
                        withRadius(WorldGenerationConfig.defaults(), 0),
                        worker);
        World world = new World();
        try {
            loader.loadAsync(world).get();
            loader.rebuildRegionAsync(
                            world,
                            Set.of(key),
                            WorldGenerationConfig.defaults())
                    .get();
        } finally {
            worker.shutdownNow();
            assertTrue(
                    worker.awaitTermination(5, TimeUnit.SECONDS));
        }

        assertEquals(2, generatorThreads.size());
        assertTrue(
                generatorThreads.stream()
                        .allMatch(
                                thread ->
                                        thread != testThread
                                                && thread.getName()
                                                        .equals(
                                                                "test-world-generation")));
    }

    @Test
    void asyncLoadPreservesFailureCauseAndTerminalState() {
        AssertionError cause = new AssertionError("async generator exploded");
        ExecutorService worker = Executors.newSingleThreadExecutor();
        WorldLoader loader =
                loader(
                        (context, key) -> {
                            throw cause;
                        },
                        withRadius(WorldGenerationConfig.defaults(), 0),
                        worker);
        try {
            CompletionException thrown =
                    assertThrows(
                            CompletionException.class,
                            () -> loader.loadAsync(new World()).join());

            WorldLoadException loadFailure =
                    assertInstanceOf(
                            WorldLoadException.class, thrown.getCause());
            assertSame(cause, loadFailure.failure().cause());
            assertSame(cause, loadFailure.getCause());
            assertEquals(WorldLoadState.FAILED, loader.state());
        } finally {
            worker.shutdownNow();
        }
    }

    @Test
    void shutDownGenerationExecutorRejectsWithoutRunningProvider() {
        AtomicInteger calls = new AtomicInteger();
        ExecutorService worker = Executors.newSingleThreadExecutor();
        WorldLoader loader =
                loader(
                        (context, key) -> {
                            calls.incrementAndGet();
                            return succeededGeneration(flatData(key, STONE));
                        },
                        withRadius(WorldGenerationConfig.defaults(), 0),
                        worker);
        worker.shutdownNow();

        assertThrows(
                RejectedExecutionException.class,
                () -> loader.loadAsync(new World()));
        assertEquals(0, calls.get());
        assertEquals(WorldLoadState.IDLE, loader.state());
    }

    @Test
    void cancellingRunningAsyncLoadInterruptsTaskAndPreventsPublication()
            throws Exception {
        ChunkKey key = new ChunkKey(0, 0);
        CountDownLatch providerStarted = new CountDownLatch(1);
        CountDownLatch releaseProvider = new CountDownLatch(1);
        CountDownLatch interruptionObserved = new CountDownLatch(1);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        WorldLoader loader =
                loader(
                        (context, generatedKey) -> {
                            providerStarted.countDown();
                            awaitAfterInterruption(
                                    releaseProvider,
                                    interruptionObserved);
                            return succeededGeneration(
                                    flatData(generatedKey, STONE));
                        },
                        withRadius(WorldGenerationConfig.defaults(), 0),
                        worker);
        World world = new World();
        java.util.concurrent.CompletableFuture<WorldLoadResult> future =
                loader.loadAsync(world);
        try {
            assertTrue(providerStarted.await(5, TimeUnit.SECONDS));

            assertTrue(future.cancel(true));
            assertThrows(CancellationException.class, future::join);
            assertTrue(
                    interruptionObserved.await(2, TimeUnit.SECONDS),
                    "Cancelling the public load future did not interrupt "
                            + "the running provider");
            releaseProvider.countDown();

            awaitGenerationTerminal(world, key);
            assertEquals(WorldLoadState.CANCELLED, loader.state());
            assertFalse(world.chunks().contains(key));
            assertEquals(
                    ChunkGenerationStatus.FAILED,
                    world.chunks().generationStatus(key));
            assertInstanceOf(
                    CancellationException.class,
                    world.chunks().generationFailure(key).orElseThrow());
        } finally {
            releaseProvider.countDown();
            worker.shutdownNow();
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void cancelledRunningLoadRetainsReservationUntilWorkerTerminates()
            throws Exception {
        CountDownLatch providerStarted = new CountDownLatch(1);
        CountDownLatch releaseProvider = new CountDownLatch(1);
        CountDownLatch interruptionObserved = new CountDownLatch(1);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        WorldLoader loader =
                loader(
                        (context, key) -> {
                            providerStarted.countDown();
                            awaitAfterInterruption(
                                    releaseProvider,
                                    interruptionObserved);
                            return succeededGeneration(
                                    flatData(key, STONE));
                        },
                        withRadius(WorldGenerationConfig.defaults(), 0),
                        worker);
        World world = new World();
        java.util.concurrent.CompletableFuture<WorldLoadResult> future =
                loader.loadAsync(world);
        try {
            assertTrue(providerStarted.await(5, TimeUnit.SECONDS));
            assertTrue(future.cancel(true));
            assertTrue(
                    interruptionObserved.await(2, TimeUnit.SECONDS));

            assertThrows(
                    IllegalStateException.class,
                    () -> loader.loadAsync(new World()));
        } finally {
            releaseProvider.countDown();
            awaitGenerationTerminal(
                    world, new ChunkKey(0, 0));
            worker.shutdownNow();
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void cancellingRunningAsyncRebuildInterruptsTaskAndPreventsCommit()
            throws Exception {
        ChunkKey key = new ChunkKey(0, 0);
        World world = loadedWorld(key, flatData(key, STONE));
        long originalRevision = world.chunks().revision(key);
        CountDownLatch providerStarted = new CountDownLatch(1);
        CountDownLatch releaseProvider = new CountDownLatch(1);
        CountDownLatch interruptionObserved = new CountDownLatch(1);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        WorldLoader loader =
                loader(
                        (context, generatedKey) -> {
                            providerStarted.countDown();
                            awaitAfterInterruption(
                                    releaseProvider,
                                    interruptionObserved);
                            return succeededGeneration(
                                    flatData(
                                            generatedKey,
                                            (byte) (STONE + 1)));
                        },
                        withRadius(WorldGenerationConfig.defaults(), 0),
                        worker);
        java.util.concurrent.CompletableFuture<WorldRebuildResult> future =
                loader.rebuildRegionAsync(
                        world,
                        Set.of(key),
                        WorldGenerationConfig.defaults());
        try {
            assertTrue(providerStarted.await(5, TimeUnit.SECONDS));

            assertTrue(future.cancel(true));
            assertThrows(CancellationException.class, future::join);
            assertTrue(
                    interruptionObserved.await(2, TimeUnit.SECONDS),
                    "Cancelling the public rebuild future did not interrupt "
                            + "the running provider");
            releaseProvider.countDown();

            awaitGenerationTerminal(world, key);
            assertEquals(originalRevision, world.chunks().revision(key));
            assertEquals(STONE, world.getBlock(0, 0, 0));
            assertEquals(
                    ChunkGenerationStatus.FAILED,
                    world.chunks().generationStatus(key));
            assertInstanceOf(
                    CancellationException.class,
                    world.chunks().generationFailure(key).orElseThrow());
        } finally {
            releaseProvider.countDown();
            worker.shutdownNow();
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void cancellationWinningPreCommitGatePreventsInitialPublication()
            throws Exception {
        ChunkKey key = new ChunkKey(0, 0);
        CountDownLatch beforeCommit = new CountDownLatch(1);
        CountDownLatch releaseCommit = new CountDownLatch(1);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        WorldLoader loader =
                loader(
                        flatGenerator(STONE),
                        withRadius(WorldGenerationConfig.defaults(), 0),
                        worker,
                        new WorldLoader.OperationObserver() {
                            @Override
                            public void beforeCommit(
                                    ChunkKey observedKey,
                                    ChunkGenerationMode mode) {
                                assertEquals(key, observedKey);
                                assertEquals(
                                        ChunkGenerationMode.INITIAL, mode);
                                beforeCommit.countDown();
                                awaitAfterInterruption(
                                        releaseCommit,
                                        new CountDownLatch(0));
                            }
                        });
        World world = new World();
        java.util.concurrent.CompletableFuture<WorldLoadResult> future =
                loader.loadAsync(world);
        try {
            assertTrue(beforeCommit.await(5, TimeUnit.SECONDS));

            assertTrue(future.cancel(true));
            releaseCommit.countDown();

            awaitGenerationTerminal(world, key);
            assertThrows(CancellationException.class, future::join);
            assertEquals(WorldLoadState.CANCELLED, loader.state());
            assertFalse(world.chunks().contains(key));
            assertEquals(
                    ChunkGenerationStatus.FAILED,
                    world.chunks().generationStatus(key));
            assertInstanceOf(
                    CancellationException.class,
                    world.chunks().generationFailure(key).orElseThrow());
        } finally {
            releaseCommit.countDown();
            worker.shutdownNow();
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void cancellationWinningLaterRebuildCommitPreservesPriorKey()
            throws Exception {
        ChunkKey first = new ChunkKey(0, 0);
        ChunkKey second = new ChunkKey(1, 0);
        World world = loadedWorld(first, flatData(first, STONE));
        commitInitial(world, second, flatData(second, STONE));
        CountDownLatch beforeSecondCommit = new CountDownLatch(1);
        CountDownLatch releaseSecondCommit = new CountDownLatch(1);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        WorldLoader loader =
                loader(
                        flatGenerator((byte) (STONE + 1)),
                        withRadius(WorldGenerationConfig.defaults(), 0),
                        worker,
                        new WorldLoader.OperationObserver() {
                            @Override
                            public void beforeCommit(
                                    ChunkKey key,
                                    ChunkGenerationMode mode) {
                                if (key.equals(second)) {
                                    beforeSecondCommit.countDown();
                                    awaitAfterInterruption(
                                            releaseSecondCommit,
                                            new CountDownLatch(0));
                                }
                            }
                        });
        java.util.concurrent.CompletableFuture<WorldRebuildResult> future =
                loader.rebuildRegionAsync(
                        world,
                        new LinkedHashSet<>(List.of(first, second)),
                        WorldGenerationConfig.defaults());
        try {
            assertTrue(beforeSecondCommit.await(5, TimeUnit.SECONDS));
            assertEquals(
                    (byte) (STONE + 1),
                    world.getBlock(0, 0, 0));
            long secondRevisionBeforeCancellation =
                    world.chunks().revision(second);

            assertTrue(future.cancel(true));
            releaseSecondCommit.countDown();

            awaitGenerationTerminal(world, second);
            assertThrows(CancellationException.class, future::join);
            assertEquals(
                    (byte) (STONE + 1),
                    world.getBlock(0, 0, 0));
            assertEquals(
                    STONE,
                    world.getBlock(second.worldOriginX(), 0, 0));
            assertEquals(
                    secondRevisionBeforeCancellation,
                    world.chunks().revision(second));
            assertEquals(
                    ChunkGenerationStatus.FAILED,
                    world.chunks().generationStatus(second));
            assertInstanceOf(
                    CancellationException.class,
                    world.chunks().generationFailure(second).orElseThrow());
        } finally {
            releaseSecondCommit.countDown();
            worker.shutdownNow();
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void cancellationWinningPreSuccessGatePreventsSucceededState()
            throws Exception {
        CountDownLatch beforeSuccess = new CountDownLatch(1);
        CountDownLatch releaseSuccess = new CountDownLatch(1);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        WorldLoader loader =
                loader(
                        flatGenerator(STONE),
                        withRadius(WorldGenerationConfig.defaults(), 0),
                        worker,
                        new WorldLoader.OperationObserver() {
                            @Override
                            public void beforeSuccess() {
                                beforeSuccess.countDown();
                                awaitAfterInterruption(
                                        releaseSuccess,
                                        new CountDownLatch(0));
                            }
                        });
        java.util.concurrent.CompletableFuture<WorldLoadResult> future =
                loader.loadAsync(new World());
        try {
            assertTrue(beforeSuccess.await(5, TimeUnit.SECONDS));

            assertTrue(future.cancel(true));
            releaseSuccess.countDown();

            assertThrows(CancellationException.class, future::join);
            worker.shutdown();
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
            assertEquals(WorldLoadState.CANCELLED, loader.state());
            assertEquals(Optional.empty(), loader.failure());
        } finally {
            releaseSuccess.countDown();
            worker.shutdownNow();
        }
    }

    @Test
    void commitGateWinningRaceRejectsCancelAndLoadCompletes()
            throws Exception {
        CountDownLatch commitGateAcquired = new CountDownLatch(1);
        CountDownLatch releaseCommit = new CountDownLatch(1);
        CountDownLatch cancelVersionSampled = new CountDownLatch(1);
        CountDownLatch beforeSuccess = new CountDownLatch(1);
        CountDownLatch releaseSuccess = new CountDownLatch(1);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        ExecutorService canceller = Executors.newSingleThreadExecutor();
        WorldLoader loader =
                loader(
                        flatGenerator(STONE),
                        withRadius(WorldGenerationConfig.defaults(), 0),
                        worker,
                        new WorldLoader.OperationObserver() {
                            @Override
                            public void commitGateAcquired(
                                    ChunkKey key,
                                    ChunkGenerationMode mode) {
                                commitGateAcquired.countDown();
                                awaitAfterInterruption(
                                        releaseCommit,
                                        new CountDownLatch(0));
                            }

                            @Override
                            public void cancelVersionSampled() {
                                cancelVersionSampled.countDown();
                            }

                            @Override
                            public void beforeSuccess() {
                                beforeSuccess.countDown();
                                awaitAfterInterruption(
                                        releaseSuccess,
                                        new CountDownLatch(0));
                            }
                        });
        World world = new World();
        java.util.concurrent.CompletableFuture<WorldLoadResult> future =
                loader.loadAsync(world);
        try {
            assertTrue(commitGateAcquired.await(5, TimeUnit.SECONDS));
            Future<Boolean> cancellation =
                    canceller.submit(() -> future.cancel(true));
            assertTrue(
                    cancelVersionSampled.await(
                            5, TimeUnit.SECONDS));

            releaseCommit.countDown();
            assertFalse(cancellation.get(5, TimeUnit.SECONDS));
            assertTrue(beforeSuccess.await(5, TimeUnit.SECONDS));
            assertFalse(future.isDone());

            releaseSuccess.countDown();
            assertEquals(Set.of(new ChunkKey(0, 0)),
                    future.join().initialChunks());
            assertEquals(WorldLoadState.SUCCEEDED, loader.state());
            assertTrue(world.chunks().contains(new ChunkKey(0, 0)));
        } finally {
            releaseCommit.countDown();
            releaseSuccess.countDown();
            worker.shutdownNow();
            canceller.shutdownNow();
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(canceller.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void successGateWinningRaceRejectsCancelAndReturnsResult()
            throws Exception {
        CountDownLatch successGateAcquired = new CountDownLatch(1);
        CountDownLatch releaseSuccess = new CountDownLatch(1);
        CountDownLatch cancelVersionSampled = new CountDownLatch(1);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        ExecutorService canceller = Executors.newSingleThreadExecutor();
        WorldLoader loader =
                loader(
                        flatGenerator(STONE),
                        withRadius(WorldGenerationConfig.defaults(), 0),
                        worker,
                        new WorldLoader.OperationObserver() {
                            @Override
                            public void successGateAcquired() {
                                successGateAcquired.countDown();
                                awaitAfterInterruption(
                                        releaseSuccess,
                                        new CountDownLatch(0));
                            }

                            @Override
                            public void cancelVersionSampled() {
                                cancelVersionSampled.countDown();
                            }
                        });
        java.util.concurrent.CompletableFuture<WorldLoadResult> future =
                loader.loadAsync(new World());
        try {
            assertTrue(successGateAcquired.await(5, TimeUnit.SECONDS));
            Future<Boolean> cancellation =
                    canceller.submit(() -> future.cancel(true));
            assertTrue(
                    cancelVersionSampled.await(
                            5, TimeUnit.SECONDS));

            releaseSuccess.countDown();
            assertFalse(cancellation.get(5, TimeUnit.SECONDS));
            assertEquals(1, future.join().initialChunks().size());
            assertEquals(WorldLoadState.SUCCEEDED, loader.state());
        } finally {
            releaseSuccess.countDown();
            worker.shutdownNow();
            canceller.shutdownNow();
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(canceller.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void duplicateAsyncLoadRejectsBeforeEnqueueAndPreservesFirstSuccess()
            throws Exception {
        CountDownLatch executorOccupied = new CountDownLatch(1);
        CountDownLatch releaseExecutor = new CountDownLatch(1);
        AtomicInteger providerCalls = new AtomicInteger();
        ExecutorService worker = Executors.newSingleThreadExecutor();
        occupyExecutor(worker, executorOccupied, releaseExecutor);
        WorldLoader loader =
                loader(
                        (context, key) -> {
                            providerCalls.incrementAndGet();
                            return succeededGeneration(flatData(key, STONE));
                        },
                        withRadius(WorldGenerationConfig.defaults(), 0),
                        worker);
        java.util.concurrent.CompletableFuture<WorldLoadResult> first =
                loader.loadAsync(new World());
        try {
            assertEquals(WorldLoadState.RUNNING, loader.state());
            assertThrows(
                    IllegalStateException.class,
                    () -> loader.loadAsync(new World()));

            releaseExecutor.countDown();
            assertEquals(1, first.join().initialChunks().size());
            assertEquals(WorldLoadState.SUCCEEDED, loader.state());
            assertEquals(1, providerCalls.get());
        } finally {
            first.cancel(true);
            releaseExecutor.countDown();
            worker.shutdownNow();
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void cancellingQueuedAsyncLoadRollsReservationToCancelledWithoutProvider()
            throws Exception {
        CountDownLatch executorOccupied = new CountDownLatch(1);
        CountDownLatch releaseExecutor = new CountDownLatch(1);
        AtomicInteger providerCalls = new AtomicInteger();
        ExecutorService worker = Executors.newSingleThreadExecutor();
        occupyExecutor(worker, executorOccupied, releaseExecutor);
        WorldLoader loader =
                loader(
                        (context, key) -> {
                            providerCalls.incrementAndGet();
                            return succeededGeneration(flatData(key, STONE));
                        },
                        withRadius(WorldGenerationConfig.defaults(), 0),
                        worker);
        java.util.concurrent.CompletableFuture<WorldLoadResult> future =
                loader.loadAsync(new World());
        try {
            assertEquals(WorldLoadState.RUNNING, loader.state());

            assertTrue(future.cancel(true));
            assertEquals(WorldLoadState.CANCELLED, loader.state());
            assertEquals(0, providerCalls.get());
        } finally {
            releaseExecutor.countDown();
            worker.shutdownNow();
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void asyncRebuildSnapshotsAndValidatesKeysBeforeEnqueue()
            throws Exception {
        ChunkKey original = new ChunkKey(0, 0);
        ChunkKey laterMutation = new ChunkKey(1, 0);
        World world = loadedWorld(original, flatData(original, STONE));
        commitInitial(
                world,
                laterMutation,
                flatData(laterMutation, STONE));
        CountDownLatch executorOccupied = new CountDownLatch(1);
        CountDownLatch releaseExecutor = new CountDownLatch(1);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        occupyExecutor(worker, executorOccupied, releaseExecutor);
        WorldLoader loader =
                loader(
                        flatGenerator((byte) (STONE + 1)),
                        withRadius(WorldGenerationConfig.defaults(), 0),
                        worker);
        LinkedHashSet<ChunkKey> requested =
                new LinkedHashSet<>(Set.of(original));
        java.util.concurrent.CompletableFuture<WorldRebuildResult> future =
                loader.rebuildRegionAsync(
                        world,
                        requested,
                        WorldGenerationConfig.defaults());
        requested.clear();
        requested.add(laterMutation);
        try {
            assertThrows(
                    NullPointerException.class,
                    () ->
                            loader.rebuildRegionAsync(
                                    world,
                                    new HashSet<>(
                                            Arrays.asList(
                                                    original, null)),
                                    WorldGenerationConfig.defaults()));

            releaseExecutor.countDown();
            WorldRebuildResult result = future.join();
            assertEquals(
                    List.of(original),
                    List.copyOf(result.outcomes().keySet()));
            assertEquals(
                    (byte) (STONE + 1),
                    world.getBlock(0, 0, 0));
            assertEquals(
                    STONE,
                    world.getBlock(
                            laterMutation.worldOriginX(),
                            0,
                            laterMutation.worldOriginZ()));
        } finally {
            future.cancel(true);
            releaseExecutor.countDown();
            worker.shutdownNow();
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void failedStagePreservesCauseCompletedKeysAndTerminalState() {
        ChunkKey failedKey = new ChunkKey(0, 1);
        ResourceLocation failedStage =
                ResourceLocation.parse("gaia:failed_stage");
        IllegalStateException stageCause =
                new IllegalStateException("stage failed");
        WorldGenerator generator =
                (context, key) ->
                        key.equals(failedKey)
                                ? failedGeneration(failedStage, stageCause)
                                : succeededGeneration(flatData(key, STONE));
        WorldLoader loader =
                loader(
                        generator,
                        withRadius(WorldGenerationConfig.defaults(), 1));
        World world = new World();

        WorldLoadException thrown =
                assertThrows(
                        WorldLoadException.class,
                        () -> loader.load(world));

        WorldLoadFailure failure = thrown.failure();
        assertEquals(WorldLoadState.FAILED, loader.state());
        assertSame(failure, loader.failure().orElseThrow());
        assertEquals(failedKey, failure.failedChunk().orElseThrow());
        assertEquals(failedStage, failure.failedStage().orElseThrow());
        assertEquals(
                ResourceLocation.parse("gaia:generation_failed"),
                failure.code());
        assertSame(stageCause, failure.cause());
        assertSame(stageCause, thrown.getCause());
        assertEquals(
                List.of(
                        new ChunkKey(-1, -1),
                        new ChunkKey(-1, 0),
                        new ChunkKey(-1, 1),
                        new ChunkKey(0, -1),
                        new ChunkKey(0, 0)),
                List.copyOf(failure.completedChunks()));
        assertThrows(
                UnsupportedOperationException.class,
                () -> failure.completedChunks().add(new ChunkKey(9, 9)));
        assertEquals(
                ChunkGenerationStatus.FAILED,
                world.chunks().generationStatus(failedKey));
        assertSame(
                stageCause,
                world.chunks().generationFailure(failedKey).orElseThrow());
    }

    @Test
    void directGeneratorThrowPreservesExactCauseAndFailsTicket() {
        AssertionError cause = new AssertionError("generator exploded");
        WorldLoader loader =
                loader(
                        (context, key) -> {
                            throw cause;
                        },
                        withRadius(WorldGenerationConfig.defaults(), 0));
        World world = new World();

        WorldLoadException thrown =
                assertThrows(
                        WorldLoadException.class,
                        () -> loader.load(world));

        assertSame(cause, thrown.failure().cause());
        assertSame(cause, thrown.getCause());
        assertEquals(WorldLoadState.FAILED, loader.state());
        assertSame(
                cause,
                world.chunks()
                        .generationFailure(new ChunkKey(0, 0))
                        .orElseThrow());
    }

    @Test
    void malformedGeneratedKeyFailsLiveTicketBeforeRepositoryConflict() {
        ChunkKey returnedKey = new ChunkKey(20, 30);
        WorldLoader loader =
                loader(
                        (context, key) ->
                                succeededGeneration(
                                        flatData(returnedKey, STONE)),
                        withRadius(WorldGenerationConfig.defaults(), 0));
        World world = new World();

        WorldLoadException thrown =
                assertThrows(
                        WorldLoadException.class,
                        () -> loader.load(world));

        assertTrue(thrown.failure().cause().getMessage().contains("key"));
        assertEquals(
                ChunkGenerationStatus.FAILED,
                world.chunks().generationStatus(new ChunkKey(0, 0)));
        assertFalse(world.chunks().contains(returnedKey));
    }

    @Test
    void malformedGeneratedHeightFailsLiveTicketBeforeRepositoryConflict() {
        int repositoryHeight = 8;
        World world =
                new World(
                        new ChunkRepository(
                                repositoryHeight,
                                new ChunkDirtyTracker()));
        WorldLoader loader =
                loader(
                        (context, key) ->
                                succeededGeneration(
                                        data(key, repositoryHeight + 1, STONE)),
                        withRadius(WorldGenerationConfig.defaults(), 0));

        WorldLoadException thrown =
                assertThrows(
                        WorldLoadException.class,
                        () -> loader.load(world));

        assertTrue(
                thrown.failure().cause().getMessage()
                        .contains("world height"));
        assertEquals(
                ChunkGenerationStatus.FAILED,
                world.chunks().generationStatus(new ChunkKey(0, 0)));
    }

    @Test
    void initialCommitConflictIsTerminalAndDoesNotLeakRunningAttempt() {
        World world = new World();
        WorldGenerator generator =
                (context, key) -> {
                    assertTrue(world.chunks().beginUnload(key));
                    assertTrue(world.chunks().completeUnload(key));
                    return succeededGeneration(flatData(key, STONE));
                };
        WorldLoader loader =
                loader(
                        generator,
                        withRadius(WorldGenerationConfig.defaults(), 0));

        WorldLoadException thrown =
                assertThrows(
                        WorldLoadException.class,
                        () -> loader.load(world));

        assertEquals(
                ResourceLocation.parse("gaia:generation_commit_failed"),
                thrown.failure().code());
        assertEquals(WorldLoadState.FAILED, loader.state());
        assertEquals(
                ChunkGenerationStatus.IDLE,
                world.chunks().generationStatus(new ChunkKey(0, 0)));
        assertFalse(world.chunks().contains(new ChunkKey(0, 0)));
    }

    @Test
    void noSafeSpawnFailsAfterPreservingCompletedChunks() {
        WorldLoader loader =
                loader(
                        keyGenerator((byte) 0),
                        withRadius(WorldGenerationConfig.defaults(), 0));

        WorldLoadException thrown =
                assertThrows(
                        WorldLoadException.class,
                        () -> loader.load(new World()));

        assertEquals(WorldLoadState.FAILED, loader.state());
        assertEquals(
                ResourceLocation.parse("gaia:no_safe_spawn"),
                thrown.failure().code());
        assertEquals(Optional.empty(), thrown.failure().failedChunk());
        assertEquals(Set.of(new ChunkKey(0, 0)),
                thrown.failure().completedChunks());
    }

    @Test
    void alreadyInterruptedLoadCancelsWithoutStartingGeneration()
            throws InterruptedException {
        AtomicReference<WorldLoader> observedLoader = new AtomicReference<>();
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            Future<WorldLoadResult> future =
                    worker.submit(
                            () -> {
                                WorldLoader loader =
                                        loader(
                                                flatGenerator(STONE),
                                                withRadius(
                                                        WorldGenerationConfig
                                                                .defaults(),
                                                        0));
                                observedLoader.set(loader);
                                Thread.currentThread().interrupt();
                                return loader.load(new World());
                            });

            ExecutionException thrown =
                    assertThrows(ExecutionException.class, future::get);
            assertNotNull(thrown.getCause());
            assertInstanceOf(CancellationException.class, thrown.getCause());
        } finally {
            worker.shutdownNow();
        }

        assertEquals(
                WorldLoadState.CANCELLED,
                observedLoader.get().state());
        assertEquals(Optional.empty(), observedLoader.get().failure());
    }

    @Test
    void cancellationThrownByStageCancelsLoadWithoutPublishingOrContinuing() {
        ChunkKey key = new ChunkKey(0, 0);
        CancellationException cancellation =
                new CancellationException("stage cancelled");
        AtomicInteger laterStageCalls = new AtomicInteger();
        WorldGenerationStage cancellingStage =
                stage(
                        "gaia:cancelling_stage",
                        (context, region) -> {
                            throw cancellation;
                        });
        WorldGenerationStage laterStage =
                stage(
                        "gaia:later_stage",
                        (context, region) -> {
                            laterStageCalls.incrementAndGet();
                            return succeededStage("gaia:later_stage");
                        });
        WorldLoader loader =
                loader(
                        new StagedWorldGenerator(
                                List.of(cancellingStage, laterStage)),
                        withRadius(WorldGenerationConfig.defaults(), 0));
        World world = new World();

        CancellationException thrown =
                assertThrows(
                        CancellationException.class,
                        () -> loader.load(world));

        assertSame(cancellation, thrown);
        assertEquals(WorldLoadState.CANCELLED, loader.state());
        assertEquals(Optional.empty(), loader.failure());
        assertFalse(world.chunks().contains(key));
        assertEquals(
                ChunkGenerationStatus.FAILED,
                world.chunks().generationStatus(key));
        assertSame(
                cancellation,
                world.chunks().generationFailure(key).orElseThrow());
        assertEquals(0, laterStageCalls.get());
    }

    @Test
    void interruptionDuringAggregateHashCancelsBeforeSuccess() {
        WorldLoader loader =
                loader(
                        flatGenerator(STONE),
                        withRadius(WorldGenerationConfig.defaults(), 0));
        InterruptingSha256Provider.reset();
        Provider provider = new InterruptingSha256Provider();
        Security.insertProviderAt(provider, 1);
        try {
            assertThrows(
                    CancellationException.class,
                    () -> loader.load(new World()));

            assertEquals(WorldLoadState.CANCELLED, loader.state());
            assertEquals(Optional.empty(), loader.failure());
        } finally {
            Thread.interrupted();
            Security.removeProvider(provider.getName());
        }
    }

    @Test
    void loadResultDefensivelyCopiesAllMutableInputs() {
        Set<ChunkKey> suppliedChunks =
                new LinkedHashSet<>(
                        List.of(new ChunkKey(0, 0), new ChunkKey(1, 0)));
        Vector3f suppliedSpawn = new Vector3f(0.5f, 31.8f, 0.5f);

        WorldLoadResult result =
                new WorldLoadResult(
                        suppliedChunks,
                        suppliedSpawn,
                        "fingerprint",
                        "generation-hash");
        suppliedChunks.clear();
        suppliedSpawn.zero();

        assertEquals(2, result.initialChunks().size());
        assertThrows(
                UnsupportedOperationException.class,
                () -> result.initialChunks().add(new ChunkKey(2, 0)));
        Vector3f returnedSpawn = result.playerFeetPosition();
        returnedSpawn.set(9.0f, 9.0f, 9.0f);
        assertEquals(
                new Vector3f(0.5f, 31.8f, 0.5f),
                result.playerFeetPosition());
        assertEquals("fingerprint", result.configFingerprint());
        assertEquals("generation-hash", result.generationHash());
    }

    @Test
    void identicalLoadsProduceIdenticalFingerprintAndAggregateHash() {
        WorldGenerationConfig config =
                withRadius(WorldGenerationConfig.defaults(), 1);

        WorldLoadResult first =
                loader(flatGenerator(STONE), config).load(new World());
        WorldLoadResult second =
                loader(flatGenerator(STONE), config).load(new World());

        assertEquals(first.configFingerprint(), second.configFingerprint());
        assertEquals(first.generationHash(), second.generationHash());
        assertEquals(64, first.configFingerprint().length());
        assertEquals(64, first.generationHash().length());
    }

    @Test
    void rebuildCommitsWithRevisionGuardAndMarksChunkDirty() {
        ChunkKey key = new ChunkKey(0, 0);
        World world = loadedWorld(key, flatData(key, STONE));
        long before = world.chunks().revision(key);
        byte replacement = (byte) (STONE + 1);
        WorldLoader loader =
                loader(
                        flatGenerator(replacement),
                        withRadius(WorldGenerationConfig.defaults(), 0));

        WorldRebuildResult result =
                loader.rebuildRegion(
                        world,
                        Set.of(key),
                        WorldGenerationConfig.defaults());

        assertEquals(Set.of(key), result.committedChunks());
        assertEquals(
                ChunkGenerationResult.Status.COMMITTED,
                result.outcomes().get(key).status());
        assertTrue(world.chunks().revision(key) > before);
        assertEquals(ChunkState.DIRTY, world.chunks().state(key));
        assertEquals(replacement, world.getBlock(0, 0, 0));
    }

    @Test
    void staleRebuildReturnsConflictWithoutOverwritingLaterMutation() {
        ChunkKey key = new ChunkKey(0, 0);
        World world = loadedWorld(key, flatData(key, STONE));
        byte laterMutation = (byte) (STONE + 2);
        WorldLoader loader =
                loader(
                        (context, generatedKey) -> {
                            assertTrue(
                                    world.setBlock(
                                            generatedKey.worldOriginX(),
                                            0,
                                            generatedKey.worldOriginZ(),
                                            laterMutation));
                            return succeededGeneration(
                                    flatData(generatedKey, (byte) (STONE + 1)));
                        },
                        withRadius(WorldGenerationConfig.defaults(), 0));

        WorldRebuildResult result =
                loader.rebuildRegion(
                        world,
                        Set.of(key),
                        WorldGenerationConfig.defaults());

        assertEquals(Set.of(), result.committedChunks());
        assertEquals(
                ChunkGenerationResult.Status.CONFLICT,
                result.outcomes().get(key).status());
        assertEquals(laterMutation, world.getBlock(0, 0, 0));
        assertNotEquals(
                ChunkGenerationStatus.GENERATING,
                world.chunks().generationStatus(key));
    }

    @Test
    void rebuildContinuesAfterStageFailureAndReportsMixedOutcomes() {
        ChunkKey good = new ChunkKey(0, 0);
        ChunkKey failed = new ChunkKey(1, 0);
        World world = loadedWorld(good, flatData(good, STONE));
        commitInitial(world, failed, flatData(failed, STONE));
        IllegalStateException cause =
                new IllegalStateException("rebuild stage failed");
        ResourceLocation stage =
                ResourceLocation.parse("gaia:rebuild_failed");
        WorldLoader loader =
                loader(
                        (context, key) ->
                                key.equals(failed)
                                        ? failedGeneration(stage, cause)
                                        : succeededGeneration(
                                                flatData(
                                                        key,
                                                        (byte) (STONE + 1))),
                        withRadius(WorldGenerationConfig.defaults(), 0));

        WorldRebuildResult result =
                loader.rebuildRegion(
                        world,
                        new LinkedHashSet<>(List.of(failed, good)),
                        WorldGenerationConfig.defaults());

        assertEquals(List.of(good), List.copyOf(result.committedChunks()));
        assertEquals(List.of(good, failed),
                List.copyOf(result.outcomes().keySet()));
        assertEquals(
                ChunkGenerationResult.Status.COMMITTED,
                result.outcomes().get(good).status());
        assertEquals(
                ChunkGenerationResult.Status.FAILED,
                result.outcomes().get(failed).status());
        assertSame(
                cause,
                result.outcomes().get(failed).failure().orElseThrow());
        assertSame(
                cause,
                world.chunks().generationFailure(failed).orElseThrow());
    }

    @Test
    void malformedRebuildDataFailsTicketRatherThanBecomingConflict() {
        ChunkKey key = new ChunkKey(0, 0);
        World world = loadedWorld(key, flatData(key, STONE));
        WorldLoader loader =
                loader(
                        (context, requested) ->
                                succeededGeneration(
                                        flatData(new ChunkKey(9, 9), STONE)),
                        withRadius(WorldGenerationConfig.defaults(), 0));

        WorldRebuildResult result =
                loader.rebuildRegion(
                        world,
                        Set.of(key),
                        WorldGenerationConfig.defaults());

        assertEquals(
                ChunkGenerationResult.Status.FAILED,
                result.outcomes().get(key).status());
        assertEquals(
                ChunkGenerationStatus.FAILED,
                world.chunks().generationStatus(key));
    }

    @Test
    void detachedGenerationProducesCanonicalDataWithoutChangingLoaderLifecycle() {
        ChunkKey key = new ChunkKey(-7, 11);
        WorldLoader loader =
                loader(
                        flatGenerator(STONE),
                        withRadius(WorldGenerationConfig.defaults(), 0));

        ChunkGenerationData generated = loader.generateDetached(key);

        assertEquals(key, generated.key());
        assertEquals(STONE, generated.getBlock(0, 0, 0));
        assertEquals(WorldLoadState.IDLE, loader.state());
        assertTrue(loader.failure().isEmpty());
    }

    @Test
    void loaderSourceHasNoGameplayGpuOrDirectWorldMutationPaths()
            throws IOException {
        String source =
                Files.readString(
                        Path.of(
                                "src/main/java/com/gaia/world/WorldLoader.java"));

        assertTrue(source.contains("ChunkGenerationMode.INITIAL"));
        assertTrue(source.contains("ChunkGenerationMode.REBUILD"));
        assertTrue(source.contains("beginGeneration("));
        assertTrue(source.contains("commitGeneration("));
        assertTrue(source.contains("failGeneration("));
        assertFalse(source.contains("world.generate("));
        assertFalse(source.contains("world.setBlock("));
        assertFalse(source.contains("WorldMutationService"));
        assertFalse(source.contains("ChunkMeshManager"));
        assertFalse(source.contains("Renderer"));
        assertFalse(source.contains("replaceMesh"));
    }

    private static WorldLoader loader(
            WorldGenerator generator, WorldGenerationConfig config) {
        return loader(generator, config, DIRECT_EXECUTOR);
    }

    private static WorldLoader loader(
            WorldGenerator generator,
            WorldGenerationConfig config,
            ExecutorService executor) {
        return loader(
                generator,
                config,
                executor,
                WorldLoader.OperationObserver.NONE);
    }

    private static WorldLoader loader(
            WorldGenerator generator,
            WorldGenerationConfig config,
            ExecutorService executor,
            WorldLoader.OperationObserver observer) {
        return new WorldLoader(
                generator,
                BLOCKS,
                config,
                new SafeSpawnSelector(),
                executor,
                observer);
    }

    private static WorldGenerator flatGenerator(byte support) {
        return keyGenerator(support);
    }

    private static WorldGenerator keyGenerator(byte support) {
        return (context, key) ->
                succeededGeneration(flatData(key, support));
    }

    private static WorldGenerationResult succeededGeneration(
            ChunkGenerationData data) {
        return new WorldGenerationResult(Optional.of(data), List.of());
    }

    private static WorldGenerationResult failedGeneration(
            ResourceLocation stage, Throwable failure) {
        return new WorldGenerationResult(
                Optional.empty(),
                List.of(
                        new GenerationStageResult(
                                stage,
                                GenerationStageResult.Status.FAILED,
                                0,
                                0,
                                Optional.of(failure))));
    }

    private static WorldGenerationStage stage(
            String id, StageBody body) {
        ResourceLocation stageId = ResourceLocation.parse(id);
        return new WorldGenerationStage() {
            @Override
            public ResourceLocation id() {
                return stageId;
            }

            @Override
            public GenerationStageResult generate(
                    GenerationContext context,
                    GenerationRegion region) {
                return body.generate(context, region);
            }
        };
    }

    private static GenerationStageResult succeededStage(String id) {
        return new GenerationStageResult(
                ResourceLocation.parse(id),
                GenerationStageResult.Status.SUCCEEDED,
                0,
                0,
                Optional.empty());
    }

    private static ChunkGenerationData flatData(
            ChunkKey key, byte support) {
        return data(key, GameConfig.Chunk.MAX_HEIGHT, support);
    }

    private static ChunkGenerationData data(
            ChunkKey key, int worldHeight, byte support) {
        byte[] blocks =
                new byte[
                        GameConfig.Chunk.SIZE
                                * worldHeight
                                * GameConfig.Chunk.SIZE];
        if (support != 0) {
            for (int z = 0; z < GameConfig.Chunk.SIZE; z++) {
                for (int x = 0; x < GameConfig.Chunk.SIZE; x++) {
                    blocks[
                                    x
                                            + z
                                                    * GameConfig.Chunk.SIZE
                                                    * worldHeight] =
                            support;
                }
            }
        }
        return new ChunkGenerationData(key, worldHeight, blocks);
    }

    private static World loadedWorld(
            ChunkKey key, ChunkGenerationData data) {
        World world = new World();
        commitInitial(world, key, data);
        return world;
    }

    private static void commitInitial(
            World world, ChunkKey key, ChunkGenerationData data) {
        ChunkGenerationTicket ticket =
                world.chunks()
                        .beginGeneration(key, ChunkGenerationMode.INITIAL);
        assertEquals(
                ChunkGenerationResult.Status.COMMITTED,
                world.chunks().commitGeneration(ticket, data).status());
    }

    private static WorldGenerationConfig withRadius(
            WorldGenerationConfig config, int radius) {
        return new WorldGenerationConfig(
                config.seed(),
                config.algorithmVersion(),
                radius,
                config.biome(),
                config.height(),
                config.cave(),
                config.surface(),
                config.decoration(),
                config.spawn());
    }

    private static void awaitAfterInterruption(
            CountDownLatch release,
            CountDownLatch interruptionObserved) {
        boolean interrupted = false;
        while (true) {
            try {
                release.await();
                break;
            } catch (InterruptedException cancellation) {
                interrupted = true;
                interruptionObserved.countDown();
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static void occupyExecutor(
            ExecutorService executor,
            CountDownLatch occupied,
            CountDownLatch release) throws InterruptedException {
        executor.submit(
                () -> {
                    occupied.countDown();
                    try {
                        release.await();
                    } catch (InterruptedException cancellation) {
                        Thread.currentThread().interrupt();
                    }
                });
        assertTrue(occupied.await(5, TimeUnit.SECONDS));
    }

    private static void awaitGenerationTerminal(
            World world, ChunkKey key) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (world.chunks().generationStatus(key)
                == ChunkGenerationStatus.GENERATING) {
            if (System.nanoTime() >= deadline) {
                throw new AssertionError(
                        "Generation ticket did not become terminal");
            }
            Thread.sleep(1);
        }
    }

    @FunctionalInterface
    private interface StageBody {
        GenerationStageResult generate(
                GenerationContext context, GenerationRegion region);
    }

    private static GaiaAssetCatalog productionCatalog() {
        return new GaiaResourceLoader(
                        new AssetManager(
                                WorldLoaderTest.class.getClassLoader()))
                .load();
    }

    public static final class InterruptingSha256Provider
            extends Provider {
        private static final String NAME =
                "GaiaTask7InterruptingSha256";

        InterruptingSha256Provider() {
            super(NAME, "1.0", "Task 7 cancellation test provider");
            put(
                    "MessageDigest.SHA-256",
                    InterruptingSha256Spi.class.getName());
        }

        static void reset() {
            InterruptingSha256Spi.reset();
        }
    }

    public static final class InterruptingSha256Spi
            extends MessageDigestSpi {
        private static int completedDigests;

        static void reset() {
            completedDigests = 0;
        }

        @Override
        protected void engineUpdate(byte input) {}

        @Override
        protected void engineUpdate(
                byte[] input, int offset, int length) {}

        @Override
        protected byte[] engineDigest() {
            completedDigests++;
            if (completedDigests == 2) {
                Thread.currentThread().interrupt();
            }
            return new byte[32];
        }

        @Override
        protected void engineReset() {}
    }
}

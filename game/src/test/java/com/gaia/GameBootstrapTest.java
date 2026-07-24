package com.gaia;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.overlord.assets.AssetDiagnostic;
import com.overlord.assets.AssetLoadReport;
import com.overlord.assets.AssetSeverity;
import com.overlord.assets.ResourceLocation;
import com.overlord.core.lifecycle.ShutdownCoordinator;
import com.overlord.voxel.ChunkMeshManager;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class GameBootstrapTest {
    @Test
    void interruptedMeshShutdownWaitsUntilTerminatedAndRestoresInterrupt() {
        List<String> cleanupOrder = new ArrayList<>();
        ScriptedExecutor meshExecutor =
                ScriptedExecutor.terminatesAfterAwaits(
                        2, 1, cleanupOrder, "mesh");
        GameBootstrap.ShutdownBarrier barrier =
                new GameBootstrap.ShutdownBarrier(
                        1, TimeUnit.SECONDS);
        ShutdownCoordinator coordinator = new ShutdownCoordinator();
        coordinator.register(
                "engine",
                () ->
                        barrier.closeEngine(
                                () -> cleanupOrder.add("engine")));
        barrier.registerChunkMeshes(
                coordinator,
                meshExecutor,
                Object::new,
                manager -> cleanupOrder.add("manager"));

        Thread.currentThread().interrupt();
        try {
            coordinator.close();

            assertTrue(Thread.currentThread().isInterrupted());
            assertEquals(2, meshExecutor.awaitCalls());
            assertTrue(meshExecutor.shutdownNowCalls() >= 3);
            assertEquals(
                    List.of("mesh", "manager", "engine"),
                    cleanupOrder);
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void timeoutSkipsManagerAndEngineCleanupAndSurfacesAllFailures() {
        ScriptedExecutor meshExecutor =
                ScriptedExecutor.neverTerminates();
        GameBootstrap.ShutdownBarrier barrier =
                new GameBootstrap.ShutdownBarrier(
                        0, TimeUnit.NANOSECONDS);
        ShutdownCoordinator coordinator = new ShutdownCoordinator();
        AtomicInteger managerCleanup = new AtomicInteger();
        AtomicInteger engineCleanup = new AtomicInteger();
        coordinator.register(
                "engine",
                () ->
                        barrier.closeEngine(
                                engineCleanup::incrementAndGet));
        barrier.registerChunkMeshes(
                coordinator,
                meshExecutor,
                Object::new,
                manager -> managerCleanup.incrementAndGet());

        IllegalStateException failure =
                assertThrows(
                        IllegalStateException.class,
                        coordinator::close);

        assertTrue(
                failure.getMessage()
                        .contains("Chunk mesh executor"));
        assertEquals(2, failure.getSuppressed().length);
        assertEquals(0, managerCleanup.get());
        assertEquals(0, engineCleanup.get());
        assertFalse(meshExecutor.isTerminated());
    }

    @Test
    void worldTimeoutStillStopsMeshButSkipsManagerAndEngineCleanup() {
        List<String> cleanupOrder = new ArrayList<>();
        ScriptedExecutor worldExecutor =
                ScriptedExecutor.neverTerminates();
        ScriptedExecutor meshExecutor =
                ScriptedExecutor.terminatesAfterAwaits(
                        1, 0, cleanupOrder, "mesh");
        GameBootstrap.ShutdownBarrier barrier =
                new GameBootstrap.ShutdownBarrier(
                        1, TimeUnit.MILLISECONDS);
        ShutdownCoordinator coordinator = new ShutdownCoordinator();
        AtomicInteger managerCleanup = new AtomicInteger();
        AtomicInteger engineCleanup = new AtomicInteger();
        coordinator.register(
                "engine",
                () ->
                        barrier.closeEngine(
                                engineCleanup::incrementAndGet));
        barrier.registerChunkMeshes(
                coordinator,
                meshExecutor,
                Object::new,
                manager -> managerCleanup.incrementAndGet());
        barrier.registerWorldExecutor(
                coordinator, worldExecutor);
        coordinator.register(
                "world-load",
                () -> cleanupOrder.add("cancel"));

        IllegalStateException failure =
                assertThrows(
                        IllegalStateException.class,
                        coordinator::close);

        assertTrue(
                failure.getMessage()
                        .contains("World loader executor"));
        assertEquals(2, failure.getSuppressed().length);
        assertEquals(List.of("cancel", "mesh"), cleanupOrder);
        assertEquals(0, managerCleanup.get());
        assertEquals(0, engineCleanup.get());
        assertTrue(meshExecutor.isTerminated());
        assertFalse(worldExecutor.isTerminated());
    }

    @Test
    void normalShutdownStopsWorldThenMeshThenManagerAndEngineExactlyOnce() {
        List<String> cleanupOrder = new ArrayList<>();
        ScriptedExecutor meshExecutor =
                ScriptedExecutor.terminatesAfterAwaits(
                        1, 0, cleanupOrder, "mesh");
        ScriptedExecutor worldExecutor =
                ScriptedExecutor.terminatesAfterAwaits(
                        1, 0, cleanupOrder, "world");
        GameBootstrap.ShutdownBarrier barrier =
                new GameBootstrap.ShutdownBarrier(
                        1, TimeUnit.SECONDS);
        ShutdownCoordinator coordinator = new ShutdownCoordinator();
        coordinator.register(
                "engine",
                () ->
                        barrier.closeEngine(
                                () -> cleanupOrder.add("engine")));
        barrier.registerChunkMeshes(
                coordinator,
                meshExecutor,
                Object::new,
                manager -> cleanupOrder.add("manager"));
        barrier.registerWorldExecutor(
                coordinator, worldExecutor);
        coordinator.register(
                "world-load",
                () -> cleanupOrder.add("cancel"));

        coordinator.close();

        assertEquals(
                List.of(
                        "cancel",
                        "world",
                        "mesh",
                        "manager",
                        "engine"),
                cleanupOrder);
        assertEquals(1, Collections.frequency(cleanupOrder, "world"));
        assertEquals(1, Collections.frequency(cleanupOrder, "mesh"));
        assertEquals(1, Collections.frequency(cleanupOrder, "manager"));
        assertEquals(1, Collections.frequency(cleanupOrder, "engine"));
    }

    @Test
    void engineCleanupMayRunAfterManagerCleanupWasAttemptedAndFailed() {
        List<String> cleanupOrder = new ArrayList<>();
        ScriptedExecutor meshExecutor =
                ScriptedExecutor.terminatesAfterAwaits(
                        1, 0, cleanupOrder, "mesh");
        GameBootstrap.ShutdownBarrier barrier =
                new GameBootstrap.ShutdownBarrier(
                        1, TimeUnit.SECONDS);
        ShutdownCoordinator coordinator = new ShutdownCoordinator();
        RuntimeException managerFailure =
                new RuntimeException("manager cleanup failed");
        coordinator.register(
                "engine",
                () ->
                        barrier.closeEngine(
                                () -> cleanupOrder.add("engine")));
        barrier.registerChunkMeshes(
                coordinator,
                meshExecutor,
                Object::new,
                manager -> {
                    cleanupOrder.add("manager");
                    throw managerFailure;
                });

        RuntimeException thrown =
                assertThrows(
                        RuntimeException.class,
                        coordinator::close);

        assertSame(managerFailure, thrown);
        assertEquals(
                List.of("mesh", "manager", "engine"),
                cleanupOrder);
    }

    @Test
    void managerConstructionFailureStopsUnregisteredMeshExecutor() {
        ScriptedExecutor meshExecutor =
                ScriptedExecutor.terminatesAfterAwaits(
                        1, 0, new ArrayList<>(), null);
        GameBootstrap.ShutdownBarrier barrier =
                new GameBootstrap.ShutdownBarrier(
                        1, TimeUnit.SECONDS);
        RuntimeException constructionFailure =
                new RuntimeException("manager construction failed");

        RuntimeException thrown =
                assertThrows(
                        RuntimeException.class,
                        () ->
                                barrier.registerChunkMeshes(
                                        new ShutdownCoordinator(),
                                        meshExecutor,
                                        () -> {
                                            throw constructionFailure;
                                        },
                                        manager -> {}));

        assertSame(constructionFailure, thrown);
        assertTrue(meshExecutor.isTerminated());
    }

    @Test
    void worldRegistrationFailureStopsUnregisteredWorldExecutor() {
        ScriptedExecutor worldExecutor =
                ScriptedExecutor.terminatesAfterAwaits(
                        1, 0, new ArrayList<>(), null);
        GameBootstrap.ShutdownBarrier barrier =
                new GameBootstrap.ShutdownBarrier(
                        1, TimeUnit.SECONDS);
        ShutdownCoordinator closedCoordinator =
                new ShutdownCoordinator();
        closedCoordinator.close();

        assertThrows(
                IllegalStateException.class,
                () ->
                        barrier.registerWorldExecutor(
                                closedCoordinator,
                                worldExecutor));

        assertTrue(worldExecutor.isTerminated());
    }

    @Test
    void managerRegistrationFailureStopsUnregisteredMeshExecutor() {
        ScriptedExecutor meshExecutor =
                ScriptedExecutor.terminatesAfterAwaits(
                        1, 0, new ArrayList<>(), null);
        GameBootstrap.ShutdownBarrier barrier =
                new GameBootstrap.ShutdownBarrier(
                        1, TimeUnit.SECONDS);
        ShutdownCoordinator closedCoordinator =
                new ShutdownCoordinator();
        closedCoordinator.close();

        assertThrows(
                IllegalStateException.class,
                () ->
                        barrier.registerChunkMeshes(
                                closedCoordinator,
                                meshExecutor,
                                Object::new,
                                manager -> {}));

        assertTrue(meshExecutor.isTerminated());
    }

    @Test
    void gameContextCarriesTheIndependentChunkMeshManager() {
        RecordComponent chunkMeshes =
                Arrays.stream(GameContext.class.getRecordComponents())
                        .filter(
                                component ->
                                        component.getName()
                                                .equals("chunkMeshes"))
                        .findFirst()
                        .orElseThrow();

        assertEquals(ChunkMeshManager.class, chunkMeshes.getType());
    }

    @Test
    void suppressesCleanupFailureOnPrimaryFailure() {
        RuntimeException primary = new RuntimeException("startup failed");
        RuntimeException cleanup = new RuntimeException("cleanup failed");
        ShutdownCoordinator coordinator = new ShutdownCoordinator();
        coordinator.register(
                "failing cleanup",
                () -> {
                    throw cleanup;
                });

        GameBootstrap.closeAfterRun(coordinator, primary);

        assertSame(cleanup, primary.getSuppressed()[0]);
    }

    @Test
    void throwsCleanupFailureWhenThereIsNoPrimaryFailure() {
        RuntimeException cleanup = new RuntimeException("cleanup failed");
        ShutdownCoordinator coordinator = new ShutdownCoordinator();
        coordinator.register(
                "failing cleanup",
                () -> {
                    throw cleanup;
                });

        RuntimeException thrown =
                assertThrows(
                        RuntimeException.class,
                        () -> GameBootstrap.closeAfterRun(coordinator, null));

        assertSame(cleanup, thrown);
    }

    @Test
    void logsEveryStructuredAssetDiagnostic() {
        AssetLoadReport.Builder report = AssetLoadReport.builder();
        report.add(
                new AssetDiagnostic(
                        AssetSeverity.WARNING,
                        "ASSET_MISSING_REGION",
                        "assets/gaia/blocks/grass.json",
                        ResourceLocation.parse("gaia:not_found"),
                        "textures.top",
                        "Block face references missing region",
                        ResourceLocation.parse("gaia:missing")));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        PrintStream original = System.out;
        try {
            System.setOut(
                    new PrintStream(
                            output, true, StandardCharsets.UTF_8));
            GameBootstrap.logAssetReport(report.build());
        } finally {
            System.setOut(original);
        }

        String logged = output.toString(StandardCharsets.UTF_8);
        assertTrue(logged.contains("WARNING"));
        assertTrue(logged.contains("ASSET_MISSING_REGION"));
        assertTrue(
                logged.contains("assets/gaia/blocks/grass.json"));
        assertTrue(logged.contains("gaia:not_found"));
        assertTrue(logged.contains("textures.top"));
        assertTrue(
                logged.contains(
                        "Block face references missing region"));
        assertTrue(logged.contains("gaia:missing"));
    }

    private static final class ScriptedExecutor
            extends AbstractExecutorService {
        private final int terminateAfterAwaitCalls;
        private final List<String> events;
        private final String terminationEvent;
        private int interruptionsRemaining;
        private int shutdownNowCalls;
        private int awaitCalls;
        private boolean shutdown;
        private boolean terminated;

        private ScriptedExecutor(
                int terminateAfterAwaitCalls,
                int interruptionsRemaining,
                List<String> events,
                String terminationEvent) {
            this.terminateAfterAwaitCalls = terminateAfterAwaitCalls;
            this.interruptionsRemaining = interruptionsRemaining;
            this.events = events;
            this.terminationEvent = terminationEvent;
        }

        static ScriptedExecutor terminatesAfterAwaits(
                int awaitCalls,
                int interruptions,
                List<String> events,
                String terminationEvent) {
            return new ScriptedExecutor(
                    awaitCalls,
                    interruptions,
                    events,
                    terminationEvent);
        }

        static ScriptedExecutor neverTerminates() {
            return new ScriptedExecutor(
                    Integer.MAX_VALUE,
                    0,
                    new ArrayList<>(),
                    null);
        }

        int shutdownNowCalls() {
            return shutdownNowCalls;
        }

        int awaitCalls() {
            return awaitCalls;
        }

        @Override
        public void shutdown() {
            shutdown = true;
        }

        @Override
        public List<Runnable> shutdownNow() {
            shutdown = true;
            shutdownNowCalls++;
            return List.of();
        }

        @Override
        public boolean isShutdown() {
            return shutdown;
        }

        @Override
        public boolean isTerminated() {
            return terminated;
        }

        @Override
        public boolean awaitTermination(
                long timeout, TimeUnit unit)
                throws InterruptedException {
            awaitCalls++;
            if (interruptionsRemaining > 0) {
                interruptionsRemaining--;
                throw new InterruptedException(
                        "scripted interruption");
            }
            if (awaitCalls >= terminateAfterAwaitCalls) {
                terminated = true;
                if (terminationEvent != null) {
                    events.add(terminationEvent);
                }
            }
            return terminated;
        }

        @Override
        public void execute(Runnable command) {
            throw new UnsupportedOperationException(
                    "Scripted executor does not execute tasks");
        }
    }
}

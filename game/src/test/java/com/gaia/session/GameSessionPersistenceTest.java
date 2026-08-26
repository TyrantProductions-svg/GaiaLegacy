package com.gaia.session;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gaia.interaction.GameMode;
import com.gaia.save.format.SaveFormatVersion;
import com.gaia.save.format.SaveGameId;
import com.gaia.save.snapshot.InventorySaveSnapshot;
import com.gaia.save.snapshot.PlayerSaveSnapshot;
import com.gaia.save.snapshot.SaveGameSnapshot;
import com.gaia.save.snapshot.WorldItemsSaveSnapshot;
import com.overlord.core.input.MouseDelta;
import com.overlord.interaction.api.EntityRef;
import com.overlord.inventory.api.BodySlot;
import com.overlord.voxel.ChunkKey;
import com.overlord.voxel.ChunkRepositorySnapshot;
import com.overlord.voxel.ChunkSnapshot;
import java.time.Instant;
import java.lang.ref.WeakReference;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class GameSessionPersistenceTest {
    private static final GameSessionConfig CONFIG =
            new GameSessionConfig(12345L, 2, GameMode.SURVIVAL, false);
    private static final SaveGameSnapshot SNAPSHOT = snapshot();

    @Test
    void pausedProductionSessionAndProcessDowntimeDoNotAdvanceRestoredWorldTick() {
        var first = GameSessionPersistenceTestFixture.restoreActualProductionSession(
                SNAPSHOT);
        first.driveToReady();
        long restored = first.captureSave().snapshot().orElseThrow().fixedTick();
        first.capturePaused();
        first.capturePaused();
        long afterPausedFrames = first.captureSave().snapshot().orElseThrow().fixedTick();
        first.close();

        var relaunched = GameSessionPersistenceTestFixture.restoreActualProductionSession(
                SNAPSHOT);
        relaunched.driveToReady();
        long afterProcessDowntime =
                relaunched.captureSave().snapshot().orElseThrow().fixedTick();

        assertEquals(SNAPSHOT.fixedTick(), restored);
        assertEquals(restored, afterPausedFrames);
        assertEquals(restored, afterProcessDowntime);
        relaunched.close();
    }

    @Test
    void captureIssuesOpaqueSessionBoundTokensAndCheckpointRejectsStaleFutureAndForeign() {
        var fixture =
                GameSessionPersistenceTestFixture.sessionHarness(
                        CONFIG,
                        List.of(
                                SessionSaveCaptureResult.pendingTransaction(),
                                GameSessionPersistenceTestFixture.runtimeCaptured(
                                        SNAPSHOT, 4L),
                                GameSessionPersistenceTestFixture.runtimeCaptured(
                                        SNAPSHOT, 7L)));
        var secondFixture =
                GameSessionPersistenceTestFixture.sessionHarness(
                        CONFIG,
                        List.of(
                                GameSessionPersistenceTestFixture.runtimeCaptured(
                                        SNAPSHOT, 4L)));
        GameSession session = fixture.session();
        secondFixture.makeReady();
        SessionPersistenceRevision secondSessionFour =
                secondFixture
                        .session()
                        .captureSave()
                        .persistenceRevision()
                        .orElseThrow();

        assertThrows(IllegalStateException.class, session::captureSave);
        assertThrows(
                IllegalStateException.class,
                () -> session.markSaved(secondSessionFour));
        assertEquals(0, fixture.runtime().captureCalls());

        fixture.makeReady();
        assertEquals(
                SessionSaveCaptureResult.Status.PENDING_TRANSACTION,
                session.captureSave().status());
        assertThrows(
                IllegalArgumentException.class,
                () -> session.markSaved(secondSessionFour));

        SessionSaveCaptureResult first = session.captureSave();
        SessionPersistenceRevision firstToken =
                first.persistenceRevision().orElseThrow();
        assertEquals(4L, first.capturedRevision().orElseThrow());
        assertEquals(4L, firstToken.value());
        assertNotEquals(firstToken, secondSessionFour);
        assertSame(SNAPSHOT, first.snapshot().orElseThrow());

        SaveGameSnapshot otherSnapshot = detachedCopy(SNAPSHOT);
        assertEquals(SNAPSHOT, otherSnapshot);
        assertNotSame(SNAPSHOT, otherSnapshot);
        assertTrue(
                GameSessionPersistenceTestFixture
                        .tryPublicCaptureRebind(
                                otherSnapshot, firstToken)
                        .isEmpty(),
                "a genuine capture token must not be publicly rebound to another snapshot identity");

        assertThrows(
                IllegalArgumentException.class,
                () -> secondFixture.session().markSaved(firstToken));

        session.markSaved(firstToken);
        session.markSaved(firstToken);
        assertEquals(
                List.of(4L),
                fixture.runtime().marked().stream()
                        .map(SessionPersistenceRevision::value)
                        .toList());

        SessionSaveCaptureResult second = session.captureSave();
        SessionPersistenceRevision secondToken =
                second.persistenceRevision().orElseThrow();
        assertEquals(7L, second.capturedRevision().orElseThrow());
        assertEquals(7L, secondToken.value());
        assertThrows(
                IllegalArgumentException.class,
                () -> secondFixture.session().markSaved(secondToken));
        session.markSaved(firstToken);
        assertEquals(
                List.of(4L),
                fixture.runtime().marked().stream()
                        .map(SessionPersistenceRevision::value)
                        .toList());

        session.markSaved(secondToken);
        session.markSaved(secondToken);
        assertEquals(
                List.of(4L, 7L),
                fixture.runtime().marked().stream()
                        .map(SessionPersistenceRevision::value)
                        .toList());
        assertThrows(
                IllegalArgumentException.class,
                () -> session.markSaved(firstToken));
        assertThrows(
                IllegalArgumentException.class,
                () -> session.markSaved(secondSessionFour));
        session.close();
        secondFixture.session().close();
    }

    @Test
    void decreasingRuntimeCaptureIsClosedAsInconsistentRevision() {
        var fixture =
                GameSessionPersistenceTestFixture.sessionHarness(
                        CONFIG,
                        List.of(
                                GameSessionPersistenceTestFixture.runtimeCaptured(
                                        SNAPSHOT, 9L),
                                GameSessionPersistenceTestFixture.runtimeCaptured(
                                        SNAPSHOT, 8L)));
        fixture.makeReady();

        SessionSaveCaptureResult first = fixture.session().captureSave();
        SessionSaveCaptureResult second = fixture.session().captureSave();

        assertEquals(9L, first.capturedRevision().orElseThrow());
        assertAll(
                () ->
                        assertEquals(
                                SessionSaveCaptureResult.Status.INCONSISTENT_REVISION,
                                second.status()),
                () -> assertTrue(second.snapshot().isEmpty()),
                () -> assertTrue(second.capturedRevision().isEmpty()),
                () -> assertTrue(second.persistenceRevision().isEmpty()));
        fixture.session().close();
    }

    @Test
    void persistenceAndCloseRejectWorkerThreadBeforeTouchingRuntime()
            throws InterruptedException {
        var fixture =
                GameSessionPersistenceTestFixture.sessionHarness(
                        CONFIG,
                        List.of(
                                GameSessionPersistenceTestFixture.runtimeCaptured(
                                        SNAPSHOT, 3L)));
        fixture.makeReady();

        AtomicReference<Throwable> captureFailure = new AtomicReference<>();
        Thread captureWorker =
                new Thread(
                        () -> {
                            try {
                                fixture.session().captureSave();
                            } catch (Throwable failure) {
                                captureFailure.set(failure);
                            }
                        },
                        "session-capture-worker");
        captureWorker.start();
        captureWorker.join();

        AtomicReference<Throwable> closeFailure = new AtomicReference<>();
        Thread closeWorker =
                new Thread(
                        () -> {
                            try {
                                fixture.session().close();
                            } catch (Throwable failure) {
                                closeFailure.set(failure);
                            }
                        },
                        "session-close-worker");
        closeWorker.start();
        closeWorker.join();

        assertAll(
                () -> assertTrue(captureFailure.get() instanceof IllegalStateException),
                () -> assertTrue(closeFailure.get() instanceof IllegalStateException),
                () -> assertEquals(0, fixture.runtime().captureCalls()),
                () -> assertEquals(GameSessionState.READY, fixture.session().state()));
        fixture.session().close();
    }

    @Test
    void publicCaptureResultSurfaceCannotIssueTokenlessOrRebindCapturedPayloads() {
        boolean hasTokenlessCapturedFactory =
                Arrays.stream(SessionSaveCaptureResult.class.getMethods())
                        .filter(method -> Modifier.isPublic(method.getModifiers()))
                        .filter(method -> Modifier.isStatic(method.getModifiers()))
                        .filter(method -> method.getName().equals("captured"))
                        .anyMatch(
                                method ->
                                        Arrays.equals(
                                                method.getParameterTypes(),
                                                new Class<?>[] {
                                                    SaveGameSnapshot.class,
                                                    long.class
                                                }));
        boolean hasPublicRebindingMethod =
                Arrays.stream(SessionSaveCaptureResult.class.getMethods())
                        .filter(method -> Modifier.isPublic(method.getModifiers()))
                        .anyMatch(
                                method ->
                                        method.getName().equals(
                                                "withPersistenceRevision"));
        boolean hasAnyPublicCapturedFactory =
                Arrays.stream(SessionSaveCaptureResult.class.getMethods())
                        .filter(method -> Modifier.isPublic(method.getModifiers()))
                        .filter(method -> Modifier.isStatic(method.getModifiers()))
                        .anyMatch(method -> method.getName().equals("captured"));
        boolean hasPublicClockRestorer =
                Arrays.stream(SessionPersistenceClock.class.getMethods())
                        .filter(method -> Modifier.isPublic(method.getModifiers()))
                        .filter(method -> Modifier.isStatic(method.getModifiers()))
                        .anyMatch(method -> method.getName().equals("restored"));
        boolean hasPublicArbitraryCaptureIssuer =
                Arrays.stream(SessionPersistenceClock.class.getMethods())
                        .filter(method -> Modifier.isPublic(method.getModifiers()))
                        .anyMatch(
                                method ->
                                        method.getName().equals("captured")
                                                && Arrays.equals(
                                                        method.getParameterTypes(),
                                                        new Class<?>[] {
                                                            SaveGameSnapshot.class,
                                                            long.class
                                                        }));

        assertAll(
                () -> assertEquals(0, SessionSaveCaptureResult.class.getConstructors().length),
                () -> assertFalse(hasTokenlessCapturedFactory),
                () -> assertFalse(hasPublicRebindingMethod),
                () -> assertFalse(hasAnyPublicCapturedFactory),
                () -> assertEquals(
                        0,
                        SessionPersistenceClock.class.getConstructors().length),
                () -> assertFalse(hasPublicClockRestorer),
                () -> assertFalse(hasPublicArbitraryCaptureIssuer));
    }

    @Test
    void opaqueRevisionDoesNotStronglyRetainCapturedSnapshot() {
        boolean hasStrongSnapshotField =
                Arrays.stream(SessionPersistenceRevision.class.getDeclaredFields())
                        .filter(field -> !Modifier.isStatic(field.getModifiers()))
                        .anyMatch(
                                field ->
                                        SaveGameSnapshot.class.isAssignableFrom(
                                                field.getType()));

        assertFalse(
                hasStrongSnapshotField,
                "opaque checkpoint tokens must not retain save payloads");
    }

    @Test
    void realSessionKeepsAuthorizationBoundedAndReleasesOldLargeCapturePayloads() {
        var access =
                GameSessionPersistenceTestFixture
                        .persistenceAuthorizationTestAccess(CONFIG);
        var foreignAccess =
                GameSessionPersistenceTestFixture
                        .persistenceAuthorizationTestAccess(CONFIG);
        access.makeReady();
        foreignAccess.makeReady();

        SaveGameSnapshot oldestSnapshot = largeSnapshot(1);
        access.enqueueCapture(oldestSnapshot, 73L);
        SessionSaveCaptureResult oldestCapture =
                access.session().captureSave();
        SessionPersistenceRevision oldestToken =
                oldestCapture.persistenceRevision().orElseThrow();
        WeakReference<SaveGameSnapshot> releasedPayload =
                new WeakReference<>(oldestSnapshot);
        oldestSnapshot = null;
        oldestCapture = null;

        SessionPersistenceRevision latestToken = oldestToken;
        for (int capture = 2; capture <= 24; capture++) {
            access.enqueueCapture(largeSnapshot(capture), 73L);
            latestToken =
                    access.session()
                            .captureSave()
                            .persistenceRevision()
                            .orElseThrow();
        }

        foreignAccess.enqueueCapture(largeSnapshot(99), 74L);
        SessionPersistenceRevision foreignFutureToken =
                foreignAccess.session()
                        .captureSave()
                        .persistenceRevision()
                        .orElseThrow();

        assertAll(
                () -> assertEquals(1, access.authorizationEntryCount()),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> access.session().markSaved(oldestToken)),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> access.session().markSaved(foreignFutureToken)));

        access.session().markSaved(latestToken);
        access.session().markSaved(latestToken);
        assertEquals(List.of(73L), access.markedRevisions());
        assertEquals(1, access.authorizationEntryCount());

        awaitGarbageCollection(releasedPayload);
        assertNull(
                releasedPayload.get(),
                "caller-released save payload remained strongly reachable");

        access.session().close();
        foreignAccess.session().close();
    }

    @Test
    void maximumFixedTickAndRevisionFailBeforeMutationAndCloseTheReadySession() {
        List<GameSessionPersistenceTestFixture.OverflowHarness> harnesses =
                List.of(
                        GameSessionPersistenceTestFixture.overflowRestoreHarness(
                                maximumTickSnapshot(),
                                SessionPersistenceTestFixture.restoredClock(
                                        Long.MAX_VALUE, 3L),
                                GameSessionPersistenceTestFixture.OverflowMutation.FIXED_STEP),
                        GameSessionPersistenceTestFixture.overflowRestoreHarness(
                                SNAPSHOT,
                                SessionPersistenceTestFixture.restoredClock(
                                        SNAPSHOT.fixedTick(), Long.MAX_VALUE),
                                GameSessionPersistenceTestFixture.OverflowMutation.REVISION_ONLY));

        for (GameSessionPersistenceTestFixture.OverflowHarness harness : harnesses) {
            harness.makeReady();
            assertEquals(GameSessionState.READY, harness.session().state());

            assertThrows(
                    ArithmeticException.class,
                    () ->
                            harness.session()
                                    .advancePlaying(
                                            1.0 / 60.0,
                                            new MouseDelta(1.0, 0.0),
                                            true));

            assertAll(
                    () -> assertEquals(GameSessionState.FAILED, harness.session().state()),
                    () -> assertEquals(0, harness.runtime().canonicalMutations()),
                    () -> assertEquals(1, harness.closeCalls().get()),
                    () ->
                            assertThrows(
                                    IllegalStateException.class,
                                    harness.session()::captureSave),
                    () ->
                            assertThrows(
                                    IllegalStateException.class,
                                    harness.session()::capturePaused),
                    () -> assertEquals(0, harness.runtime().captureCalls()),
                    () -> assertEquals(0, harness.runtime().pausedFrameCalls()));
            harness.session().close();
            assertEquals(1, harness.closeCalls().get());
        }
    }

    @Test
    void typedRestoreUsesDedicatedFreshPathAndNeverInvokesGenerationAssembler() {
        List<String> events = new ArrayList<>();
        RestoreRuntime runtime = new RestoreRuntime();
        GameSessionFactory factory =
                new GameSessionFactory(
                        (config, world, shutdown) -> {
                            events.add("generation-fallback");
                            throw new AssertionError(
                                    "validated restore must not invoke new-world generation");
                        },
                        (snapshot, world, shutdown) -> {
                            events.add("restore");
                            assertSame(SNAPSHOT, snapshot);
                            assertTrue(world.chunks().keys().isEmpty());
                            shutdown.register(
                                    "restored-runtime",
                                    () -> events.add("close-restored-runtime"));
                            return runtime;
                        });

        GameSession session = factory.restore(SNAPSHOT);

        assertEquals(List.of("restore"), events);
        assertEquals(GameSessionState.LOADING, session.state());
        session.pollLoad();
        assertEquals(GameSessionState.LOADING, session.state());
        runtime.completeLoad = true;
        session.pollLoad();
        assertEquals(GameSessionState.READY, session.state());
        session.close();
        assertEquals(
                List.of("restore", "close-restored-runtime"),
                events);
    }

    @Test
    void restoreAssemblyFailureClosesHalfBuiltOwnershipOnceInReverseOrder() {
        List<String> events = new ArrayList<>();
        AtomicInteger generationCalls = new AtomicInteger();
        RuntimeException primary =
                new RuntimeException("injected canonical restore failure");
        RuntimeException cleanup =
                new RuntimeException("injected restore cleanup failure");
        GameSessionFactory factory =
                new GameSessionFactory(
                        (config, world, shutdown) -> {
                            generationCalls.incrementAndGet();
                            throw new AssertionError(
                                    "restore failure must not fall back to generation");
                        },
                        (snapshot, world, shutdown) -> {
                            shutdown.register(
                                    "canonical-services",
                                    () -> {
                                        events.add("canonical-services");
                                        throw cleanup;
                                    });
                            shutdown.register(
                                    "physical-projections",
                                    () -> events.add("physical-projections"));
                            throw primary;
                        });

        RuntimeException thrown =
                assertThrows(
                        RuntimeException.class,
                        () -> factory.restore(SNAPSHOT));

        assertSame(primary, thrown);
        assertEquals(0, generationCalls.get());
        assertEquals(
                List.of("physical-projections", "canonical-services"),
                events);
        assertEquals(1, thrown.getSuppressed().length);
        assertSame(cleanup, thrown.getSuppressed()[0]);
    }

    @ParameterizedTest(name = "actual production failure at {0}")
    @MethodSource("productionFailureCases")
    void actualProductionFailureClosesRealOwnersAndRetryIsIsolated(
            GameSessionFactory.ProductionFailurePoint failurePoint,
            Optional<GameSessionState> expectedFailedState,
            int expectedCapturedFrames) {
        var access =
                GameSessionPersistenceTestFixture
                        .productionLifecycleTestAccess(
                                failurePoint,
                                withOrientation(SNAPSHOT, 37.5, -12.25),
                                SNAPSHOT);

        RuntimeException thrown =
                assertThrows(RuntimeException.class, access::triggerFailure);

        assertAll(
                () -> assertSame(access.primaryFailure(), thrown),
                () -> assertEquals(expectedFailedState, access.failedSessionState()),
                () -> assertEquals(-90.0f, access.camera().getYaw()),
                () -> assertEquals(0.0f, access.camera().getPitch()),
                () -> assertFalse(access.readyPublished()),
                () -> assertEquals(
                        expectedCapturedFrames,
                        access.capturedFrameCount()),
                () -> assertEquals(1, access.failureHookCalls()),
                () -> assertEquals(1, access.closeCalls()),
                () -> assertEquals(0, access.physicsBodyCount()),
                () -> assertEquals(0, access.inventoryPendingReservations()),
                () -> assertEquals(0, access.worldItemPendingReservations()),
                () -> assertEquals(0, access.liveWorkerCount()),
                () -> assertEquals(1, thrown.getSuppressed().length),
                () -> assertSame(
                        access.cleanupFailure(),
                        thrown.getSuppressed()[0]));

        access.closeFailedSessionAgain();
        assertEquals(1, access.closeCalls());
        access.startSuccessfulRetry();
        assertAll(
                () -> assertEquals(-90.0f, access.camera().getYaw()),
                () -> assertEquals(0.0f, access.camera().getPitch()),
                () -> assertEquals(
                        GameSessionState.READY,
                        access.successfulRetryState()),
                () -> assertEquals(2, access.successfulRetryFrameCount()));
        access.closeSuccessfulRetry();
        assertEquals(0, access.liveWorkerCount());
    }

    private static Stream<Arguments> productionFailureCases() {
        return Stream.of(
                Arguments.of(
                        GameSessionFactory.ProductionFailurePoint.CHUNKS,
                        Optional.empty(),
                        0),
                Arguments.of(
                        GameSessionFactory.ProductionFailurePoint.INVENTORY,
                        Optional.empty(),
                        0),
                Arguments.of(
                        GameSessionFactory.ProductionFailurePoint.WORLD_ITEMS,
                        Optional.empty(),
                        0),
                Arguments.of(
                        GameSessionFactory.ProductionFailurePoint.PLAYER,
                        Optional.empty(),
                        0),
                Arguments.of(
                        GameSessionFactory.ProductionFailurePoint.PROJECTION,
                        Optional.empty(),
                        0),
                Arguments.of(
                        GameSessionFactory.ProductionFailurePoint.INITIAL_FRAME,
                        Optional.empty(),
                        0),
                Arguments.of(
                        GameSessionFactory.ProductionFailurePoint.MESH_PUMP,
                        Optional.of(GameSessionState.FAILED),
                        1),
                Arguments.of(
                        GameSessionFactory.ProductionFailurePoint.READY_FRAME,
                        Optional.of(GameSessionState.FAILED),
                        1));
    }

    private static SaveGameSnapshot snapshot() {
        int worldHeight = 16;
        int radius = 2;
        EntityRef owner = new EntityRef(0);
        List<ChunkSnapshot> savedChunks = new ArrayList<>();
        for (int chunkX = -radius; chunkX <= radius; chunkX++) {
            for (int chunkZ = -radius; chunkZ <= radius; chunkZ++) {
                savedChunks.add(
                        ChunkSnapshot.empty(
                                new ChunkKey(chunkX, chunkZ),
                                1L,
                                worldHeight));
            }
        }
        ChunkRepositorySnapshot chunks =
                new ChunkRepositorySnapshot(
                        worldHeight,
                        1L,
                        savedChunks);
        return new SaveGameSnapshot(
                new SaveGameSnapshot.StaticMetadata(
                        SaveFormatVersion.CURRENT,
                        "0.2.0-test",
                        SaveGameId.parse(
                                "99999999-8888-4777-8666-555555555555"),
                        "Factory Restore",
                        Instant.parse("2026-08-10T13:00:00Z"),
                        12345L,
                        "gaia-v2",
                        "2".repeat(64),
                        radius,
                        worldHeight,
                        Optional.empty()),
                5L,
                chunks,
                new PlayerSaveSnapshot(
                        owner,
                        2.0,
                        8.0,
                        2.0,
                        0.0,
                        0.0,
                        0.0,
                        -90.0,
                        0.0,
                        GameMode.SURVIVAL,
                        false),
                new InventorySaveSnapshot(
                        owner,
                        Map.of(),
                        BodySlot.LEFT_HAND,
                        false,
                        0L),
                new WorldItemsSaveSnapshot(
                        5L,
                        List.of(),
                        0L,
                        false));
    }

    private static SaveGameSnapshot maximumTickSnapshot() {
        return new SaveGameSnapshot(
                SNAPSHOT.metadata(),
                Long.MAX_VALUE,
                SNAPSHOT.chunks(),
                SNAPSHOT.player(),
                SNAPSHOT.inventory(),
                new WorldItemsSaveSnapshot(
                        Long.MAX_VALUE,
                        List.of(),
                        0L,
                        false));
    }

    private static SaveGameSnapshot largeSnapshot(int identity) {
        int worldHeight = 256;
        int radius = 2;
        List<ChunkSnapshot> chunks = new ArrayList<>();
        byte[] blocks = new byte[16 * worldHeight * 16];
        Arrays.fill(blocks, (byte) identity);
        for (int chunkX = -radius; chunkX <= radius; chunkX++) {
            for (int chunkZ = -radius; chunkZ <= radius; chunkZ++) {
                chunks.add(
                        ChunkSnapshot.of(
                                new ChunkKey(chunkX, chunkZ),
                                identity,
                                worldHeight,
                                blocks));
            }
        }
        EntityRef owner = new EntityRef(0);
        return new SaveGameSnapshot(
                new SaveGameSnapshot.StaticMetadata(
                        SaveFormatVersion.CURRENT,
                        "0.2.0-test",
                        SaveGameId.parse(
                                "99999999-8888-4777-8666-555555555555"),
                        "Large authorization capture " + identity,
                        Instant.parse("2026-08-10T13:00:00Z"),
                        12345L,
                        "gaia-v2",
                        "2".repeat(64),
                        radius,
                        worldHeight,
                        Optional.empty()),
                73L,
                new ChunkRepositorySnapshot(
                        worldHeight,
                        identity,
                        chunks),
                new PlayerSaveSnapshot(
                        owner,
                        2.0,
                        8.0,
                        2.0,
                        0.0,
                        0.0,
                        0.0,
                        -90.0,
                        0.0,
                        GameMode.SURVIVAL,
                        false),
                new InventorySaveSnapshot(
                        owner,
                        Map.of(),
                        BodySlot.LEFT_HAND,
                        false,
                        0L),
                new WorldItemsSaveSnapshot(
                        73L,
                        List.of(),
                        0L,
                        false));
    }

    private static void awaitGarbageCollection(
            WeakReference<?> reference) {
        for (int attempt = 0;
                attempt < 40 && reference.get() != null;
                attempt++) {
            byte[][] pressure = new byte[8][];
            for (int allocation = 0;
                    allocation < pressure.length;
                    allocation++) {
                pressure[allocation] = new byte[256 * 1024];
            }
            System.gc();
            Thread.yield();
        }
    }

    private static SaveGameSnapshot withOrientation(
            SaveGameSnapshot snapshot, double yaw, double pitch) {
        PlayerSaveSnapshot player = snapshot.player();
        return new SaveGameSnapshot(
                snapshot.metadata(),
                snapshot.fixedTick(),
                snapshot.chunks(),
                new PlayerSaveSnapshot(
                        player.owner(),
                        player.feetPositionX(),
                        player.feetPositionY(),
                        player.feetPositionZ(),
                        player.velocityX(),
                        player.velocityY(),
                        player.velocityZ(),
                        yaw,
                        pitch,
                        player.gameMode(),
                        player.noclip()),
                snapshot.inventory(),
                snapshot.worldItems());
    }

    private static SaveGameSnapshot detachedCopy(SaveGameSnapshot snapshot) {
        return new SaveGameSnapshot(
                snapshot.metadata(),
                snapshot.fixedTick(),
                snapshot.chunks(),
                snapshot.player(),
                snapshot.inventory(),
                snapshot.worldItems());
    }

    private static final class RestoreRuntime
            implements GameSessionFactory.SessionRuntime {
        private boolean completeLoad;

        @Override
        public boolean pollLoad() {
            return completeLoad;
        }

        @Override
        public GameSessionFrame advancePlaying(
                double frameDeltaSeconds,
                MouseDelta look,
                boolean focused) {
            throw new AssertionError("advancePlaying was not expected");
        }

        @Override
        public GameSessionFrame capturePaused() {
            throw new AssertionError("capturePaused was not expected");
        }

        @Override
        public SessionSaveCaptureResult captureSave() {
            throw new AssertionError("captureSave was not expected");
        }

        @Override
        public void markSaved(SessionPersistenceRevision revision) {
            throw new AssertionError("markSaved was not expected");
        }

        @Override
        public void discardGameplayEligibility() {
            throw new AssertionError(
                    "discardGameplayEligibility was not expected");
        }

        @Override
        public void discardFixedTime() {
            throw new AssertionError("discardFixedTime was not expected");
        }
    }
}

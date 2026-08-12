package com.gaia.session;

import com.gaia.interaction.GameModeManager;
import com.gaia.inventory.BodyInventoryService;
import com.gaia.save.snapshot.SaveGameSnapshot;
import com.overlord.core.input.MouseDelta;
import com.overlord.interaction.api.EntityRef;
import com.overlord.physics.PlayerController;
import com.overlord.renderer.Camera;
import com.overlord.voxel.World;
import com.overlord.worlditem.LogicalWorldItemService;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.function.LongSupplier;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/** Test-only bridge to the package-owned session persistence seams. */
public final class GameSessionPersistenceTestFixture {
    private GameSessionPersistenceTestFixture() {}

    public static SessionSaveCaptureResult capture(CaptureSource source) {
        CaptureSource capture = Objects.requireNonNull(source, "source");
        return GameSessionFactory.captureSave(
                capture.metadata(),
                capture.persistenceRevision(),
                capture.fixedTick(),
                capture.world(),
                capture.inventoryOwner(),
                capture.inventory(),
                capture.worldItems(),
                capture.playerController(),
                capture.camera(),
                capture.gameModes());
    }

    public static SessionHarness sessionHarness(
            GameSessionConfig config,
            List<SessionSaveCaptureResult> captures) {
        RecordingRuntime runtime = new RecordingRuntime(captures);
        GameSessionFactory factory =
                new GameSessionFactory(
                        (ignoredConfig, ignoredWorld, ignoredShutdown) -> runtime);
        return new SessionHarness(factory.create(config), runtime);
    }

    public static SessionSaveCaptureResult runtimeCaptured(
            SaveGameSnapshot snapshot, long revision) {
        return SessionPersistenceTestFixture.captured(snapshot, revision);
    }

    /** Exercises only public API shapes; it never opens non-public authority. */
    public static Optional<SessionSaveCaptureResult> tryPublicCaptureRebind(
            SaveGameSnapshot otherSnapshot,
            SessionPersistenceRevision genuineToken) {
        Objects.requireNonNull(otherSnapshot, "otherSnapshot");
        Objects.requireNonNull(genuineToken, "genuineToken");

        for (Constructor<?> constructor :
                SessionSaveCaptureResult.class.getConstructors()) {
            Class<?>[] parameters = constructor.getParameterTypes();
            if (parameters.length == 4
                    && parameters[0] == SessionSaveCaptureResult.Status.class
                    && parameters[1] == Optional.class
                    && parameters[2] == OptionalLong.class
                    && parameters[3] == Optional.class) {
                Optional<SessionSaveCaptureResult> rebound = invokeCapture(
                        () -> constructor.newInstance(
                                SessionSaveCaptureResult.Status.CAPTURED,
                                Optional.of(otherSnapshot),
                                OptionalLong.of(genuineToken.value()),
                                Optional.of(genuineToken)));
                if (rebound.isPresent()) {
                    return rebound;
                }
            }
        }

        for (Method factory : SessionSaveCaptureResult.class.getMethods()) {
            if (!Modifier.isPublic(factory.getModifiers())
                    || !Modifier.isStatic(factory.getModifiers())
                    || !factory.getName().equals("captured")) {
                continue;
            }
            Class<?>[] parameters = factory.getParameterTypes();
            if (parameters.length == 2
                    && parameters[0] == SaveGameSnapshot.class
                    && parameters[1] == SessionPersistenceRevision.class) {
                Optional<SessionSaveCaptureResult> rebound = invokeCapture(
                        () -> factory.invoke(null, otherSnapshot, genuineToken));
                if (rebound.isPresent()) {
                    return rebound;
                }
            } else if (parameters.length == 2
                    && parameters[0] == SaveGameSnapshot.class
                    && parameters[1] == long.class) {
                Optional<SessionSaveCaptureResult> raw = invokeCapture(
                        () -> factory.invoke(
                                null, otherSnapshot, genuineToken.value()));
                if (raw.isEmpty()) {
                    continue;
                }
                for (Method rebinder : SessionSaveCaptureResult.class.getMethods()) {
                    if (Modifier.isPublic(rebinder.getModifiers())
                            && rebinder.getName().equals("withPersistenceRevision")
                            && Arrays.equals(
                                    rebinder.getParameterTypes(),
                                    new Class<?>[] {
                                        SessionPersistenceRevision.class
                                    })) {
                        Optional<SessionSaveCaptureResult> rebound = invokeCapture(
                                () -> rebinder.invoke(
                                        raw.orElseThrow(), genuineToken));
                        if (rebound.isPresent()) {
                            return rebound;
                        }
                    }
                }
            }
        }
        return Optional.empty();
    }

    private static Optional<SessionSaveCaptureResult> invokeCapture(
            ReflectiveCapture invocation) {
        try {
            return Optional.of(
                    (SessionSaveCaptureResult) invocation.invoke());
        } catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof IllegalArgumentException) {
                return Optional.empty();
            }
            throw new AssertionError(
                    "unexpected public capture API failure", failure.getCause());
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(
                    "public capture API was not invocable", failure);
        }
    }

    @FunctionalInterface
    private interface ReflectiveCapture {
        Object invoke() throws ReflectiveOperationException;
    }

    public static OverflowHarness overflowRestoreHarness(
            SaveGameSnapshot snapshot,
            SessionPersistenceClock persistenceClock,
            OverflowMutation mutation) {
        OverflowRuntime runtime =
                new OverflowRuntime(persistenceClock, mutation);
        AtomicInteger closeCalls = new AtomicInteger();
        GameSessionFactory factory =
                new GameSessionFactory(
                        (ignoredConfig, ignoredWorld, ignoredShutdown) -> {
                            throw new AssertionError(
                                    "overflow restore must not generate a world");
                        },
                        (ignoredSnapshot, ignoredWorld, shutdown) -> {
                            shutdown.register(
                                    "overflow-runtime",
                                    closeCalls::incrementAndGet);
                            return runtime;
                        });
        return new OverflowHarness(
                factory.restore(Objects.requireNonNull(snapshot, "snapshot")),
                runtime,
                closeCalls);
    }

    public static GameSession restoreThroughFactory(
            SaveGameSnapshot snapshot,
            Runnable realRestore,
            Runnable cleanup) {
        Objects.requireNonNull(realRestore, "realRestore");
        Objects.requireNonNull(cleanup, "cleanup");
        GameSessionFactory factory =
                new GameSessionFactory(
                        (ignoredConfig, ignoredWorld, ignoredShutdown) -> {
                            throw new AssertionError(
                                    "canonical restore failure must not generate a world");
                        },
                        (ignoredSnapshot, ignoredWorld, shutdown) -> {
                            shutdown.register("real-restore", cleanup);
                            realRestore.run();
                            throw new AssertionError(
                                    "the injected real restore was expected to fail");
                        });
        return factory.restore(Objects.requireNonNull(snapshot, "snapshot"));
    }

    /**
     * Wished-for observer over a real OwnedGameSession and its package-owned
     * bounded authorization port. It must not expose token issuance.
     */
    public static GameSessionFactory.PersistenceAuthorizationTestAccess
            persistenceAuthorizationTestAccess(
                    GameSessionConfig config) {
        return GameSessionFactory.persistenceAuthorizationTestAccess(
                Objects.requireNonNull(config, "config"));
    }

    /**
     * Wished-for failure injection into the actual production assembler and
     * concrete ProductionSessionRuntime. The access object only observes real
     * owners and must not reproduce restore, frame, or cleanup logic.
     */
    public static GameSessionFactory.ProductionLifecycleTestAccess
            productionLifecycleTestAccess(
                    GameSessionFactory.ProductionFailurePoint failurePoint,
                    SaveGameSnapshot failedSnapshot,
                    SaveGameSnapshot successfulRetrySnapshot) {
        return GameSessionFactory.productionLifecycleTestAccess(
                Objects.requireNonNull(failurePoint, "failurePoint"),
                Objects.requireNonNull(failedSnapshot, "failedSnapshot"),
                Objects.requireNonNull(
                        successfulRetrySnapshot,
                        "successfulRetrySnapshot"));
    }

    /**
     * Typed bridge over a real headless production factory. The package-owned
     * access supplies only construction and read-only observations; this
     * fixture itself invokes the public factory/session persistence boundary.
     */
    public static ActualProductionSession restoreActualProductionSession(
            SaveGameSnapshot decoded) {
        var access = GameSessionFactory.productionSessionTestAccess();
        GameSession session = access.factory().restore(
                Objects.requireNonNull(decoded, "decoded"));
        return new ActualProductionSession(
                session,
                access::generationInvocationCount,
                access::readyPublicationCount,
                access::capturedFrameCount,
                access::transientPresentationCount,
                access::physicsBodyCount,
                access::inventoryPendingReservations,
                access::worldItemPendingReservations,
                access::liveWorkerCount,
                () -> access.authorizationEntryCount(session));
    }

    /**
     * Actual public production restore attempt that retains only read-only
     * observations when validation rejects before a session is returned.
     */
    public static ActualProductionRestoreAttempt attemptActualProductionRestore(
            SaveGameSnapshot decoded) {
        var access = GameSessionFactory.productionSessionTestAccess();
        SaveGameSnapshot validated = Objects.requireNonNull(decoded, "decoded");
        return new ActualProductionRestoreAttempt(
                () -> access.factory().restore(validated),
                access::generationInvocationCount,
                access::readyPublicationCount,
                access::capturedFrameCount,
                access::liveWorkerCount);
    }

    public static final class ActualProductionRestoreAttempt
            implements AutoCloseable {
        private static final int MAX_LOAD_POLLS = 100_000;

        private final Supplier<GameSession> restore;
        private final IntSupplier generationInvocationCount;
        private final IntSupplier readyPublicationCount;
        private final IntSupplier capturedFrameCount;
        private final IntSupplier liveWorkerCount;
        private GameSession session;

        private ActualProductionRestoreAttempt(
                Supplier<GameSession> restore,
                IntSupplier generationInvocationCount,
                IntSupplier readyPublicationCount,
                IntSupplier capturedFrameCount,
                IntSupplier liveWorkerCount) {
            this.restore = Objects.requireNonNull(restore, "restore");
            this.generationInvocationCount = Objects.requireNonNull(
                    generationInvocationCount, "generationInvocationCount");
            this.readyPublicationCount = Objects.requireNonNull(
                    readyPublicationCount, "readyPublicationCount");
            this.capturedFrameCount = Objects.requireNonNull(
                    capturedFrameCount, "capturedFrameCount");
            this.liveWorkerCount = Objects.requireNonNull(
                    liveWorkerCount, "liveWorkerCount");
        }

        public Optional<Throwable> restoreAndDriveToReady() {
            try {
                session = restore.get();
                for (int poll = 0;
                        poll < MAX_LOAD_POLLS
                                && session.state() == GameSessionState.LOADING;
                        poll++) {
                    session.pollLoad();
                    if (session.state() == GameSessionState.LOADING) {
                        Thread.yield();
                    }
                }
                if (session.state() == GameSessionState.LOADING) {
                    throw new IllegalStateException(
                            "actual production restore attempt did not finish loading");
                }
                return Optional.empty();
            } catch (RuntimeException | Error failure) {
                return Optional.of(failure);
            }
        }

        public Optional<GameSessionState> sessionState() {
            return session == null
                    ? Optional.empty()
                    : Optional.of(session.state());
        }

        public int generationInvocationCount() {
            return generationInvocationCount.getAsInt();
        }

        public int readyPublicationCount() {
            return readyPublicationCount.getAsInt();
        }

        public int capturedFrameCount() {
            return capturedFrameCount.getAsInt();
        }

        public int liveWorkerCount() {
            return liveWorkerCount.getAsInt();
        }

        @Override
        public void close() {
            if (session != null) {
                session.close();
            }
        }
    }

    /** Public-session driver plus read-only observations of actual owners. */
    public static final class ActualProductionSession implements AutoCloseable {
        private static final int MAX_LOAD_POLLS = 100_000;

        private final GameSession session;
        private final IntSupplier generationInvocationCount;
        private final IntSupplier readyPublicationCount;
        private final IntSupplier capturedFrameCount;
        private final IntSupplier transientPresentationCount;
        private final IntSupplier physicsBodyCount;
        private final IntSupplier inventoryPendingReservations;
        private final IntSupplier worldItemPendingReservations;
        private final IntSupplier liveWorkerCount;
        private final IntSupplier authorizationEntryCount;

        private ActualProductionSession(
                GameSession session,
                IntSupplier generationInvocationCount,
                IntSupplier readyPublicationCount,
                IntSupplier capturedFrameCount,
                IntSupplier transientPresentationCount,
                IntSupplier physicsBodyCount,
                IntSupplier inventoryPendingReservations,
                IntSupplier worldItemPendingReservations,
                IntSupplier liveWorkerCount,
                IntSupplier authorizationEntryCount) {
            this.session = Objects.requireNonNull(session, "session");
            this.generationInvocationCount = Objects.requireNonNull(
                    generationInvocationCount, "generationInvocationCount");
            this.readyPublicationCount = Objects.requireNonNull(
                    readyPublicationCount, "readyPublicationCount");
            this.capturedFrameCount = Objects.requireNonNull(
                    capturedFrameCount, "capturedFrameCount");
            this.transientPresentationCount = Objects.requireNonNull(
                    transientPresentationCount, "transientPresentationCount");
            this.physicsBodyCount = Objects.requireNonNull(
                    physicsBodyCount, "physicsBodyCount");
            this.inventoryPendingReservations = Objects.requireNonNull(
                    inventoryPendingReservations, "inventoryPendingReservations");
            this.worldItemPendingReservations = Objects.requireNonNull(
                    worldItemPendingReservations, "worldItemPendingReservations");
            this.liveWorkerCount = Objects.requireNonNull(
                    liveWorkerCount, "liveWorkerCount");
            this.authorizationEntryCount = Objects.requireNonNull(
                    authorizationEntryCount, "authorizationEntryCount");
        }

        public void driveToReady() {
            for (int poll = 0;
                    poll < MAX_LOAD_POLLS
                            && session.state() == GameSessionState.LOADING;
                    poll++) {
                session.pollLoad();
                if (session.state() == GameSessionState.LOADING) {
                    Thread.yield();
                }
            }
            if (session.state() == GameSessionState.LOADING) {
                throw new IllegalStateException(
                        "actual production session did not finish loading");
            }
        }

        public GameSessionState state() {
            return session.state();
        }

        public GameSessionFrame capturePaused() {
            return session.capturePaused();
        }

        public SessionSaveCaptureResult captureSave() {
            return session.captureSave();
        }

        public void markSaved(SessionPersistenceRevision revision) {
            session.markSaved(Objects.requireNonNull(revision, "revision"));
        }

        public SaveGameSnapshot captureAndMarkSaved() {
            SessionSaveCaptureResult captured = captureSave();
            markSaved(captured.persistenceRevision().orElseThrow());
            return captured.snapshot().orElseThrow();
        }

        public int generationInvocationCount() {
            return generationInvocationCount.getAsInt();
        }

        public int readyPublicationCount() {
            return readyPublicationCount.getAsInt();
        }

        public int capturedFrameCount() {
            return capturedFrameCount.getAsInt();
        }

        public int transientPresentationCount() {
            return transientPresentationCount.getAsInt();
        }

        public int physicsBodyCount() {
            return physicsBodyCount.getAsInt();
        }

        public int inventoryPendingReservations() {
            return inventoryPendingReservations.getAsInt();
        }

        public int worldItemPendingReservations() {
            return worldItemPendingReservations.getAsInt();
        }

        public int liveWorkerCount() {
            return liveWorkerCount.getAsInt();
        }

        public int authorizationEntryCount() {
            return authorizationEntryCount.getAsInt();
        }

        @Override
        public void close() {
            session.close();
        }
    }

    public record CaptureSource(
            SaveGameSnapshot.StaticMetadata metadata,
            LongSupplier persistenceRevision,
            LongSupplier fixedTick,
            World world,
            EntityRef inventoryOwner,
            BodyInventoryService inventory,
            LogicalWorldItemService worldItems,
            PlayerController playerController,
            Camera camera,
            GameModeManager gameModes) {
        public CaptureSource {
            Objects.requireNonNull(metadata, "metadata");
            Objects.requireNonNull(persistenceRevision, "persistenceRevision");
            Objects.requireNonNull(fixedTick, "fixedTick");
            Objects.requireNonNull(world, "world");
            Objects.requireNonNull(inventoryOwner, "inventoryOwner");
            Objects.requireNonNull(inventory, "inventory");
            Objects.requireNonNull(worldItems, "worldItems");
            Objects.requireNonNull(playerController, "playerController");
            Objects.requireNonNull(camera, "camera");
            Objects.requireNonNull(gameModes, "gameModes");
        }
    }

    public record SessionHarness(
            GameSession session, RecordingRuntime runtime) {
        public SessionHarness {
            Objects.requireNonNull(session, "session");
            Objects.requireNonNull(runtime, "runtime");
        }

        public void makeReady() {
            runtime.completeLoad = true;
            session.pollLoad();
        }
    }

    public record OverflowHarness(
            GameSession session,
            OverflowRuntime runtime,
            AtomicInteger closeCalls) {
        public OverflowHarness {
            Objects.requireNonNull(session, "session");
            Objects.requireNonNull(runtime, "runtime");
            Objects.requireNonNull(closeCalls, "closeCalls");
        }

        public void makeReady() {
            session.pollLoad();
        }
    }

    public enum OverflowMutation {
        FIXED_STEP,
        REVISION_ONLY
    }

    public static final class OverflowRuntime
            implements GameSessionFactory.SessionRuntime {
        private final SessionPersistenceClock persistenceClock;
        private final OverflowMutation mutation;
        private int canonicalMutations;
        private int captureCalls;
        private int pausedFrameCalls;

        private OverflowRuntime(
                SessionPersistenceClock persistenceClock,
                OverflowMutation mutation) {
            this.persistenceClock =
                    Objects.requireNonNull(
                            persistenceClock, "persistenceClock");
            this.mutation = Objects.requireNonNull(mutation, "mutation");
        }

        public int canonicalMutations() {
            return canonicalMutations;
        }

        public int captureCalls() {
            return captureCalls;
        }

        public int pausedFrameCalls() {
            return pausedFrameCalls;
        }

        @Override
        public boolean pollLoad() {
            return true;
        }

        @Override
        public GameSessionFrame advancePlaying(
                double frameDeltaSeconds,
                MouseDelta look,
                boolean focused) {
            var reservation =
                    mutation == OverflowMutation.FIXED_STEP
                            ? persistenceClock.reserveFixedStep()
                            : persistenceClock.reserveRevisionMutation();
            canonicalMutations++;
            reservation.commit();
            throw new AssertionError("maximum clock reservation must fail");
        }

        @Override
        public GameSessionFrame capturePaused() {
            pausedFrameCalls++;
            throw new AssertionError("failed session must not capture a frame");
        }

        @Override
        public SessionSaveCaptureResult captureSave() {
            captureCalls++;
            throw new AssertionError("failed session must not capture a save");
        }

        @Override
        public void discardGameplayEligibility() {}

        @Override
        public void discardFixedTime() {}
    }

    public static final class RecordingRuntime
            implements GameSessionFactory.SessionRuntime {
        private final Deque<SessionSaveCaptureResult> captures;
        private final List<SessionPersistenceRevision> marked =
                new ArrayList<>();
        private boolean completeLoad;
        private int captureCalls;

        private RecordingRuntime(List<SessionSaveCaptureResult> captures) {
            this.captures =
                    new ArrayDeque<>(
                            List.copyOf(
                                    Objects.requireNonNull(
                                            captures, "captures")));
        }

        public int captureCalls() {
            return captureCalls;
        }

        public List<SessionPersistenceRevision> marked() {
            return List.copyOf(marked);
        }

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
            captureCalls++;
            if (captures.isEmpty()) {
                throw new AssertionError("no recorded capture remains");
            }
            return captures.removeFirst();
        }

        @Override
        public void markSaved(SessionPersistenceRevision revision) {
            marked.add(Objects.requireNonNull(revision, "revision"));
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

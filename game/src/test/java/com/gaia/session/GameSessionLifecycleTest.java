package com.gaia.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.gaia.interaction.GameMode;
import com.overlord.core.input.MouseDelta;
import com.overlord.core.lifecycle.ShutdownCoordinator;
import com.overlord.voxel.World;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class GameSessionLifecycleTest {
    private static final GameSessionConfig CONFIG =
            new GameSessionConfig(73L, 2, GameMode.SURVIVAL, false);

    @Test
    void loadingBecomesReadyOnlyAfterPollObservesCompletedResources() {
        RecordingSessionFixture fixture = new RecordingSessionFixture();
        GameSession session = fixture.factory().create(CONFIG);

        assertEquals(GameSessionState.LOADING, session.state());
        session.pollLoad();
        assertEquals(GameSessionState.LOADING, session.state());

        fixture.runtime(0).completeLoad();
        session.pollLoad();

        assertEquals(GameSessionState.READY, session.state());
        assertEquals(2, fixture.runtime(0).pollCalls());
    }

    @Test
    void closingWhileLoadingCancelsAndClosesResourcesInReverseOrder() {
        RecordingSessionFixture fixture = new RecordingSessionFixture();
        GameSession session = fixture.factory().create(CONFIG);

        session.close();

        assertEquals(GameSessionState.CLOSED, session.state());
        assertEquals(
                List.of("mesh", "world-load", "gameplay"),
                fixture.closeOrder(0));
    }

    @Test
    void failedLoadPreservesFailureAndCleansEveryResource() {
        RecordingSessionFixture fixture = new RecordingSessionFixture();
        GameSession session = fixture.factory().create(CONFIG);
        RuntimeException loadFailure =
                new RuntimeException("recorded world load failure");
        fixture.runtime(0).failLoad(loadFailure);

        RuntimeException thrown =
                assertThrows(RuntimeException.class, session::pollLoad);

        assertSame(loadFailure, thrown);
        assertEquals(GameSessionState.FAILED, session.state());
        assertEquals(
                List.of("mesh", "world-load", "gameplay"),
                fixture.closeOrder(0));
    }

    @Test
    void closeIsIdempotentAfterReadyAndAfterFailureCleanup() {
        RecordingSessionFixture fixture = new RecordingSessionFixture();
        GameSession ready = fixture.factory().create(CONFIG);
        fixture.runtime(0).completeLoad();
        ready.pollLoad();

        ready.close();
        ready.close();

        assertEquals(GameSessionState.CLOSED, ready.state());
        assertEquals(
                List.of("mesh", "world-load", "gameplay"),
                fixture.closeOrder(0));

        GameSession failed = fixture.factory().create(CONFIG);
        fixture.runtime(1).failLoad(new RuntimeException("failed"));
        assertThrows(RuntimeException.class, failed::pollLoad);

        failed.close();
        failed.close();

        assertEquals(
                List.of("mesh", "world-load", "gameplay"),
                fixture.closeOrder(1));
    }
}

final class RecordingSessionFixture {
    private final List<GameSessionConfig> configs = new ArrayList<>();
    private final List<World> worlds = new ArrayList<>();
    private final List<RecordingRuntime> runtimes = new ArrayList<>();
    private final GameSessionFactory factory =
            new GameSessionFactory(this::assemble);

    GameSessionFactory factory() {
        return factory;
    }

    List<GameSessionConfig> configs() {
        return List.copyOf(configs);
    }

    List<World> worlds() {
        return List.copyOf(worlds);
    }

    RecordingRuntime runtime(int index) {
        return runtimes.get(index);
    }

    List<String> closeOrder(int index) {
        return runtime(index).closeOrder();
    }

    private GameSessionFactory.SessionRuntime assemble(
            GameSessionConfig config,
            World world,
            ShutdownCoordinator shutdown) {
        configs.add(config);
        worlds.add(world);
        RecordingRuntime runtime = new RecordingRuntime();
        runtimes.add(runtime);
        shutdown.register("gameplay", () -> runtime.recordClose("gameplay"));
        shutdown.register("world-load", () -> runtime.recordClose("world-load"));
        shutdown.register("mesh", () -> runtime.recordClose("mesh"));
        return runtime;
    }

    static final class RecordingRuntime
            implements GameSessionFactory.SessionRuntime {
        private final List<String> closeOrder = new ArrayList<>();
        private boolean loadComplete;
        private RuntimeException loadFailure;
        private int pollCalls;

        void completeLoad() {
            loadComplete = true;
        }

        void failLoad(RuntimeException failure) {
            loadFailure = failure;
        }

        int pollCalls() {
            return pollCalls;
        }

        List<String> closeOrder() {
            return List.copyOf(closeOrder);
        }

        void recordClose(String resource) {
            closeOrder.add(resource);
        }

        @Override
        public boolean pollLoad() {
            pollCalls++;
            if (loadFailure != null) {
                throw loadFailure;
            }
            return loadComplete;
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

package com.gaia.session;

import com.gaia.save.archive.SaveArchiveReadResult;
import com.gaia.save.format.SaveGameId;
import com.gaia.save.session.SaveCoordinator;
import com.gaia.save.snapshot.SaveGameSnapshot;
import com.overlord.core.input.MouseDelta;
import java.time.Instant;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;

/** Creates fresh persistence-aware sessions for New and Load requests. */
public final class GameSessionLauncher {
    private final NewSessionFactory newSessions;
    private final RestoreSessionFactory restoredSessions;
    private final SaveLoader saves;
    private final SaveCoordinator coordinator;
    private final Function<NewWorldRequest, GameSessionConfig> configs;
    private final Supplier<Instant> clock;

    public GameSessionLauncher(
            NewSessionFactory newSessions,
            RestoreSessionFactory restoredSessions,
            SaveLoader saves,
            SaveCoordinator coordinator,
            Function<NewWorldRequest, GameSessionConfig> configs,
            Supplier<Instant> clock) {
        this.newSessions = Objects.requireNonNull(newSessions, "newSessions");
        this.restoredSessions = Objects.requireNonNull(restoredSessions, "restoredSessions");
        this.saves = Objects.requireNonNull(saves, "saves");
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
        this.configs = Objects.requireNonNull(configs, "configs");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public GameSession newWorld(NewWorldRequest request) {
        NewWorldRequest validated = Objects.requireNonNull(request, "request");
        GameSessionConfig config = Objects.requireNonNull(
                configs.apply(validated), "session config");
        GameSession delegate = Objects.requireNonNull(
                newSessions.create(validated, config),
                "new session");
        return new PersistenceAwareSession(
                delegate,
                coordinator,
                clock,
                Initialization.initialSave(validated));
    }

    public GameSession loadWorld(LoadWorldRequest request) {
        LoadWorldRequest validated = Objects.requireNonNull(request, "request");
        SaveArchiveReadResult read = Objects.requireNonNull(
                saves.load(validated.saveGameId()), "save read result");
        if (read.status() != SaveArchiveReadResult.Status.VALID) {
            throw new IllegalStateException("The selected save is not loadable");
        }
        SaveGameSnapshot snapshot = read.snapshot().orElseThrow();
        if (!snapshot.metadata().saveGameId().equals(validated.saveGameId())) {
            throw new IllegalStateException("The selected save identity does not match its slot");
        }
        GameSession delegate = Objects.requireNonNull(
                restoredSessions.restore(snapshot), "restored session");
        return new PersistenceAwareSession(
                delegate,
                coordinator,
                clock,
                Initialization.loadedCheckpoint());
    }

    @FunctionalInterface
    public interface NewSessionFactory {
        GameSession create(NewWorldRequest request, GameSessionConfig config);
    }

    @FunctionalInterface
    public interface RestoreSessionFactory {
        GameSession restore(SaveGameSnapshot snapshot);
    }

    @FunctionalInterface
    public interface SaveLoader {
        SaveArchiveReadResult load(SaveGameId saveGameId);
    }

    private record Initialization(NewWorldRequest initialSaveRequest) {
        private static Initialization initialSave(NewWorldRequest request) {
            return new Initialization(Objects.requireNonNull(request, "request"));
        }

        private static Initialization loadedCheckpoint() {
            return new Initialization(null);
        }

        private boolean requiresInitialSave() {
            return initialSaveRequest != null;
        }
    }

    private static final class PersistenceAwareSession implements GameSession {
        private final GameSession delegate;
        private final SaveCoordinator coordinator;
        private final Supplier<Instant> clock;
        private final Initialization initialization;
        private GameSessionState state = GameSessionState.LOADING;

        private PersistenceAwareSession(
                GameSession delegate,
                SaveCoordinator coordinator,
                Supplier<Instant> clock,
                Initialization initialization) {
            this.delegate = delegate;
            this.coordinator = coordinator;
            this.clock = clock;
            this.initialization = initialization;
        }

        @Override
        public GameSessionState state() {
            return state;
        }

        @Override
        public void pollLoad() {
            if (state != GameSessionState.LOADING) {
                return;
            }
            try {
                delegate.pollLoad();
                if (delegate.state() == GameSessionState.FAILED) {
                    state = GameSessionState.FAILED;
                    return;
                }
                if (delegate.state() != GameSessionState.READY) {
                    return;
                }
                if (initialization.requiresInitialSave()) {
                    GameSessionSaveResult result = coordinator.saveInitial(
                            delegate,
                            Objects.requireNonNull(clock.get(), "current time"),
                            initialization.initialSaveRequest());
                    if (result.status() != GameSessionSaveResult.Status.SUCCESS) {
                        delegate.close();
                        state = GameSessionState.FAILED;
                        return;
                    }
                } else {
                    coordinator.markLoaded(delegate);
                }
                state = GameSessionState.READY;
            } catch (RuntimeException | Error failure) {
                state = GameSessionState.FAILED;
                closeSuppressing(failure);
                throw failure;
            }
        }

        @Override
        public GameSessionFrame advancePlaying(
                double frameDeltaSeconds, MouseDelta look, boolean focused) {
            requireReady();
            return delegate.advancePlaying(frameDeltaSeconds, look, focused);
        }

        @Override
        public GameSessionFrame capturePaused() {
            requireReady();
            return delegate.capturePaused();
        }

        @Override
        public SessionSaveCaptureResult captureSave() {
            requireReady();
            return delegate.captureSave();
        }

        @Override
        public void markSaved(SessionPersistenceRevision revision) {
            requireReady();
            delegate.markSaved(revision);
        }

        @Override
        public GameSessionSaveResult save() {
            requireReady();
            return coordinator.save(
                    delegate, Objects.requireNonNull(clock.get(), "current time"));
        }

        @Override
        public boolean hasUnsavedChanges() {
            requireReady();
            return delegate.hasUnsavedChanges();
        }

        @Override
        public void discardFixedTime() {
            if (state == GameSessionState.LOADING || state == GameSessionState.READY) {
                delegate.discardFixedTime();
            }
        }

        @Override
        public void close() {
            if (state == GameSessionState.CLOSED) {
                return;
            }
            delegate.close();
            state = GameSessionState.CLOSED;
        }

        private void requireReady() {
            if (state != GameSessionState.READY) {
                throw new IllegalStateException("session is not ready");
            }
        }

        private void closeSuppressing(Throwable primary) {
            try {
                delegate.close();
            } catch (RuntimeException | Error cleanupFailure) {
                if (cleanupFailure != primary) {
                    primary.addSuppressed(cleanupFailure);
                }
            }
        }
    }
}

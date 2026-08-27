package com.gaia.session;

import com.gaia.save.archive.SaveArchiveReadResult;
import com.gaia.save.format.SaveGameId;
import com.gaia.save.session.SaveCoordinator;
import com.gaia.save.snapshot.SaveGameSnapshot;
import com.overlord.core.input.MouseDelta;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
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
        PreparedWorldLoad prepared = prepareLoadWorld(request);
        return prepared.publishOnOwner(prepared.readDetached());
    }

    public PreparedWorldLoad prepareLoadWorld(LoadWorldRequest request) {
        return new PreparedWorldLoad(
                Thread.currentThread(),
                Objects.requireNonNull(request, "request"),
                saves,
                restoredSessions,
                coordinator,
                clock);
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

    /** Detached archive read followed by a single owner-thread runtime publication. */
    public static final class PreparedWorldLoad {
        private final Thread ownerThread;
        private final LoadWorldRequest request;
        private final SaveLoader saves;
        private final RestoreSessionFactory restoredSessions;
        private final SaveCoordinator coordinator;
        private final Supplier<Instant> clock;
        private final AtomicBoolean readStarted = new AtomicBoolean();
        private final AtomicReference<SaveGameSnapshot> detachedSnapshot =
                new AtomicReference<>();
        private boolean published;

        private PreparedWorldLoad(
                Thread ownerThread,
                LoadWorldRequest request,
                SaveLoader saves,
                RestoreSessionFactory restoredSessions,
                SaveCoordinator coordinator,
                Supplier<Instant> clock) {
            this.ownerThread = ownerThread;
            this.request = request;
            this.saves = saves;
            this.restoredSessions = restoredSessions;
            this.coordinator = coordinator;
            this.clock = clock;
        }

        public SaveGameSnapshot readDetached() {
            if (!readStarted.compareAndSet(false, true)) {
                throw new IllegalStateException("prepared world load already read");
            }
            SaveArchiveReadResult read = Objects.requireNonNull(
                    saves.load(request.saveGameId()), "save read result");
            if (read.status() != SaveArchiveReadResult.Status.VALID) {
                throw new IllegalStateException("The selected save is not loadable");
            }
            SaveGameSnapshot snapshot = read.snapshot().orElseThrow();
            if (!snapshot.metadata().saveGameId().equals(request.saveGameId())) {
                throw new IllegalStateException(
                        "The selected save identity does not match its slot");
            }
            detachedSnapshot.set(snapshot);
            return snapshot;
        }

        public GameSession publishOnOwner(SaveGameSnapshot snapshot) {
            if (Thread.currentThread() != ownerThread) {
                throw new IllegalStateException(
                        "prepared world load publication must run on its owner thread");
            }
            if (published) {
                throw new IllegalStateException("prepared world load already published");
            }
            SaveGameSnapshot checked = Objects.requireNonNull(snapshot, "snapshot");
            SaveGameSnapshot exact = detachedSnapshot.get();
            if (exact == null || !exact.equals(checked)) {
                throw new IllegalArgumentException(
                        "detached snapshot does not match this prepared read");
            }
            if (!checked.metadata().saveGameId().equals(request.saveGameId())) {
                throw new IllegalArgumentException(
                        "detached save identity does not match prepared request");
            }
            GameSession delegate = Objects.requireNonNull(
                    restoredSessions.restore(checked), "restored session");
            published = true;
            return new PersistenceAwareSession(
                    delegate,
                    coordinator,
                    clock,
                    Initialization.loadedCheckpoint());
        }
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
        private SaveCoordinator.PreparedSave preparedInitialSave;
        private boolean delegateClosed;

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
            pollLoadResponsive();
            if (state == GameSessionState.LOADING && preparedInitialSave != null) {
                SaveCoordinator.PreparedSave prepared = preparedInitialSave;
                SaveCoordinator.AtomicSaveWrite write;
                try {
                    write = prepared.requiresDetachedWrite()
                            ? prepared.writeDetached()
                            : null;
                } catch (RuntimeException | Error failure) {
                    completeInitialSave(prepared.failOnOwner(failure));
                    return;
                }
                completeInitialSave(prepared.completeOnOwner(write));
            }
        }

        @Override
        public void pollLoadResponsive() {
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
                    if (preparedInitialSave == null) {
                        preparedInitialSave = coordinator.prepareInitialSave(
                                delegate,
                                Objects.requireNonNull(clock.get(), "current time"),
                                initialization.initialSaveRequest());
                    }
                } else {
                    coordinator.markLoaded(delegate);
                    state = GameSessionState.READY;
                }
            } catch (RuntimeException | Error failure) {
                state = GameSessionState.FAILED;
                closeSuppressing(failure);
                throw failure;
            }
        }

        @Override
        public Optional<SaveCoordinator.PreparedSave> preparedInitialSave() {
            return Optional.ofNullable(preparedInitialSave);
        }

        @Override
        public void completeInitialSave(GameSessionSaveResult result) {
            if (state != GameSessionState.LOADING || preparedInitialSave == null) {
                throw new IllegalStateException("no initial save awaits completion");
            }
            Objects.requireNonNull(result, "result");
            preparedInitialSave = null;
            if (result.status() == GameSessionSaveResult.Status.SUCCESS) {
                state = GameSessionState.READY;
            } else {
                closeDelegateOnce();
                state = GameSessionState.FAILED;
            }
        }

        @Override
        public void cancelPreparedInitialSave() {
            if (preparedInitialSave == null) {
                return;
            }
            preparedInitialSave.cancelOnOwner();
            preparedInitialSave = null;
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
        public SaveCoordinator.PreparedSave prepareDetachedSave() {
            requireReady();
            return coordinator.prepareSave(
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
            cancelPreparedInitialSave();
            closeDelegateOnce();
            state = GameSessionState.CLOSED;
        }

        private void requireReady() {
            if (state != GameSessionState.READY) {
                throw new IllegalStateException("session is not ready");
            }
        }

        private void closeSuppressing(Throwable primary) {
            try {
                closeDelegateOnce();
            } catch (RuntimeException | Error cleanupFailure) {
                if (cleanupFailure != primary) {
                    primary.addSuppressed(cleanupFailure);
                }
            }
        }

        private void closeDelegateOnce() {
            if (delegateClosed) {
                return;
            }
            delegateClosed = true;
            delegate.close();
        }
    }
}

package com.gaia.shell;

import com.gaia.audio.MusicManager;
import com.gaia.audio.MusicRoute;
import com.gaia.save.format.SaveGameId;
import com.gaia.save.session.SaveCoordinator;
import com.gaia.save.snapshot.SaveGameSnapshot;
import com.gaia.save.store.SaveDeleteResult;
import com.gaia.save.store.SaveRecoveryResult;
import com.gaia.session.GameSession;
import com.gaia.session.GameSessionFrame;
import com.gaia.session.GameSessionLauncher;
import com.gaia.session.GameSessionSaveResult;
import com.gaia.session.GameSessionState;
import com.gaia.session.NewWorldRequest;
import com.gaia.shell.ui.ProductScreenInputController;
import com.gaia.shell.ui.ProductScreenPresenter;
import com.gaia.shell.ui.ProductUiLayout;
import com.gaia.shell.world.NewWorldDraftController;
import com.gaia.shell.world.WorldSlotsController;
import com.overlord.core.input.InputManager;
import com.overlord.core.input.UiInputSnapshot;
import com.overlord.config.GameConfig;
import com.overlord.renderer.ui.UiLayoutContext;
import java.util.Objects;
import java.util.Optional;
import java.util.function.DoubleSupplier;
import java.util.function.Supplier;

/** The sole product-level owner loop around zero or one active game session. */
public final class ProductLoop {
    private final InputManager inputManager;
    private final ProductShellController shell;
    private final ProductScreenInputController screenInput;
    private final ProductScreenPresenter presenter;
    private final Supplier<? extends GameSession> sessionLauncher;
    private final Optional<PersistenceServices> persistenceServices;
    private final DoubleSupplier frameDeltaSource;
    private final FrameHost host;
    private final Optional<MusicManager> musicManager;
    private final Runnable closePolicy;
    private final Thread ownerThread;
    private final ProductOperationRunner operations;

    private GameSession session;
    private boolean cursorCaptured;
    private boolean lastFocused;
    private boolean exitRequested;
    private boolean musicManagerCloseInvoked;
    private boolean closePolicyInvoked;
    private long nextUiSampleId;
    private ProductLifecycleIntent.Save pendingSave;
    private NewWorldRequest pendingNewWorld;
    private GameSessionLauncher.PreparedWorldLoad pendingWorldLoad;
    private GameSessionLauncher.PreparedWorldLoad activeWorldLoad;
    private SaveCoordinator.PreparedSave activePreparedSave;
    private ProductLifecycleIntent.Save activeSave;
    private boolean activeInitialSave;
    private long activeCreateGeneration;
    private long restoringLoadGeneration;
    private long activeSaveGeneration;

    ProductLoop(
            InputManager inputManager,
            ProductShellController shell,
            ProductScreenInputController screenInput,
            ProductScreenPresenter presenter,
            Supplier<? extends GameSession> sessionLauncher,
            DoubleSupplier frameDeltaSource,
            FrameHost host) {
        this(
                inputManager,
                shell,
                screenInput,
                presenter,
                sessionLauncher,
                Optional.empty(),
                frameDeltaSource,
                host,
                Optional.empty(),
                () -> {});
    }

    ProductLoop(
            InputManager inputManager,
            ProductShellController shell,
            ProductScreenInputController screenInput,
            ProductScreenPresenter presenter,
            Supplier<? extends GameSession> sessionLauncher,
            DoubleSupplier frameDeltaSource,
            FrameHost host,
            Runnable closePolicy) {
        this(
                inputManager,
                shell,
                screenInput,
                presenter,
                sessionLauncher,
                Optional.empty(),
                frameDeltaSource,
                host,
                Optional.empty(),
                closePolicy);
    }

    public ProductLoop(
            InputManager inputManager,
            ProductShellController shell,
            ProductScreenInputController screenInput,
            ProductScreenPresenter presenter,
            Supplier<? extends GameSession> sessionLauncher,
            DoubleSupplier frameDeltaSource,
            FrameHost host,
            MusicManager musicManager,
            Runnable closePolicy) {
        this(
                inputManager,
                shell,
                screenInput,
                presenter,
                sessionLauncher,
                Optional.empty(),
                frameDeltaSource,
                host,
                Optional.of(Objects.requireNonNull(musicManager, "musicManager")),
                closePolicy);
    }

    ProductLoop(
            InputManager inputManager,
            ProductShellController shell,
            ProductScreenInputController screenInput,
            ProductScreenPresenter presenter,
            PersistenceServices persistenceServices,
            DoubleSupplier frameDeltaSource,
            FrameHost host) {
        this(
                inputManager,
                shell,
                screenInput,
                presenter,
                () -> {
                    throw new IllegalStateException("legacy session launcher is unavailable");
                },
                Optional.of(Objects.requireNonNull(
                        persistenceServices, "persistenceServices")),
                frameDeltaSource,
                host,
                Optional.empty(),
                () -> {});
    }

    public ProductLoop(
            InputManager inputManager,
            ProductShellController shell,
            ProductScreenInputController screenInput,
            ProductScreenPresenter presenter,
            PersistenceServices persistenceServices,
            DoubleSupplier frameDeltaSource,
            FrameHost host,
            MusicManager musicManager,
            Runnable closePolicy) {
        this(
                inputManager,
                shell,
                screenInput,
                presenter,
                () -> {
                    throw new IllegalStateException("legacy session launcher is unavailable");
                },
                Optional.of(Objects.requireNonNull(
                        persistenceServices, "persistenceServices")),
                frameDeltaSource,
                host,
                Optional.of(Objects.requireNonNull(musicManager, "musicManager")),
                closePolicy);
    }

    private ProductLoop(
            InputManager inputManager,
            ProductShellController shell,
            ProductScreenInputController screenInput,
            ProductScreenPresenter presenter,
            Supplier<? extends GameSession> sessionLauncher,
            Optional<PersistenceServices> persistenceServices,
            DoubleSupplier frameDeltaSource,
            FrameHost host,
            Optional<MusicManager> musicManager,
            Runnable closePolicy) {
        this.inputManager = Objects.requireNonNull(inputManager, "inputManager");
        this.shell = Objects.requireNonNull(shell, "shell");
        this.screenInput = Objects.requireNonNull(screenInput, "screenInput");
        this.presenter = Objects.requireNonNull(presenter, "presenter");
        this.sessionLauncher = Objects.requireNonNull(sessionLauncher, "sessionLauncher");
        this.persistenceServices = Objects.requireNonNull(
                persistenceServices, "persistenceServices");
        this.frameDeltaSource = Objects.requireNonNull(frameDeltaSource, "frameDeltaSource");
        this.host = Objects.requireNonNull(host, "host");
        this.musicManager = Objects.requireNonNull(musicManager, "musicManager");
        this.closePolicy = Objects.requireNonNull(closePolicy, "closePolicy");
        ownerThread = Thread.currentThread();
        operations = ProductOperationRunner.createForOwner(
                ownerThread, "gaia-product-io");
        lastFocused = inputManager.isWindowFocused();
        inputManager.invalidateGameplayInput();
        host.setCursorCaptured(false);
    }

    public void run() {
        assertOwnerThread();
        Throwable primaryFailure = null;
        try {
            while (!exitRequested && !host.shouldClose()) {
                runFrame(frameDeltaSource.getAsDouble());
            }
        } catch (RuntimeException | Error failure) {
            primaryFailure = failure;
            throw failure;
        } finally {
            closeProduct(primaryFailure);
        }
    }

    public void runFrame(double frameDeltaSeconds) {
        assertOwnerThread();
        if (!Double.isFinite(frameDeltaSeconds) || frameDeltaSeconds < 0.0d) {
            throw new IllegalArgumentException(
                    "frameDeltaSeconds must be finite and non-negative");
        }

        boolean wasPlayingEligible = playingEligible(
                shell.snapshot(), lastFocused, cursorCaptured);

        host.pollEvents();
        host.beginFrame(frameDeltaSeconds);
        shell.advanceOperationAnimation(frameDeltaSeconds);
        Optional<OperationProgressSnapshot> workerProgress = operations.progress();
        if (workerProgress.isPresent()) {
            shell.updateOperationProgress(workerProgress.orElseThrow());
        }
        drainProductOperation();
        startDeferredProductOperation();
        boolean focusLostDuringPoll =
                inputManager.consumeMouseInteractionInvalidation();
        UiInputSnapshot input = inputManager.captureUiInput(nextSampleId());
        boolean executingQueuedSave = activeSave != null || pendingSave != null;
        ProductShellSnapshot routeBeforeInput = shell.snapshot();
        ProductUiLayout inputLayout = presenter.present(
                routeBeforeInput, host.layoutContext());
        Optional<ScreenCommand> command = executingQueuedSave
                ? Optional.empty()
                : routeProductInput(input, inputLayout, routeBeforeInput);

        if (persistenceServices.isEmpty()
                && routeBeforeInput.screen() == ScreenId.MAIN_MENU
                && command.orElse(null) instanceof ScreenCommand.OpenNewWorldSetup) {
            shell.startLegacySession();
            startSession(Objects.requireNonNull(
                    sessionLauncher.get(), "launched game session"));
            command = Optional.empty();
        }

        ProductLifecycleIntent lifecycleIntent = command
                .map(value -> shell.handle(value, dirtyFor(value)))
                .orElseGet(() -> routeShortcut(input, routeBeforeInput));
        applyLifecycleIntent(lifecycleIntent);
        pollActiveLoad();
        if ((!input.focused() || focusLostDuringPoll)
                && shell.snapshot().screen() == ScreenId.PLAYING) {
            shell.togglePlaying();
        }

        ProductShellSnapshot routeAfterTransitions = shell.snapshot();
        updateMusic(routeAfterTransitions, input.focused(), frameDeltaSeconds);
        boolean isPlayingEligible = playingEligible(
                routeAfterTransitions, input.focused(), true);
        boolean enteredPlayingThisFrame =
                !wasPlayingEligible && isPlayingEligible;
        boolean focusChanged = input.focused() != lastFocused;
        if (wasPlayingEligible != isPlayingEligible) {
            applyPlayingEligibilityBoundary(isPlayingEligible);
        } else if (focusChanged
                || !routeBeforeInput.equals(routeAfterTransitions)) {
            inputManager.invalidateGameplayInput();
        }
        if (!isPlayingEligible) {
            inputManager.discardFixedInputEdges();
        }
        lastFocused = input.focused();

        GameSessionFrame sessionFrame = captureSessionFrame(
                enteredPlayingThisFrame ? 0.0d : frameDeltaSeconds,
                isPlayingEligible);
        ProductUiLayout productPresentation = presenter.presentFocused(
                shell.snapshot(), host.layoutContext(), screenInput.highlightedControl());
        if (sessionFrame != null) {
            host.renderSession(sessionFrame);
        }
        host.renderProduct(productPresentation);
        host.swapBuffers();
    }

    private void updateMusic(
            ProductShellSnapshot snapshot,
            boolean focused,
            double frameDeltaSeconds) {
        if (musicManager.isEmpty()) {
            return;
        }
        MusicManager manager = musicManager.orElseThrow();
        manager.setFocused(focused);
        manager.requestRoute(musicRoute(snapshot));
        manager.update(frameDeltaSeconds);
    }

    private static MusicRoute musicRoute(ProductShellSnapshot snapshot) {
        return switch (snapshot.screen()) {
            case MAIN_MENU, NEW_WORLD_SETUP, WORLD_SLOTS -> MusicRoute.MAIN_MENU;
            case LOADING, PLAYING -> MusicRoute.GAMEPLAY;
            case PAUSED, SAVING -> MusicRoute.PAUSED;
            case SETTINGS ->
                    snapshot.returnTarget().orElseThrow() == ScreenReturnTarget.PAUSED
                            ? MusicRoute.SETTINGS_FROM_PAUSE
                            : MusicRoute.MAIN_MENU;
            case CONTROLS ->
                    snapshot.returnTarget().orElseThrow() == ScreenReturnTarget.PAUSED
                            ? MusicRoute.CONTROLS_FROM_PAUSE
                            : MusicRoute.MAIN_MENU;
        };
    }

    private ProductLifecycleIntent routeShortcut(
            UiInputSnapshot input,
            ProductShellSnapshot beforeInput) {
        if (!input.focused()) {
            if (beforeInput.screen() == ScreenId.PLAYING
                    && beforeInput.modal().isEmpty()) {
                shell.togglePlaying();
            }
            return ProductLifecycleIntent.none();
        }
        if (beforeInput.modal().isPresent()) {
            return ProductLifecycleIntent.none();
        }
        if (beforeInput.screen() == ScreenId.PLAYING
                && (input.isKeyPressed(GameConfig.Input.KEY_CLOSE)
                        || input.isKeyPressed(GameConfig.Input.KEY_CURSOR_CAPTURE))) {
            shell.togglePlaying();
        } else if (beforeInput.screen() == ScreenId.PAUSED
                && input.isKeyPressed(GameConfig.Input.KEY_CURSOR_CAPTURE)) {
            shell.togglePlaying();
        }
        return ProductLifecycleIntent.none();
    }

    private Optional<ScreenCommand> routeProductInput(
            UiInputSnapshot input,
            ProductUiLayout layout,
            ProductShellSnapshot route) {
        if (persistenceServices.isEmpty()) {
            return screenInput.route(input, layout);
        }
        PersistenceServices services = persistenceServices.orElseThrow();
        return switch (route.screen()) {
            case NEW_WORLD_SETUP -> screenInput.routeNewWorld(
                    input,
                    layout,
                    services.newWorldDraft(),
                    services.saveGameIds());
            case WORLD_SLOTS -> screenInput.routeWorldSlots(
                    input, layout, services.worldSlots());
            default -> screenInput.route(input, layout);
        };
    }

    private boolean dirtyFor(ScreenCommand command) {
        if (!(command instanceof ScreenCommand.ReturnToMainMenu)
                || persistenceServices.isEmpty()
                || session == null) {
            return true;
        }
        return session.hasUnsavedChanges();
    }

    private void applyLifecycleIntent(ProductLifecycleIntent intent) {
        if (intent instanceof ProductLifecycleIntent.None) {
            return;
        }
        if (intent instanceof ProductLifecycleIntent.StartNewWorld start) {
            PersistenceServices services = requirePersistenceServices();
            if (pendingNewWorld != null || pendingWorldLoad != null) {
                throw new IllegalStateException("Cannot queue a second world launch");
            }
            pendingNewWorld = start.request();
            services.newWorldDraft().reset();
            return;
        }
        if (intent instanceof ProductLifecycleIntent.LoadWorld load) {
            if (pendingNewWorld != null || pendingWorldLoad != null) {
                throw new IllegalStateException("Cannot queue a second world launch");
            }
            pendingWorldLoad = requirePersistenceServices().sessions()
                    .prepareLoadWorld(load.request());
            return;
        }
        if (intent instanceof ProductLifecycleIntent.Save save) {
            if (session == null || pendingSave != null) {
                throw new IllegalStateException("Cannot queue this session save");
            }
            pendingSave = save;
            inputManager.invalidateGameplayInput();
            inputManager.discardFixedInputEdges();
            return;
        }
        if (intent instanceof ProductLifecycleIntent.DeleteWorld delete) {
            PersistenceServices services = requirePersistenceServices();
            SaveDeleteResult result = services.worldSlotOperations()
                    .delete(delete.saveGameId());
            services.worldSlots().refresh();
            if (result.status() != SaveDeleteResult.Status.SUCCESS
                    && result.status()
                            != SaveDeleteResult.Status.DELETED_WITH_CLEANUP_WARNING) {
                shell.operationFailed();
            }
            return;
        }
        if (intent instanceof ProductLifecycleIntent.RecoverBackup recover) {
            PersistenceServices services = requirePersistenceServices();
            SaveRecoveryResult result = services.worldSlotOperations()
                    .recover(recover.saveGameId());
            services.worldSlots().refresh();
            if (result.status() != SaveRecoveryResult.Status.SUCCESS) {
                shell.operationFailed();
            }
            return;
        }
        if (intent instanceof ProductLifecycleIntent.CloseActiveSession) {
            cancelActiveLoadIfPossible();
            pendingNewWorld = null;
            pendingWorldLoad = null;
            closeActiveSession(null);
            return;
        }
        if (intent instanceof ProductLifecycleIntent.ExitProduct) {
            exitRequested = true;
            return;
        }
        throw new IllegalStateException(
                "Lifecycle intent is not wired into ProductLoop yet: " + intent);
    }

    private void startSession(GameSession launched) {
        if (session != null) {
            throw new IllegalStateException(
                    "Cannot start a second active game session");
        }
        session = Objects.requireNonNull(launched, "launched game session");
    }

    private void startDeferredProductOperation() {
        if (operations.acceptedCount() != 0) {
            return;
        }
        if (pendingWorldLoad != null) {
            activeWorldLoad = pendingWorldLoad;
            pendingWorldLoad = null;
            GameSessionLauncher.PreparedWorldLoad detached = activeWorldLoad;
            OperationProgressSnapshot initial = OperationProgressSnapshot.indeterminate(
                    OperationProgressSnapshot.Kind.LOAD_WORLD,
                    "READING SAVE",
                    "Validating the selected world",
                    true);
            operations.start(initial, context -> detached.readDetached());
            shell.updateOperationProgress(operations.progress().orElseThrow());
            return;
        }
        if (pendingNewWorld != null) {
            NewWorldRequest request = pendingNewWorld;
            pendingNewWorld = null;
            activeCreateGeneration = operations.startOwner(
                    OperationProgressSnapshot.indeterminate(
                    OperationProgressSnapshot.Kind.CREATE_WORLD,
                    "CREATING WORLD",
                    "Starting deterministic terrain generation",
                    true));
            publishRunnerProgress();
            startSession(requirePersistenceServices().sessions().newWorld(request));
            return;
        }
        if (pendingSave != null) {
            if (session == null) {
                throw new IllegalStateException("Cannot save without an active session");
            }
            activeSave = pendingSave;
            pendingSave = null;
            activePreparedSave = session.prepareDetachedSave();
            OperationProgressSnapshot.Kind kind =
                    activeSave.policy() == ProductLifecycleIntent.SavePolicy.SAVE_AND_QUIT
                            ? OperationProgressSnapshot.Kind.SAVE_AND_QUIT
                            : OperationProgressSnapshot.Kind.SAVE_WORLD;
            OperationProgressSnapshot initial = OperationProgressSnapshot.indeterminate(
                    kind,
                    "WRITING CHECKPOINT",
                    "Publishing world data atomically",
                    false);
            activeSaveGeneration = operations.startOwner(initial);
            publishRunnerProgress();
            if (!activePreparedSave.requiresDetachedWrite()) {
                GameSessionSaveResult result = activePreparedSave.completeOnOwner(null);
                operations.completeOwnerWork(activeSaveGeneration, result);
                activePreparedSave = null;
                finishRegularSaveOperation(result, null);
                return;
            }
            SaveCoordinator.PreparedSave detached = activePreparedSave;
            if (!operations.submitWorker(
                    activeSaveGeneration,
                    context -> detached.writeDetached())) {
                throw new IllegalStateException("save operation rejected detached work");
            }
        }
    }

    private void drainProductOperation() {
        if (restoringLoadGeneration != 0L) {
            return;
        }
        Optional<ProductOperationRunner.Completion> completion =
                operations.peekCompletion();
        if (completion.isEmpty()) {
            return;
        }
        ProductOperationRunner.Completion done = completion.orElseThrow();
        if (activeWorldLoad != null) {
            GameSessionLauncher.PreparedWorldLoad prepared = activeWorldLoad;
            activeWorldLoad = null;
            if (done.canceled()) {
                publishRunnerProgress();
                operations.releaseTerminal(done.generation());
                return;
            }
            if (done.failure().isPresent()) {
                operations.finishFailure(
                        done.generation(), done.failure().orElseThrow());
                publishRunnerProgress();
                operations.releaseTerminal(done.generation());
                shell.loadingFailed();
                return;
            }
            SaveGameSnapshot snapshot = (SaveGameSnapshot) done.value().orElseThrow();
            try {
                startSession(prepared.publishOnOwner(snapshot));
            } catch (RuntimeException | Error publicationFailure) {
                operations.finishFailure(done.generation(), publicationFailure);
                publishRunnerProgress();
                operations.releaseTerminal(done.generation());
                shell.loadingFailed();
                throw publicationFailure;
            }
            operations.ownerUpdate(done.generation(), OperationProgressUpdate.indeterminate(
                    1,
                    "RESTORING SESSION",
                    "Publishing validated runtime state",
                    false));
            publishRunnerProgress();
            restoringLoadGeneration = done.generation();
            return;
        }
        if (activePreparedSave != null) {
            SaveCoordinator.PreparedSave prepared = activePreparedSave;
            activePreparedSave = null;
            GameSessionSaveResult result;
            if (done.failure().isPresent()) {
                result = prepared.failDetachedOnOwner(
                        done.failure().orElseThrow());
            } else if (done.canceled()) {
                prepared.cancelOnOwner();
                result = GameSessionSaveResult.failed(
                        GameSessionSaveResult.Status.WRITE_FAILED,
                        java.util.List.of(com.gaia.save.archive.SaveDiagnostic.of(
                                "save-write.canceled", "Save was canceled before publication")));
            } else {
                result = prepared.completeOnOwner(
                        (SaveCoordinator.AtomicSaveWrite) done.value().orElseThrow());
            }
            if (activeInitialSave) {
                activeInitialSave = false;
                session.completeInitialSave(result);
                if (session.state() == GameSessionState.FAILED) {
                    finishCreateOperation(result, done.failure().orElse(null));
                    handleActiveLoadFailure(null);
                }
            } else {
                finishRegularSaveOperation(result, done.failure().orElse(null));
            }
        }
    }

    private void finishRegularSaveOperation(
            GameSessionSaveResult result, Throwable failure) {
        finishRunnerOperation(activeSaveGeneration, result, failure);
        activeSaveGeneration = 0L;
        finishRegularSave(result);
    }

    private void finishCreateOperation(
            GameSessionSaveResult result, Throwable failure) {
        finishRunnerOperation(activeCreateGeneration, result, failure);
        activeCreateGeneration = 0L;
    }

    private void finishRunnerOperation(
            long generation,
            GameSessionSaveResult result,
            Throwable failure) {
        if (generation == 0L) {
            return;
        }
        if (result.status() == GameSessionSaveResult.Status.SUCCESS) {
            operations.finishSuccess(generation);
        } else {
            Throwable terminalFailure = failure != null
                    ? failure
                    : new IllegalStateException(
                            "owner publication failed: " + result.status());
            operations.finishFailure(generation, terminalFailure);
        }
        publishRunnerProgress();
        if (!operations.releaseTerminal(generation)) {
            throw new IllegalStateException(
                    "terminal operation did not release its capacity token");
        }
    }

    private void publishRunnerProgress() {
        operations.progress().ifPresent(shell::updateOperationProgress);
    }

    private void finishRegularSave(GameSessionSaveResult result) {
        ProductLifecycleIntent.Save save = Objects.requireNonNull(
                activeSave, "activeSave");
        activeSave = null;
        if (result.status() == GameSessionSaveResult.Status.SUCCESS) {
            applyLifecycleIntent(shell.savingSucceeded(save.policy()));
        } else {
            shell.savingFailed();
        }
    }

    private void cancelActiveLoadIfPossible() {
        if (activeWorldLoad == null
                && activeCreateGeneration == 0L
                && restoringLoadGeneration == 0L) {
            return;
        }
        operations.activeGeneration().ifPresent(generation -> {
            if (operations.cancel(generation)) {
                operations.releaseTerminal(generation);
            }
        });
        activeWorldLoad = null;
        activeCreateGeneration = 0L;
        restoringLoadGeneration = 0L;
    }

    private PersistenceServices requirePersistenceServices() {
        return persistenceServices.orElseThrow(() ->
                new IllegalStateException("Persistence services are unavailable"));
    }

    private void pollActiveLoad() {
        if (session == null) {
            return;
        }
        if (session.state() == GameSessionState.READY) {
            finishSuccessfulRestoreIfPresent();
            if (activeCreateGeneration != 0L
                    && activePreparedSave == null) {
                operations.completeOwnerWork(activeCreateGeneration, session);
                operations.finishSuccess(activeCreateGeneration);
                publishRunnerProgress();
                operations.releaseTerminal(activeCreateGeneration);
                activeCreateGeneration = 0L;
            }
            shell.loadingSucceeded();
            return;
        }
        if (session.state() != GameSessionState.LOADING) {
            return;
        }
        try {
            session.pollLoadResponsive();
        } catch (RuntimeException loadFailure) {
            handleActiveLoadFailure(loadFailure);
            return;
        }
        if (session.state() == GameSessionState.LOADING
                && session.preparedInitialSave().isPresent()
                && activePreparedSave == null
                && activeCreateGeneration != 0L) {
            activePreparedSave = session.preparedInitialSave().orElseThrow();
            activeInitialSave = true;
            OperationProgressSnapshot initial = OperationProgressSnapshot.indeterminate(
                    OperationProgressSnapshot.Kind.CREATE_WORLD,
                    1,
                    "SAVING NEW WORLD",
                    "Publishing the initial checkpoint",
                    false);
            operations.ownerUpdate(
                    activeCreateGeneration,
                    OperationProgressUpdate.indeterminate(
                            1,
                            initial.phase(),
                            initial.status(),
                            false));
            publishRunnerProgress();
            if (!activePreparedSave.requiresDetachedWrite()) {
                GameSessionSaveResult result = activePreparedSave.completeOnOwner(null);
                operations.completeOwnerWork(activeCreateGeneration, result);
                activePreparedSave = null;
                activeInitialSave = false;
                session.completeInitialSave(result);
                finishCreateOperation(result, null);
            } else {
                SaveCoordinator.PreparedSave detached = activePreparedSave;
                if (!operations.submitWorker(
                        activeCreateGeneration,
                        context -> detached.writeDetached())) {
                    throw new IllegalStateException(
                            "initial save operation rejected detached work");
                }
            }
        }
        if (session.state() == GameSessionState.READY) {
            finishSuccessfulRestoreIfPresent();
            if (activeCreateGeneration != 0L
                    && activePreparedSave == null) {
                operations.completeOwnerWork(activeCreateGeneration, session);
                operations.finishSuccess(activeCreateGeneration);
                publishRunnerProgress();
                operations.releaseTerminal(activeCreateGeneration);
                activeCreateGeneration = 0L;
            }
            shell.loadingSucceeded();
        } else if (session.state() == GameSessionState.FAILED) {
            handleActiveLoadFailure(null);
        }
    }

    private void finishSuccessfulRestoreIfPresent() {
        long generation = restoringLoadGeneration;
        if (generation == 0L) {
            return;
        }
        if (!operations.finishSuccess(generation)) {
            throw new IllegalStateException(
                    "successful load could not publish terminal progress");
        }
        publishRunnerProgress();
        if (!operations.releaseTerminal(generation)) {
            throw new IllegalStateException(
                    "successful load could not release its capacity token");
        }
        restoringLoadGeneration = 0L;
    }

    private void handleActiveLoadFailure(RuntimeException loadFailure) {
        try {
            closeActiveSession(null);
        } catch (RuntimeException | Error cleanupFailure) {
            finishFailedLoadingOperation(
                    loadFailure != null ? loadFailure : cleanupFailure);
            if (loadFailure != null && cleanupFailure != loadFailure) {
                loadFailure.addSuppressed(cleanupFailure);
                throw loadFailure;
            }
            throw cleanupFailure;
        }
        finishFailedLoadingOperation(
                loadFailure != null
                        ? loadFailure
                        : new IllegalStateException("session load failed"));
        if (loadFailure != null && loadFailure.getSuppressed().length > 0) {
            throw loadFailure;
        }
        shell.loadingFailed();
    }

    private void finishFailedLoadingOperation(Throwable failure) {
        long generation = restoringLoadGeneration != 0L
                ? restoringLoadGeneration
                : activeCreateGeneration;
        if (generation == 0L) {
            return;
        }
        if (!operations.finishFailure(generation, failure)) {
            throw new IllegalStateException(
                    "failed loading operation could not publish terminal progress");
        }
        publishRunnerProgress();
        if (!operations.releaseTerminal(generation)) {
            throw new IllegalStateException(
                    "failed loading operation could not release its capacity token");
        }
        if (restoringLoadGeneration == generation) {
            restoringLoadGeneration = 0L;
        }
        if (activeCreateGeneration == generation) {
            activeCreateGeneration = 0L;
        }
    }

    private GameSessionFrame captureSessionFrame(
            double frameDeltaSeconds,
            boolean playingEligible) {
        if (session == null) {
            return null;
        }
        if (session.state() != GameSessionState.READY) {
            return null;
        }
        if (playingEligible) {
            return session.advancePlaying(
                    frameDeltaSeconds,
                    inputManager.consumeMouseDelta(),
                    true);
        }
        return session.capturePaused();
    }

    private void applyPlayingEligibilityBoundary(boolean enteringPlaying) {
        inputManager.invalidateGameplayInput();
        if (session != null) {
            session.discardFixedTime();
        }
        cursorCaptured = enteringPlaying;
        host.setCursorCaptured(enteringPlaying);
    }

    private static boolean playingEligible(
            ProductShellSnapshot snapshot,
            boolean focused,
            boolean cursorCaptured) {
        return snapshot.screen() == ScreenId.PLAYING
                && snapshot.modal().isEmpty()
                && focused
                && cursorCaptured;
    }

    private long nextSampleId() {
        if (nextUiSampleId == Long.MAX_VALUE) {
            throw new IllegalStateException("Product UI input sample id exhausted");
        }
        return nextUiSampleId++;
    }

    private void assertOwnerThread() {
        if (Thread.currentThread() != ownerThread) {
            throw new IllegalStateException(
                    "ProductLoop must run on its GLFW/OpenGL owner thread");
        }
    }

    private void closeActiveSession(Throwable primaryFailure) {
        GameSession current = session;
        if (current == null) {
            return;
        }
        try {
            current.close();
            session = null;
            persistenceServices.ifPresent(services -> services.worldSlots().refresh());
        } catch (RuntimeException | Error cleanupFailure) {
            if (primaryFailure == null) {
                throw cleanupFailure;
            }
            primaryFailure.addSuppressed(cleanupFailure);
        }
    }

    private void closeProduct(Throwable primaryFailure) {
        Throwable firstFailure = primaryFailure;
        boolean operationWorkerStopped = false;
        try {
            operations.close();
            operationWorkerStopped = true;
            if (activePreparedSave != null) {
                if (operations.peekCompletion().isPresent()) {
                    drainProductOperation();
                } else if (activeInitialSave && session != null) {
                    session.cancelPreparedInitialSave();
                    activePreparedSave = null;
                    activeInitialSave = false;
                } else {
                    activePreparedSave.cancelOnOwner();
                    activePreparedSave = null;
                    activeSave = null;
                    activeInitialSave = false;
                }
            }
        } catch (RuntimeException | Error operationCloseFailure) {
            firstFailure = mergeFailure(firstFailure, operationCloseFailure);
        }
        if (operationWorkerStopped) {
            try {
                closeActiveSession(null);
            } catch (RuntimeException | Error sessionCloseFailure) {
                firstFailure = mergeFailure(firstFailure, sessionCloseFailure);
            }
        }
        try {
            closeMusicManagerOnce();
        } catch (RuntimeException | Error musicCloseFailure) {
            firstFailure = mergeFailure(firstFailure, musicCloseFailure);
        }
        try {
            closeFinalPolicyOnce();
        } catch (RuntimeException | Error settingsCloseFailure) {
            firstFailure = mergeFailure(firstFailure, settingsCloseFailure);
        }
        if (primaryFailure == null && firstFailure != null) {
            rethrow(firstFailure);
        }
    }

    private void closeMusicManagerOnce() {
        if (musicManagerCloseInvoked) {
            return;
        }
        musicManagerCloseInvoked = true;
        musicManager.ifPresent(MusicManager::close);
    }

    private void closeFinalPolicyOnce() {
        if (closePolicyInvoked) {
            return;
        }
        closePolicyInvoked = true;
        closePolicy.run();
    }

    private static Throwable mergeFailure(Throwable firstFailure, Throwable nextFailure) {
        if (firstFailure == null) {
            return nextFailure;
        }
        if (firstFailure != nextFailure) {
            firstFailure.addSuppressed(nextFailure);
        }
        return firstFailure;
    }

    private static void rethrow(Throwable failure) {
        if (failure instanceof RuntimeException runtimeFailure) {
            throw runtimeFailure;
        }
        throw (Error) failure;
    }

    /** Product persistence owners shared by input, presentation, and lifecycle orchestration. */
    public record PersistenceServices(
            GameSessionLauncher sessions,
            NewWorldDraftController newWorldDraft,
            WorldSlotsController worldSlots,
            Supplier<SaveGameId> saveGameIds,
            WorldSlotOperations worldSlotOperations) {
        public PersistenceServices {
            Objects.requireNonNull(sessions, "sessions");
            Objects.requireNonNull(newWorldDraft, "newWorldDraft");
            Objects.requireNonNull(worldSlots, "worldSlots");
            Objects.requireNonNull(saveGameIds, "saveGameIds");
            Objects.requireNonNull(worldSlotOperations, "worldSlotOperations");
        }
    }

    /** Explicit root-confined mutations available to the World Slots route. */
    public interface WorldSlotOperations {
        SaveDeleteResult delete(SaveGameId saveGameId);

        SaveRecoveryResult recover(SaveGameId saveGameId);
    }

    /** Owner-thread host for GLFW polling and immutable renderer presentation. */
    public interface FrameHost {
        boolean shouldClose();

        void pollEvents();

        default void beginFrame(double frameDeltaSeconds) {}

        UiLayoutContext layoutContext();

        void setCursorCaptured(boolean captured);

        void renderSession(GameSessionFrame frame);

        void renderProduct(ProductUiLayout layout);

        void swapBuffers();
    }
}

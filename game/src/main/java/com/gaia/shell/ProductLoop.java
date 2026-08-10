package com.gaia.shell;

import com.gaia.audio.MusicManager;
import com.gaia.audio.MusicRoute;
import com.gaia.session.GameSession;
import com.gaia.session.GameSessionFrame;
import com.gaia.session.GameSessionState;
import com.gaia.shell.ProductShellController.LifecycleIntent;
import com.gaia.shell.ui.ProductScreenInputController;
import com.gaia.shell.ui.ProductScreenPresenter;
import com.gaia.shell.ui.ProductUiLayout;
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
    private final DoubleSupplier frameDeltaSource;
    private final FrameHost host;
    private final Optional<MusicManager> musicManager;
    private final Runnable closePolicy;
    private final Thread ownerThread;

    private GameSession session;
    private boolean cursorCaptured;
    private boolean lastFocused;
    private boolean exitRequested;
    private boolean musicManagerCloseInvoked;
    private boolean closePolicyInvoked;
    private long nextUiSampleId;

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
            DoubleSupplier frameDeltaSource,
            FrameHost host,
            Optional<MusicManager> musicManager,
            Runnable closePolicy) {
        this.inputManager = Objects.requireNonNull(inputManager, "inputManager");
        this.shell = Objects.requireNonNull(shell, "shell");
        this.screenInput = Objects.requireNonNull(screenInput, "screenInput");
        this.presenter = Objects.requireNonNull(presenter, "presenter");
        this.sessionLauncher = Objects.requireNonNull(sessionLauncher, "sessionLauncher");
        this.frameDeltaSource = Objects.requireNonNull(frameDeltaSource, "frameDeltaSource");
        this.host = Objects.requireNonNull(host, "host");
        this.musicManager = Objects.requireNonNull(musicManager, "musicManager");
        this.closePolicy = Objects.requireNonNull(closePolicy, "closePolicy");
        ownerThread = Thread.currentThread();
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
        boolean focusLostDuringPoll =
                inputManager.consumeMouseInteractionInvalidation();
        UiInputSnapshot input = inputManager.captureUiInput(nextSampleId());
        ProductShellSnapshot routeBeforeInput = shell.snapshot();
        ProductUiLayout inputLayout = presenter.present(
                routeBeforeInput, host.layoutContext());
        Optional<ScreenCommand> command = screenInput.route(input, inputLayout);

        LifecycleIntent lifecycleIntent = command
                .map(shell::handle)
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
        ProductUiLayout productPresentation = presenter.present(
                shell.snapshot(), host.layoutContext(), screenInput.presentationHighlight());
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
            case MAIN_MENU -> MusicRoute.MAIN_MENU;
            case LOADING, PLAYING -> MusicRoute.GAMEPLAY;
            case PAUSED -> MusicRoute.PAUSED;
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

    private LifecycleIntent routeShortcut(
            UiInputSnapshot input,
            ProductShellSnapshot beforeInput) {
        if (!input.focused()) {
            if (beforeInput.screen() == ScreenId.PLAYING
                    && beforeInput.modal().isEmpty()) {
                shell.togglePlaying();
            }
            return LifecycleIntent.NONE;
        }
        if (beforeInput.modal().isPresent()) {
            return LifecycleIntent.NONE;
        }
        if (beforeInput.screen() == ScreenId.PLAYING
                && (input.isKeyPressed(GameConfig.Input.KEY_CLOSE)
                        || input.isKeyPressed(GameConfig.Input.KEY_CURSOR_CAPTURE))) {
            shell.togglePlaying();
        } else if (beforeInput.screen() == ScreenId.PAUSED
                && input.isKeyPressed(GameConfig.Input.KEY_CURSOR_CAPTURE)) {
            shell.togglePlaying();
        }
        return LifecycleIntent.NONE;
    }

    private void applyLifecycleIntent(LifecycleIntent intent) {
        switch (intent) {
            case NONE -> {
                // Route-only command.
            }
            case START_NEW_SESSION -> {
                if (session != null) {
                    throw new IllegalStateException(
                            "Cannot start a second active game session");
                }
                session = Objects.requireNonNull(
                        sessionLauncher.get(), "launched game session");
            }
            case CLOSE_ACTIVE_SESSION -> closeActiveSession(null);
            case EXIT_PRODUCT -> exitRequested = true;
        }
    }

    private void pollActiveLoad() {
        if (session == null || session.state() != GameSessionState.LOADING) {
            return;
        }
        try {
            session.pollLoad();
        } catch (RuntimeException loadFailure) {
            handleActiveLoadFailure(loadFailure);
            return;
        }
        if (session.state() == GameSessionState.READY) {
            shell.loadingSucceeded();
        } else if (session.state() == GameSessionState.FAILED) {
            handleActiveLoadFailure(null);
        }
    }

    private void handleActiveLoadFailure(RuntimeException loadFailure) {
        try {
            closeActiveSession(null);
        } catch (RuntimeException | Error cleanupFailure) {
            if (loadFailure != null && cleanupFailure != loadFailure) {
                loadFailure.addSuppressed(cleanupFailure);
                throw loadFailure;
            }
            throw cleanupFailure;
        }
        if (loadFailure != null && loadFailure.getSuppressed().length > 0) {
            throw loadFailure;
        }
        shell.loadingFailed();
    }

    private GameSessionFrame captureSessionFrame(
            double frameDeltaSeconds,
            boolean playingEligible) {
        if (session == null) {
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
        } catch (RuntimeException | Error cleanupFailure) {
            if (primaryFailure == null) {
                throw cleanupFailure;
            }
            primaryFailure.addSuppressed(cleanupFailure);
        }
    }

    private void closeProduct(Throwable primaryFailure) {
        Throwable firstFailure = primaryFailure;
        try {
            closeActiveSession(null);
        } catch (RuntimeException | Error sessionCloseFailure) {
            firstFailure = mergeFailure(firstFailure, sessionCloseFailure);
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

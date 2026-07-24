package com.gaia;

import com.gaia.world.WorldLoadResult;
import com.overlord.config.GameConfig;
import com.overlord.core.ModuleManager;
import com.overlord.core.Window;
import com.overlord.core.input.InputSnapshot;
import com.overlord.core.input.MouseDelta;
import com.overlord.event.EventBus;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;

public final class GameLoop {
    private final GameContext context;
    private State state = State.LOADING;
    private WorldLoadResult loadResult;
    private boolean cursorCaptured = true;

    public GameLoop(GameContext context) {
        this.context = context;
    }

    public void run() {
        Window window = context.engine().getWindow();
        while (state != State.STOPPING) {
            double frameDeltaSeconds = context.frameClock().tick();
            window.pollEvents();
            if (context.inputManager().consumeKeyPress(GameConfig.Input.KEY_CURSOR_CAPTURE)) {
                cursorCaptured = !cursorCaptured;
                window.setCursorCaptured(cursorCaptured);
                context.inputManager().resetMouseBaseline();
            }
            MouseDelta mouseDelta = context.inputManager().consumeMouseDelta();

            if (!context.engine().isRunning()
                    || window.shouldClose()
                    || context.inputManager().isKeyDown(GameConfig.Input.KEY_CLOSE)
                    || context.inputManager().isKeyPressed(GameConfig.Input.KEY_CLOSE)) {
                state = State.STOPPING;
                break;
            }

            window.consumeFramebufferResize()
                    .ifPresent(
                            size ->
                                    context.engine()
                                            .getRenderer()
                                            .resizeFramebuffer(size.width(), size.height()));

            if (state == State.LOADING) {
                completeLoadingIfReady();
            } else if (state == State.RUNNING) {
                runFixedUpdates(frameDeltaSeconds, mouseDelta);
            }
            if (loadResult != null) {
                pumpChunkMeshes();
            }

            if (state == State.STOPPING) {
                break;
            }

            context.engine().getRenderer().clear();
            if (state == State.RUNNING) {
                context.engine()
                        .getRenderer()
                        .renderChunks(
                                context.chunkMeshes().renderObjects());
            }
            window.swapBuffers();
        }
    }

    private void completeLoadingIfReady() {
        if (loadResult != null) {
            return;
        }
        if (!context.worldLoad().isDone()) {
            return;
        }

        try {
            loadResult = context.worldLoad().join();
        } catch (CancellationException cancellation) {
            state = State.STOPPING;
            return;
        } catch (CompletionException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof CancellationException) {
                state = State.STOPPING;
                return;
            }
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new RuntimeException("World loading failed", cause);
        }

        context.engine()
                .getCamera()
                .setPosition(loadResult.spawnPosition());
        context.engine().getCamera().setPitch(-30.0f);
        context.physicsManager().initializeSpawnPosition();
    }

    private void pumpChunkMeshes() {
        context.chunkMeshes().scheduleEligible();
        context.chunkMeshes().processMainThreadWork();
        Throwable meshFailure =
                context.chunkMeshes().pollFailure().orElse(null);
        if (meshFailure != null) {
            rethrowMeshFailure(meshFailure);
        }
        if (state == State.LOADING
                && context.chunkMeshes()
                        .allRenderable(
                                loadResult.initialChunks())) {
            state = State.RUNNING;
        }
    }

    private static void rethrowMeshFailure(Throwable failure) {
        if (failure instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
        throw (Error) failure;
    }

    private void runFixedUpdates(double frameDeltaSeconds, MouseDelta mouseDelta) {
        if (cursorCaptured) {
            context.playerManager().applyLook(mouseDelta);
        }
        int fixedSteps = context.fixedStepClock().advance(frameDeltaSeconds);
        if (fixedSteps == 0) {
            return;
        }

        InputSnapshot input = context.inputManager().consumeFixedInput();
        if (input.isKeyPressed(GameConfig.Input.KEY_CLOSE)) {
            state = State.STOPPING;
            return;
        }
        for (int step = 0; step < fixedSteps; step++) {
            float fixedDelta = context.fixedStepClock().fixedStepSeconds();
            context.playerManager().fixedUpdate(fixedDelta, input);
            ModuleManager.getInstance().updateAll(fixedDelta);
            EventBus.getInstance().processAll();
        }
    }

    private enum State {
        LOADING,
        RUNNING,
        STOPPING
    }
}

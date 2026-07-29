package com.gaia;

import com.gaia.inventory.InventoryDropLocation;
import com.gaia.interaction.MouseInteractionLifecycle;
import com.gaia.interaction.BlockInteractionViewModel;
import com.gaia.interaction.feedback.InteractionFeedbackCoordinator;
import com.gaia.ui.HudDebugSnapshot;
import com.gaia.ui.HudFrameCoordinator;
import com.gaia.ui.HudVisibility;
import com.gaia.world.WorldLoadResult;
import com.gaia.world.WorldLoadState;
import com.overlord.config.GameConfig;
import com.overlord.core.ModuleManager;
import com.overlord.core.Window;
import com.overlord.core.input.InputSnapshot;
import com.overlord.core.input.MouseDelta;
import com.overlord.event.EventBus;
import com.overlord.physics.PlayerController;
import com.overlord.renderer.RenderFrameInput;
import com.overlord.renderer.RenderSurfaceMetrics;
import com.overlord.renderer.feedback.FeedbackVisibility;
import com.overlord.renderer.feedback.InteractionFeedbackFrame;
import com.overlord.worlditem.api.WorldItemSnapshot;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.Supplier;
import org.joml.Vector3f;

public final class GameLoop {
    private final GameContext context;
    private final MouseInteractionLifecycle mouseInteractionLifecycle;
    private State state = State.LOADING;
    private WorldLoadResult loadResult;
    private boolean cursorCaptured = true;
    private long inventoryTick;
    private final FrameDebugInputCapture hudDebugInputs;
    private final Vector3f interpolationScratch = new Vector3f();
    private final Vector3f dropPositionScratch = new Vector3f();
    private final Vector3f dropVelocityScratch = new Vector3f();

    public GameLoop(GameContext context) {
        this.context = context;
        mouseInteractionLifecycle = new MouseInteractionLifecycle(
                context.blockInteraction()::cancel);
        hudDebugInputs = new FrameDebugInputCapture(
                context.playerController().body(),
                () -> context.engine().getRenderer().metrics().snapshot());
    }

    public void run() {
        Window window = context.engine().getWindow();
        while (state != State.STOPPING) {
            double frameDeltaSeconds = context.frameClock().tick();
            GameLoopFrameOrchestrator.FixedBatch hudFixedBatch =
                    GameLoopFrameOrchestrator.FixedBatch.zeroSteps();
            window.pollEvents();
            boolean feedbackLifecycleBoundary = false;
            if (context.inputManager().consumeKeyPress(GameConfig.Input.KEY_CURSOR_CAPTURE)) {
                cursorCaptured = mouseInteractionLifecycle.toggleCursorCapture(
                        cursorCaptured, window::setCursorCaptured);
                context.inputManager().resetMouseBaseline();
                feedbackLifecycleBoundary = true;
            }
            if (context.inputManager().consumeMouseInteractionInvalidation()) {
                mouseInteractionLifecycle.onFocusLost();
                feedbackLifecycleBoundary = true;
            }
            MouseDelta mouseDelta = context.inputManager().consumeMouseDelta();

            if (!context.engine().isRunning()
                    || window.shouldClose()
                    || context.inputManager().isKeyDown(GameConfig.Input.KEY_CLOSE)
                    || context.inputManager().isKeyPressed(GameConfig.Input.KEY_CLOSE)) {
                state = State.STOPPING;
                break;
            }

            if (state != State.RUNNING) {
                context.inputManager().discardFixedInputEdges();
            }

            boolean focused = context.inputManager().isWindowFocused();

            window.consumeSurfaceUpdate()
                    .ifPresent(
                            size ->
                                    context.engine()
                                            .getRenderer()
                                            .updateSurface(size));

            if (state == State.LOADING) {
                completeLoadingIfReady();
            } else if (state == State.RUNNING) {
                hudFixedBatch = runFixedUpdates(frameDeltaSeconds, mouseDelta);
            }
            if (loadResult != null) {
                pumpChunkMeshes();
            }

            if (state == State.STOPPING) {
                break;
            }

            if (state == State.RUNNING) {
                updateRenderCamera();
            }
            boolean feedbackBlocked = context.interactionBlockState().blocked();
            boolean feedbackLifecycleBoundaryForRender = feedbackLifecycleBoundary;
            GameLoopFrameOrchestrator.FixedBatch presentationBatch = hudFixedBatch;
            FrameDebugInputCapture.CapturedInput hudDebugInput = hudDebugInputs.capture();
            List<WorldItemSnapshot> worldItemSnapshots =
                    List.copyOf(context.worldItems().snapshots());
            dispatchFeedbackFrame(
                    () -> handleFeedbackLifecycle(
                            context.interactionFeedback(),
                            feedbackLifecycleBoundaryForRender,
                            state == State.RUNNING,
                            cursorCaptured,
                            focused,
                            feedbackBlocked),
                    () -> feedbackSnapshot(
                            context.interactionFeedback(),
                            context.blockInteraction().viewModel(),
                            worldItemSnapshots,
                            state == State.RUNNING,
                            cursorCaptured,
                            focused,
                            feedbackBlocked),
                    feedback -> {
                        GameLoopFrameOrchestrator.captureAndRender(
                                presentationBatch,
                                fixedInput -> captureHudFrame(
                                        fixedInput,
                                        hudDebugInput,
                                        worldItemSnapshots,
                                        feedback,
                                        frameDeltaSeconds,
                                        focused,
                                        feedbackBlocked,
                                        window.currentSurfaceMetrics()),
                                hud -> context.engine().getRenderer().renderFrame(
                                        new RenderFrameInput(
                                                state == State.RUNNING
                                                        ? List.copyOf(
                                                                context.chunkMeshes()
                                                                        .renderObjects())
                                                        : List.of(),
                                                frameDeltaSeconds,
                                                context.chunkMeshes().meshQueueDepth(),
                                                feedback,
                                                hud.frame())));
                    });
            hudDebugInputs.recordCompletedRender();
            context.renderMetricsReporter().report(context.engine().getRenderer().metrics().snapshot());
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
            state = State.FAILED;
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new RuntimeException("World loading failed", cause);
        }

        if (context.worldLoader().state()
                != WorldLoadState.SUCCEEDED) {
            state = State.FAILED;
            throw new IllegalStateException(
                    "World load future completed while loader state was "
                            + context.worldLoader().state());
        }
        completePlayerLoading(
                context.playerController(), loadResult);
        updateRenderCamera();
        context.engine().getCamera().setPitch(-30.0f);
    }

    static void completePlayerLoading(
            PlayerController playerController,
            WorldLoadResult loadResult) {
        Objects.requireNonNull(
                playerController, "playerController");
        Objects.requireNonNull(loadResult, "loadResult");
        playerController.teleport(
                loadResult.playerFeetPosition());
        if (!playerController.recoverFromPenetration()) {
            throw new IllegalStateException(
                    "Player safe spawn recovery failed after world loading");
        }
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
            if (context.worldLoader().state()
                    != WorldLoadState.SUCCEEDED) {
                state = State.FAILED;
                throw new IllegalStateException(
                        "World loader is not successful");
            }
            state = State.RUNNING;
        }
    }

    private static void rethrowMeshFailure(Throwable failure) {
        if (failure instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
        throw (Error) failure;
    }

    private GameLoopFrameOrchestrator.FixedBatch runFixedUpdates(
            double frameDeltaSeconds, MouseDelta mouseDelta) {
        if (cursorCaptured) {
            context.playerManager().applyLook(mouseDelta);
        }
        int fixedSteps = context.fixedStepClock().advance(frameDeltaSeconds);
        if (fixedSteps == 0) {
            return GameLoopFrameOrchestrator.FixedBatch.zeroSteps();
        }

        GameLoopFrameOrchestrator.FixedBatch batch =
                GameLoopFrameOrchestrator.runFixedBatch(
                        fixedSteps,
                        () -> {
                            InputSnapshot input = context.inputManager().consumeFixedInput();
                            if (input.isKeyPressed(GameConfig.Input.KEY_CLOSE)) {
                                state = State.STOPPING;
                            }
                            return input;
                        },
                        stepInput -> {
                            if (state == State.STOPPING) {
                                return;
                            }
                            float fixedDelta = context.fixedStepClock().fixedStepSeconds();
                            runFixedSystemStep(
                                    () -> {
                                        context.inventoryInput().handle(
                                                stepInput,
                                                inventoryTick,
                                                Optional.of(dropLocation()));
                                        runInventoryDebugShortcut(stepInput);
                                        context.playerManager().fixedUpdate(fixedDelta, stepInput);
                                        context.physicsWorld().step(fixedDelta);
                                    },
                                    () -> interactionEnabled(
                                            state == State.RUNNING,
                                            cursorCaptured,
                                            context.inputManager().isWindowFocused(),
                                            context.interactionBlockState().blocked()),
                                    fixedInteractionEnabled ->
                                            context.blockInteraction().fixedUpdate(
                                                    stepInput,
                                                    fixedDelta,
                                                    inventoryTick,
                                                    Math.max(0L, System.nanoTime()),
                                                    fixedInteractionEnabled),
                                    fixedInteractionEnabled ->
                                            context.interactionFeedback().fixedUpdate(
                                                    context.blockInteraction().viewModel(),
                                                    fixedInteractionEnabled,
                                                    inventoryTick),
                                    () -> {
                                        ModuleManager.getInstance().updateAll(fixedDelta);
                                        EventBus.getInstance().processAll();
                                    });
                            inventoryTick++;
                        });
        return batch;
    }

    private HudFrameCoordinator.CapturedFrame captureHudFrame(
            Optional<InputSnapshot> fixedInput,
            FrameDebugInputCapture.CapturedInput hudDebugInput,
            List<WorldItemSnapshot> worldItems,
            InteractionFeedbackFrame feedback,
            double frameDeltaSeconds,
            boolean focused,
            boolean blocked,
            RenderSurfaceMetrics surface) {
        HudDebugSnapshot.Counts counts = new HudDebugSnapshot.Counts(
                context.engine().getWorld().chunks().keys().size(),
                context.physicsWorld().bodies().size(),
                worldItems.size(),
                feedback.blockDamage().isPresent() ? 1 : 0,
                feedback.worldItems().size(),
                feedback.particles().particles().size());
        return context.hudFrames().capture(new HudFrameCoordinator.FrameCapture(
                context.inventoryService().viewModel(context.inventoryOwner()).orElseThrow(),
                context.blockInteraction().viewModel(),
                hudDebugInput.previousFrameMetrics(),
                hudDebugInput.feet(),
                counts,
                fixedInput,
                frameDeltaSeconds,
                state == State.RUNNING
                        ? HudVisibility.Lifecycle.RUNNING
                        : HudVisibility.Lifecycle.LOADING,
                focused,
                cursorCaptured,
                blocked,
                surface));
    }

    private InventoryDropLocation dropLocation() {
        context.playerController().body().position(dropPositionScratch);
        dropPositionScratch.y += GameConfig.Player.EYE_HEIGHT;
        context.engine().getCamera().getForward(dropVelocityScratch)
                .mul(GameConfig.Interaction.DROP_SPEED);
        return new InventoryDropLocation(
                dropPositionScratch.x,
                dropPositionScratch.y,
                dropPositionScratch.z,
                dropVelocityScratch.x,
                dropVelocityScratch.y,
                dropVelocityScratch.z);
    }

    private void runInventoryDebugShortcut(InputSnapshot input) {
        if (!context.inventoryDebugShortcuts()) {
            return;
        }
        String command = null;
        if (input.isKeyPressed(GameConfig.Input.KEY_DEBUG_INVENTORY_SEED)) {
            command = "seed";
        } else if (input.isKeyPressed(GameConfig.Input.KEY_DEBUG_INVENTORY_CLEAR)) {
            command = "clear";
        } else if (input.isKeyPressed(GameConfig.Input.KEY_DEBUG_INVENTORY_FILL)) {
            command = "fill";
        } else if (input.isKeyPressed(GameConfig.Input.KEY_DEBUG_INVENTORY_PRINT)) {
            command = "print";
        }
        if (command != null) {
            System.out.println("[InventoryDebug] "
                    + context.inventoryDebugCommands().execute(command).message());
        }
    }

    private void updateRenderCamera() {
        Vector3f cameraFeet =
                context.playerController()
                        .body()
                        .interpolatedPosition(
                                (float)
                                        context.fixedStepClock()
                                                .interpolationAlpha(),
                                interpolationScratch);
        cameraFeet.y += GameConfig.Player.EYE_HEIGHT;
        context.engine().getCamera().setPosition(cameraFeet);
    }

    static boolean interactionEnabled(
            boolean running,
            boolean cursorCaptured,
            boolean focused,
            boolean blocked) {
        return running && cursorCaptured && focused && !blocked;
    }

    static void clearFeedbackForLifecycleBoundary(
            InteractionFeedbackCoordinator coordinator) {
        Objects.requireNonNull(coordinator, "coordinator").clearTransient();
    }

    static void handleFeedbackLifecycle(
            InteractionFeedbackCoordinator coordinator,
            boolean lifecycleBoundary,
            boolean running,
            boolean cursorCaptured,
            boolean focused,
            boolean blocked) {
        if (lifecycleBoundary || !interactionEnabled(running, cursorCaptured, focused, blocked)) {
            clearFeedbackForLifecycleBoundary(coordinator);
        }
    }

    static InteractionFeedbackFrame dispatchFeedbackFrame(
            Runnable lifecycle,
            Supplier<InteractionFeedbackFrame> snapshot,
            Consumer<InteractionFeedbackFrame> renderer) {
        Objects.requireNonNull(lifecycle, "lifecycle").run();
        InteractionFeedbackFrame frame =
                Objects.requireNonNull(snapshot, "snapshot").get();
        Objects.requireNonNull(renderer, "renderer")
                .accept(Objects.requireNonNull(frame, "feedback frame"));
        return frame;
    }

    static void runFixedBatch(int fixedSteps, IntConsumer fixedStep) {
        if (fixedSteps < 0) {
            throw new IllegalArgumentException("fixedSteps must be non-negative");
        }
        Objects.requireNonNull(fixedStep, "fixedStep");
        for (int step = 0; step < fixedSteps; step++) {
            fixedStep.accept(step);
        }
    }

    static void runFixedSystemStep(
            Runnable leadingSystems,
            BooleanSupplier interactionEnablement,
            Consumer<Boolean> interaction,
            Consumer<Boolean> feedback,
            Runnable trailingSystems) {
        Objects.requireNonNull(leadingSystems, "leadingSystems").run();
        boolean enabled = Objects.requireNonNull(
                interactionEnablement, "interactionEnablement").getAsBoolean();
        Objects.requireNonNull(interaction, "interaction").accept(enabled);
        Objects.requireNonNull(feedback, "feedback").accept(enabled);
        Objects.requireNonNull(trailingSystems, "trailingSystems").run();
    }

    static InteractionFeedbackFrame feedbackSnapshot(
            InteractionFeedbackCoordinator coordinator,
            BlockInteractionViewModel view,
            List<WorldItemSnapshot> worldItems,
            boolean running,
            boolean cursorCaptured,
            boolean focused,
            boolean blocked) {
        FeedbackVisibility visibility =
                new FeedbackVisibility(running, cursorCaptured, focused, blocked);
        List<WorldItemSnapshot> snapshots =
                List.copyOf(Objects.requireNonNull(worldItems, "worldItems"));
        List<WorldItemSnapshot> presentedWorldItems = running
                ? snapshots
                : List.of();
        return Objects.requireNonNull(coordinator, "coordinator")
                .snapshot(
                        Objects.requireNonNull(view, "view"),
                        presentedWorldItems,
                        visibility);
    }

    private enum State {
        LOADING,
        RUNNING,
        FAILED,
        STOPPING
    }
}

package com.gaia;

import com.gaia.interaction.BlockInteractionViewModel;
import com.gaia.interaction.feedback.FirstPersonMovementState;
import com.gaia.interaction.feedback.InteractionFeedbackCoordinator;
import com.gaia.world.WorldLoadResult;
import com.gaia.worlditem.WorldItemPresentationSnapshot;
import com.overlord.config.GameConfig;
import com.overlord.physics.PlayerController;
import com.overlord.renderer.feedback.FeedbackVisibility;
import com.overlord.renderer.feedback.InteractionFeedbackFrame;
import com.overlord.worlditem.api.WorldItemSnapshot;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.Supplier;
import org.joml.Vector3f;

/** Session-internal gameplay helpers retained behind {@code GameSessionFactory}. */
public final class GameLoop {
    private GameLoop() {}

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

    static boolean interactionEnabled(
            boolean running,
            boolean cursorCaptured,
            boolean focused,
            boolean blocked) {
        return running
                && cursorCaptured
                && focused
                && !blocked;
    }

    static void clearFeedbackForLifecycleBoundary(
            InteractionFeedbackCoordinator coordinator) {
        Objects.requireNonNull(coordinator, "coordinator")
                .clearTransient();
    }

    static void handleFeedbackLifecycle(
            InteractionFeedbackCoordinator coordinator,
            boolean lifecycleBoundary,
            boolean running,
            boolean cursorCaptured,
            boolean focused,
            boolean blocked) {
        if (lifecycleBoundary
                || !interactionEnabled(
                        running,
                        cursorCaptured,
                        focused,
                        blocked)) {
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
                .accept(
                        Objects.requireNonNull(
                                frame, "feedback frame"));
        return frame;
    }

    static void runFixedBatch(
            int fixedSteps,
            IntConsumer fixedStep) {
        if (fixedSteps < 0) {
            throw new IllegalArgumentException(
                    "fixedSteps must be non-negative");
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
        Objects.requireNonNull(leadingSystems, "leadingSystems")
                .run();
        boolean enabled =
                Objects.requireNonNull(
                                interactionEnablement,
                                "interactionEnablement")
                        .getAsBoolean();
        Objects.requireNonNull(interaction, "interaction")
                .accept(enabled);
        Objects.requireNonNull(feedback, "feedback")
                .accept(enabled);
        Objects.requireNonNull(trailingSystems, "trailingSystems")
                .run();
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
                new FeedbackVisibility(
                        running,
                        cursorCaptured,
                        focused,
                        blocked);
        List<WorldItemSnapshot> snapshots =
                List.copyOf(
                        Objects.requireNonNull(
                                worldItems, "worldItems"));
        List<WorldItemSnapshot> presentedWorldItems =
                running ? snapshots : List.of();
        return Objects.requireNonNull(
                        coordinator, "coordinator")
                .snapshot(
                        Objects.requireNonNull(view, "view"),
                        presentedWorldItems,
                        visibility);
    }

    static InteractionFeedbackFrame feedbackSnapshotPhysical(
            InteractionFeedbackCoordinator coordinator,
            BlockInteractionViewModel view,
            List<WorldItemPresentationSnapshot> worldItems,
            float interpolationAlpha,
            boolean running,
            boolean cursorCaptured,
            boolean focused,
            boolean blocked) {
        FeedbackVisibility visibility =
                new FeedbackVisibility(
                        running,
                        cursorCaptured,
                        focused,
                        blocked);
        List<WorldItemPresentationSnapshot> snapshots =
                List.copyOf(
                        Objects.requireNonNull(
                                worldItems, "worldItems"));
        List<WorldItemPresentationSnapshot> presentedWorldItems =
                running ? snapshots : List.of();
        return Objects.requireNonNull(
                        coordinator, "coordinator")
                .snapshotPhysical(
                        Objects.requireNonNull(view, "view"),
                        presentedWorldItems,
                        interpolationAlpha,
                        visibility);
    }

    static FirstPersonMovementState movementState(
            PlayerController playerController,
            Vector3f positionScratch,
            Vector3f velocityScratch) {
        Objects.requireNonNull(
                playerController, "playerController");
        Objects.requireNonNull(
                positionScratch, "positionScratch");
        Objects.requireNonNull(
                velocityScratch, "velocityScratch");
        playerController.body().position(positionScratch);
        playerController.body().linearVelocity(velocityScratch);
        float horizontalSpeed =
                (float)
                        Math.sqrt(
                                velocityScratch.x
                                                * velocityScratch.x
                                        + velocityScratch.z
                                                * velocityScratch.z);
        return new FirstPersonMovementState(
                positionScratch.y,
                horizontalSpeed,
                velocityScratch.y,
                playerController.isGrounded(),
                playerController.isNoclip());
    }
}

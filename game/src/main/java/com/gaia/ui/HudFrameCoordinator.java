package com.gaia.ui;

import com.gaia.world.streaming.ChunkStreamingMetrics;
import com.gaia.interaction.BlockInteractionViewModel;
import com.overlord.core.input.InputSnapshot;
import com.overlord.inventory.api.BodyInventoryViewModel;
import com.overlord.renderer.RenderSurfaceMetrics;
import com.overlord.renderer.metrics.RenderMetricsSnapshot;
import com.overlord.renderer.ui.UiFrame;
import com.overlord.renderer.ui.UiLayoutContext;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Captures one immutable HUD presentation and UI frame per rendered frame. */
public final class HudFrameCoordinator {
    private static final InputSnapshot NEUTRAL_INPUT =
            new InputSnapshot(Set.of(), Set.of());

    private final HudPresenter presenter;
    private final GaiaHudScreen screen;
    private long nextInputSampleId;

    public HudFrameCoordinator(HudPresenter presenter, GaiaHudScreen screen) {
        this.presenter = Objects.requireNonNull(presenter, "presenter");
        this.screen = Objects.requireNonNull(screen, "screen");
    }

    public CapturedFrame capture(FrameCapture input) {
        Objects.requireNonNull(input, "input");
        if (nextInputSampleId == Long.MAX_VALUE) {
            throw new IllegalStateException("HUD input sample id exhausted");
        }
        InputSnapshot presentationInput = input.fixedInput().orElse(NEUTRAL_INPUT);
        HudPresentationSnapshot presentation = presenter.capture(new HudPresenter.FrameInput(
                input.inventory(),
                input.interaction(),
                input.previousFrameMetrics(),
                input.feet(),
                input.counts(),
                input.streamingMetrics(),
                presentationInput,
                nextInputSampleId++,
                input.fixedInput().isPresent(),
                input.frameDeltaSeconds(),
                input.lifecycle(),
                input.focused(),
                input.cursorCaptured(),
                input.blockingUi()));
        UiFrame frame = input.surface().framebufferWidth() == 0
                        || input.surface().framebufferHeight() == 0
                ? UiFrame.empty()
                : screen.compose(
                        presentation,
                        new UiLayoutContext(input.surface()));
        return new CapturedFrame(presentation, frame);
    }

    public record CapturedFrame(
            HudPresentationSnapshot presentation,
            UiFrame frame) {
        public CapturedFrame {
            Objects.requireNonNull(presentation, "presentation");
            Objects.requireNonNull(frame, "frame");
        }
    }

    public record FrameCapture(
            BodyInventoryViewModel inventory,
            BlockInteractionViewModel interaction,
            Optional<RenderMetricsSnapshot> previousFrameMetrics,
            HudDebugSnapshot.FeetPosition feet,
            HudDebugSnapshot.Counts counts,
            ChunkStreamingMetrics streamingMetrics,
            Optional<InputSnapshot> fixedInput,
            double frameDeltaSeconds,
            HudVisibility.Lifecycle lifecycle,
            boolean focused,
            boolean cursorCaptured,
            boolean blockingUi,
            RenderSurfaceMetrics surface) {
        public FrameCapture(
                BodyInventoryViewModel inventory,
                BlockInteractionViewModel interaction,
                Optional<RenderMetricsSnapshot> previousFrameMetrics,
                HudDebugSnapshot.FeetPosition feet,
                HudDebugSnapshot.Counts counts,
                Optional<InputSnapshot> fixedInput,
                double frameDeltaSeconds,
                HudVisibility.Lifecycle lifecycle,
                boolean focused,
                boolean cursorCaptured,
                boolean blockingUi,
                RenderSurfaceMetrics surface) {
            this(inventory, interaction, previousFrameMetrics, feet, counts,
                    ChunkStreamingMetrics.empty(), fixedInput, frameDeltaSeconds,
                    lifecycle, focused, cursorCaptured, blockingUi, surface);
        }

        public FrameCapture {
            Objects.requireNonNull(inventory, "inventory");
            Objects.requireNonNull(interaction, "interaction");
            previousFrameMetrics = Objects.requireNonNull(
                    previousFrameMetrics, "previousFrameMetrics");
            feet = Objects.requireNonNull(feet, "feet");
            counts = Objects.requireNonNull(counts, "counts");
            streamingMetrics = Objects.requireNonNull(
                    streamingMetrics, "streamingMetrics");
            fixedInput = Objects.requireNonNull(fixedInput, "fixedInput");
            lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
            surface = Objects.requireNonNull(surface, "surface");
            if (!Double.isFinite(frameDeltaSeconds) || frameDeltaSeconds < 0) {
                throw new IllegalArgumentException(
                        "frameDeltaSeconds must be finite and non-negative");
            }
        }
    }
}

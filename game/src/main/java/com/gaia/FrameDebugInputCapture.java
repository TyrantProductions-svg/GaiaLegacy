package com.gaia;

import com.gaia.ui.HudDebugSnapshot;
import com.overlord.physics.PhysicsBody;
import com.overlord.renderer.metrics.RenderMetricsSnapshot;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import org.joml.Vector3f;

/** Main-thread capture of copied debug inputs owned by the game-loop frame boundary. */
final class FrameDebugInputCapture {
    private final PhysicsBody playerBody;
    private final Supplier<RenderMetricsSnapshot> completedMetrics;
    private final Vector3f feetScratch = new Vector3f();
    private Optional<RenderMetricsSnapshot> previousFrameMetrics = Optional.empty();

    FrameDebugInputCapture(
            PhysicsBody playerBody,
            Supplier<RenderMetricsSnapshot> completedMetrics) {
        this.playerBody = Objects.requireNonNull(playerBody, "playerBody");
        this.completedMetrics = Objects.requireNonNull(completedMetrics, "completedMetrics");
    }

    CapturedInput capture() {
        playerBody.position(feetScratch);
        return new CapturedInput(
                previousFrameMetrics,
                new HudDebugSnapshot.FeetPosition(
                        feetScratch.x, feetScratch.y, feetScratch.z));
    }

    void recordCompletedRender() {
        previousFrameMetrics = Optional.of(Objects.requireNonNull(
                completedMetrics.get(), "completed render metrics"));
    }

    record CapturedInput(
            Optional<RenderMetricsSnapshot> previousFrameMetrics,
            HudDebugSnapshot.FeetPosition feet) {
        CapturedInput {
            previousFrameMetrics = Objects.requireNonNull(
                    previousFrameMetrics, "previousFrameMetrics");
            feet = Objects.requireNonNull(feet, "feet");
        }
    }
}

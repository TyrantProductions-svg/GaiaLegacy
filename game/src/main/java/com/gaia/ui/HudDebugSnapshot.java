package com.gaia.ui;

import com.gaia.world.streaming.ChunkStreamingMetrics;
import com.overlord.renderer.metrics.RenderMetricsSnapshot;
import java.util.Objects;
import java.util.Optional;

/** Copied debug values; render metrics intentionally describe the previous completed frame. */
public record HudDebugSnapshot(
        Optional<RenderMetricsSnapshot> previousFrameMetrics,
        FeetPosition feet,
        Counts counts,
        ChunkStreamingMetrics streamingMetrics) {
    public HudDebugSnapshot(
            Optional<RenderMetricsSnapshot> previousFrameMetrics,
            FeetPosition feet,
            Counts counts) {
        this(previousFrameMetrics, feet, counts, ChunkStreamingMetrics.empty());
    }

    public HudDebugSnapshot {
        previousFrameMetrics = Objects.requireNonNull(previousFrameMetrics, "previousFrameMetrics");
        feet = Objects.requireNonNull(feet, "feet");
        counts = Objects.requireNonNull(counts, "counts");
        streamingMetrics = Objects.requireNonNull(streamingMetrics, "streamingMetrics");
    }

    public record FeetPosition(double x, double y, double z) {
        public FeetPosition {
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
                throw new IllegalArgumentException("authoritative feet position must be finite");
            }
        }
    }

    public record Counts(
            int loadedChunks,
            int physicsBodies,
            int worldItems,
            int blockDamageVisuals,
            int feedbackWorldItems,
            int particles) {
        public Counts {
            if (loadedChunks < 0
                    || physicsBodies < 0
                    || worldItems < 0
                    || blockDamageVisuals < 0
                    || feedbackWorldItems < 0
                    || particles < 0) {
                throw new IllegalArgumentException("debug counts must be non-negative");
            }
        }
    }
}

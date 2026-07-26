package com.gaia;

import com.overlord.renderer.metrics.RenderMetricsSnapshot;
import java.io.PrintStream;
import java.util.Locale;
import java.util.Objects;
import java.util.function.LongSupplier;

public final class RenderMetricsConsoleReporter {
    private final boolean enabled; private final LongSupplier nanoTime; private final PrintStream output;
    private long lastReportNanos = Long.MIN_VALUE;
    public RenderMetricsConsoleReporter(boolean enabled, LongSupplier nanoTime, PrintStream output) {
        this.enabled = enabled; this.nanoTime = Objects.requireNonNull(nanoTime); this.output = Objects.requireNonNull(output);
    }
    public void report(RenderMetricsSnapshot metrics) {
        Objects.requireNonNull(metrics); if (!enabled) return;
        long now = nanoTime.getAsLong(); if (lastReportNanos == Long.MIN_VALUE) { lastReportNanos = now; return; }
        if (now - lastReportNanos < 1_000_000_000L) return;
        lastReportNanos = now;
        output.printf(Locale.ROOT, "RenderMetrics fps=%.2f frameMs=%.2f visibleChunks=%d drawCalls=%d triangles=%d meshQueue=%d%n", metrics.framesPerSecond(), metrics.frameTimeMilliseconds(), metrics.visibleChunks(), metrics.drawCalls(), metrics.triangles(), metrics.meshQueueDepth());
    }
}

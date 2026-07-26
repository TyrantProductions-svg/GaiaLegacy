package com.overlord.renderer;

import com.overlord.renderer.metrics.RenderMetrics;
import com.overlord.renderer.metrics.RenderMetricsRecorder;
import com.overlord.renderer.metrics.RenderMetricsSnapshot;

final class RenderMetricsCollector implements RenderMetrics, RenderMetricsRecorder {
    private RenderMetricsSnapshot snapshot = new RenderMetricsSnapshot(0.0d, 0.0d, 0, 0, 0L, 0);
    private double frameDeltaSeconds;
    private int meshQueueDepth;
    private int visibleChunks;
    private int drawCalls;
    private long triangles;

    void beginFrame(double frameDeltaSeconds, int meshQueueDepth) {
        if (!Double.isFinite(frameDeltaSeconds) || frameDeltaSeconds < 0.0d) {
            throw new IllegalArgumentException("frameDeltaSeconds must be finite and non-negative");
        }
        if (meshQueueDepth < 0) {
            throw new IllegalArgumentException("meshQueueDepth must be non-negative");
        }
        this.frameDeltaSeconds = frameDeltaSeconds;
        this.meshQueueDepth = meshQueueDepth;
        visibleChunks = 0;
        drawCalls = 0;
        triangles = 0L;
    }

    void setVisibleChunks(int visibleChunks) {
        if (visibleChunks < 0) {
            throw new IllegalArgumentException("visibleChunks must be non-negative");
        }
        this.visibleChunks = visibleChunks;
    }

    @Override
    public void recordDraw(long triangles) {
        if (triangles < 0L) {
            throw new IllegalArgumentException("triangles must be non-negative");
        }
        drawCalls++;
        this.triangles = Math.addExact(this.triangles, triangles);
    }

    void finishFrame() {
        double framesPerSecond = frameDeltaSeconds == 0.0d ? 0.0d : 1.0d / frameDeltaSeconds;
        snapshot = new RenderMetricsSnapshot(
                framesPerSecond, frameDeltaSeconds * 1000.0d, visibleChunks, drawCalls, triangles, meshQueueDepth);
    }

    @Override
    public RenderMetricsSnapshot snapshot() {
        return snapshot;
    }
}

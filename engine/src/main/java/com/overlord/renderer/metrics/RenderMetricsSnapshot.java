package com.overlord.renderer.metrics;

public record RenderMetricsSnapshot(
        double framesPerSecond,
        double frameTimeMilliseconds,
        int visibleChunks,
        int drawCalls,
        long triangles,
        int meshQueueDepth) {
    public RenderMetricsSnapshot {
        if (!Double.isFinite(framesPerSecond) || framesPerSecond < 0.0d) {
            throw new IllegalArgumentException("framesPerSecond must be finite and non-negative");
        }
        if (!Double.isFinite(frameTimeMilliseconds) || frameTimeMilliseconds < 0.0d) {
            throw new IllegalArgumentException("frameTimeMilliseconds must be finite and non-negative");
        }
        if (visibleChunks < 0 || drawCalls < 0 || triangles < 0L || meshQueueDepth < 0) {
            throw new IllegalArgumentException("render metrics values must be non-negative");
        }
    }
}

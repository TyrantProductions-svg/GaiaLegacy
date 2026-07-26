package com.overlord.renderer.metrics;

public interface RenderMetricsRecorder {
    void recordDraw(long triangles);
}

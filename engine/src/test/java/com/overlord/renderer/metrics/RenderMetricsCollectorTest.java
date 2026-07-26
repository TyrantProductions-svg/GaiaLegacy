package com.overlord.renderer;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.overlord.renderer.metrics.RenderMetricsSnapshot;
import org.junit.jupiter.api.Test;

class RenderMetricsCollectorTest {
    @Test
    void publishesFrameTimingAndResetsDrawTotalsBetweenFrames() {
        RenderMetricsCollector collector = new RenderMetricsCollector();

        collector.beginFrame(0.02d, 7);
        collector.setVisibleChunks(3);
        collector.recordDraw(1);
        collector.recordDraw(12);
        collector.finishFrame();

        RenderMetricsSnapshot first = collector.snapshot();
        assertEquals(50.0d, first.framesPerSecond());
        assertEquals(20.0d, first.frameTimeMilliseconds());
        assertEquals(3, first.visibleChunks());
        assertEquals(2, first.drawCalls());
        assertEquals(13L, first.triangles());
        assertEquals(7, first.meshQueueDepth());

        collector.beginFrame(0.0d, 0);
        collector.finishFrame();
        assertEquals(new RenderMetricsSnapshot(0.0d, 0.0d, 0, 0, 0L, 0), collector.snapshot());
        assertEquals(2, first.drawCalls());
    }
}

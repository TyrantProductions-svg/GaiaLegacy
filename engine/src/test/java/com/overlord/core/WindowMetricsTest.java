package com.overlord.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class WindowMetricsTest {
    @Test
    void tracksLogicalAndFramebufferSizesIndependently() {
        WindowMetrics metrics = new WindowMetrics(1280, 720, 2560, 1440);

        metrics.updateLogicalSize(1500, 900);

        assertEquals(1500, metrics.logicalWidth());
        assertEquals(900, metrics.logicalHeight());
        assertEquals(2560, metrics.framebufferWidth());
        assertEquals(1440, metrics.framebufferHeight());
    }

    @Test
    void exposesEachFramebufferResizeOnce() {
        WindowMetrics metrics = new WindowMetrics(1280, 720, 2560, 1440);

        metrics.updateFramebufferSize(3000, 1800);

        assertEquals(
                new WindowMetrics.FramebufferSize(3000, 1800),
                metrics.consumeFramebufferResize().orElseThrow());
        assertTrue(metrics.consumeFramebufferResize().isEmpty());
    }
}

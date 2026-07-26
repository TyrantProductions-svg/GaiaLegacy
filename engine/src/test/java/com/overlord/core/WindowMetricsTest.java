package com.overlord.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class WindowMetricsTest {
    @Test
    void validatesAndCoalescesEverySurfaceCallbackToOneLatestSnapshot() {
        assertThrows(IllegalArgumentException.class,
                () -> new com.overlord.renderer.RenderSurfaceMetrics(-1, 0, 0, 0, 1.0f, 1.0f));
        assertThrows(IllegalArgumentException.class,
                () -> new com.overlord.renderer.RenderSurfaceMetrics(1, 1, 1, 1, Float.NaN, 1.0f));
        WindowMetrics metrics = new WindowMetrics(
                new com.overlord.renderer.RenderSurfaceMetrics(800, 600, 1600, 900, 2.0f, 1.5f));
        metrics.updateFramebufferSize(0, 0);
        metrics.updateContentScale(1.25f, 1.25f);
        metrics.updateLogicalSize(1024, 768);
        assertEquals(new com.overlord.renderer.RenderSurfaceMetrics(1024, 768, 0, 0, 1.25f, 1.25f),
                metrics.current());
        assertEquals(metrics.current(), metrics.consumeSurfaceUpdate().orElseThrow());
        assertTrue(metrics.consumeSurfaceUpdate().isEmpty());
    }
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
                new com.overlord.renderer.RenderSurfaceMetrics(1280, 720, 3000, 1800, 1.0f, 1.0f),
                metrics.consumeSurfaceUpdate().orElseThrow());
        assertTrue(metrics.consumeSurfaceUpdate().isEmpty());
    }
}

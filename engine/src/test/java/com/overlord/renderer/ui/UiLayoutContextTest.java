package com.overlord.renderer.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.overlord.renderer.RenderSurfaceMetrics;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class UiLayoutContextTest {
    @ParameterizedTest(name = "{0}")
    @MethodSource("layoutSnapshots")
    void derivesLogicalUiSizeAndSnapsEveryEdgeIndependently(
            String scenario,
            RenderSurfaceMetrics metrics,
            double expectedLogicalWidth,
            double expectedLogicalHeight,
            UiRect expectedFramebufferBounds) {
        UiLayoutContext layout = new UiLayoutContext(metrics);

        assertEquals(metrics.logicalWidth(), layout.logicalWindowWidth());
        assertEquals(metrics.logicalHeight(), layout.logicalWindowHeight());
        assertEquals(metrics.framebufferWidth(), layout.framebufferWidth());
        assertEquals(metrics.framebufferHeight(), layout.framebufferHeight());
        assertEquals(metrics.contentScaleX(), layout.contentScaleX());
        assertEquals(metrics.contentScaleY(), layout.contentScaleY());
        assertEquals(expectedLogicalWidth, layout.logicalWidth(), 0.000_001d);
        assertEquals(expectedLogicalHeight, layout.logicalHeight(), 0.000_001d);
        assertEquals(
                new UiRect(0.0d, 0.0d, expectedLogicalWidth, expectedLogicalHeight),
                layout.safeArea());
        assertEquals(
                expectedFramebufferBounds,
                layout.toFramebuffer(new UiRect(10.2d, 20.4d, 30.6d, 40.8d)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("surfaceTransitions")
    void resizeMaximiseAndMonitorScaleTransitionsUseOnlyTheIncomingSnapshot(
            String scenario,
            RenderSurfaceMetrics metrics,
            UiRect expectedSafeArea,
            UiRect expectedFramebufferBounds) {
        UiLayoutContext layout = new UiLayoutContext(metrics);

        assertEquals(expectedSafeArea, layout.safeArea());
        assertEquals(
                expectedFramebufferBounds,
                layout.toFramebuffer(new UiRect(10.25d, 20.25d, 30.25d, 40.75d)));
    }

    @Test
    void logicalWindowMismatchNeverOverridesFramebufferDerivedUiSize() {
        UiLayoutContext layout =
                new UiLayoutContext(
                        new RenderSurfaceMetrics(640, 480, 1600, 900, 1.25f, 1.5f));

        assertEquals(640, layout.logicalWindowWidth());
        assertEquals(480, layout.logicalWindowHeight());
        assertEquals(1280.0d, layout.logicalWidth());
        assertEquals(600.0d, layout.logicalHeight());
        assertEquals(13, layout.snapX(10.2d));
        assertEquals(31, layout.snapY(20.4d));
    }

    @Test
    void rejectsMissingMetricsAndNonFiniteOrOverflowingCoordinates() {
        assertThrows(NullPointerException.class, () -> new UiLayoutContext(null));

        UiLayoutContext layout =
                new UiLayoutContext(new RenderSurfaceMetrics(800, 600, 800, 600, 1.0f, 1.0f));
        assertThrows(IllegalArgumentException.class, () -> layout.snapX(Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> layout.snapY(Double.POSITIVE_INFINITY));
        assertThrows(IllegalArgumentException.class, () -> layout.snapX(Double.MAX_VALUE));
    }

    private static Stream<Arguments> layoutSnapshots() {
        return Stream.of(
                snapshot("800x600 4:3 at 100%", 800, 600, 1.0f, 800.0d, 600.0d,
                        new UiRect(10.0d, 20.0d, 31.0d, 41.0d)),
                snapshot("800x600 4:3 at 125%", 800, 600, 1.25f, 640.0d, 480.0d,
                        new UiRect(13.0d, 26.0d, 38.0d, 51.0d)),
                snapshot("800x600 4:3 at 150%", 800, 600, 1.5f,
                        533.333_333_333_333_4d, 400.0d,
                        new UiRect(15.0d, 31.0d, 46.0d, 61.0d)),
                snapshot("800x600 4:3 at 200%", 800, 600, 2.0f, 400.0d, 300.0d,
                        new UiRect(20.0d, 41.0d, 61.0d, 82.0d)),
                snapshot("1024x768 4:3 at 100%", 1024, 768, 1.0f, 1024.0d, 768.0d,
                        new UiRect(10.0d, 20.0d, 31.0d, 41.0d)),
                snapshot("1024x768 4:3 at 125%", 1024, 768, 1.25f, 819.2d, 614.4d,
                        new UiRect(13.0d, 26.0d, 38.0d, 51.0d)),
                snapshot("1024x768 4:3 at 150%", 1024, 768, 1.5f,
                        682.666_666_666_666_6d, 512.0d,
                        new UiRect(15.0d, 31.0d, 46.0d, 61.0d)),
                snapshot("1024x768 4:3 at 200%", 1024, 768, 2.0f, 512.0d, 384.0d,
                        new UiRect(20.0d, 41.0d, 61.0d, 82.0d)),
                snapshot("1280x720 16:9 at 100%", 1280, 720, 1.0f, 1280.0d, 720.0d,
                        new UiRect(10.0d, 20.0d, 31.0d, 41.0d)),
                snapshot("1280x720 16:9 at 125%", 1280, 720, 1.25f, 1024.0d, 576.0d,
                        new UiRect(13.0d, 26.0d, 38.0d, 51.0d)),
                snapshot("1280x720 16:9 at 150%", 1280, 720, 1.5f,
                        853.333_333_333_333_4d, 480.0d,
                        new UiRect(15.0d, 31.0d, 46.0d, 61.0d)),
                snapshot("1280x720 16:9 at 200%", 1280, 720, 2.0f, 640.0d, 360.0d,
                        new UiRect(20.0d, 41.0d, 61.0d, 82.0d)),
                snapshot("1920x1080 16:9 at 100%", 1920, 1080, 1.0f, 1920.0d, 1080.0d,
                        new UiRect(10.0d, 20.0d, 31.0d, 41.0d)),
                snapshot("1920x1080 16:9 at 125%", 1920, 1080, 1.25f, 1536.0d, 864.0d,
                        new UiRect(13.0d, 26.0d, 38.0d, 51.0d)),
                snapshot("1920x1080 16:9 at 150%", 1920, 1080, 1.5f, 1280.0d, 720.0d,
                        new UiRect(15.0d, 31.0d, 46.0d, 61.0d)),
                snapshot("1920x1080 16:9 at 200%", 1920, 1080, 2.0f, 960.0d, 540.0d,
                        new UiRect(20.0d, 41.0d, 61.0d, 82.0d)),
                snapshot("2560x1440 16:9 at 100%", 2560, 1440, 1.0f, 2560.0d, 1440.0d,
                        new UiRect(10.0d, 20.0d, 31.0d, 41.0d)),
                snapshot("2560x1440 16:9 at 125%", 2560, 1440, 1.25f, 2048.0d, 1152.0d,
                        new UiRect(13.0d, 26.0d, 38.0d, 51.0d)),
                snapshot("2560x1440 16:9 at 150%", 2560, 1440, 1.5f,
                        1706.666_666_666_666_7d, 960.0d,
                        new UiRect(15.0d, 31.0d, 46.0d, 61.0d)),
                snapshot("2560x1440 16:9 at 200%", 2560, 1440, 2.0f, 1280.0d, 720.0d,
                        new UiRect(20.0d, 41.0d, 61.0d, 82.0d)),
                snapshot("3840x2160 4K at 100%", 3840, 2160, 1.0f, 3840.0d, 2160.0d,
                        new UiRect(10.0d, 20.0d, 31.0d, 41.0d)),
                snapshot("3840x2160 4K at 125%", 3840, 2160, 1.25f, 3072.0d, 1728.0d,
                        new UiRect(13.0d, 26.0d, 38.0d, 51.0d)),
                snapshot("3840x2160 4K at 150%", 3840, 2160, 1.5f, 2560.0d, 1440.0d,
                        new UiRect(15.0d, 31.0d, 46.0d, 61.0d)),
                snapshot("3840x2160 4K at 200%", 3840, 2160, 2.0f, 1920.0d, 1080.0d,
                        new UiRect(20.0d, 41.0d, 61.0d, 82.0d)),
                snapshot("2560x1600 16:10 at 100%", 2560, 1600, 1.0f, 2560.0d, 1600.0d,
                        new UiRect(10.0d, 20.0d, 31.0d, 41.0d)),
                snapshot("2560x1600 16:10 at 125%", 2560, 1600, 1.25f, 2048.0d, 1280.0d,
                        new UiRect(13.0d, 26.0d, 38.0d, 51.0d)),
                snapshot("2560x1600 16:10 at 150%", 2560, 1600, 1.5f,
                        1706.666_666_666_666_7d, 1066.666_666_666_666_7d,
                        new UiRect(15.0d, 31.0d, 46.0d, 61.0d)),
                snapshot("2560x1600 16:10 at 200%", 2560, 1600, 2.0f, 1280.0d, 800.0d,
                        new UiRect(20.0d, 41.0d, 61.0d, 82.0d)),
                snapshot("3440x1440 ultrawide at 100%", 3440, 1440, 1.0f, 3440.0d, 1440.0d,
                        new UiRect(10.0d, 20.0d, 31.0d, 41.0d)),
                snapshot("3440x1440 ultrawide at 125%", 3440, 1440, 1.25f, 2752.0d, 1152.0d,
                        new UiRect(13.0d, 26.0d, 38.0d, 51.0d)),
                snapshot("3440x1440 ultrawide at 150%", 3440, 1440, 1.5f,
                        2293.333_333_333_333_5d, 960.0d,
                        new UiRect(15.0d, 31.0d, 46.0d, 61.0d)),
                snapshot("3440x1440 ultrawide at 200%", 3440, 1440, 2.0f, 1720.0d, 720.0d,
                        new UiRect(20.0d, 41.0d, 61.0d, 82.0d)),
                snapshot("1001x751 odd at 100%", 1001, 751, 1.0f, 1001.0d, 751.0d,
                        new UiRect(10.0d, 20.0d, 31.0d, 41.0d)),
                snapshot("1001x751 odd at 125%", 1001, 751, 1.25f, 800.8d, 600.8d,
                        new UiRect(13.0d, 26.0d, 38.0d, 51.0d)),
                snapshot("1001x751 odd at 150%", 1001, 751, 1.5f,
                        667.333_333_333_333_4d, 500.666_666_666_666_7d,
                        new UiRect(15.0d, 31.0d, 46.0d, 61.0d)),
                snapshot("1001x751 odd at 200%", 1001, 751, 2.0f, 500.5d, 375.5d,
                        new UiRect(20.0d, 41.0d, 61.0d, 82.0d)));
    }

    private static Stream<Arguments> surfaceTransitions() {
        return Stream.of(
                Arguments.of(
                        "resize to an odd framebuffer",
                        new RenderSurfaceMetrics(800, 600, 1001, 751, 1.0f, 1.0f),
                        new UiRect(0.0d, 0.0d, 1001.0d, 751.0d),
                        new UiRect(10.0d, 20.0d, 30.0d, 41.0d)),
                Arguments.of(
                        "maximise at 150%",
                        new RenderSurfaceMetrics(1920, 1080, 2880, 1620, 1.5f, 1.5f),
                        new UiRect(0.0d, 0.0d, 1920.0d, 1080.0d),
                        new UiRect(15.0d, 30.0d, 45.0d, 61.0d)),
                Arguments.of(
                        "move to a monitor with nonuniform scale",
                        new RenderSurfaceMetrics(1536, 864, 3840, 2160, 1.25f, 1.5f),
                        new UiRect(0.0d, 0.0d, 3072.0d, 1440.0d),
                        new UiRect(13.0d, 30.0d, 38.0d, 61.0d)));
    }

    private static Arguments snapshot(
            String scenario,
            int framebufferWidth,
            int framebufferHeight,
            float contentScale,
            double expectedLogicalWidth,
            double expectedLogicalHeight,
            UiRect expectedFramebufferBounds) {
        return Arguments.of(
                scenario,
                new RenderSurfaceMetrics(
                        framebufferWidth,
                        framebufferHeight,
                        framebufferWidth,
                        framebufferHeight,
                        contentScale,
                        contentScale),
                expectedLogicalWidth,
                expectedLogicalHeight,
                expectedFramebufferBounds);
    }
}

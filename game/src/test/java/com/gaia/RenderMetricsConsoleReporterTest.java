package com.gaia;

import static org.junit.jupiter.api.Assertions.assertEquals;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLong;
import com.overlord.renderer.metrics.RenderMetricsSnapshot;
import org.junit.jupiter.api.Test;

class RenderMetricsConsoleReporterTest {
    private static final RenderMetricsSnapshot SNAPSHOT = new RenderMetricsSnapshot(50, 20, 3, 4, 5, 6);
    @Test void disabledAndRateLimitedReportingAreDeterministic() {
        AtomicLong clock = new AtomicLong(); ByteArrayOutputStream bytes = new ByteArrayOutputStream(); PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8);
        new RenderMetricsConsoleReporter(false, clock::get, out).report(SNAPSHOT);
        assertEquals("", bytes.toString(StandardCharsets.UTF_8));
        RenderMetricsConsoleReporter reporter = new RenderMetricsConsoleReporter(true, clock::get, out);
        reporter.report(SNAPSHOT); reporter.report(SNAPSHOT); clock.set(999_999_999L); reporter.report(SNAPSHOT);
        assertEquals("", bytes.toString(StandardCharsets.UTF_8));
        clock.set(1_000_000_000L); reporter.report(SNAPSHOT);
        assertEquals(1, bytes.toString(StandardCharsets.UTF_8).lines().count());
    }
}

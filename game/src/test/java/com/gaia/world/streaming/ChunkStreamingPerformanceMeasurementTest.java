package com.gaia.world.streaming;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestReporter;

/** Captures Gate 15F observations without environment-dependent pass/fail timing thresholds. */
class ChunkStreamingPerformanceMeasurementTest {
    @Test
    void currentGapObservationIsImmutableAndCappedAtSixteen() {
        ChunkStreamingMetrics metrics = ChunkStreamingMetrics.empty();

        Object raw = assertDoesNotThrow(() -> metrics.getClass()
                .getMethod("gaps")
                .invoke(metrics));
        List<?> gaps = assertInstanceOf(List.class, raw);

        assertTrue(gaps.size() <= 16);
        assertThrows(UnsupportedOperationException.class,
                () -> ((List<Object>) gaps).add(new Object()));
    }

    @Test
    void recordsProductionArchiveQueueLifecycleRebaseAndLatencyObservations(
            TestReporter reporter) {
        Gate15FMeasurementObservation measurement = ChunkStreamingSoakTest.probe()
                .runMeasurement();

        reporter.publishEntry("gate15f-measurement", measurement.toString());
        assertFalse(measurement.archives().isEmpty(),
                "measurement must capture actual archive/file observations");
        assertTrue(measurement.archives().stream()
                        .anyMatch(archive -> archive.fileCount() > 0L),
                "at least one observed archive/file root must contain files");
        assertFalse(measurement.epochs().isEmpty(),
                "measurement must capture actual streaming epochs");
        assertFalse(measurement.pipelineCounters().isEmpty(),
                "measurement must capture pipeline counters");
        assertFalse(measurement.originSequence().isEmpty(),
                "measurement must capture origin observations");
        assertFalse(measurement.latencies().isEmpty(),
                "measurement must capture scalar latency observations");
        measurement.epochs().forEach(epoch -> {
            ChunkStreamingMetrics metrics = epoch.metrics();
            assertWork(metrics.loadGenerationWork(), 32, 4);
            assertWork(metrics.meshWork(), 32, 2);
            assertWork(metrics.saveWork(), 8, 1);
            assertTrue(metrics.residentChunks() <= 225,
                    "resident authority may use the unload hysteresis footprint");
            assertTrue(metrics.publicationsThisFrame() <= 2L);
            assertTrue(metrics.uploadsThisFrame() <= 2L);
            assertTrue(metrics.destructionsThisFrame() <= 4L);
        });
        assertTrue(measurement.pipelineCounters().stream()
                        .anyMatch(sample -> sample.canceled() > 0L),
                "measurement must include real cancellation activity");
        assertTrue(measurement.pipelineCounters().stream()
                        .allMatch(sample -> sample.stale() >= 0L),
                "stale-work diagnostics remain a non-negative current counter");
        assertTrue(measurement.latencies().stream()
                        .map(Gate15FLatencyObservation::operation)
                        .collect(java.util.stream.Collectors.toSet())
                        .containsAll(java.util.Set.of("load", "generation", "mesh", "save", "restore")));
    }

    private static void assertWork(
            ChunkStreamingMetrics.WorkMetrics work, int acceptedLimit, int activeLimit) {
        assertTrue(work.accepted() == work.queued() + work.active() + work.completed());
        assertTrue(work.accepted() <= acceptedLimit);
        assertTrue(work.active() <= activeLimit);
    }
}

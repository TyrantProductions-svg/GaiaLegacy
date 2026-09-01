package com.gaia.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.util.Collection;
import org.junit.jupiter.api.Test;

class DetailEditPerformanceFixtureTest {
    @Test
    void deterministicPressedEdgesProduceOneBoundedAttemptEach() {
        DetailEditPerformanceFixture.Result result =
                DetailEditPerformanceFixture.run(64, 0);

        assertEquals(64, result.attempts());
        assertEquals(64, result.applied());
        assertEquals(0, result.stale());
        assertEquals(0, result.rejected());
        assertEquals(0, result.finalOccupiedCount());
        assertTrue(result.affectedChunks().size() <= 9);
        assertTrue(result.totalLatencyNanos() >= result.maximumLatencyNanos());
        assertTrue(result.meshAcceptedPeak() >= 1);
        assertTrue(result.meshActivePeak() >= 1);
        assertTrue(result.meshCompletedPeak() >= 1);
        assertEquals(1, result.staleMeshResults());
        assertTrue(result.meshOutputBytesPeak() > 0L);
        assertTrue(result.meshCpuBudgetBytesPeak() > 0L);
        assertTrue(result.meshCpuBudgetBytesPeak()
                <= result.meshCpuBudgetBytesLimit());
        assertEquals(1, result.meshAffectedChunks());
        assertTrue(result.meshBuildLatencyNanos() >= 0L);
    }

    @Test
    void staleEdgesRejectWithoutRetryOrUnboundedHistory() {
        DetailEditPerformanceFixture.Result result =
                DetailEditPerformanceFixture.run(25, 5);

        assertEquals(25, result.attempts());
        assertEquals(5, result.stale());
        assertEquals(20, result.applied());
        assertEquals(0, result.rejected());
        for (Field field : DetailEditPerformanceFixture.Result.class.getDeclaredFields()) {
            assertTrue(!Collection.class.isAssignableFrom(field.getType())
                            || field.getName().equals("affectedChunks"),
                    "measurement result must not retain per-edit history: " + field.getName());
        }
    }

    @Test
    void currentMeshCompletionIsNotMislabeledAsStale() {
        assertEquals(0, DetailEditPerformanceFixture.run(1, 0).staleMeshResults());
        assertEquals(0, DetailEditPerformanceFixture.run(2, 2).staleMeshResults());
    }
}
